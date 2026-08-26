package com.kinetica.keyboard.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kinetica.keyboard.R
import org.json.JSONObject

/**
 * Basic emoji picker: category tabs over a scrollable grid, loaded from the
 * bundled assets/emoji_data.json (no network, ever). Swapped in place of the
 * KeyboardView by InputContainerView while open.
 */
class EmojiPickerView(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {

    /**
     * One emoji with the metadata the asset carries. [name] and [keywords] are
     * unused by the tabs, and kept deliberately: emoji_data.json ships them, and
     * a frequently-used or searchable panel needs them. Dropping them again would
     * mean re-reading the asset differently later for no gain now.
     */
    private class Emoji(val ch: String, val name: String, val keywords: List<String>)

    private class Category(val name: String, val icon: String, val emoji: List<Emoji>)

    private val categories: List<Category>
    private val gridScroll = ScrollView(context)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val density = resources.displayMetrics.density
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val tabViews = ArrayList<TextView>()
    private val footerViews = ArrayList<TextView>()
    private var shownCategory = 0

    /** The asset's own entries by character, so a recorded emoji keeps its name. */
    private val byChar = HashMap<String, Emoji>()
    private var recentEntries: List<Emoji> = emptyList()

    /**
     * The emoji this user picks most, best first, from [EmojiRecents].
     *
     * Pushed when the panel is opened rather than after every tap, deliberately:
     * re-ordering the first tab under a finger that is still tapping would move
     * the next cell out from under it. The tab is absent entirely until there is
     * something to put in it, so a fresh install sees exactly the shipped panel.
     */
    var recents: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            recentEntries = value.map { byChar[it] ?: Emoji(it, "", emptyList()) }
            rebuildTabs()
            showCategory(shownCategory.coerceIn(0, (panels().size - 1).coerceAtLeast(0)))
        }

    /**
     * Resolved color roles, pushed from the service like [SuggestionBarView.theme]
     * and [KeyboardView.theme].
     *
     * The panel used to paint itself from R.color.kbd_background directly, so it
     * was wired to the bundled dark palette and never followed a custom hue or the
     * light theme - and its two footer controls were platform Buttons, which an
     * F-Droid reviewer saw rendering in the system light style inside a dark
     * keyboard. Everything the panel draws now comes from here.
     */
    var theme: KeyboardTheme = KeyboardTheme.fromResources(context)
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    init {
        orientation = VERTICAL
        categories = loadCategories()
        for (cat in categories) for (e in cat.emoji) byChar.putIfAbsent(e.ch, e)

        rebuildTabs()
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        gridScroll.addView(grid)
        addView(gridScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
        // TextViews, not Buttons: a platform Button carries its own background and
        // text colour from the app theme, which is how these came out light inside
        // a dark keyboard. These are painted from the palette like every other
        // surface here.
        bottom.addView(
            footerControl(context.getString(R.string.emoji_back_to_letters)) { onClose() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        bottom.addView(
            footerControl("\u232b") { onBackspace() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        applyTheme()
        if (panels().isNotEmpty()) showCategory(0)
    }

    /**
     * What the tab strip addresses: the frequently-used panel when it has
     * contents, then the asset's categories. Recomputed rather than stored so
     * the tab index and the panel list can never disagree.
     */
    private fun panels(): List<Category> =
        if (recentEntries.isEmpty()) {
            categories
        } else {
            listOf(Category(context.getString(R.string.emoji_recent), RECENT_ICON, recentEntries)) + categories
        }

    private fun rebuildTabs() {
        tabs.removeAllViews()
        tabViews.clear()
        for ((i, cat) in panels().withIndex()) {
            val tab = TextView(context).apply {
                text = cat.icon
                contentDescription = cat.name
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setTextColor(theme.keyText)
                setOnClickListener { showCategory(i) }
            }
            tabViews.add(tab)
            tabs.addView(tab)
        }
    }

    private fun footerControl(label: String, onTap: () -> Unit): TextView {
        val v = TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            setOnClickListener { onTap() }
        }
        footerViews.add(v)
        return v
    }

    /** Stains every surface the panel owns from the resolved roles. */
    private fun applyTheme() {
        setBackgroundColor(theme.background)
        for (t in tabViews) t.setTextColor(theme.keyText)
        for (f in footerViews) {
            f.setTextColor(theme.keyText)
            f.setBackgroundColor(theme.keySpecial)
        }
        // The grid is rebuilt per category, so its cells take the colour there.
        if (panels().isNotEmpty()) showCategory(shownCategory)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun loadCategories(): List<Category> {
        val json = context.assets.open("emoji_data.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json).getJSONArray("categories")
        val out = ArrayList<Category>(root.length())
        for (i in 0 until root.length()) {
            val c = root.getJSONObject(i)
            val arr = c.getJSONArray("emoji")
            val entries = ArrayList<Emoji>(arr.length())
            for (j in 0 until arr.length()) {
                val e = c.getJSONArray("emoji").getJSONObject(j)
                val kwArr = e.optJSONArray("kw")
                val kw = if (kwArr == null) {
                    emptyList()
                } else {
                    List(kwArr.length()) { kwArr.getString(it) }
                }
                entries.add(Emoji(e.getString("ch"), e.optString("name", ""), kw))
            }
            out.add(Category(c.getString("name"), c.getString("icon"), entries))
        }
        return out
    }

    private fun showCategory(index: Int) {
        val shown = panels().getOrNull(index) ?: return
        shownCategory = index
        grid.removeAllViews()
        gridScroll.scrollTo(0, 0)
        val perRow = COLUMNS
        var row: LinearLayout? = null
        for ((i, entry) in shown.emoji.withIndex()) {
            val ch = entry.ch
            if (i % perRow == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            row?.addView(
                TextView(context).apply {
                    text = ch
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, dp(6))
                    setTextColor(theme.keyText)
                    setOnClickListener { onEmoji(ch) }
                },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        // Pad the last row so cells keep equal width.
        val last = grid.getChildAt(grid.childCount - 1) as? LinearLayout
        if (last != null) {
            while (last.childCount < perRow) {
                last.addView(TextView(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    private companion object {
        const val COLUMNS = 8

        /** Marks the frequently-used tab. A star, not a clock: it is ordered by
         *  how often an emoji is used, not by when it last was. */
        const val RECENT_ICON = "\u2605"
    }
}
