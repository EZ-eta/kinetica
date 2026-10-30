package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a commit is learned with no primary language, when the resident languages are passed and
 * [SharedWordFiling]'s held map reads the bundled lists alone.
 */
class FollowFilingTest {

    private val residents = listOf("en", "it")

    @Test
    fun aWordOneListHoldsIsFiledThere() {
        // Provenance English: the decode drew it from a copy the user's learning put there.
        assertEquals("it", SharedWordFiling().languageFor(mapOf("it" to 179), "en", residents))
    }

    @Test
    fun aWordOneListHoldsKeepsItsProvenanceWithAPrimary() {
        assertEquals("en", SharedWordFiling().languageFor(mapOf("it" to 179), "en"))
    }

    @Test
    fun anUnheldWordGoesWhereTheLastSingleListWordWent() {
        val f = SharedWordFiling()
        f.languageFor(mapOf("it" to 170), "it", residents)
        // Provenance English: a tapped word no candidate list holds defaults to the active one.
        assertEquals("it", f.languageFor(emptyMap(), "en", residents))
    }

    @Test
    fun anUnheldWordKeepsItsProvenanceWithAPrimary() {
        val f = SharedWordFiling()
        f.languageFor(mapOf("it" to 170), "it")
        assertEquals("en", f.languageFor(emptyMap(), "en"))
    }

    @Test
    fun anUnheldWordWithNoSingleListWordKeepsItsProvenance() {
        assertEquals("en", SharedWordFiling().languageFor(emptyMap(), "en", residents))
    }

    @Test
    fun aLastSingleLanguageThatLeftIsIgnored() {
        val f = SharedWordFiling()
        f.languageFor(mapOf("pl" to 170), "pl", listOf("pl", "en"))
        assertEquals("en", f.languageFor(emptyMap(), "en", residents))
    }
}
