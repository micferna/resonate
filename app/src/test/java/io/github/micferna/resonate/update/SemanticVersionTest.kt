package io.github.micferna.resonate.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cette comparaison décide si l'app propose une mise à jour. Une erreur ici se traduit
 * soit par une notification qui revient sans fin, soit par une version installée qui
 * n'est jamais mise à jour — deux défauts très visibles.
 */
class SemanticVersionTest {

    @Test
    fun `le prefixe v est accepte`() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parseOrNull("v1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parseOrNull("1.2.3"))
    }

    @Test
    fun `le correctif est facultatif`() {
        assertEquals(SemanticVersion(2, 1, 0), SemanticVersion.parseOrNull("v2.1"))
    }

    @Test
    fun `un tag illisible est ignore plutot que devine`() {
        assertNull(SemanticVersion.parseOrNull("latest"))
        assertNull(SemanticVersion.parseOrNull("release-2024"))
        assertNull(SemanticVersion.parseOrNull(""))
        assertNull(SemanticVersion.parseOrNull("v.1.2"))
    }

    @Test
    fun `ordre sur chaque composante`() {
        val base = SemanticVersion(1, 2, 3)
        assertTrue(SemanticVersion(2, 0, 0) > base)
        assertTrue(SemanticVersion(1, 3, 0) > base)
        assertTrue(SemanticVersion(1, 2, 4) > base)
        assertTrue(SemanticVersion(1, 2, 2) < base)
        assertTrue(SemanticVersion(0, 9, 9) < base)
    }

    @Test
    fun `une version definitive prime sur sa preversion`() {
        val stable = SemanticVersion.parseOrNull("v1.0.0")!!
        val beta = SemanticVersion.parseOrNull("v1.0.0-beta.1")!!

        assertTrue(stable > beta)
        assertTrue(beta < stable)
    }

    @Test
    fun `deux versions identiques ne declenchent pas de mise a jour`() {
        val installed = SemanticVersion.parseOrNull("0.1.0")!!
        val published = SemanticVersion.parseOrNull("v0.1.0")!!

        assertEquals(0, published.compareTo(installed))
    }

    @Test
    fun `representation textuelle`() {
        assertEquals("1.2.3", SemanticVersion(1, 2, 3).toString())
        assertEquals("1.2.3-rc1", SemanticVersion(1, 2, 3, "rc1").toString())
    }
}
