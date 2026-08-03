package io.github.micferna.resonate.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.Cache
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        // SupervisorJob : l'échec d'un travail de fond (une indexation qui casse) ne
        // doit pas emporter les autres coroutines de l'application.
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun okHttpClient(@ApplicationContext context: Context): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // illimité : un morceau se lit en continu
            .retryOnConnectionFailure(true)
            // Ce cache sert les métadonnées et les pochettes. L'audio a son propre
            // cache Media3, qui sait indexer des morceaux de fichier et les épingler.
            .cache(Cache(File(context.cacheDir, "http"), HTTP_CACHE_BYTES))
            .build()

    @Provides
    fun callFactory(client: OkHttpClient): Call.Factory = client

    private const val HTTP_CACHE_BYTES = 32L * 1024 * 1024
}
