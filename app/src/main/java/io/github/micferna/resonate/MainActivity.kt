package io.github.micferna.resonate

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.micferna.resonate.ui.AppShellViewModel
import io.github.micferna.resonate.ui.ResonateApp
import io.github.micferna.resonate.ui.theme.ResonateTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Android 13 et suivants exigent une autorisation pour afficher des notifications.
     * Elle est demandée au premier lancement : sans elle, ni la notification de lecture,
     * ni la progression des téléchargements, ni l'annonce d'une mise à jour n'apparaîtraient.
     * Un refus n'empêche rien de fonctionner, il rend seulement ces états invisibles.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* refus accepté */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureSearchQuery(intent)

        setContent {
            val shellViewModel: AppShellViewModel = hiltViewModel()
            val settings by shellViewModel.settings.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) { requestNotificationPermission() }

            // `intent` est mis à jour par onNewIntent : relire à chaque changement
            // permet de traiter aussi les demandes reçues app déjà ouverte.
            LaunchedEffect(pendingSearch) {
                pendingSearch?.let { query ->
                    shellViewModel.playFromSearch(query)
                    pendingSearch = null
                }
            }

            ResonateTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                ResonateApp(shellViewModel = shellViewModel)
            }
        }
    }

    /**
     * Requête vocale en attente de traitement.
     *
     * L'intention peut arriver avant que le lecteur ne soit prêt ; on la garde le
     * temps que l'interface se compose plutôt que de la perdre.
     */
    private var pendingSearch by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureSearchQuery(intent)
    }

    /** « Écoute … sur Resonate » dicté à l'Assistant, notamment via Android Auto. */
    private fun captureSearchQuery(source: Intent?) {
        if (source?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        pendingSearch = source.getStringExtra(SearchManager.QUERY).orEmpty()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
