package com.kinetica.keyboard.ime

/**
 * The words this keyboard committed in the current field, each with the alternatives its bar
 * had. A cursor placed inside one later offers those, which re-decoding the letters cannot:
 * `key` swiped had key, let, meet, met, jet, and its letters alone give only completions.
 *
 * Records are kept by start offset and moved by every own edit before them, so typing earlier in
 * the field does not strand them; an edit across a record drops it. Pure; main thread only.
 */
class CommitHistory(private val capacity: Int = CAPACITY) {

    data class Record(
        val start: Int,
        val word: String,
        /** The bar's other words for it, in their order. */
        val alternatives: List<String>,
        /** Each spelling's language, lowercased, for learning a pick where it came from. */
        val languages: Map<String, String>,
    ) {
        val end: Int get() = start + word.length
    }

    private val records = ArrayDeque<Record>()

    val size: Int get() = records.size

    fun record(start: Int, word: String, alternatives: List<String>, languages: Map<String, String>) {
        if (start < 0 || word.isEmpty()) return
        records.removeAll { it.start < start + word.length && start < it.end }
        records.addLast(Record(start, word, alternatives.filterNot { it.equals(word, ignoreCase = true) }, languages))
        while (records.size > capacity) records.removeFirst()
    }

    /**
     * The record for the word now written from [start] as [current]: the word committed there, or
     * one of its alternatives picked since (a correction or a recent-word swap).
     */
    fun at(start: Int, current: String): Record? = records.lastOrNull { r ->
        r.start == start && (r.word.equals(current, ignoreCase = true) ||
            r.alternatives.any { it.equals(current, ignoreCase = true) })
    }

    /** The word at [start] became [word], its record's other words kept; false with no record. */
    fun replaced(start: Int, old: String, word: String): Boolean {
        val i = records.indexOfLast { it.start == start && it.word.equals(old, ignoreCase = true) }
        if (i < 0) return false
        val r = records[i]
        val alts = (listOf(r.word) + r.alternatives).filterNot { it.equals(word, ignoreCase = true) }
        val delta = word.length - r.word.length
        records[i] = r.copy(word = word, alternatives = alts)
        if (delta != 0) shiftAfter(r.end, delta, except = i)
        return true
    }

    /**
     * An own edit at [at]: [deleted] characters gone before [at] and [inserted] written. Records
     * after it move by the difference; a record the edit touches is dropped.
     */
    fun onEdit(at: Int, deleted: Int, inserted: Int) {
        val from = at - deleted
        records.removeAll { it.start < at && from < it.end }
        shiftAfter(at, inserted - deleted, except = -1)
    }

    private fun shiftAfter(at: Int, delta: Int, except: Int) {
        if (delta == 0) return
        for (i in records.indices) {
            if (i != except && records[i].start >= at) records[i] = records[i].copy(start = records[i].start + delta)
        }
    }

    fun clear() = records.clear()

    companion object {
        /** More words than a reply holds; an essay keeps its last paragraphs. */
        const val CAPACITY = 64
    }
}
