package io.github.micferna.resonate.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Bandeau d'échec de lecture.
 *
 * Une source distante qui lâche est une situation banale, pas un plantage : le
 * bandeau reste discret, se place au-dessus du mini-lecteur, et disparaît de
 * lui-même dès que la lecture repart. Tant qu'une reprise est en cours, l'indicateur
 * circulaire montre que quelque chose se passe — sans quoi l'utilisateur relancerait
 * à la main un morceau qui allait revenir seul.
 */
@Composable
fun PlaybackFailureBanner(
    message: String?,
    retrying: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = message != null,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (retrying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = message.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
