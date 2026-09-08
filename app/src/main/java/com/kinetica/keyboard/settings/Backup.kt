package com.kinetica.keyboard.settings

/**
 * The whole of a user's keyboard, as lines of text.
 *
 * A line format rather than one JSON document, encoded and decoded a record at a time, so a
 * large personal dictionary cannot exhaust memory. Nothing is held whole but the caller's own
 * collections.
 *
 * **Pure on purpose, and no `org.json`.** The JVM test runtime stubs Android's JSON classes,
 * so a document built on them could not be tested at all. Everything here is `String` in,
 * `String` out; the activity does the file picking and the database reads.
 *
 * Tabs separate fields and newlines separate records, so any value containing either is
 * refused rather than escaped. Preference values, words, chords and language codes cannot
 * contain a tab. A multi-line chord expansion would need the format version to go up.
 */
object Backup {

    const val FORMAT = "kinetica-backup"

    /** Bump when a record type changes meaning. A reader refuses what it does not know. */
    const val VERSION = 1

    /** Preference value types, spelled out so a restore writes the right one. */
    enum class PrefType { BOOL, INT, STRING, SET }

    data class Pref(val key: String, val type: PrefType, val value: String)

    data class Word(val lang: String, val word: String, val count: Int)

    data class Blocked(val lang: String, val word: String)

    data class Chord(val chord: String, val expansion: String)

    data class Phrase(val lang: String, val prev: String, val next: String, val count: Int)

    data class Data(
        val prefs: List<Pref> = emptyList(),
        val words: List<Word> = emptyList(),
        val blocked: List<Blocked> = emptyList(),
        val chords: List<Chord> = emptyList(),
        /** Empty unless the user ticked the box; see [Data.phrases] at the call site. */
        val phrases: List<Phrase> = emptyList(),
        /**
         * Imported base dictionaries present on the source device, by language. Recorded but
         * NOT carried: a merged wordlist is tens of megabytes and the user still has the file
         * it came from, so the restore names what is missing instead of moving it.
         */
        val importedBase: List<String> = emptyList(),
    )

    /** True when [s] can survive a round trip: no separator, no newline. */
    fun encodable(s: String): Boolean = s.none { it == '\t' || it == '\n' || it == '\r' }

    /**
     * The backup as lines, header first. A record whose fields cannot survive the separators
     * is dropped rather than mangled, which is the only lossy thing here and is reported by
     * [encodeCounted].
     */
    fun encode(data: Data): Sequence<String> = sequence {
        yield("$FORMAT\t$VERSION")
        for (p in data.prefs) {
            if (encodable(p.key) && encodable(p.value)) {
                yield("pref\t${p.type.name.lowercase()}\t${p.key}\t${p.value}")
            }
        }
        for (w in data.words) {
            if (encodable(w.lang) && encodable(w.word)) yield("word\t${w.lang}\t${w.word}\t${w.count}")
        }
        for (b in data.blocked) {
            if (encodable(b.lang) && encodable(b.word)) yield("block\t${b.lang}\t${b.word}")
        }
        for (c in data.chords) {
            if (encodable(c.chord) && encodable(c.expansion)) yield("chord\t${c.chord}\t${c.expansion}")
        }
        for (p in data.phrases) {
            if (encodable(p.lang) && encodable(p.prev) && encodable(p.next)) {
                yield("phrase\t${p.lang}\t${p.prev}\t${p.next}\t${p.count}")
            }
        }
        for (lang in data.importedBase) {
            if (encodable(lang)) yield("basedict\t$lang")
        }
    }

    /** How many records [encode] would drop, so the export can say so rather than lie. */
    fun unencodable(data: Data): Int =
        data.prefs.count { !encodable(it.key) || !encodable(it.value) } +
            data.words.count { !encodable(it.lang) || !encodable(it.word) } +
            data.blocked.count { !encodable(it.lang) || !encodable(it.word) } +
            data.chords.count { !encodable(it.chord) || !encodable(it.expansion) } +
            data.phrases.count { !encodable(it.lang) || !encodable(it.prev) || !encodable(it.next) }

    /** What a file turned out to be. */
    sealed class Result {
        data class Ok(val data: Data, val skipped: Int) : Result()

        /** Not a Kinetica backup at all. */
        object NotABackup : Result()

        /** A backup, from a newer version than this build understands. */
        data class TooNew(val version: Int) : Result()
    }

    /**
     * Reads a backup back.
     *
     * The version IS checked, unlike `kinetica-personal-1`, whose `format` field is written
     * and never read, so a file claiming any version at all imports identically there. A
     * restore rewrites every setting the user has, so it refuses what it does not recognise
     * rather than doing its best.
     *
     * Unknown record types and malformed lines are skipped and counted, not fatal: a backup
     * from a later version that fails the check above is refused outright, but a line this
     * build has no use for should not lose the user their dictionary.
     */
    fun decode(lines: Sequence<String>): Result {
        val it = lines.iterator()
        var header: String? = null
        while (it.hasNext()) {
            val l = it.next()
            if (l.isNotBlank()) { header = l; break }
        }
        val h = header?.split("\t") ?: return Result.NotABackup
        if (h.size < 2 || h[0] != FORMAT) return Result.NotABackup
        val v = h[1].toIntOrNull() ?: return Result.NotABackup
        if (v > VERSION) return Result.TooNew(v)

        val prefs = ArrayList<Pref>()
        val words = ArrayList<Word>()
        val blocked = ArrayList<Blocked>()
        val chords = ArrayList<Chord>()
        val phrases = ArrayList<Phrase>()
        val base = ArrayList<String>()
        var skipped = 0
        while (it.hasNext()) {
            val line = it.next()
            if (line.isBlank()) continue
            val f = line.split("\t")
            val ok = when (f[0]) {
                "pref" -> parsePref(f)?.also { p -> prefs.add(p) } != null
                "word" -> parseWord(f)?.also { w -> words.add(w) } != null
                "block" -> if (f.size == 3 && f[1].isNotEmpty() && f[2].isNotEmpty()) {
                    blocked.add(Blocked(f[1], f[2])); true
                } else {
                    false
                }
                "chord" -> if (f.size == 3 && f[1].isNotEmpty() && f[2].isNotEmpty()) {
                    chords.add(Chord(f[1], f[2])); true
                } else {
                    false
                }
                "phrase" -> parsePhrase(f)?.also { p -> phrases.add(p) } != null
                "basedict" -> if (f.size == 2 && f[1].isNotEmpty()) { base.add(f[1]); true } else false
                else -> false
            }
            if (!ok) skipped++
        }
        return Result.Ok(Data(prefs, words, blocked, chords, phrases, base), skipped)
    }

    private fun parsePref(f: List<String>): Pref? {
        // A pref value may legitimately be empty (pref_comma_custom), so only the key is
        // required. Split with a limit so a value can never be mistaken for extra fields.
        if (f.size < 3) return null
        val type = PrefType.entries.firstOrNull { it.name.equals(f[1], ignoreCase = true) } ?: return null
        if (f[2].isEmpty()) return null
        return Pref(f[2], type, if (f.size >= 4) f.subList(3, f.size).joinToString("\t") else "")
    }

    private fun parseWord(f: List<String>): Word? {
        if (f.size != 4 || f[1].isEmpty() || f[2].isEmpty()) return null
        val n = f[3].toIntOrNull() ?: return null
        if (n < 1) return null
        return Word(f[1], f[2], n)
    }

    private fun parsePhrase(f: List<String>): Phrase? {
        if (f.size != 5 || f[1].isEmpty() || f[2].isEmpty() || f[3].isEmpty()) return null
        val n = f[4].toIntOrNull() ?: return null
        if (n < 1) return null
        return Phrase(f[1], f[2], f[3], n)
    }
}
