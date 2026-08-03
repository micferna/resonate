package io.github.micferna.resonate.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.micferna.resonate.data.prefs.NetworkPolicy
import io.github.micferna.resonate.data.prefs.Settings
import io.github.micferna.resonate.data.prefs.ThemeMode
import io.github.micferna.resonate.ui.formatBytes
import io.github.micferna.resonate.ui.formatRelativeTime
import io.github.micferna.resonate.ui.formatTotalDuration
import io.github.micferna.resonate.ui.pluralize
import io.github.micferna.resonate.update.UpdateProgress

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Le sélecteur de fichiers du système : aucune permission de stockage requise,
    // et l'utilisateur choisit lui-même où atterrit un fichier qui contient ses
    // mots de passe.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportConfiguration) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importConfiguration) }

    // Durée longue, et non la valeur par défaut : ces messages répondent tous à un
    // geste délibéré — vérifier les mises à jour, exporter, importer — et plusieurs
    // portent des décomptes qu'il faut avoir le temps de lire. Une confirmation qui
    // s'efface en une seconde équivaut à ne rien afficher : l'utilisateur appuie,
    // ne voit rien, et appuie de nouveau.
    LaunchedEffect(Unit) {
        viewModel.messages.collect {
            snackbarHost.showSnackbar(it, duration = SnackbarDuration.Long)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Réglages") }) },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 88.dp),
        ) {
            UpdateCard(
                state = update,
                onCheck = viewModel::checkForUpdate,
                onInstall = viewModel::installUpdate,
                onDismiss = viewModel::dismissUpdate,
                onGrantPermission = { context.startActivity(viewModel.installPermissionIntent()) },
            )

            Section("Bibliothèque")
            InfoRow("Morceaux", pluralize(stats.trackCount, "titre"))
            InfoRow("Artistes", stats.artistCount.toString())
            InfoRow("Albums", stats.albumCount.toString())
            InfoRow("Favoris", stats.likedCount.toString())
            InfoRow("Disponibles hors-ligne", "${stats.downloadedCount} · ${formatBytes(viewModel.offlineBytes())}")
            InfoRow("Durée totale", formatTotalDuration(stats.totalDurationMs))
            TextButton(
                onClick = viewModel::rescanAll,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text("Réanalyser toutes les sources")
            }

            Section("Apparence")
            ChoiceRow(
                label = "Thème",
                options = ThemeMode.entries.map { it to it.label },
                selected = settings.themeMode,
                onSelect = viewModel::setThemeMode,
            )
            SwitchRow(
                title = "Couleurs dynamiques",
                subtitle = "Reprend la palette du fond d'écran (Android 12+).",
                checked = settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )

            Section("Réseau")
            ChoiceRow(
                label = "Écoute en streaming",
                options = NetworkPolicy.entries.map { it to it.label },
                selected = settings.streamingPolicy,
                onSelect = viewModel::setStreamingPolicy,
            )
            ChoiceRow(
                label = "Téléchargements hors-ligne",
                options = NetworkPolicy.entries.map { it to it.label },
                selected = settings.downloadPolicy,
                onSelect = viewModel::setDownloadPolicy,
            )
            CacheSizeRow(settings = settings, onSelect = viewModel::setCacheSize)

            Section("Indexation")
            SwitchRow(
                title = "Analyse automatique",
                subtitle = "Détecte les morceaux ajoutés ou supprimés sur vos serveurs.",
                checked = settings.autoScanEnabled,
                onCheckedChange = viewModel::setAutoScan,
            )
            if (settings.autoScanEnabled) {
                ChoiceRow(
                    label = "Fréquence",
                    options = listOf(6L to "6 h", 12L to "12 h", 24L to "24 h", 72L to "3 jours"),
                    selected = settings.autoScanIntervalHours,
                    onSelect = viewModel::setScanInterval,
                )
            }

            Section("Son")
            SwitchRow(
                title = "Égaliser le volume",
                subtitle = "Aligne les morceaux entre eux d'après leur gain ReplayGain. " +
                    "Utile quand la bibliothèque mêle plusieurs sources et plusieurs époques.",
                checked = settings.normalizeVolume,
                onCheckedChange = viewModel::setNormalizeVolume,
            )
            SwitchRow(
                title = "Égaliseur",
                subtitle = if (viewModel.equalizerPresets.isEmpty()) {
                    "Cet appareil ne propose pas d'égaliseur système."
                } else {
                    "Utilise l'égaliseur d'Android, accéléré par le matériel."
                },
                checked = settings.equalizerEnabled,
                onCheckedChange = viewModel::setEqualizerEnabled,
            )
            if (settings.equalizerEnabled && viewModel.equalizerPresets.isNotEmpty()) {
                ChoiceRow(
                    label = "Préréglage",
                    options = viewModel.equalizerPresets.map { it.index.toInt() to it.name },
                    selected = settings.equalizerPreset,
                    onSelect = viewModel::setEqualizerPreset,
                )
            }

            Section("Lecture")
            SwitchRow(
                title = "Sauter les morceaux rejetés",
                subtitle = "Un morceau marqué « je n'aime pas » est passé automatiquement.",
                checked = settings.skipDislikedTracks,
                onCheckedChange = viewModel::setSkipDisliked,
            )
            SwitchRow(
                title = "Télécharger les favoris",
                subtitle = "Rend automatiquement disponible hors-ligne tout morceau aimé.",
                checked = settings.autoDownloadLiked,
                onCheckedChange = viewModel::setAutoDownloadLiked,
            )

            Section("Sauvegarde et transfert")
            Text(
                text = "Les sauvegardes automatiques d'Android sont désactivées : elles ne " +
                    "transporteraient que des secrets scellés par une clé propre à cet " +
                    "appareil, donc illisibles ailleurs. L'export ci-dessous est la voie " +
                    "prévue pour changer de téléphone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { exportLauncher.launch(viewModel.backupFileName) }) {
                    Text("Exporter la configuration")
                }
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text("Importer")
                }
            }
            Text(
                text = "Le fichier exporté contient vos mots de passe et clés privées en " +
                    "clair : ils doivent être relisibles sur le nouvel appareil. Rangez-le " +
                    "en conséquence.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Section("Mises à jour")
            SwitchRow(
                title = "Vérifier automatiquement",
                subtitle = "Consulte les Releases GitHub deux fois par jour et vous prévient.",
                checked = settings.autoUpdateCheckEnabled,
                onCheckedChange = viewModel::setAutoUpdateCheck,
            )
            SwitchRow(
                title = "Inclure les préversions",
                subtitle = "Propose aussi les versions marquées comme préversions.",
                checked = settings.includePrereleases,
                onCheckedChange = viewModel::setIncludePrereleases,
            )
            InfoRow("Dernière vérification", formatRelativeTime(settings.lastUpdateCheckAt))
        }
    }
}

@Composable
private fun UpdateCard(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Version installée ${state.installedVersion}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))

            val available = state.available
            if (available == null) {
                Text(
                    text = "Les mises à jour sont publiées sur le dépôt GitHub public du projet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onCheck, enabled = !state.checking) {
                    Text(if (state.checking) "Vérification…" else "Vérifier maintenant")
                }
            } else {
                Text(
                    text = "Resonate ${available.version} est disponible.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (available.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = available.notes.take(NOTES_PREVIEW),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(12.dp))
                when (val progress = state.progress) {
                    is UpdateProgress.Downloading -> {
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${formatBytes(progress.downloadedBytes)} / " +
                                formatBytes(progress.totalBytes),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    UpdateProgress.Verifying -> Text(
                        "Vérification de l'empreinte du fichier…",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    UpdateProgress.AwaitingConfirmation -> Text(
                        "Confirmez l'installation dans la fenêtre système.",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    is UpdateProgress.Failed -> Text(
                        progress.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit
                }

                Spacer(Modifier.height(12.dp))
                if (!state.canInstall) {
                    Text(
                        text = "Android demande votre autorisation pour installer une app " +
                            "hors magasin. Cette autorisation ne concerne que Resonate.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onGrantPermission) { Text("Autoriser l'installation") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onInstall,
                            enabled = state.progress !is UpdateProgress.Downloading,
                        ) {
                            Text("Installer")
                        }
                        TextButton(onClick = onDismiss) { Text("Ignorer cette version") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

@Composable
private fun CacheSizeRow(settings: Settings, onSelect: (Long) -> Unit) {
    val options = listOf(
        512L * 1024 * 1024 to "512 Mo",
        1L * 1024 * 1024 * 1024 to "1 Go",
        2L * 1024 * 1024 * 1024 to "2 Go",
        8L * 1024 * 1024 * 1024 to "8 Go",
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Cache de streaming", style = MaterialTheme.typography.bodyLarge)
        Text(
            text = "Ce que vous écoutez est gardé en cache pour éviter de le retélécharger. " +
                "Sans effet sur les morceaux téléchargés exprès pour le hors-ligne.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (bytes, text) ->
                FilterChip(
                    selected = settings.cacheSizeBytes == bytes,
                    onClick = { onSelect(bytes) },
                    label = { Text(text) },
                )
            }
        }
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "Système"
        ThemeMode.LIGHT -> "Clair"
        ThemeMode.DARK -> "Sombre"
    }

private val NetworkPolicy.label: String
    get() = when (this) {
        NetworkPolicy.ANY -> "Tout réseau"
        NetworkPolicy.UNMETERED_ONLY -> "Wi-Fi uniquement"
    }

private const val NOTES_PREVIEW = 500
