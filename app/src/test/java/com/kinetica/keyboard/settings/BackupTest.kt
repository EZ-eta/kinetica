package com.kinetica.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup format, round-tripped.
 *
 * A restore rewrites every setting a user has, and unlike the personal-dictionary export
 * this format checks its own version rather than writing a field nobody reads.
 *
 * Pure because [Backup] is: no Android, no `org.json` (which the JVM runtime stubs), no file.
 */
class BackupTest {

    private fun sample() = Backup.Data(
        prefs = listOf(
            Backup.Pref("pref_autospace", Backup.PrefType.BOOL, "true"),
            Backup.Pref("pref_keyboard_height_pct", Backup.PrefType.INT, "42"),
            Backup.Pref("pref_language", Backup.PrefType.STRING, "it"),
            Backup.Pref("pref_enabled_languages", Backup.PrefType.SET, "en,it"),
            // A value that is legitimately empty: pref_comma_custom when unset.
            Backup.Pref("pref_comma_custom", Backup.PrefType.STRING, ""),
        ),
        words = listOf(Backup.Word("en", "keyboard", 12), Backup.Word("it", "biologia", 3)),
        blocked = listOf(Backup.Blocked("en", "teh")),
        chords = listOf(Backup.Chord("v", "action:paste"), Backup.Chord("s", "supercalifragilistic")),
        phrases = listOf(Backup.Phrase("en", "i", "am", 4)),
        importedBase = listOf("en"),
    )

    private fun roundTrip(d: Backup.Data): Backup.Data {
        val res = Backup.decode(Backup.encode(d))
        assertTrue("expected a readable backup, got $res", res is Backup.Result.Ok)
        return (res as Backup.Result.Ok).data
    }

    @Test
    fun everythingSurvivesTheRoundTrip() {
        val d = sample()
        val back = roundTrip(d)
        assertEquals(d.prefs, back.prefs)
        assertEquals(d.words, back.words)
        assertEquals(d.blocked, back.blocked)
        assertEquals(d.chords, back.chords)
        assertEquals(d.phrases, back.phrases)
        assertEquals(d.importedBase, back.importedBase)
    }

    @Test
    fun anEmptyBackupIsStillAValidOne() {
        // A user who has changed nothing must get a file that restores to nothing, rather
        // than a file that fails to parse on the other device.
        val back = roundTrip(Backup.Data())
        assertEquals(Backup.Data(), back)
    }

    @Test
    fun everyPreferenceTypeKeepsItsType() {
        // The types are not decoration: pref_enabled_languages is a StringSet, and writing
        // it back as a String makes KeyboardConfig.from throw inside the IME's listener.
        val back = roundTrip(sample())
        assertEquals(Backup.PrefType.BOOL, back.prefs.first { it.key == "pref_autospace" }.type)
        assertEquals(Backup.PrefType.INT, back.prefs.first { it.key == "pref_keyboard_height_pct" }.type)
        assertEquals(Backup.PrefType.SET, back.prefs.first { it.key == "pref_enabled_languages" }.type)
        assertEquals("", back.prefs.first { it.key == "pref_comma_custom" }.value)
    }

    @Test
    fun aChordExpansionKeepsItsReservedCommand() {
        // action: strings are how a chord runs paste rather than typing the word "paste".
        assertEquals("action:paste", roundTrip(sample()).chords.first { it.chord == "v" }.expansion)
    }

    @Test
    fun theHeaderComesFirstAndNamesTheFormat() {
        val first = Backup.encode(sample()).first()
        assertEquals("${Backup.FORMAT}\t${Backup.VERSION}", first)
    }

    @Test
    fun somethingElseEntirelyIsRefused() {
        for (junk in listOf(
            sequenceOf("{\"format\":\"kinetica-personal-1\"}"),
            sequenceOf("hello world"),
            sequenceOf(""),
            emptySequence(),
        )) {
            assertEquals(Backup.Result.NotABackup, Backup.decode(junk))
        }
    }

    @Test
    fun aNewerBackupIsRefusedRatherThanGuessedAt() {
        // The whole reason the version is read. Doing our best with a file we do not
        // understand means silently dropping settings a later build wrote.
        val res = Backup.decode(sequenceOf("${Backup.FORMAT}\t99", "pref\tbool\tpref_autospace\ttrue"))
        assertEquals(Backup.Result.TooNew(99), res)
    }

    @Test
    fun anOlderBackupStillReads() {
        val res = Backup.decode(sequenceOf("${Backup.FORMAT}\t1", "word\ten\thello\t3"))
        assertTrue(res is Backup.Result.Ok)
        assertEquals(listOf(Backup.Word("en", "hello", 3)), (res as Backup.Result.Ok).data.words)
    }

    @Test
    fun aLineThisBuildDoesNotKnowIsSkippedAndCounted() {
        // Forward compatibility within a version: a record type added later must not cost
        // the user their dictionary.
        val res = Backup.decode(
            sequenceOf(
                "${Backup.FORMAT}\t1",
                "word\ten\thello\t3",
                "gadget\tsomething\tnew",
                "word\ten\tbroken",
                "word\ten\thello\tnotanumber",
            ),
        )
        assertTrue(res is Backup.Result.Ok)
        val ok = res as Backup.Result.Ok
        assertEquals(1, ok.data.words.size)
        assertEquals("three malformed or unknown lines", 3, ok.skipped)
    }

    @Test
    fun aValueCarryingASeparatorIsDroppedRatherThanMangled() {
        // Tabs and newlines are the record structure, so a value holding one cannot be
        // written. Nothing real does - but a corrupted preference must not silently shift
        // every field after it on the way back in.
        val d = Backup.Data(
            prefs = listOf(
                Backup.Pref("pref_comma_custom", Backup.PrefType.STRING, "a\tb"),
                Backup.Pref("pref_language", Backup.PrefType.STRING, "en"),
            ),
        )
        assertEquals("one unencodable record", 1, Backup.unencodable(d))
        val back = roundTrip(d)
        assertEquals(1, back.prefs.size)
        assertEquals("pref_language", back.prefs.first().key)
    }

    @Test
    fun phrasesAreCarriedOnlyWhenTheyAreGiven() {
        // The export leaves them out unless the box is ticked, so the encoder must not
        // invent them and the decoder must not mind their absence.
        val without = sample().copy(phrases = emptyList())
        assertTrue(Backup.encode(without).none { it.startsWith("phrase\t") })
        assertEquals(emptyList<Backup.Phrase>(), roundTrip(without).phrases)
    }

    @Test
    fun countsBelowOneAreRefused() {
        // A zero count means a word the user de-reinforced to nothing; importing it as a
        // real entry would resurrect it.
        val res = Backup.decode(
            sequenceOf("${Backup.FORMAT}\t1", "word\ten\thello\t0", "phrase\ten\ti\tam\t0"),
        )
        assertTrue(res is Backup.Result.Ok)
        val ok = res as Backup.Result.Ok
        assertTrue(ok.data.words.isEmpty())
        assertTrue(ok.data.phrases.isEmpty())
        assertEquals(2, ok.skipped)
    }
}
