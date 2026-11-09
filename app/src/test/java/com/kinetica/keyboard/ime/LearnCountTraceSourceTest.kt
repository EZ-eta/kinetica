package com.kinetica.keyboard.ime

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A learn reads its stored row back on every build and corrects a live count that reads short; a
 * disagreement is traced, so a word a load left out shows at the commit that misses its boost. The
 * learn path needs a service, so the guard is a source scan.
 *
 * Read off disk ([ImeSources]), so a fail-first check here needs `--rerun-tasks`.
 */
class LearnCountTraceSourceTest {

    @Test
    fun aLearnReadsItsStoredCountBackAfterTheWrite() {
        val src = ImeSources.declaring("fun learnWord(").text
        val start = src.indexOf("fun learnWord(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        val write = body.indexOf(".upsertAdd(w, lang, amount, now)")
        val read = body.indexOf(".storedCount(w, lang)")
        assertTrue("the learn no longer writes its row", write >= 0)
        assertTrue("the stored count is not read back after the write", read > write)
        // Every build: the read comes before the trace switch, not under it.
        assertTrue("the read is not on every build", read < body.indexOf("DecodeTrace.enabled"))
        assertTrue("the trace line is gone", body.contains("learn count word=\$w lang=\$lang live=\$after stored="))
    }

    @Test
    fun theCorrectedCountsAskTheReload() {
        val src = ImeSources.declaring("fun learnWord(").text
        val start = src.indexOf("fun learnWord(")
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        assertTrue(body.contains("val gap = learnedCountGap(after, stored)"))
        assertTrue(body.contains("residents.correctLearn(mark, lang, w, gap)"))
        assertTrue(body.contains("askUserDictReload(w, lang, before + gap, after + gap)"))
        assertTrue(body.contains("userDictDemoted(before + gap, after + gap)"))
        assertTrue("the mark is taken after the journal note", body.indexOf("residents.learnMark()") > body.indexOf("residents.noteLearn("))
    }

    @Test
    fun anInstallRecordsItsLoadBeforeTheComposerReadsIt() {
        val src = ImeSources.declaring("internal class ResidentDictionaries").text
        val load = src.substring(src.indexOf("fun load("), src.indexOf("fun releaseInstall("))
        val installed = load.indexOf("learnJournal.installed(journal)")
        assertTrue(installed >= 0)
        assertTrue(installed < load.indexOf("onReady(p)"))
    }
}
