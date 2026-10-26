package com.kinetica.keyboard.ime

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.preference.PreferenceManager
import com.kinetica.keyboard.engine.DecodeTrace
import com.kinetica.keyboard.settings.KeyboardConfig
import com.kinetica.keyboard.settings.Prefs

/**
 * Keeps the language typed here and Android's input subtype in agreement, both ways: the picker
 * offers the languages enabled here, and a subtype chosen there becomes the language when the
 * sync setting allows it. The French arrangement rides along, since it follows the language.
 */
internal class LanguageSync(private val service: InputMethodService, private val config: () -> KeyboardConfig) {

    private val inputMethodManager by lazy { service.getSystemService(InputMethodManager::class.java) }

    /**
     * This IME's own entry in the system list, or null if the system does not report it.
     *
     * Nullable, not `first { }`: it is read from `onStartInput`, where a throw kills the
     * keyboard instead of one feature. Language synchronisation becomes a no-op.
     */
    private val inputMethodInfo by lazy {
        inputMethodManager.inputMethodList.firstOrNull { it.packageName == service.packageName }
    }

    fun onSubtypeChanged(newSubtype: InputMethodSubtype?) {
        // Inbound only: with the sync off, Android may still change its own subtype but
        // it no longer decides what Kinetica types.
        if (!config().syncSystemLanguage) return
        val language = subtypeLanguage(newSubtype) ?: return
        acceptSubtypeLanguage(language)
    }

    @Suppress("DEPRECATION") // method.xml declares legacy imeSubtypeLocale values.
    private fun subtypeLanguage(subtype: InputMethodSubtype?): String? =
        kineticaLanguageOf(subtype?.locale?.substringBefore('_'))

    private fun acceptSubtypeLanguage(language: String) {
        // A subtype locale this build has no dictionary for must never reach the
        // preference: the load throws, the previous predictor keeps serving, and the
        // settings row shows nothing selected. kineticaLanguageOf already folds the known
        // spellings, so anything left over is a language we do not ship.
        if (language !in Prefs.ALL_LANGUAGES) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(service)
        // Called from onStartInput: asking first avoids opening a preference editor at every
        // field focus when everything already agrees.
        if (!languageSyncNeedsWrite(
                prefs.getString(Prefs.LANGUAGE, null),
                prefs.getString(Prefs.SYNCED_LANGUAGE, null),
                language,
            )
        ) {
            return
        }
        prefs.edit()
            .putString(Prefs.LANGUAGE, language)
            .putString(Prefs.SYNCED_LANGUAGE, language)
            .apply()
    }

    /**
     * Writes [arrangementOnLanguageChange]'s answer, or nothing when there is nothing to
     * write. Cheap enough to ask on every input start, so an existing French user gets
     * AZERTY without re-selecting the language.
     *
     * A write re-enters the preference listener once, harmlessly: the nested call sees the
     * language unchanged and builds the same board the caller is about to build.
     */
    fun applyArrangementForLanguage(prefs: SharedPreferences, language: String) {
        val stored = config().keyArrangement
        val auto = prefs.getBoolean(
            Prefs.ARRANGEMENT_AUTO_APPLIED, Prefs.DEFAULT_ARRANGEMENT_AUTO_APPLIED,
        )
        val change = arrangementOnLanguageChange(stored, auto, language)
        if (change.arrangement == null && change.autoApplied == auto) return
        val edit = prefs.edit()
        change.arrangement?.let { edit.putString(Prefs.KEY_ARRANGEMENT, it) }
        edit.putBoolean(Prefs.ARRANGEMENT_AUTO_APPLIED, change.autoApplied)
        edit.apply()
    }

    /** The subtype set last handed to Android, so an unchanged one is not sent again. */
    private var enabledSubtypeHashes: IntArray? = null

    /**
     * Enables in Android the subtypes of the languages enabled here, so the picker names the
     * language being typed, not the system default (`English (US)` with English off). API
     * 34 is the first with a public call for it; earlier releases keep the system's choice.
     */
    fun syncEnabledSubtypes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val info = inputMethodInfo ?: return
        val subtypes = (0 until info.subtypeCount).map { info.getSubtypeAt(it) }
        val hashes = subtypesFor(subtypes.map { it.locale }, config().enabledLanguages)
            .map { subtypes[it].hashCode() }.toIntArray()
        if (hashes.isEmpty() || hashes.contentEquals(enabledSubtypeHashes)) return
        try {
            inputMethodManager.setExplicitlyEnabledInputMethodSubtypes(info.id, hashes)
            enabledSubtypeHashes = hashes
            DecodeTrace.log { "  subtypes enabled=${config().enabledLanguages}" }
        } catch (e: RuntimeException) {
            // Refused by the system: its own choice stays, as before API 34.
            DecodeTrace.log { "  subtypes refused ${e.javaClass.simpleName}" }
        }
    }

    fun synchronizeLanguageOnStart() {
        syncEnabledSubtypes()
        val prefs = PreferenceManager.getDefaultSharedPreferences(service)
        val systemLanguage = subtypeLanguage(inputMethodManager.currentInputMethodSubtype)
        val language = languageOnInputStart(
            prefs.getString(Prefs.LANGUAGE, null),
            prefs.getString(Prefs.SYNCED_LANGUAGE, null),
            systemLanguage,
            config().syncSystemLanguage,
        )
        if (language == systemLanguage) {
            acceptSubtypeLanguage(language)
        } else {
            requestLanguageSubtype(language)
        }
        applyArrangementForLanguage(prefs, language)
    }

    fun requestLanguageSubtype(language: String) {
        val info = inputMethodInfo ?: return
        // A Settings edit can arrive while another IME is selected. Keep the
        // local choice unacknowledged until this IME is active again.
        if (Settings.Secure.getString(service.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) !=
            info.id
        ) return
        val subtype = (0 until info.subtypeCount).asSequence()
            .map { info.getSubtypeAt(it) }
            .firstOrNull { subtypeLanguage(it) == language } ?: return
        if (subtype == inputMethodManager.currentInputMethodSubtype) {
            acceptSubtypeLanguage(language)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.switchInputMethod(info.id, subtype)
        } else {
            // The service overload was added in API 28; the IME token supplies
            // the same authority through InputMethodManager on Android 8.
            val token = service.window.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            inputMethodManager.setInputMethodAndSubtype(token, info.id, subtype)
        }
    }
}
