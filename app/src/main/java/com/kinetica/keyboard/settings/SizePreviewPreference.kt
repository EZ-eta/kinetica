package com.kinetica.keyboard.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.SizePreviewView

/**
 * A live picture of the keyboard's size at the top of Size and layout and of Suggestion bar,
 * so a height or a margin can be judged without leaving settings. [KeyboardPrefsFragment]
 * hands it a new [SizePreviewView.Spec] whenever one of the size settings changes.
 */
class SizePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private var spec: SizePreviewView.Spec? = null
    private var landscape: SizePreviewView.Spec? = null

    init {
        layoutResource = R.layout.preference_size_preview
        isSelectable = false
        isPersistent = false
    }

    /** [spec] in portrait, [landscape] with the phone turned, each with its own settings. */
    fun show(spec: SizePreviewView.Spec, landscape: SizePreviewView.Spec?) {
        this.spec = spec
        this.landscape = landscape
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.size_preview) as? SizePreviewView)?.spec = spec
        (holder.findViewById(R.id.size_preview_landscape) as? SizePreviewView)?.spec = landscape
    }
}
