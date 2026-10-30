package com.kinetica.keyboard.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.kinetica.keyboard.R
import com.kinetica.keyboard.ui.SizePreviewView

/**
 * A live picture of the keyboard's size in each orientation's group of Size and layout and at
 * the top of Suggestion bar, so a height or a margin can be judged without leaving settings.
 * [KeyboardPrefsFragment] hands it a new [SizePreviewView.Spec] whenever a size setting changes.
 */
class SizePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    private var spec: SizePreviewView.Spec? = null

    init {
        layoutResource = R.layout.preference_size_preview
        isSelectable = false
        isPersistent = false
    }

    fun show(spec: SizePreviewView.Spec) {
        this.spec = spec
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(R.id.size_preview) as? SizePreviewView)?.spec = spec
    }
}
