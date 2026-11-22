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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The clip store's Android half has no JVM reach: the Keystore and the backup rules are read off
 * disk. A fail-first check needs `--rerun-tasks` or a clean test task.
 */
class ClipStoreSourceTest {

    private val store by lazy { read("app/src/main/java/com/kinetica/keyboard/data/ClipStore.kt") }

    @Test
    fun theKeyIsAKeystoreAesGcmKeyOf256Bits() {
        assertTrue(store.contains("private const val PROVIDER = \"AndroidKeyStore\""))
        assertTrue(store.contains("KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)"))
        assertTrue(store.contains("KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT"))
        assertTrue(store.contains(".setBlockModes(KeyProperties.BLOCK_MODE_GCM)"))
        assertTrue(store.contains(".setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)"))
        assertTrue(store.contains(".setKeySize(256)"))
        // The cipher picks a fresh IV per seal; turning that off would let one be reused.
        assertFalse(store.contains("setRandomizedEncryptionRequired(false)"))
        assertEquals("AES/GCM/NoPadding", ClipSeal.TRANSFORMATION)
    }

    @Test
    fun theFileLivesInNoBackupAndNowhereElse() {
        assertTrue(store.contains("File(context.noBackupFilesDir, FILE)"))
        assertFalse(store.contains("filesDir,"))
        assertFalse(store.contains("cacheDir"))
        assertFalse(store.contains("externalFilesDir"))
        assertEquals("clip_history.sealed", ClipStore.FILE)
    }

    @Test
    fun aFileThatWillNotOpenIsDeletedWithItsKey() {
        val unreadable = Regex("private fun unreadable\\(e: Exception\\): List<Clip> \\{[^}]*wipe\\(\\)").find(store)
        assertTrue("unreadable() must wipe", unreadable != null)
        val wipe = store.substring(store.indexOf("private fun wipe(file: File)"))
        assertTrue(wipe.contains("ClipKey.delete()"))
        assertTrue(store.contains("if (!enabled) return"))
    }

    @Test
    fun noBackupOrDeviceTransferCarriesTheStore() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val exclude = "<exclude domain=\"root\" path=\"no_backup/${ClipStore.FILE}\" />"
        val full = read("app/src/main/res/xml/backup_rules.xml")
        assertTrue(full.contains(exclude))
        val rules = read("app/src/main/res/xml/data_extraction_rules.xml")
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue(cloud.contains(exclude))
        assertTrue(transfer.contains(exclude))
        // Exclusions only: an include would drop everything else from the backup.
        assertFalse(full.contains("<include"))
        assertFalse(rules.contains("<include"))
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun kineticasExportNeverReadsTheStore() {
        val run = read("app/src/main/java/com/kinetica/keyboard/settings/BackupRun.kt")
        val format = read("app/src/main/java/com/kinetica/keyboard/settings/Backup.kt")
        for (src in listOf(run, format)) {
            assertFalse(src.contains("ClipStore"))
            assertFalse(src.contains("ClipHistory"))
            assertFalse(src.contains("noBackupFilesDir"))
        }
    }

    private fun read(rel: String): String {
        val direct = Paths.get(rel.removePrefix("app/"))
        val p: Path = if (Files.exists(direct)) direct else Paths.get(rel)
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }
}
