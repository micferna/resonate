package io.github.micferna.resonate.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(ResonateConverters::class)
abstract class ResonateDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val NAME = "resonate.db"
    }
}
