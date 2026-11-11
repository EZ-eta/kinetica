package com.kinetica.keyboard.keys

import com.kinetica.keyboard.layout.KeyType
import com.kinetica.keyboard.layout.LayoutMode
import com.kinetica.keyboard.layout.LayoutTransforms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editing page (#49): its default, what is stored, the limits, the board built from it, held keys. */
class EditPageTest {

    @Test
    fun theDefaultIsTheDPadWithSelectionAndTheClipboard() {
        val rows = EditPage.DEFAULT.rows
        assertEquals(listOf(5, 5, 5, 5), rows.map { it.size })
        assertEquals(EditorAction.SELECT_MODE.output, rows[0][0])
        assertEquals(EditorAction.CTRL_NEXT.output, rows[0].last())
        assertEquals(
            listOf("combo:ctrl+left", EditorAction.ARROW_LEFT.output, EditorAction.ARROW_DOWN.output, EditorAction.ARROW_RIGHT.output, "combo:ctrl+right"),
            rows[2],
        )
        assertEquals(EditorAction.TAB.output, rows[1][0])
        assertEquals(EditorAction.ARROW_UP.output, rows[1][2])
        for (k in rows.flatten()) assertNull(k, EditPage.problem(k))
    }

    @Test
    fun upSitsOverDownOnTheDefaultPage() {
        val keys = EditPage.DEFAULT.layout().keys
        val up = keys.single { it.output == EditorAction.ARROW_UP.output }
        val down = keys.single { it.output == EditorAction.ARROW_DOWN.output }
        assertEquals(down.x + down.w / 2, up.x + up.w / 2, 1e-4f)
        assertTrue(up.y < down.y)
    }

    @Test
    fun aPageTheUserEditedKeepsItsOwnRows() {
        // Stored before Tab joined the default: the old second row, as saved, stays as it was.
        val old = EditPage(
            listOf(
                listOf(EditorAction.SELECT_MODE.output, EditorAction.COPY.output),
                listOf(EditorAction.HOME.output, EditorAction.ARROW_UP.output, EditorAction.END.output, EditorAction.PAGE_UP.output),
            ),
        )
        val read = EditPage.parse(old.serialize())
        assertEquals(old, read)
        assertFalse(EditorAction.TAB.output in read.rows.flatten())
    }

    @Test
    fun aPageReadsBackFromWhatItStores() {
        assertEquals(EditPage.DEFAULT, EditPage.parse(EditPage.DEFAULT.serialize()))
        // Text holding the separators and the escape itself still reads back.
        val page = EditPage(listOf(listOf("a,b", "x|y", "100%", "hi there"), listOf("combo:ctrl+z")))
        assertEquals(page, EditPage.parse(page.serialize()))
    }

    @Test
    fun nothingStoredIsTheDefaultAndAnEmptiedPageStaysEmpty() {
        assertEquals(EditPage.DEFAULT, EditPage.parse(null))
        val empty = EditPage(emptyList())
        assertTrue(empty.serialize().isNotEmpty())
        assertEquals(empty, EditPage.parse(empty.serialize()))
        assertEquals(empty, EditPage.parse(""))
    }

    @Test
    fun textThatDoesNotDecodeIsTheDefault() {
        assertEquals(EditPage.DEFAULT, EditPage.parse("%zz,x"))
    }

    @Test
    fun aStoredPageIsHeldToItsLimits() {
        val row = (1..8).joinToString(",") { "k$it" }
        val parsed = EditPage.parse(List(6) { row }.joinToString("|"))
        assertEquals(EditPage.MAX_ROWS, parsed.rows.size)
        assertTrue(parsed.rows.all { it.size == EditPage.MAX_KEYS })
        // A lone letter, the page's own action and an empty row are dropped.
        val odd = EditPage.parse("a,xy||" + java.net.URLEncoder.encode(EditorAction.EDIT_PAGE.output, "UTF-8"))
        assertEquals(listOf(listOf("xy")), odd.rows)
    }

    @Test
    fun keysAreAddedChangedMovedAndDeleted() {
        val page = EditPage(listOf(listOf("a1", "a2"), List(EditPage.MAX_KEYS) { "b$it" }))
        assertEquals(listOf("a1", "a2", "n"), page.withAdded(0, "n")!!.rows[0])
        assertNull("a full row takes no more", page.withAdded(1, "n"))
        assertEquals(listOf("n"), page.withAdded(2, "n")!!.rows[2])
        val full = EditPage(List(EditPage.MAX_ROWS) { listOf("k") })
        assertNull("a full page takes no row", full.withAdded(EditPage.MAX_ROWS, "n"))
        assertEquals(listOf("a1", "z"), page.withKey(0, 1, "z").rows[0])
        assertEquals(listOf("a2", "a1"), page.moved(0, 0, 1).rows[0])
        assertEquals(page, page.moved(0, 0, -1))
        assertEquals(page, page.moved(0, 1, 1))
        val gone = page.without(0, 0).without(0, 0)
        assertEquals("an emptied row goes", 1, gone.rows.size)
    }

    @Test
    fun theBoardKeepsItsWayBackUnderTheEditedRows() {
        val page = EditPage(listOf(listOf("a1", "b2", "c3"), listOf(EditorAction.ARROW_LEFT.output)))
        val l = page.layout()
        assertEquals(EditPage.NAME, l.name)
        val bottom = l.keys.takeLast(4)
        assertEquals(listOf("mode", "space", "backspace", "enter"), bottom.map { it.id })
        assertEquals(listOf(KeyType.MODE_ALPHA, KeyType.SPACE, KeyType.BACKSPACE, KeyType.ENTER), bottom.map { it.type })
        assertEquals(1f, bottom.sumOf { it.w.toDouble() }.toFloat(), 1e-4f)
        // Three rows share the height; a row's keys share its width.
        assertTrue(l.keys.all { kotlin.math.abs(it.h - 1f / 3f) < 1e-4f })
        assertTrue(l.keys.filter { it.y == 0f }.all { kotlin.math.abs(it.w - 1f / 3f) < 1e-4f })
        assertEquals(1f, l.keys.single { it.output == EditorAction.ARROW_LEFT.output }.w, 1e-4f)
        // No letter key, so the page never turns swiping on.
        assertFalse(l.keys.any { it.isLetter })
        assertFalse(EditPage.DEFAULT.layout().keys.any { it.isLetter })
        // An emptied page is its bottom row alone.
        assertEquals(4, EditPage(emptyList()).layout().keys.size)
    }

    @Test
    fun theBoardIsNeverSplit() {
        assertEquals(LayoutMode.FULL, LayoutTransforms.modeFor(LayoutMode.SPLIT, EditPage.NAME))
        assertEquals(LayoutMode.SPLIT, LayoutTransforms.modeFor(LayoutMode.SPLIT, "qwerty"))
    }

    @Test
    fun movesAndDeleteRepeatWhileHeld() {
        for (a in listOf(EditorAction.ARROW_LEFT, EditorAction.ARROW_RIGHT, EditorAction.ARROW_UP, EditorAction.ARROW_DOWN, EditorAction.FORWARD_DELETE)) {
            assertTrue(a.name, EditPage.repeats(a.output))
        }
        assertTrue(EditPage.repeats("combo:ctrl+left"))
        assertTrue(EditPage.repeats("combo:ctrl+delete"))
        for (o in listOf(EditorAction.HOME.output, EditorAction.PAGE_UP.output, EditorAction.COPY.output, "combo:ctrl+a", "x", "")) {
            assertFalse(o, EditPage.repeats(o))
        }
    }

    @Test
    fun aKeyRefusesALoneLetterAndThePagesOwnAction() {
        assertEquals(EditPage.Problem.LETTER, EditPage.problem("a"))
        assertEquals(EditPage.Problem.LETTER, EditPage.problem("Q"))
        assertNull(EditPage.problem("ab"))
        assertNull(EditPage.problem("1"))
        assertEquals(EditPage.Problem.EMPTY, EditPage.problem(""))
        assertEquals(EditPage.Problem.THIS_PAGE, EditPage.problem(EditorAction.EDIT_PAGE.output))
        assertNotEquals(EditPage.DEFAULT, EditPage(emptyList()))
    }
}
