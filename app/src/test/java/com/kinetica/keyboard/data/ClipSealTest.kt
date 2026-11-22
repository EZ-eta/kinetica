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

import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The sealed clip file's format, with a plain AES key in place of the Keystore's: the same cipher,
 * IV and tag the phone uses, only the key's home differs.
 */
class ClipSealTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val seal = ClipSeal { key }
    private val clips = listOf(
        Clip(3, "via Roma 12, Milano", 1_700_000_000_000L),
        Clip(2, "caffè ☕ 𝄞", 1_699_999_000_000L),
    )

    @Test
    fun aListRoundTrips() {
        val back = ClipCodec.decode(seal.open(seal.seal(ClipCodec.encode(clips))))
        assertEquals(clips, back)
    }

    @Test
    fun theSealedBytesNeverHoldTheText() {
        val sealed = seal.seal(ClipCodec.encode(clips))
        val asText = String(sealed, Charsets.ISO_8859_1)
        assertFalse(asText.contains("Roma"))
        assertFalse(asText.contains("Milano"))
    }

    @Test
    fun twoSealsOfTheSameListDiffer() {
        val plain = ClipCodec.encode(clips)
        assertNotEquals(seal.seal(plain).toList(), seal.seal(plain).toList())
    }

    @Test(expected = GeneralSecurityException::class)
    fun aChangedByteFailsToOpen() {
        val sealed = seal.seal(ClipCodec.encode(clips))
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 1).toByte()
        seal.open(sealed)
    }

    @Test(expected = GeneralSecurityException::class)
    fun anotherKeyFailsToOpen() {
        val sealed = seal.seal(ClipCodec.encode(clips))
        val other = newKey()
        ClipSeal { other }.open(sealed)
    }

    @Test(expected = IOException::class)
    fun aShortFileIsNotAList() {
        seal.open(byteArrayOf(1, 2, 3))
    }

    @Test
    fun anEmptyListRoundTrips() {
        val plain = ClipCodec.encode(emptyList())
        assertArrayEquals(plain, seal.open(seal.seal(plain)))
        assertEquals(emptyList<Clip>(), ClipCodec.decode(plain))
    }

    @Test(expected = IOException::class)
    fun aCutListThrows() {
        val plain = ClipCodec.encode(clips)
        ClipCodec.decode(plain.copyOf(plain.size - 3))
    }

    @Test(expected = IOException::class)
    fun aCountPastTheCapThrows() {
        val plain = ClipCodec.encode(clips)
        plain[7] = (ClipRules.MAX_CLIPS + 1).toByte()
        ClipCodec.decode(plain)
    }

    @Test(expected = IOException::class)
    fun aWrongMagicThrows() {
        val plain = ClipCodec.encode(clips)
        plain[0] = 0
        ClipCodec.decode(plain)
    }
}
