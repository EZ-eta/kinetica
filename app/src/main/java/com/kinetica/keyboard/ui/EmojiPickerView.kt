package com.kinetica.keyboard.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
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

    private class Category(val name: String, val icon: String, val emoji: List<String>)

    private val categories: List<Category>
    private val gridScroll = ScrollView(context)
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }
    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.kbd_background))
        categories = loadCategories()

        val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
        for ((i, cat) in categories.withIndex()) {
            tabs.addView(
                TextView(context).apply {
                    text = cat.icon
                    contentDescription = cat.name
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener { showCategory(i) }
                },
            )
        }
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
        bottom.addView(
            Button(context).apply {
                text = context.getString(R.string.emoji_back_to_letters)
                setOnClickListener { onClose() }
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        bottom.addView(
            Button(context).apply {
                text = "⌫"
                setOnClickListener { onBackspace() }
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        if (categories.isNotEmpty()) showCategory(0)
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
            val chars = ArrayList<String>(arr.length())
            for (j in 0 until arr.length()) {
                chars.add(arr.getJSONObject(j).getString("ch"))
            }
            out.add(Category(c.getString("name"), c.getString("icon"), chars))
        }
        return out
    }

    private fun showCategory(index: Int) {
        grid.removeAllViews()
        gridScroll.scrollTo(0, 0)
        val perRow = COLUMNS
        var row: LinearLayout? = null
        for ((i, ch) in categories[index].emoji.withIndex()) {
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
    }
}
