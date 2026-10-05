package com.kinetica.keyboard.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The Contribute screen opens its page in the browser, so the app needs no network permission. */
class ContributeLinkTest {

    private fun read(rel: String): String {
        val direct = Paths.get(rel.removePrefix("app/"))
        val p: Path = if (Files.exists(direct)) direct else Paths.get(rel)
        assumeTrue("$rel not found", Files.exists(p))
        return Files.newBufferedReader(p).use { it.readText() }
    }

    @Test
    fun theManifestStillDeclaresNoNetworkPermission() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("ACCESS_NETWORK_STATE"))
    }

    @Test
    fun theLinkIsTheOneTheReadmePublishes() {
        val readme = Paths.get("../README.md").takeIf { Files.exists(it) } ?: Paths.get("README.md")
        assumeTrue("README.md not found", Files.exists(readme))
        val text = Files.newBufferedReader(readme).use { it.readText() }
        assertTrue(ContributeActivity.KO_FI_URL.startsWith("https://"))
        assertTrue("README does not carry ${ContributeActivity.KO_FI_URL}", text.contains("(${ContributeActivity.KO_FI_URL})"))
    }
}
