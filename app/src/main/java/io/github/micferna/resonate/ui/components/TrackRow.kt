package io.github.micferna.resonate.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.micferna.resonate.data.db.entity.OfflineState
import io.github.micferna.resonate.data.db.entity.Rating
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.ui.formatDuration

/** Actions proposées dans le menu contextuel d'un morceau. */
data class TrackActions(
    val onPlay: () -> Unit,
    val onPlayNext: () -> Unit,
    val onEnqueue: () -> Unit,
    val onToggleLike: () -> Unit,
    val onToggleDislike: () -> Unit,
    val onToggleOffline: () -> Unit,
    val onAddToPlaylist: () -> Unit,
)

/**
 * Une ligne de morceau.
 *
 * Les indicateurs à droite (appréciation, état hors-ligne) sont volontairement
 * discrets : sur une liste de plusieurs centaines de titres, un badge coloré par
 * ligne rendrait le défilement illisible.
 */
@Composable
fun TrackRow(
    track: TrackEntity,
    actions: TrackActions,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    showArtwork: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = actions.onPlay,
                onLongClick = { menuOpen = true },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArtwork) {
            Artwork(url = track.artworkUrl, size = 48.dp)
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            track.offlineBadge()?.let { badge ->
                Icon(
                    imageVector = badge.first,
                    contentDescription = badge.second,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            when (track.rating) {
                Rating.LIKED -> Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Aimé",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )

                Rating.DISLIKED -> Icon(
                    Icons.Filled.ThumbDown,
                    contentDescription = "Rejeté",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )

                Rating.NEUTRAL -> Unit
            }

            Text(
                text = formatDuration(track.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Autres actions")
                }
                TrackMenu(
                    expanded = menuOpen,
                    track = track,
                    actions = actions,
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
}

@Composable
private fun TrackMenu(
    expanded: Boolean,
    track: TrackEntity,
    actions: TrackActions,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Lire ensuite") },
            onClick = { actions.onPlayNext(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Ajouter à la file") },
            onClick = { actions.onEnqueue(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text("Ajouter à une playlist…") },
            onClick = { actions.onAddToPlaylist(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(if (track.rating == Rating.LIKED) "Retirer des favoris" else "J'aime") },
            onClick = { actions.onToggleLike(); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(if (track.rating == Rating.DISLIKED) "Annuler le rejet" else "Je n'aime pas") },
            onClick = { actions.onToggleDislike(); onDismiss() },
        )
        DropdownMenuItem(
            text = {
                Text(
                    when (track.offlineState) {
                        OfflineState.DOWNLOADED -> "Retirer du hors-ligne"
                        OfflineState.QUEUED, OfflineState.DOWNLOADING -> "Annuler le téléchargement"
                        else -> "Rendre disponible hors-ligne"
                    },
                )
            },
            onClick = { actions.onToggleOffline(); onDismiss() },
        )
    }
}

private fun TrackEntity.offlineBadge() = when (offlineState) {
    OfflineState.DOWNLOADED -> Icons.Filled.DownloadDone to "Disponible hors-ligne"
    OfflineState.DOWNLOADING, OfflineState.QUEUED -> Icons.Filled.CloudDownload to "Téléchargement en cours"
    OfflineState.FAILED -> Icons.Filled.ErrorOutline to "Téléchargement échoué"
    OfflineState.NONE -> null
}
