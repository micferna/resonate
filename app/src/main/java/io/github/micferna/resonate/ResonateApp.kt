package io.github.micferna.resonate

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class ResonateApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        installFullBouncyCastle()
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
