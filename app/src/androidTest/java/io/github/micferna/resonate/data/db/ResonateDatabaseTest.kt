package io.github.micferna.resonate.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.micferna.resonate.data.db.dao.TrackMetadataPatch
import io.github.micferna.resonate.data.db.dao.TrackSeenPatch
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.PlaylistEntity
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.SourceEntity
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.data.db.entity.TrackEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vérifie le comportement réel de SQLite : cascades, contraintes d'unicité,
 * agrégations. Ces règles vivent dans le moteur, pas dans le code Kotlin — un
 * test JVM avec des données en mémoire ne prouverait rien à leur sujet.
 *
 * Exécution : `./gradlew connectedDebugAndroidTest` (appareil ou émulateur requis).
 */
@RunWith(AndroidJUnit4::class)
class ResonateDatabaseTest {

    private lateinit var database: ResonateDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ResonateDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun reindexation_preserve_les_donnees_utilisateur() = runTest {
        val sourceId = database.sourceDao().insert(newSource())
        val trackDao = database.trackDao()
        val track = newTrack(sourceId, "/musique/a.flac", stamp = 1_000)

        trackDao.reconcile(listOf(track), emptyList(), emptyList())
        trackDao.setRating(track.id, Rating.LIKED)
        trackDao.recordPlay(track.id, at = 5_000)

        // Second passage d'indexation : le morceau est revu, ses métadonnées
        // « devinées » sont recalculées, mais rien de ce que l'utilisateur a
        // ajouté ne doit disparaître.
        trackDao.reconcile(
            discovered = listOf(track.copy(title = "Titre différent", lastSeenAt = 2_000)),
            metadataPatches = emptyList(),
            seenPatches = listOf(TrackSeenPatch(track.id, lastSeenAt = 2_000, sizeBytes = 4_242)),
        )

        val stored = trackDao.byId(track.id)
        assertNotNull(stored)
        assertEquals(Rating.LIKED, stored!!.rating)
        assertEquals(1, stored.playCount)
        assertEquals(2_000, stored.lastSeenAt)
        assertEquals(4_242, stored.sizeBytes)
        // Le titre d'origine est conservé : seule une source faisant autorité
        // a le droit de le réécrire.
        assertEquals("a", stored.title)
    }

    @Test
    fun une_source_faisant_autorite_rafraichit_les_metadonnees() = runTest {
        val sourceId = database.sourceDao().insert(newSource(SourceKind.SUBSONIC))
        val trackDao = database.trackDao()
        val track = newTrack(sourceId, "/42", stamp = 1_000)
        trackDao.reconcile(listOf(track), emptyList(), emptyList())

        trackDao.reconcile(
            discovered = emptyList(),
            metadataPatches = listOf(
                TrackMetadataPatch(
                    id = track.id,
                    title = "Vrai titre",
                    artist = "Vrai artiste",
                    album = "Vrai album",
                    albumArtist = "Vrai artiste",
                    trackNumber = 4,
                    discNumber = 1,
                    year = 2001,
                    genre = "Electronic",
                    durationMs = 210_000,
                    sizeBytes = 9_000,
                    mimeType = "audio/flac",
                    artworkUrl = "https://exemple/cover",
                    tagsResolved = true,
                    searchKey = "vrai titre vrai artiste vrai album",
                    lastSeenAt = 2_000,
                    folderPath = "/",
                ),
            ),
            seenPatches = emptyList(),
        )

        val stored = trackDao.byId(track.id)!!
        assertEquals("Vrai titre", stored.title)
        assertEquals(2001, stored.year)
        assertTrue(stored.tagsResolved)
    }

    @Test
    fun les_morceaux_disparus_sont_retires_apres_un_balayage() = runTest {
        val sourceId = database.sourceDao().insert(newSource())
        val trackDao = database.trackDao()
        val present = newTrack(sourceId, "/present.flac", stamp = 2_000)
        val disparu = newTrack(sourceId, "/disparu.flac", stamp = 1_000)
        trackDao.reconcile(listOf(present, disparu), emptyList(), emptyList())

        val removed = trackDao.deleteVanished(sourceId, stamp = 2_000)

        assertEquals(1, removed)
        assertNotNull(trackDao.byId(present.id))
        assertNull(trackDao.byId(disparu.id))
    }

    @Test
    fun supprimer_une_source_vide_ses_morceaux_et_les_playlists() = runTest {
        val sourceId = database.sourceDao().insert(newSource())
        val trackDao = database.trackDao()
        val playlistDao = database.playlistDao()
        val track = newTrack(sourceId, "/a.flac", stamp = 1_000)
        trackDao.reconcile(listOf(track), emptyList(), emptyList())

        val now = System.currentTimeMillis()
        val playlistId = playlistDao.insert(
            PlaylistEntity(name = "Test", createdAt = now, updatedAt = now),
        )
        playlistDao.append(playlistId, listOf(track.id), now)
        assertEquals(1, playlistDao.tracks(playlistId).size)

        database.sourceDao().delete(database.sourceDao().byId(sourceId)!!)

        // La cascade doit atteindre `playlist_tracks` : une playlist qui pointerait
        // vers des morceaux disparus produirait des files de lecture cassées.
        assertNull(trackDao.byId(track.id))
        assertEquals(0, playlistDao.tracks(playlistId).size)
    }

    @Test
    fun un_meme_chemin_n_est_indexe_qu_une_fois() = runTest {
        val sourceId = database.sourceDao().insert(newSource())
        val trackDao = database.trackDao()
        val track = newTrack(sourceId, "/doublon.flac", stamp = 1_000)

        trackDao.insertNew(listOf(track))
        // Identifiant différent mais même (sourceId, remotePath) : l'index unique
        // doit empêcher le doublon, sans faire échouer l'indexation.
        trackDao.insertNew(listOf(track.copy(id = "autre-identifiant")))

        assertEquals(1, trackDao.observeAll().first().size)
    }

    @Test
    fun les_statistiques_comptent_les_bons_morceaux() = runTest {
        val sourceId = database.sourceDao().insert(newSource())
        val trackDao = database.trackDao()
        trackDao.insertNew(
            listOf(
                newTrack(sourceId, "/1.flac", 1_000).copy(rating = Rating.LIKED, durationMs = 1_000),
                newTrack(sourceId, "/2.flac", 1_000).copy(
                    offlineState = OfflineState.DOWNLOADED,
                    durationMs = 2_000,
                ),
                newTrack(sourceId, "/3.flac", 1_000).copy(durationMs = 3_000),
            ),
        )

        val stats = trackDao.observeStats().first()

        assertEquals(3, stats.trackCount)
        assertEquals(1, stats.likedCount)
        assertEquals(1, stats.downloadedCount)
        assertEquals(6_000, stats.totalDurationMs)
    }

    // ------------------------------------------------------------------ fixtures

    private fun newSource(kind: SourceKind = SourceKind.SFTP) = SourceEntity(
        kind = kind,
        displayName = "Serveur de test",
        host = "exemple.test",
        port = kind.defaultPort,
        username = "utilisateur",
        secretCipher = null,
    )

    private fun newTrack(sourceId: Long, path: String, stamp: Long) = TrackEntity(
        id = io.github.micferna.resonate.core.util.TrackIdentity.of(sourceId, path),
        sourceId = sourceId,
        remotePath = path,
        title = path.substringAfterLast('/').substringBeforeLast('.'),
        artist = "Artiste",
        album = "Album",
        albumArtist = "Artiste",
        trackNumber = 1,
        discNumber = 1,
        year = 2020,
        genre = "",
        durationMs = 0,
        sizeBytes = 1_000,
        mimeType = "audio/flac",
        addedAt = stamp,
        lastSeenAt = stamp,
        searchKey = "recherche",
    )
}
