package com.kinetica.keyboard.ime

import android.content.Context
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/**
 * Which installed voice keyboard the voice key hands the field to.
 *
 * The platform marks one by a subtype whose mode is `voice`, the same test the system's own
 * shortcut lookup makes. A keyboard that types and also dictates declares only `keyboard`, so it is
 * never picked: switching to it would not start listening.
 */
object DictationHandoff {

    /** `InputMethodSubtype.getMode()` of a voice subtype. */
    const val SUBTYPE_MODE = "voice"

    /** The settings screen that names a voice keyboard to install. */
    const val SCREEN = "pref_group_dictation"

    /** Offline and MIT, no network permission, declares a voice subtype. */
    const val SUGGESTED_URL = "https://f-droid.org/packages/org.y20k.voxscribe/"

    /**
     * Offline, declares a voice subtype, but source-available rather than open source and holding
     * network access for its models; the row says both. Its own page, which lists every store.
     */
    const val FUTO_URL = "https://voiceinput.futo.tech/"

    /** An enabled input method as [pick] reads it: its enabled subtypes' modes, in order. */
    data class Enabled(val id: String, val packageName: String, val modes: List<String>)

    /** The input method to switch to, and the index of its voice subtype among [Enabled.modes]. */
    data class Target(val id: String, val subtype: Int)

    /**
     * The first enabled input method, in the system's order, with an enabled voice subtype, or
     * null. This app is skipped by package, so the DEV build never hands off to the release one.
     */
    fun pick(enabled: List<Enabled>, ownPackage: String): Target? {
        for (ime in enabled) {
            if (ime.packageName == ownPackage) continue
            val i = ime.modes.indexOf(SUBTYPE_MODE)
            if (i >= 0) return Target(ime.id, i)
        }
        return null
    }

    /** What [find] returns: the platform objects the switch takes. */
    class Found(val info: InputMethodInfo, val subtype: InputMethodSubtype)

    /**
     * The voice keyboard on this phone, or null. Implicit subtypes count: a voice keyboard may
     * declare no locale, and only the implicit list holds it then.
     */
    fun find(context: Context): Found? {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return null
        val infos = imm.enabledInputMethodList
        val subtypes = infos.map { imm.getEnabledInputMethodSubtypeList(it, true) }
        val enabled = infos.mapIndexed { i, info -> Enabled(info.id, info.packageName, subtypes[i].map { it.mode }) }
        val target = pick(enabled, context.packageName) ?: return null
        val i = infos.indexOfFirst { it.id == target.id }
        return Found(infos[i], subtypes[i][target.subtype])
    }
}
