package com.kinetica.keyboard.settings

/**
 * The whole of a user's keyboard, as lines of text.
 *
 * A line format, not one JSON document, encoded and decoded a record at a time so a large
 * personal dictionary cannot exhaust memory. Nothing is held whole but the caller's own
 * collections.
 *
 * Pure, with no `org.json`: the JVM test runtime stubs Android's JSON classes, so a document
 * built on them could not be tested. Everything here is `String` in, `String` out; the activity
 * does the file picking and the database reads.
 *
 * Tabs separate fields and newlines separate records. Expansion targets are escaped ([esc]);
 * any other value holding either is refused.
 */
object Backup {

    const val FORMAT = "kinetica-backup"

    /**
     * Suggested export filename, stamped with [at].
     *
     * Stamped so a second export does not overwrite the first under the same name. Minutes, not
     * seconds: two exports in the same minute are the same export, and the name stays readable
     * enough to sort by eye in a file picker.
     */
    fun filename(at: java.time.LocalDateTime): String =
        "kinetica_backup_" +
            at.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")) +
            ".txt"

    /** Bump when a record type changes meaning. A reader refuses what it does not know. */
    const val VERSION = 1

    /** Preference value types, spelled out so a restore writes the right one. */
    enum class PrefType { BOOL, INT, STRING, SET }

    data class Pref(val key: String, val type: PrefType, val value: String)

    data class Word(val lang: String, val word: String, val count: Int)

    data class Blocked(val lang: String, val word: String)

    data class Chord(val chord: String, val expansion: String)

    data class Expand(val trigger: String, val position: Int, val target: String)

    data class Phrase(val lang: String, val prev: String, val next: String, val count: Int)

    data class Data(
        val prefs: List<Pref> = emptyList(),
        val words: List<Word> = emptyList(),
        val blocked: List<Blocked> = emptyList(),
        val chords: List<Chord> = emptyList(),
        val expansions: List<Expand> = emptyList(),
        /** Empty unless the user ticked the box; see [Data.phrases] at the call site. */
        val phrases: List<Phrase> = emptyList(),
        /**
         * Imported base dictionaries present on the source device, by language. Recorded but not
         * carried: a merged wordlist is tens of megabytes and the user still has the file it came
         * from, so the restore names what is missing instead of moving it.
         */
        val importedBase: List<String> = emptyList(),
    )

    /** True when [s] can survive a round trip: no separator, no newline. */
    fun encodable(s: String): Boolean = s.none { it == '\t' || it == '\n' || it == '\r' }

    /**
     * A value with its separators written out, for the one record type that may hold them.
     *
     * An expansion target may be several lines, a bullet block for example, which the line
     * format cannot carry. Every other record still refuses instead of escaping, so nothing
     * already written changes meaning and [VERSION] stays at 1. [decode] refuses a newer file
     * outright, so a bump would make every new backup unreadable in full by every installed
     * build; a new record type is skipped and counted instead, and the rest of the file restores.
     */
    fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** Inverse of [esc]. An unknown escape keeps its backslash. */
    fun unesc(s: String): String {
        if (!s.contains('\\')) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                out.append(c)
                i++
                continue
            }
            when (val next = s[i + 1]) {
                '\\' -> out.append('\\')
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                else -> out.append(c).append(next)
            }
            i += 2
        }
        return out.toString()
    }

    /**
     * The backup as lines, header first. A record whose fields cannot survive the separators is
     * dropped, not mangled; that is the only loss here, and [unencodable] counts it.
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
        for (e in data.expansions) {
            // The target is escaped, so only a trigger with a separator drops a row, and the
            // editor refuses to save one.
            if (encodable(e.trigger)) {
                yield("expand\t${e.trigger}\t${e.position}\t${esc(e.target)}")
            }
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

    /** How many records [encode] would drop, so the export can say so. */
    fun unencodable(data: Data): Int =
        data.prefs.count { !encodable(it.key) || !encodable(it.value) } +
            data.words.count { !encodable(it.lang) || !encodable(it.word) } +
            data.blocked.count { !encodable(it.lang) || !encodable(it.word) } +
            data.chords.count { !encodable(it.chord) || !encodable(it.expansion) } +
            data.expansions.count { !encodable(it.trigger) } +
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
     * The version is checked, unlike `kinetica-personal-1`'s `format` field: a restore rewrites
     * every setting the user has, so it refuses a version it does not know.
     *
     * Unknown record types and malformed lines are skipped and counted, not fatal: a line this
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
        val expansions = ArrayList<Expand>()
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
                "expand" -> parseExpand(f)?.also { e -> expansions.add(e) } != null
                "phrase" -> parsePhrase(f)?.also { p -> phrases.add(p) } != null
                "basedict" -> if (f.size == 2 && f[1].isNotEmpty()) { base.add(f[1]); true } else false
                else -> false
            }
            if (!ok) skipped++
        }
        return Result.Ok(Data(prefs, words, blocked, chords, expansions, phrases, base), skipped)
    }

    private fun parseExpand(f: List<String>): Expand? {
        // The target is the last field and is escaped, so it never looks like extra fields and
        // an exact size check is safe here, unlike for a pref.
        if (f.size != 4 || f[1].isEmpty()) return null
        val position = f[2].toIntOrNull() ?: return null
        if (position < 0) return null
        val target = unesc(f[3])
        if (target.isEmpty()) return null
        return Expand(f[1], position, target)
    }

    private fun parsePref(f: List<String>): Pref? {
        // A pref value may be empty (pref_comma_custom), so only the key is required. Fields
        // after the key are joined back, so a value is never mistaken for extra fields.
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
