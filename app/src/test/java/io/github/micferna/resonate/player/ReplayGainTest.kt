package io.github.micferna.resonate.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La conversion gain → volume agit sur chaque morceau lu : une erreur ici rendrait
 * la bibliothèque inaudible ou saturée, sans message d'erreur pour l'expliquer.
 */
class ReplayGainTest {

    @Test
    fun `un morceau sans tag garde son volume d'origine`() {
        assertEquals(1f, replayGainToVolume(0f), 0.0001f)
    }

    @Test
    fun `moins six decibels correspond a la moitie de l'amplitude`() {
        // -6,02 dB est le rapport exact de 1/2 en amplitude.
        assertEquals(0.5f, replayGainToVolume(-6.02f), 0.01f)
    }

    @Test
    fun `moins vingt decibels correspond au dixieme`() {
        assertEquals(0.1f, replayGainToVolume(-20f), 0.01f)
    }

    @Test
    fun `un gain positif n'amplifie pas au-dela du volume nominal`() {
        // Amplifier écrêterait les morceaux déjà forts : le facteur est plafonné.
        assertEquals(1f, replayGainToVolume(6f), 0.0001f)
        assertEquals(1f, replayGainToVolume(20f), 0.0001f)
    }

    @Test
    fun `un gain aberrant ne rend pas le morceau inaudible`() {
        val volume = replayGainToVolume(-90f)
        assertTrue("volume=$volume", volume >= 0.1f)
    }

    @Test
    fun `l'attenuation est monotone`() {
        val quiet = replayGainToVolume(-3f)
        val quieter = replayGainToVolume(-9f)
        assertTrue("$quieter devrait être inférieur à $quiet", quieter < quiet)
    }
}
