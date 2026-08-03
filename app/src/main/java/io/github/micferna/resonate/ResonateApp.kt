package io.github.micferna.resonate

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.micferna.resonate.di.ApplicationScope
import io.github.micferna.resonate.player.OfflineLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class ResonateApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    // Un Provider, et non l'instance : demander l'injection directe forcerait la
    // construction des caches sur le thread principal, ce que l'on cherche à éviter.
    @Inject lateinit var offlineLibrary: Provider<OfflineLibrary>

    override fun onCreate() {
        super.onCreate()
        installFullBouncyCastle()
        warmUpMediaCaches()
    }

    /**
     * Construit les caches Media3 hors du thread principal.
     *
     * `SimpleCache` inventorie son répertoire à la construction : sur une
     * bibliothèque de plusieurs milliers de morceaux hors-ligne, cela représente un
     * parcours de fichiers non négligeable. Or les deux endroits qui en ont besoin —
     * l'injection dans un ViewModel et `PlaybackService.onCreate()` — s'exécutent sur
     * le thread principal, et paieraient donc ce parcours en gel de l'interface.
     *
     * Les instances étant des singletons, les construire ici en tâche de fond fait
     * que tout le monde les trouve déjà prêtes ensuite.
     */
    private fun warmUpMediaCaches() {
        applicationScope.launch(Dispatchers.IO) {
            runCatching { offlineLibrary.get() }
                .onFailure { android.util.Log.w("ResonateApp", "Préchauffage des caches échoué", it) }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.WARN)
            .build()

    /**
     * Android livre sous le nom « BC » une version tronquée de BouncyCastle, amputée
     * de la plupart des algorithmes. sshj y chercherait des chiffrements et des
     * courbes elliptiques qui n'y sont pas, et échouerait à négocier avec un serveur
     * OpenSSH récent. On remplace donc ce fournisseur par celui embarqué avec l'app,
     * complet et maîtrisé en version.
     */
    private fun installFullBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
                ?.javaClass?.name == BouncyCastleProvider::class.java.name
        ) {
            return
        }
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }
}
