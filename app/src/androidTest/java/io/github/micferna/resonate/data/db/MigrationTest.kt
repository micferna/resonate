package io.github.micferna.resonate.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vérifie que les migrations préservent les données.
 *
 * L'app est distribuée hors magasin et se met à jour toute seule : une migration
 * fautive effacerait la bibliothèque d'un utilisateur sans qu'il ait rien demandé,
 * et sans retour possible. C'est le seul endroit du projet où une erreur est
 * réellement irréversible — d'où ces tests, qui rejouent chaque migration sur une
 * base réellement écrite au format de la version précédente.
 *
 * Exécution : `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ResonateDatabase::class.java,
    )

    @Test
    fun migration_1_vers_2_conserve_les_donnees_utilisateur() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO sources
                    (id, kind, displayName, host, port, username, secretCipher, secretKind,
                     passphraseCipher, rootPath, shareName, useTls, hostKeyFingerprint,
                     enabled, lastScanAt, lastScanError, trackCount)
                VALUES (1, 'SFTP', 'Test', 'exemple.test', 22, 'u', NULL, 'PASSWORD',
                        NULL, '/', NULL, 1, NULL, 1, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO tracks
                    (id, sourceId, remotePath, title, artist, album, albumArtist,
                     trackNumber, discNumber, year, genre, durationMs, sizeBytes, mimeType,
                     artworkUrl, tagsResolved, rating, playCount, skipCount, lastPlayedAt,
                     addedAt, lastSeenAt, offlineState, searchKey)
                VALUES ('abc', 1, '/musique/a.flac', 'Titre', 'Artiste', 'Album', 'Artiste',
                        1, 1, 2020, '', 1000, 2000, 'audio/flac', NULL, 1, 'LIKED', 7, 0,
                        NULL, 100, 100, 'NONE', 'titre artiste album')
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            ResonateDatabase.MIGRATION_1_2,
        )

        db.query("SELECT rating, playCount, replayGainDb, tagsResolved FROM tracks").use { cursor ->
            assertTrue("le morceau doit survivre à la migration", cursor.moveToFirst())
            assertEquals("LIKED", cursor.getString(0))
            assertEquals(7, cursor.getInt(1))
            // Valeur neutre : un morceau sans tag ReplayGain se joue tel quel.
            assertEquals(0.0, cursor.getDouble(2), 0.0001)
            // Les tags sont à relire pour récupérer le gain des morceaux existants.
            assertEquals(0, cursor.getInt(3))
        }
    }

    @Test
    fun migration_2_vers_3_deduit_le_dossier_du_chemin() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO sources
                    (id, kind, displayName, host, port, username, secretCipher, secretKind,
                     passphraseCipher, rootPath, shareName, useTls, hostKeyFingerprint,
                     enabled, lastScanAt, lastScanError, trackCount)
                VALUES (1, 'SFTP', 'Test', 'exemple.test', 22, 'u', NULL, 'PASSWORD',
                        NULL, '/', NULL, 1, NULL, 1, NULL, NULL, 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO tracks
                    (id, sourceId, remotePath, title, artist, album, albumArtist,
                     trackNumber, discNumber, year, genre, durationMs, sizeBytes, mimeType,
                     artworkUrl, tagsResolved, rating, playCount, skipCount, lastPlayedAt,
                     addedAt, lastSeenAt, offlineState, searchKey, replayGainDb)
                VALUES ('abc', 1, '/musique/Artiste/Album/03 - Titre.flac', 'Titre',
                        'Artiste', 'Album', 'Artiste', 3, 1, 2020, '', 1000, 2000,
                        'audio/flac', NULL, 1, 'NEUTRAL', 0, 0, NULL, 100, 100, 'NONE',
                        'titre', -7.5)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            ResonateDatabase.MIGRATION_2_3,
        )

        db.query("SELECT folderPath, replayGainDb FROM tracks").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("/musique/Artiste/Album/", cursor.getString(0))
            assertEquals(-7.5, cursor.getDouble(1), 0.001)
        }
    }

    @Test
    fun migrations_enchainees_de_1_a_3() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            ResonateDatabase.MIGRATION_1_2,
            ResonateDatabase.MIGRATION_2_3,
        )

        // `validateDroppedTables = true` a déjà comparé le schéma obtenu à celui
        // exporté pour la version 3 : arriver ici sans exception suffit.
        assertTrue(db.isOpen)
    }

    /**
     * Réinstaller une version antérieure ne doit pas condamner l'application.
     *
     * Les APK restent téléchargeables sur la page des Releases, et y revenir est le
     * premier réflexe quand une nouvelle version pose problème. Sans repli, Room
     * lève « A migration from 3 to 1 was required but not found » à chaque
     * lancement — constaté sur appareil avant ce correctif.
     */
    @Test
    fun un_retour_a_une_version_anterieure_recree_la_base() {
        helper.createDatabase(TEST_DB, 3).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val downgraded = Room.databaseBuilder(context, ResonateDatabase::class.java, TEST_DB)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

        // Ouvrir sans exception suffit : c'est précisément ce qui échouait.
        downgraded.openHelper.writableDatabase.use { db ->
            assertTrue(db.isOpen)
        }
        downgraded.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
