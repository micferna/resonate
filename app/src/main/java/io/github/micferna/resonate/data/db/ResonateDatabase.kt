package io.github.micferna.resonate.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.github.micferna.resonate.data.db.dao.PlaylistDao
import io.github.micferna.resonate.data.db.dao.SourceDao
import io.github.micferna.resonate.data.db.dao.TrackDao
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.PlaylistEntity
import io.github.micferna.resonate.data.db.entity.PlaylistTrackEntity
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.SecretKind
import io.github.micferna.resonate.data.db.entity.SourceEntity
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.data.db.entity.TrackEntity

/**
 * Les énumérations sont persistées par leur nom plutôt que par leur ordinal :
 * réordonner une énumération ne doit pas réinterpréter silencieusement les
 * lignes déjà en base. Les requêtes d'agrégation du DAO comparent d'ailleurs
 * ces colonnes à des littéraux textuels.
 */
object ResonateConverters {
    @TypeConverter fun sourceKindToString(value: SourceKind): String = value.name

    @TypeConverter fun stringToSourceKind(value: String): SourceKind = SourceKind.valueOf(value)

    @TypeConverter fun secretKindToString(value: SecretKind): String = value.name

    @TypeConverter fun stringToSecretKind(value: String): SecretKind = SecretKind.valueOf(value)

    @TypeConverter fun ratingToString(value: Rating): String = value.name

    @TypeConverter fun stringToRating(value: String): Rating = Rating.valueOf(value)

    @TypeConverter fun offlineStateToString(value: OfflineState): String = value.name

    @TypeConverter fun stringToOfflineState(value: String): OfflineState = OfflineState.valueOf(value)
}

@Database(
    entities = [
        SourceEntity::class,
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(ResonateConverters::class)
abstract class ResonateDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val NAME = "resonate.db"

        /**
         * Ajout du gain ReplayGain.
         *
         * Une migration explicite plutôt qu'une recréation destructive : la v0.1.2
         * est publiée, des bibliothèques existent avec leurs appréciations et leurs
         * compteurs d'écoute. La colonne est ajoutée à zéro, valeur neutre, et se
         * remplira à mesure que la résolution des tags repassera sur les morceaux.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE tracks ADD COLUMN replayGainDb REAL NOT NULL DEFAULT 0",
                )
                // Les tags doivent être relus pour récupérer le gain des morceaux
                // déjà indexés ; la tâche de fond s'en chargera par lots.
                connection.execSQL("UPDATE tracks SET tagsResolved = 0")
            }
        }

        /**
         * Ajout du dossier d'affichage.
         *
         * Les morceaux déjà indexés reçoivent le dossier déduit de leur chemin —
         * exact pour les sources de fichiers. Ceux de la source locale, dont le
         * chemin n'est qu'un identifiant MediaStore, seront corrigés à la
         * prochaine indexation, que la migration déclenche en les marquant comme
         * jamais vus.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE tracks ADD COLUMN folderPath TEXT NOT NULL DEFAULT '/'",
                )
                connection.execSQL(
                    "UPDATE tracks SET folderPath = " +
                        "rtrim(remotePath, replace(remotePath, '/', ''))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracks_folderPath ON tracks(folderPath)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_tracks_genre ON tracks(genre)",
                )
            }
        }
    }
}
