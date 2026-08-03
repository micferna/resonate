package io.github.micferna.resonate

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

        setContent {
            val shellViewModel: AppShellViewModel = hiltViewModel()
            val settings by shellViewModel.settings.collectAsStateWithLifecycle()

            LaunchedEffect(Unit) { requestNotificationPermission() }

            ResonateTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                ResonateApp(shellViewModel = shellViewModel)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
