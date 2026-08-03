package io.github.micferna.resonate.ui.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.micferna.resonate.player.PlayerUiState
import io.github.micferna.resonate.player.SleepTimerState
import io.github.micferna.resonate.ui.components.Artwork
import io.github.micferna.resonate.ui.formatDuration

/**
 * Lecteur plein écran.
 *
 * Pendant que l'utilisateur fait glisser la barre de progression, l'affichage suit son
 * doigt plutôt que la position réelle : sans cela, le curseur reviendrait en arrière à
 * chaque rafraîchissement et deviendrait impossible à manipuler.
 */
@Composable
fun NowPlayingSheet(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleDislike: () -> Unit,
    onOpenQueue: () -> Unit,
    sleepTimer: SleepTimerState,
    onSleepTimer: (minutes: Int?) -> Unit,
    isLiked: Boolean,
    isDisliked: Boolean,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var scrubbing by remember { mutableStateOf(false) }
    var timerOpen by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(
                url = state.artworkUri,
                size = 280.dp,
                cornerRadius = 20.dp,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .aspectRatio(1f),
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = state.title.ifBlank { "Sans titre" },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = state.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(20.dp))
            val duration = state.durationMs.coerceAtLeast(1).toFloat()
            val displayed = if (scrubbing) scrubPosition else state.positionMs.toFloat()
            Slider(
                value = displayed.coerceIn(0f, duration),
                valueRange = 0f..duration,
                onValueChange = { value ->
                    scrubbing = true
                    scrubPosition = value
                },
                onValueChangeFinished = {
                    onSeek(scrubPosition.toLong())
                    scrubbing = false
                },
                enabled = state.durationMs > 0,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(displayed.toLong()), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Lecture aléatoire",
                        tint = if (state.shuffleEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onPrevious, enabled = state.hasPrevious || state.positionMs > 0) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Précédent",
                        modifier = Modifier.size(36.dp),
                    )
                }
                FilledIconButton(onClick = onTogglePlay, modifier = Modifier.size(68.dp)) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onNext, enabled = state.hasNext) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Suivant",
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                            Icons.Filled.RepeatOne
                        } else {
                            Icons.Filled.Repeat
                        },
                        contentDescription = "Répétition",
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = onToggleLike) {
                    Icon(
                        imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "J'aime",
                        tint = if (isLiked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onToggleDislike) {
                    Icon(
                        Icons.Filled.ThumbDown,
                        contentDescription = "Je n'aime pas",
                        tint = if (isDisliked) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "File de lecture",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { timerOpen = true }) {
                    Icon(
                        Icons.Filled.Bedtime,
                        contentDescription = "Minuterie d'arrêt",
                        tint = if (sleepTimer.isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (sleepTimer.isRunning) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (sleepTimer.untilEndOfTrack) {
                        "Arrêt à la fin du morceau"
                    } else {
                        "Arrêt dans ${formatDuration(sleepTimer.remainingMs)}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (timerOpen) {
        SleepTimerDialog(
            active = sleepTimer.isRunning,
            onPick = { minutes -> onSleepTimer(minutes); timerOpen = false },
            onDismiss = { timerOpen = false },
        )
    }
}

/**
 * Choix de la minuterie.
 *
 * « À la fin du morceau » figure en premier parce que c'est le besoin le plus
 * fréquent en déplacement : arriver sans se faire couper au milieu d'une chanson.
 * Les durées fixes servent surtout à s'endormir.
 */
@Composable
private fun SleepTimerDialog(
    active: Boolean,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Minuterie d'arrêt") },
        text = {
            Column {
                Text(
                    text = "À la fin du morceau",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(0) }
                        .padding(vertical = 14.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    Text(
                        text = "$minutes minutes",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(minutes) }
                            .padding(vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {
            if (active) {
                androidx.compose.material3.TextButton(onClick = { onPick(null) }) {
                    Text("Annuler la minuterie")
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Fermer") }
        },
    )
}
