package io.github.micferna.resonate.ui.screens.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.micferna.resonate.data.db.entity.SourceEntity
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.ui.components.EmptyState
import io.github.micferna.resonate.ui.formatRelativeTime
import io.github.micferna.resonate.ui.pluralize

@Composable
fun SourcesScreen(
    modifier: Modifier = Modifier,
    viewModel: SourcesViewModel = hiltViewModel(),
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val localAudioGranted by viewModel.localAudioGranted.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    // Le résultat, accepté comme refusé, est simplement reporté dans l'état :
    // l'écran se met à jour, sans insister ni bloquer l'utilisateur.
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshLocalAudioPermission() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Sources") }) },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.startCreate() }) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter une source")
            }
        },
    ) { padding ->
        if (sources.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(padding),
                icon = Icons.Filled.Dns,
                title = "Aucune source",
                description = "Resonate lit votre musique là où elle se trouve : serveur SSH, " +
                    "partage réseau d'un NAS, Nextcloud, ou serveur Navidrome/Jellyfin. " +
                    "Rien n'est copié tant que vous ne le demandez pas.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 88.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        onEdit = { viewModel.startEdit(source) },
                        onToggle = { viewModel.setEnabled(source, it) },
                        onRescan = { viewModel.rescan(source) },
                        onForgetHostKey = { viewModel.forgetHostKey(source) },
                        onDelete = { viewModel.delete(source) },
                    )
                }
            }
        }
    }

    editor?.let { state ->
        SourceEditorSheet(
            state = state,
            onDismiss = viewModel::closeEditor,
            onDraftChange = viewModel::updateDraft,
            onKindChange = viewModel::changeKind,
            onProbe = viewModel::probe,
            onSave = viewModel::save,
            localAudioGranted = localAudioGranted,
            onRequestAudioPermission = {
                audioPermissionLauncher.launch(viewModel.localAudioPermission)
            },
        )
    }
}

@Composable
private fun SourceCard(
    source: SourceEntity,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRescan: () -> Unit,
    onForgetHostKey: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (source.kind.isLocal) {
                            source.kind.label
                        } else {
                            "${source.kind.label} · ${source.host}:${source.port}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Modifier") },
                            onClick = { onEdit(); menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Analyser maintenant") },
                            onClick = { onRescan(); menuOpen = false },
                        )
                        if (source.kind == SourceKind.SFTP && source.hostKeyFingerprint != null) {
                            DropdownMenuItem(
                                text = { Text("Oublier la clé d'hôte") },
                                onClick = { onForgetHostKey(); menuOpen = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Supprimer") },
                            onClick = { onDelete(); menuOpen = false },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "${pluralize(source.trackCount, "morceau", "morceaux")} · " +
                    "analysée ${formatRelativeTime(source.lastScanAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            source.lastScanError?.let { error ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            source.hostKeyFingerprint?.let { fingerprint ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Clé d'hôte : $fingerprint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Libellé lisible d'un protocole, pour l'interface. */
val SourceKind.label: String
    get() = when (this) {
        SourceKind.LOCAL -> "Musique de l'appareil"
        SourceKind.SFTP -> "SFTP / SSH"
        SourceKind.SMB -> "Partage SMB"
        SourceKind.WEBDAV -> "WebDAV / Nextcloud"
        SourceKind.SUBSONIC -> "Subsonic / Navidrome"
    }
