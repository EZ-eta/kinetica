package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A reloaded word is filed without leading Follow's filing, so the buffer is read before the
 * commit clears it.
 *
 * Read after the commit, every buffer is empty and no word is ever seeded: an autocorrected
 * reload that only the other list holds would lead again, and the next word no list holds would
 * follow it there. The commit path needs a live editor, so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class SeededFilingSourceTest {

    @Test
    fun theSeededReadComesBeforeTheCommitAndReachesTheFiling() {
        val src = ImeSources.declaring("private fun commitWordInternal(").text
        val start = src.indexOf("private fun commitWordInternal(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        val read = body.indexOf("val seeded = ime.composer?.bufferSeeded == true")
        val commit = body.indexOf("ime.composer?.commitWord(")
        assertTrue("the buffer is no longer read for a reload", read >= 0)
        assertTrue("the buffer is read after the commit cleared it", commit >= 0 && read < commit)
        assertTrue("the filing no longer hears of a reload", body.contains("ime.filedLanguage(word, lang, seeded)"))
    }
}
