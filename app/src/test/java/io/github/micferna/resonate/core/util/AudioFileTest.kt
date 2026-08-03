package io.github.micferna.resonate.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFileTest {

    @Test
    fun `les extensions audio courantes sont reconnues`() {
        listOf("a.mp3", "b.FLAC", "c.m4a", "d.opus", "e.Ogg", "f.wav").forEach {
            assertTrue("$it devrait être indexé", AudioFile.isSupported(it))
        }
    }

    @Test
    fun `les fichiers non audio sont ignores a l indexation`() {
        listOf("cover.jpg", "notes.txt", "album.cue", "video.mkv.part", "sans-extension").forEach {
            assertFalse("$it ne devrait pas être indexé", AudioFile.isSupported(it))
        }
    }

    @Test
    fun `type mime deduit de l extension`() {
        assertEquals("audio/mpeg", AudioFile.mimeTypeOf("track.mp3"))
        assertEquals("audio/flac", AudioFile.mimeTypeOf("track.FLAC"))
        assertEquals("audio/*", AudioFile.mimeTypeOf("track.inconnu"))
    }

    @Test
    fun `nom de base sans extension`() {
        assertEquals("01 - Nude", AudioFile.baseNameOf("01 - Nude.flac"))
        assertEquals("piste.avec.points", AudioFile.baseNameOf("piste.avec.points.mp3"))
    }
}

/**
 * L'identifiant d'un morceau conditionne la préservation des likes, des compteurs
 * d'écoute et de l'appartenance aux playlists d'une indexation à l'autre : il doit
 * être strictement reproductible.
 */
class TrackIdentityTest {

    @Test
    fun `identifiant stable pour une meme source et un meme chemin`() {
        val first = TrackIdentity.of(7, "/musique/a/b.flac")
        val second = TrackIdentity.of(7, "/musique/a/b.flac")

        assertEquals(first, second)
    }

    @Test
    fun `deux sources distinctes ne partagent pas l identifiant d un meme chemin`() {
        assertNotEquals(
            TrackIdentity.of(1, "/musique/a/b.flac"),
            TrackIdentity.of(2, "/musique/a/b.flac"),
        )
    }

    @Test
    fun `deux chemins distincts donnent des identifiants distincts`() {
        assertNotEquals(
            TrackIdentity.of(1, "/musique/a/b.flac"),
            TrackIdentity.of(1, "/musique/a/c.flac"),
        )
    }

    @Test
    fun `format hexadecimal de 32 caracteres`() {
        val id = TrackIdentity.of(42, "/x/y.mp3")

        assertEquals(32, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }
}
