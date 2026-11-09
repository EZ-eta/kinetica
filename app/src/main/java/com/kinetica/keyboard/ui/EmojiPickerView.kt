package com.kinetica.keyboard.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kinetica.keyboard.R

/**
 * Basic emoji picker: category tabs over a scrollable grid, from the bundled set in
 * [EmojiAssets] (no network, ever). Swapped in place of the KeyboardView by InputContainerView
 * while open.
 */
class EmojiPickerView(
    context: Context,
    private val assets: EmojiAssets,
    private val onEmoji: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onClose: () -> Unit,
    private val onSearch: () -> Unit,
) : LinearLayout(context) {

    private val categories: List<EmojiCatalog.Group>
    private val gridScroll = ScrollView(context)
    private val density = resources.displayMetrics.density
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val tabViews = ArrayList<TextView>()
    private val footerViews = ArrayList<TextView>()
    private var shownCategory = 0

    /**
     * Each tab's grid, built on its first showing and kept: Flags and People hold hundreds of
     * cells, too many to rebuild on every tab switch. Cleared when the colours change.
     *
     * Must stay above the `init` block: initializers run in declaration order, and below it the
     * map is still null when `init` shows the first tab, which kills the keyboard.
     */
    private val grids = HashMap<EmojiCatalog.Group, LinearLayout>()

    /** The set's own entries by character, so a recorded emoji keeps its name. */
    private val byChar = HashMap<String, EmojiCatalog.Entry>()
    private var recentEntries: List<EmojiCatalog.Entry> = emptyList()
    private var recentGroup: EmojiCatalog.Group? = null

    /**
     * The emoji this user picks most, best first, from [EmojiRecents].
     *
     * Pushed when the panel opens, not after every tap, so the first tab never reorders under
     * a finger still tapping. The tab is absent until it has contents, so a fresh install sees
     * the plain panel.
     */
    var recents: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            // Filtered like the asset is: a recent picked on another device, or before a
            // system update removed a font, must not come back as a tofu box.
            recentEntries = value.filter { assets.canDraw(it) }
                .map { byChar[it] ?: EmojiCatalog.Entry(it, "", -1) }
            recentGroup?.let { grids.remove(it) }
            recentGroup = null
            rebuildTabs()
            showCategory(shownCategory.coerceIn(0, (panels().size - 1).coerceAtLeast(0)))
        }

    /**
     * Resolved color roles, pushed from the service like [SuggestionBarView.theme] and
     * [KeyboardView.theme]. Everything the panel draws comes from here, so it follows a custom
     * hue and the light theme.
     */
    var theme: KeyboardTheme = KeyboardTheme.fromResources(context)
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    init {
        orientation = VERTICAL
        categories = assets.catalog.groups
        for (e in assets.catalog.entries) byChar.putIfAbsent(e.ch, e)

        rebuildTabs()
        addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        addView(gridScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(context).apply { orientation = HORIZONTAL }
        // TextViews, not Buttons: a platform Button takes its colours from the app theme and
        // came out light inside a dark keyboard. These are painted from the palette.
        bottom.addView(
            footerControl(context.getString(R.string.emoji_back_to_letters)) { onClose() },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        bottom.addView(
            footerControl(context.getString(R.string.emoji_search)) { onSearch() },
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
     * What the tab strip addresses: the frequently-used panel when it has contents, then the
     * asset's categories. Recomputed, not stored, so the tab index and the list always agree.
     */
    private fun panels(): List<EmojiCatalog.Group> {
        if (recentEntries.isEmpty()) return categories
        val recent = recentGroup
            ?: EmojiCatalog.Group(context.getString(R.string.emoji_recent), RECENT_ICON, recentEntries)
                .also { recentGroup = it }
        return listOf(recent) + categories
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
        // The cells take the colour when a grid is built, so the kept ones are built again.
        grids.clear()
        if (panels().isNotEmpty()) showCategory(shownCategory)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showCategory(index: Int) {
        val shown = panels().getOrNull(index) ?: return
        shownCategory = index
        gridScroll.removeAllViews()
        gridScroll.addView(grids.getOrPut(shown) { buildGrid(shown) })
        gridScroll.scrollTo(0, 0)
    }

    private fun buildGrid(shown: EmojiCatalog.Group): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        val perRow = COLUMNS
        var row: LinearLayout? = null
        for ((i, entry) in shown.entries.withIndex()) {
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
        return grid
    }

    private companion object {
        const val COLUMNS = 8

        /** Marks the frequently-used tab. A star, not a clock: it is ordered by
         *  how often an emoji is used, not by when it last was. */
        const val RECENT_ICON = "\u2605"
    }
}
