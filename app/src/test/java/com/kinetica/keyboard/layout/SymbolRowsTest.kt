package com.kinetica.keyboard.layout

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The symbol pages typed as text (#42): rows replaced, the fixed keys untouched, nothing that
 * would turn on swipe decoding over a symbol page.
 *
 * The bundled pages are read as text into keys, as `SymbolsLayoutTest` reads them: `org.json`
 * is stubbed on the JVM.
 */
class SymbolRowsTest {

    @Test
    fun theBuiltInRowsReadBackAsText() {
        val rows = SymbolRows.defaults(page("symbols"))
        assertEquals("1 2 3 4 5 6 7 8 9 0", rows[0])
        assertEquals("@ # $ % & - + ( ) =", rows[1])
        assertEquals(7, SymbolRows.parse(rows[2]).size)
    }

    @Test
    fun aLetterIsRefusedInEitherCase() {
        assertEquals(SymbolRows.Problem.LETTER, SymbolRows.problem("! a ?", 0)?.problem)
        assertEquals("Q", SymbolRows.problem("! Q ?", 1)?.key)
        assertEquals(SymbolRows.Problem.LETTER, SymbolRows.problem(":-D", 2)?.problem)
    }

    @Test
    fun symbolsThatAreLettersOfNoBoardStayLegal() {
        // The shipped second page carries π; the engine's letters are the board's a-z.
        assertNull(SymbolRows.problem("π € ß µ ° ∞", 0))
    }

    @Test
    fun aRowTooLongOrAKeyTooLongIsRefused() {
        assertEquals(SymbolRows.Problem.TOO_MANY, SymbolRows.problem("1 2 3 4 5 6 7 8", 2)?.problem)
        assertNull(SymbolRows.problem("1 2 3 4 5 6 7", 2))
        assertEquals(SymbolRows.Problem.TOO_LONG, SymbolRows.problem("!!!!!", 0)?.problem)
        assertNull(SymbolRows.problem(":-)", 0))
    }

    @Test
    fun aFourthRowIsRefusedAndBlankRowsAreNoProblem() {
        assertEquals(SymbolRows.Problem.TOO_MANY_ROWS, SymbolRows.firstProblem(listOf("1", "2", "3", "4"))?.second?.problem)
        assertNull(SymbolRows.firstProblem(SymbolRows.lines("\n\n\n")))
        assertNull(SymbolRows.problem("   ", 0))
    }

    @Test
    fun theFixedKeysKeepTheirRects() {
        // Backspace and the bottom row sit where the letters' do (SymbolsLayoutTest); an edit
        // must not move them.
        for (name in listOf("symbols", "symbols2")) {
            val base = page(name)
            val edited = SymbolRows.apply(base, if (name == "symbols2") 2 else 1, listOf("! ?", "* *", "~"))
            for (k in base.keys.filter { it.type != KeyType.CHAR || it.y > 0.7f }) {
                val same = edited.keys.single { it.id == k.id }
                assertEquals("$name ${k.id}", listOf(k.x, k.y, k.w, k.h), listOf(same.x, same.y, same.w, same.h))
            }
        }
    }

    @Test
    fun noEditedPageHoldsALetterKey() {
        val edited = SymbolRows.apply(page("symbols"), 1, listOf("a b c", "Z", "é"))
        assertFalse(edited.keys.any { it.isLetter })
        val fine = SymbolRows.apply(page("symbols"), 1, listOf("! a", "", ""))
        assertEquals("a refused row keeps the built-in keys", SymbolRows.defaults(page("symbols"))[0], SymbolRows.defaults(fine)[0])
    }

    @Test
    fun aShorterRowIsCentredAtKeyWidth() {
        val edited = SymbolRows.apply(page("symbols"), 1, listOf("< >", null, "^"))
        val top = edited.keys.filter { it.type == KeyType.CHAR && it.y == 0f }.sortedBy { it.x }
        assertEquals(listOf("<", ">"), top.map { it.output })
        assertEquals(0.40f, top[0].x, 1e-4f)
        assertEquals(0.10f, top[0].w, 1e-4f)
        // The third row centres between the page key and backspace.
        val third = edited.keys.single { it.type == KeyType.CHAR && abs(it.y - 0.5f) < 1e-3f }
        assertEquals(0.45f, third.x, 1e-4f)
    }

    @Test
    fun aSymbolKeepsItsLongPressListWhereverItGoes() {
        val base = page("symbols")
        val dollar = base.keys.single { it.output == "$" }
        // Moved within its row: the same key, id and all.
        val inRow = SymbolRows.apply(base, 1, listOf(null, "+ $", null)).keys.single { it.output == "$" }
        assertEquals(dollar.id, inRow.id)
        assertEquals(dollar.alternates, inRow.alternates)
        // Moved to another row: the list follows, the id is new, the old row lets it go.
        val moved = SymbolRows.apply(base, 1, listOf("$ 1", "@ #", null)).keys.single { it.output == "$" }
        assertEquals(dollar.alternates, moved.alternates)
        assertEquals(0f, moved.y, 0f)
        assertFalse(moved.id == dollar.id)
    }

    @Test
    fun everyKeyIdStaysUnique() {
        val edited = SymbolRows.apply(page("symbols"), 1, listOf("! ! !", "1 2", "# #"))
        val ids = edited.keys.map { it.id }
        assertEquals(ids.toString(), ids.size, ids.toSet().size)
    }

    // ------------------------------------------------------------------ fixture

    private fun layoutPath(name: String): Path {
        val rel = "src/main/assets/layouts/$name.json"
        return listOf(Paths.get(rel), Paths.get("app/$rel")).first { Files.exists(it) }
    }

    /** The bundled page as keys, read as text one key object per line. */
    private fun page(name: String): KeyboardLayout {
        val keys = Files.readAllLines(layoutPath(name)).mapNotNull { line ->
            if (!line.trimStart().startsWith("{ \"id\"")) return@mapNotNull null
            fun str(f: String) = Regex(""""$f": "((?:[^"\\]|\\.)*)"""").find(line)?.groupValues?.get(1)?.replace("\\\\", "\\")
            fun num(f: String) = Regex(""""$f": ([0-9.]+)""").find(line)!!.groupValues[1].toFloat()
            val alts = Regex(""""alternates": \[([^\]]*)]""").find(line)?.groupValues?.get(1)
                ?.let { a -> Regex(""""((?:[^"\\]|\\.)*)"""").findAll(a).map { it.groupValues[1] }.toList() }
                .orEmpty()
            val type = KeyType.fromJson(str("type")!!)
            Key(
                id = str("id")!!,
                type = type,
                label = str("label").orEmpty(),
                output = str("output").orEmpty(),
                x = num("x"), y = num("y"), w = num("w"), h = num("h"),
                hint = str("hint"),
                alternates = alts,
            )
        }
        assertTrue("$name read no keys", keys.size > 30)
        return KeyboardLayout(name, "en_US", keys)
    }
}
