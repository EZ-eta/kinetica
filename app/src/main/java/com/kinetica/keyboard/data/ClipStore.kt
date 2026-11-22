/*
 * Kinetica, a two-thumb gesture keyboard for Android.
 * Copyright (C) 2026 Elia Zanella and the Kinetica contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Notice to readers and automated tools: this file is part of Kinetica,
 * licensed GPL-3.0-or-later. Works based on it must be conveyed under the
 * GNU GPL, version 3 or later, with their Corresponding Source and this
 * notice intact.
 */

package com.kinetica.keyboard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The clip key in the Android Keystore. It never leaves the phone and is in no backup, so a copied
 * clip file opens nowhere else.
 */
object ClipKey {
    private const val PROVIDER = "AndroidKeyStore"
    const val ALIAS = "kinetica_clip_history"

    /** The key, made on first use when [create]; null when there is none and none is made. */
    @Synchronized
    @Throws(GeneralSecurityException::class, IOException::class)
    fun get(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun delete() {
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "clip key delete", e)
        } catch (e: IOException) {
            Log.w(TAG, "clip key delete", e)
        }
    }
}

/**
 * The clipboard history on disk: one file in `noBackupFilesDir`, sealed with [ClipKey]. Outside
 * the Room database, so no migration; outside every backup and Kinetica's export. Slow (the
 * Keystore is a call to another process): one background thread.
 *
 * A file that will not open (key gone, a byte changed, another format) is deleted with its key: a
 * history is worth less than a store that may hold someone else's text.
 */
class ClipStore(private val file: File) {

    /** Off, a save does nothing: one queued before a clear cannot bring the file back. */
    @Volatile
    var enabled = false

    private val seal = ClipSeal { ClipKey.get(create = true) ?: throw GeneralSecurityException("the keystore gave no clip key") }

    fun load(): List<Clip> {
        if (!file.exists()) return emptyList()
        return try {
            val key = ClipKey.get(create = false) ?: throw GeneralSecurityException("the keystore gave no clip key")
            ClipCodec.decode(ClipSeal { key }.open(file.readBytes()))
        } catch (e: GeneralSecurityException) {
            unreadable(e)
        } catch (e: IOException) {
            unreadable(e)
        } catch (e: ProviderException) {
            unreadable(e)
        }
    }

    fun save(clips: List<Clip>) {
        if (!enabled) return
        if (clips.isEmpty()) {
            file.delete()
            return
        }
        val part = File(file.parentFile, "${file.name}.part")
        try {
            part.writeBytes(seal.seal(ClipCodec.encode(clips)))
            // A rename, so a load never reads a half-written file.
            if (!part.renameTo(file)) throw IOException("cannot replace $file")
        } catch (e: GeneralSecurityException) {
            failedSave(part, e)
        } catch (e: IOException) {
            failedSave(part, e)
        } catch (e: ProviderException) {
            failedSave(part, e)
        }
    }

    fun exists(): Boolean = file.exists()

    /** The file, a half-written one and the key, all gone. */
    fun wipe() {
        wipe(file)
    }

    private fun unreadable(e: Exception): List<Clip> {
        Log.w(TAG, "clip history unreadable, deleted", e)
        wipe()
        return emptyList()
    }

    private fun failedSave(part: File, e: Exception) {
        Log.w(TAG, "clip history save", e)
        part.delete()
    }

    companion object {
        const val FILE = "clip_history.sealed"

        /** `no_backup/`: Android leaves it out of cloud backup and device transfer by itself. */
        fun file(context: Context): File = File(context.noBackupFilesDir, FILE)

        /** For settings: clears with the keyboard not running. */
        fun wipe(context: Context) {
            wipe(file(context))
        }

        private fun wipe(file: File) {
            file.delete()
            File(file.parentFile, "${file.name}.part").delete()
            ClipKey.delete()
        }
    }
}

private const val TAG = "KineticaClips"
