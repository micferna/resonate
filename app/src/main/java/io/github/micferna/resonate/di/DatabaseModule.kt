package io.github.micferna.resonate.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.micferna.resonate.data.db.ResonateDatabase
import io.github.micferna.resonate.data.db.dao.PlaylistDao
import io.github.micferna.resonate.data.db.dao.SourceDao
import io.github.micferna.resonate.data.db.dao.TrackDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ResonateDatabase =
        Room.databaseBuilder(context, ResonateDatabase::class.java, ResonateDatabase.NAME)
            // Journalisation WAL : l'indexation écrit par lots de plusieurs centaines
            // de lignes pendant que l'UI lit la bibliothèque. En mode journal classique,
            // chaque lot bloquerait les lectures et ferait saccader les listes.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun sourceDao(database: ResonateDatabase): SourceDao = database.sourceDao()

    @Provides fun trackDao(database: ResonateDatabase): TrackDao = database.trackDao()

    @Provides fun playlistDao(database: ResonateDatabase): PlaylistDao = database.playlistDao()
}
