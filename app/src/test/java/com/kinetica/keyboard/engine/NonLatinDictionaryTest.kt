package com.kinetica.keyboard.engine

import com.kinetica.keyboard.settings.Prefs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Russian, Hebrew, Arabic and Ukrainian real-asset goldens (experimental). Each decodes on its
 * own board's geometry, in its own alphabet, with the same engine and constants as Latin.
 *
 * Goldens were picked by measuring (ADDING_A_LANGUAGE §6): every word here leads as a clean
 * swipe and at three overshoots. Three that do not are recorded instead of dropped:
 * `всё` shows as the commoner `все` it folds onto, `בית` loses to `בבית` because a doubled
 * letter shares its path, and `إلى` slips to `أبى` at the widest overshoot. Ukrainian:
 * `україна` slips to `країна` past a clean swipe, `школа` to `шкода` at 0.4 kw, and `київ` is
 * not in a list built from film subtitles at all.
 */
class NonLatinDictionaryTest {

    private fun assetPath(name: String): Path {
        val direct = Paths.get("src/main/assets/dictionaries/$name")
        if (Files.exists(direct)) return direct
        return Paths.get("app/src/main/assets/dictionaries/$name")
    }

    private fun loadDict(lang: String): LoadedDictionary {
        val p = assetPath("${lang}_wordlist.txt")
        assumeTrue("$lang wordlist asset not found", Files.exists(p))
        return Files.newBufferedReader(p).use { DictionaryLoader.load(it, alphabet = Alphabet.forLanguage(lang)) }
    }

    private fun predictor(lang: String, g: KeyboardGeometry): WordPredictor {
        val d = loadDict(lang)
        return WordPredictor(d.trie, BigramTable.EMPTY, g, d.forms, language = lang)
    }

    private fun assertLeads(p: WordPredictor, g: KeyboardGeometry, words: List<String>) {
        for (w in words) {
            // An apostrophe has no key: the trie puts it in for free (`п'ять` from п-я-т-ь).
            val key = AccentFolder.fold(w).replace("'", "")
            val clean = p.decode(listOf(TestData.swipe(key, g, 0, 600)), emptyList()).firstOrNull()?.word
            assertEquals("$w clean", w, clean)
            for (o in listOf(0.2f, 0.4f, 0.6f)) {
                val sloppy = p.decode(listOf(TestData.sloppySwipe(key, g, 0, 600, overshootKw = o)), emptyList()).firstOrNull()?.word
                assertEquals("$w sloppy $o", w, sloppy)
            }
        }
    }

    @Test
    fun eachLoadsWithinTheBudgetInItsOwnAlphabet() {
        for ((lang, words) in mapOf(
            "ru" to listOf("привет", "спасибо", "объект"),
            "he" to listOf("שלום", "צריך", "כאן"),
            "ar" to listOf("مرحبا", "مدرسة", "أنا"),
            "uk" to listOf("привіт", "п'ять", "їжа"),
        )) {
            val d = loadDict(lang)
            assertEquals(Alphabet.forLanguage(lang), d.trie.alphabet)
            assertTrue("$lang word count ${d.trie.wordCount}", d.trie.wordCount >= 30_000)
            assertTrue("$lang trie bytes ${d.trie.sizeBytes()}", d.trie.sizeBytes() < 4 * 1024 * 1024)
            for (w in words) assertTrue("$lang missing $w", d.trie.contains(AccentFolder.fold(w)))
        }
    }

    @Test
    fun eachBigramTableLoads() {
        for ((lang, pair) in mapOf("ru" to ("я" to "не"), "he" to ("אני" to "לא"), "ar" to ("لا" to "أعرف"), "uk" to ("що" to "ти"))) {
            val d = loadDict(lang)
            val p = assetPath("${lang}_bigrams.txt")
            assumeTrue("$lang bigrams not found", Files.exists(p))
            val table = Files.newBufferedReader(p).use { DictionaryLoader.loadBigrams(it, d.trie) }
            // Arabic has 69k Tatoeba sentences against Russian's 1.2M: a smaller table, a
            // smaller boost, not a defect.
            assertTrue("$lang bigram count ${table.size}", table.size > 10_000)
            val boost = table.multiplier(d.trie.nodeFor(AccentFolder.fold(pair.first)), d.trie.nodeFor(AccentFolder.fold(pair.second)))
            assertTrue("$lang ${pair.first}->${pair.second} boost $boost", boost > 1f)
        }
    }

    @Test
    fun russianSwipesDecodeTop1OnJcuken() {
        val g = TestData.jcukenGeometry()
        // `объект` passes the hard sign, a key of its own at the end of the top row.
        assertLeads(predictor("ru", g), g, listOf("привет", "спасибо", "хорошо", "сейчас", "человек", "объект"))
    }

    @Test
    fun russianYoComesBackThroughItsForms() {
        // ё folds onto е's key; the list still offers the word spelled with it.
        val g = TestData.jcukenGeometry()
        val list = predictor("ru", g).decode(listOf(TestData.swipe("еще", g, 0, 600)), emptyList()).map { it.word }
        assertTrue("ещё not offered: $list", "ещё" in list)
    }

    @Test
    fun hebrewSwipesDecodeTop1IncludingFinalForms() {
        val g = TestData.hebrewGeometry()
        // Four end on a final form (ם ך ן), each a key of its own.
        assertLeads(predictor("he", g), g, listOf("שלום", "תודה", "עכשיו", "בסדר", "צריך", "כאן"))
    }

    @Test
    fun arabicSwipesDecodeTop1WithTheirHamzaRestored() {
        val g = TestData.arabicGeometry()
        // `أنا` is drawn from alef's key and shown with its hamza; `مدرسة` ends on taa marbuta.
        assertLeads(predictor("ar", g), g, listOf("مرحبا", "شكرا", "لماذا", "مدرسة", "سيارة", "أنا"))
    }

    @Test
    fun ukrainianSwipesDecodeTop1OnItsOwnBoard() {
        val g = TestData.ukrainianGeometry()
        // `їжа`, `єдиний` and `гроші` pass the three letters Russian's board lacks; the last two
        // have an apostrophe the swipe never draws.
        assertLeads(
            predictor("uk", g), g,
            listOf("привіт", "дякую", "сьогодні", "хлопець", "їжа", "єдиний", "гроші", "п'ять", "пам'ятаю"),
        )
    }

    @Test
    fun ukrainianGheComesBackThroughItsForms() {
        // ґ folds onto г's key; `грати` (to play) and `ґрати` (bars) share it.
        val g = TestData.ukrainianGeometry()
        val list = predictor("uk", g).decode(listOf(TestData.swipe("грати", g, 0, 600)), emptyList()).map { it.word }
        assertTrue("ґрати not offered: $list", "ґрати" in list)
        assertEquals("грати", list.first())
    }

    @Test
    fun theUkrainianListIsUkrainian() {
        // The generator's controls, read off the shipped asset: Russian spelled with shared
        // letters, the halves of split apostrophe words and mis-decoded text all stay out.
        val p = assetPath("uk_wordlist.txt")
        assumeTrue(Files.exists(p))
        val words = Files.readAllLines(p).map { it.substringBefore('\t') }.toSet()
        for (w in listOf("що", "це", "він", "на", "так", "дякую", "книга", "п'ять", "ім'я", "сім'я")) assertTrue("missing $w", w in words)
        for (w in listOf("что", "как", "мне", "он", "нет", "спасибо", "п", "ять", "кбй", "пґп")) assertFalse("kept $w", w in words)
    }

    @Test
    fun aRussianTrieOnAUkrainianBoardDecodesNothing() {
        // Cyrillic both, but not one alphabet: Russian's codes would read Ukrainian's keys wrong.
        val p = predictor("ru", TestData.ukrainianGeometry())
        assertTrue(p.decode(listOf(TestData.swipe("привіт", TestData.ukrainianGeometry(), 0, 600)), emptyList()).isEmpty())
    }

    @Test
    fun aBoardAndATrieInDifferentScriptsDecodeNothing() {
        // The moment a language switch leaves the old board up: codes would read wrong keys.
        val p = predictor("ru", TestData.qwertyGeometry())
        assertTrue(p.decode(listOf(TestData.swipe("hello", TestData.qwertyGeometry(), 0, 600)), emptyList()).isEmpty())
    }

    @Test
    fun theExperimentalLanguagesAreNeverOnByDefault() {
        for (lang in Prefs.EXPERIMENTAL_LANGUAGES) {
            assertTrue(lang in Prefs.ALL_LANGUAGES)
            assertFalse(lang in Prefs.DEFAULT_ENABLED_LANGUAGES)
        }
        // Appended, never inserted: the nine before them keep their order.
        assertEquals(listOf("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no"), Prefs.ALL_LANGUAGES.take(9))
        assertEquals(Prefs.ALL_LANGUAGES.take(9), Prefs.DEFAULT_ENABLED_LANGUAGES)
    }
}
