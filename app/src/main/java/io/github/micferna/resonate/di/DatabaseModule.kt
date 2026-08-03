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
            .addMigrations(ResonateDatabase.MIGRATION_1_2, ResonateDatabase.MIGRATION_2_3)
            /*
             * Retour à une version antérieure : la base est recréée au lieu de faire
             * planter l'app.
             *
             * Les APK restent téléchargeables sur la page des Releases, et réinstaller
             * une version précédente est le premier réflexe quand une nouvelle pose
             * problème. Or l'ancienne ne connaît pas le schéma écrit par la récente :
             * Room refuse d'ouvrir la base et lève une exception à chaque lancement,
             * sans issue autre que l'effacement des données depuis les réglages
             * d'Android — que personne ne devine.
             *
             * Perdre la bibliothèque locale est désagréable mais réparable : une
             * ré-indexation la reconstitue, et Réglages > Sauvegarde et transfert
             * permet de restaurer sources, playlists et appréciations. Une app qui ne
             * démarre plus, elle, n'offre aucune porte de sortie.
             */
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides fun sourceDao(database: ResonateDatabase): SourceDao = database.sourceDao()

    @Provides fun trackDao(database: ResonateDatabase): TrackDao = database.trackDao()

    @Provides fun playlistDao(database: ResonateDatabase): PlaylistDao = database.playlistDao()
}
