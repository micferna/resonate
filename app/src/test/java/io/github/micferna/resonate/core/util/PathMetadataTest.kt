package io.github.micferna.resonate.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La déduction depuis le chemin est ce que voit l'utilisateur juste après l'ajout
 * d'une source, avant que les vrais tags ne soient lus. Elle doit donc être juste
 * sur les agencements réellement répandus.
 */
class PathMetadataTest {

    @Test
    fun `arborescence artiste puis album`() {
        val result = PathMetadata.fromPath("/musique/Radiohead/In Rainbows/03 - Nude.flac", "/musique")

        assertEquals("Radiohead", result.artist)
        assertEquals("In Rainbows", result.album)
        assertEquals("Nude", result.title)
        assertEquals(3, result.trackNumber)
    }

    @Test
    fun `numero de piste avec numero de disque prefixe`() {
        val result = PathMetadata.fromPath("/m/Pink Floyd/The Wall/2-05 Comfortably Numb.mp3", "/m")

        assertEquals(5, result.trackNumber)
        assertEquals("Comfortably Numb", result.title)
    }

    @Test
    fun `separateurs de numero varies`() {
        assertEquals("Idioteque", PathMetadata.fromPath("/a/b/07. Idioteque.mp3", "/").title)
        assertEquals("Idioteque", PathMetadata.fromPath("/a/b/07 Idioteque.mp3", "/").title)
        assertEquals("Idioteque", PathMetadata.fromPath("/a/b/07_Idioteque.mp3", "/").title)
    }

    @Test
    fun `fichier isole au format artiste tiret titre`() {
        val result = PathMetadata.fromPath("/Aphex Twin - Xtal.flac", "/")

        assertEquals("Aphex Twin", result.artist)
        assertEquals("Xtal", result.title)
    }

    @Test
    fun `les dossiers generiques ne deviennent pas des artistes`() {
        val result = PathMetadata.fromPath("/data/music/Boards of Canada/Geogaddi/01 Ready Lets Go.mp3", "/data")

        assertEquals("Boards of Canada", result.artist)
        assertEquals("Geogaddi", result.album)
    }

    @Test
    fun `chemin sans dossier informatif retombe sur inconnu`() {
        val result = PathMetadata.fromPath("/track.mp3", "/")

        assertEquals(PathMetadata.UNKNOWN_ARTIST, result.artist)
        assertEquals(PathMetadata.UNKNOWN_ALBUM, result.album)
        assertEquals("track", result.title)
    }

    @Test
    fun `un titre entierement numerique n est pas confondu avec un numero de piste`() {
        // « 1979 » est le titre, pas la piste 19 suivie de « 79 ».
        val result = PathMetadata.fromPath("/m/Smashing Pumpkins/Mellon Collie/1979.mp3", "/m")

        assertEquals("1979", result.title)
        assertEquals(0, result.trackNumber)
    }

    @Test
    fun `la cle de recherche agrege et normalise`() {
        assertEquals(
            "nude radiohead in rainbows",
            buildSearchKey("Nude", "Radiohead", "In Rainbows"),
        )
    }

    @Test
    fun `la cle de recherche ignore les champs vides`() {
        assertEquals("nude radiohead", buildSearchKey("Nude", "Radiohead", ""))
    }
}
