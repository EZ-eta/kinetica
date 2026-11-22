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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The clip list as bytes: a magic word, a count, then id, time and UTF-8 text per clip. Read with
 * every length checked, so a damaged file fails as [IOException] instead of allocating its garbage.
 */
object ClipCodec {
    private const val MAGIC = 0x4B434831 // "KCH1"

    fun encode(clips: List<Clip>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(clips.size)
            for (c in clips) {
                val text = c.text.toByteArray(Charsets.UTF_8)
                out.writeLong(c.id)
                out.writeLong(c.copiedAt)
                out.writeInt(text.size)
                out.write(text)
            }
        }
        return bytes.toByteArray()
    }

    @Throws(IOException::class)
    fun decode(bytes: ByteArray): List<Clip> {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("no clip list header")
                val n = input.readInt()
                if (n < 0 || n > ClipRules.MAX_CLIPS) throw IOException("clip count $n")
                return List(n) {
                    val id = input.readLong()
                    val at = input.readLong()
                    val len = input.readInt()
                    // UTF-8 takes at most 4 bytes per char (a pair of surrogates is 4 for 2).
                    if (len < 0 || len > ClipRules.MAX_CLIP_CHARS * 4) throw IOException("clip length $len")
                    val text = ByteArray(len)
                    input.readFully(text)
                    Clip(id, String(text, Charsets.UTF_8), at)
                }
            }
        } catch (e: EOFException) {
            throw IOException("clip list cut short", e)
        }
    }
}

/**
 * Seals bytes with AES-GCM under [key]: a version byte, the cipher's own 12-byte IV, then the
 * ciphertext and its 128-bit tag. GCM, so a changed byte fails to open rather than reading as other
 * text. The key comes from the caller: the Android Keystore on the phone, a plain key in tests.
 */
class ClipSeal(private val key: () -> SecretKey) {

    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "iv of ${iv.size} bytes, want $IV_BYTES" }
        val body = cipher.doFinal(plain)
        return byteArrayOf(VERSION) + iv + body
    }

    /** Throws [java.security.GeneralSecurityException] for a wrong key or a changed byte. */
    @Throws(IOException::class, java.security.GeneralSecurityException::class)
    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < 1 + IV_BYTES + TAG_BITS / 8 || sealed[0] != VERSION) throw IOException("not a sealed clip list")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
        cipher.updateAAD(AAD)
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        private const val VERSION: Byte = 1

        /** Binds the bytes to this use: a sealed blob of another kind does not open as clips. */
        private val AAD = "kinetica-clips-1".toByteArray(Charsets.UTF_8)
    }
}
