package io.github.micferna.resonate.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.micferna.resonate.source.ResonateDataSourceFactory
import io.github.micferna.resonate.source.SourceConnector
import io.github.micferna.resonate.source.SourceRegistry
import io.github.micferna.resonate.source.local.LocalConnector
import io.github.micferna.resonate.source.sftp.SftpConnector
import io.github.micferna.resonate.source.smb.SmbConnector
import io.github.micferna.resonate.source.subsonic.SubsonicConnector
import io.github.micferna.resonate.source.webdav.WebDavConnector
import javax.inject.Singleton

/**
 * Enregistre les connecteurs dans un ensemble, plutôt que dans un `when` sur le
 * protocole. Ajouter un protocole se résume alors à une ligne ici : rien d'autre
 * dans l'app n'énumère les types de sources.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SourceModule {

    @Binds @IntoSet abstract fun local(connector: LocalConnector): SourceConnector

    @Binds @IntoSet abstract fun sftp(connector: SftpConnector): SourceConnector

    @Binds @IntoSet abstract fun smb(connector: SmbConnector): SourceConnector

    @Binds @IntoSet abstract fun webdav(connector: WebDavConnector): SourceConnector

    @Binds @IntoSet abstract fun subsonic(connector: SubsonicConnector): SourceConnector

    companion object {
        @Provides
        @Singleton
        fun dataSourceFactory(registry: SourceRegistry): ResonateDataSourceFactory =
            ResonateDataSourceFactory(registry)
    }
}
