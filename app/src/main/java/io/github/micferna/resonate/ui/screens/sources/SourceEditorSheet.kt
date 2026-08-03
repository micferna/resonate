package io.github.micferna.resonate.ui.screens.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.micferna.resonate.data.db.entity.SecretKind
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.data.repo.SourceDraft
import io.github.micferna.resonate.source.ProbeResult

/**
 * Formulaire d'ajout ou de modification d'une source.
 *
 * Les champs affichés dépendent du type choisi : demander un partage à un serveur
 * Subsonic, ou une adresse de serveur à la musique du téléphone, n'aurait aucun sens
 * et laisserait l'utilisateur remplir du vide. Le bouton « Tester » est mis en avant
 * avant l'enregistrement, car une erreur d'identifiant se diagnostique bien mieux ici
 * que dans un rapport d'indexation un quart d'heure plus tard.
 */
@Composable
fun SourceEditorSheet(
    state: EditorState,
    onDismiss: () -> Unit,
    onDraftChange: ((SourceDraft) -> SourceDraft) -> Unit,
    onKindChange: (SourceKind) -> Unit,
    onProbe: () -> Unit,
    onSave: () -> Unit,
    localAudioGranted: Boolean,
    onRequestAudioPermission: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val draft = state.draft

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (state.isNew) "Nouvelle source" else "Modifier la source",
                style = MaterialTheme.typography.headlineSmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceKind.entries.forEach { kind ->
                    FilterChip(
                        selected = draft.kind == kind,
                        onClick = { onKindChange(kind) },
                        label = { Text(kind.shortLabel) },
                    )
                }
            }
            Text(
                text = draft.kind.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = draft.displayName,
                onValueChange = { value -> onDraftChange { it.copy(displayName = value) } },
                label = { Text("Nom affiché (facultatif)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (draft.kind.isLocal) {
                LocalAudioAccess(
                    granted = localAudioGranted,
                    onRequestPermission = onRequestAudioPermission,
                )
            } else {
                ServerFields(state = state, onDraftChange = onDraftChange)
            }

            state.probeResult?.let { ProbeFeedback(it) }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onProbe,
                    enabled = !state.probing && (draft.kind.isLocal || draft.host.isNotBlank()),
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.probing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Tester")
                    }
                }
                Button(
                    onClick = onSave,
                    enabled = state.canSave && !state.saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.isNew) "Ajouter" else "Enregistrer")
                }
            }
        }
    }
}

/** Champs propres aux sources distantes : serveur, identifiants, emplacement. */
@Composable
private fun ServerFields(
    state: EditorState,
    onDraftChange: ((SourceDraft) -> SourceDraft) -> Unit,
) {
    val draft = state.draft

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = draft.host,
                onValueChange = { value -> onDraftChange { it.copy(host = value.trim()) } },
                label = { Text("Hôte") },
                placeholder = { Text("nas.local ou 192.168.1.20") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = draft.port.toString(),
                onValueChange = { value ->
                    val port = value.filter(Char::isDigit).take(5).toIntOrNull() ?: 0
                    onDraftChange { it.copy(port = port) }
                },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        if (draft.kind.supportsTls) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("HTTPS", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "À laisser activé sauf serveur local sans certificat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = draft.useTls,
                    onCheckedChange = { value -> onDraftChange { it.copy(useTls = value) } },
                )
            }
        }

        OutlinedTextField(
            value = draft.username,
            onValueChange = { value -> onDraftChange { it.copy(username = value) } },
            label = { Text("Identifiant") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (draft.kind == SourceKind.SFTP) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.secretKind == SecretKind.PASSWORD,
                    onClick = { onDraftChange { it.copy(secretKind = SecretKind.PASSWORD) } },
                    label = { Text("Mot de passe") },
                )
                FilterChip(
                    selected = draft.secretKind == SecretKind.SSH_PRIVATE_KEY,
                    onClick = { onDraftChange { it.copy(secretKind = SecretKind.SSH_PRIVATE_KEY) } },
                    label = { Text("Clé privée") },
                )
            }
        }

        SecretField(state = state, onDraftChange = onDraftChange)

        if (draft.kind == SourceKind.SFTP && draft.secretKind == SecretKind.SSH_PRIVATE_KEY) {
            OutlinedTextField(
                value = draft.keyPassphrase.orEmpty(),
                onValueChange = { value ->
                    onDraftChange { it.copy(keyPassphrase = value.ifEmpty { null }) }
                },
                label = { Text("Phrase de passe de la clé (si protégée)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (draft.kind.requiresShare) {
            OutlinedTextField(
                value = draft.shareName,
                onValueChange = { value -> onDraftChange { it.copy(shareName = value.trim()) } },
                label = { Text("Nom du partage") },
                placeholder = { Text("Musique") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (draft.kind != SourceKind.SUBSONIC) {
            OutlinedTextField(
                value = draft.rootPath,
                onValueChange = { value -> onDraftChange { it.copy(rootPath = value) } },
                label = {
                    Text(if (draft.kind.requiresShare) "Dossier dans le partage" else "Dossier racine")
                },
                placeholder = { Text("/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Accès à la musique de l'appareil.
 *
 * Android exige une autorisation explicite pour lire les fichiers audio. On explique
 * d'abord à quoi elle sert : une demande de permission surgissant sans contexte est
 * précisément celle que les utilisateurs refusent.
 */
@Composable
private fun LocalAudioAccess(granted: Boolean, onRequestPermission: () -> Unit) {
    if (granted) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "Accès aux fichiers audio accordé.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Resonate a besoin de votre autorisation pour lire les fichiers audio " +
                "stockés sur ce téléphone. Elle ne donne accès qu'à la musique, à rien d'autre.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRequestPermission) { Text("Autoriser l'accès à la musique") }
    }
}

@Composable
private fun SecretField(
    state: EditorState,
    onDraftChange: ((SourceDraft) -> SourceDraft) -> Unit,
) {
    val draft = state.draft
    val isKey = draft.kind == SourceKind.SFTP && draft.secretKind == SecretKind.SSH_PRIVATE_KEY
    var revealed by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = draft.secret.orEmpty(),
        onValueChange = { value -> onDraftChange { it.copy(secret = value.ifEmpty { null }) } },
        label = {
            Text(
                when {
                    isKey -> "Clé privée OpenSSH"
                    draft.kind == SourceKind.SUBSONIC -> "Mot de passe ou jeton d'API"
                    else -> "Mot de passe"
                },
            )
        },
        placeholder = {
            if (isKey) {
                Text("-----BEGIN OPENSSH PRIVATE KEY-----")
            } else if (!state.isNew) {
                Text("Inchangé")
            }
        },
        supportingText = {
            if (!state.isNew && draft.secret == null) {
                Text("Laissez vide pour conserver le secret enregistré.")
            }
        },
        singleLine = !isKey,
        minLines = if (isKey) 4 else 1,
        visualTransformation = if (revealed || isKey) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingIcon = {
            if (!isKey) {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (revealed) "Masquer" else "Afficher",
                    )
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProbeFeedback(result: ProbeResult) {
    val success = result is ProbeResult.Success
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = if (success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Column {
            Text(
                text = when (result) {
                    is ProbeResult.Success -> result.message
                    is ProbeResult.Failure -> result.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (success) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            // La première connexion SSH mémorise l'identité du serveur ; l'afficher
            // permet de la comparer à ce qu'annonce le serveur (`ssh-keygen -lf`).
            (result as? ProbeResult.Success)?.hostKeyFingerprint?.let { fingerprint ->
                Text(
                    text = "Clé d'hôte mémorisée : $fingerprint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val SourceKind.shortLabel: String
    get() = when (this) {
        SourceKind.LOCAL -> "Appareil"
        SourceKind.SFTP -> "SFTP"
        SourceKind.SMB -> "SMB"
        SourceKind.WEBDAV -> "WebDAV"
        SourceKind.SUBSONIC -> "Subsonic"
    }

private val SourceKind.hint: String
    get() = when (this) {
        SourceKind.LOCAL ->
            "La musique déjà stockée sur ce téléphone. Aucun serveur, aucun réseau : " +
                "Android demandera simplement l'accès à vos fichiers audio."
        SourceKind.SFTP ->
            "Tout serveur SSH. La clé d'hôte est mémorisée à la première connexion et " +
                "vérifiée ensuite, comme le fait la commande ssh."
        SourceKind.SMB ->
            "Partage Windows, Samba, NAS Synology ou QNAP. L'identifiant accepte " +
                "les écritures DOMAINE\\utilisateur et utilisateur@domaine."
        SourceKind.WEBDAV ->
            "Nextcloud, ownCloud, Seafile ou tout serveur WebDAV. Pour Nextcloud, " +
                "le dossier racine ressemble à /remote.php/dav/files/votrecompte/Musique."
        SourceKind.SUBSONIC ->
            "Navidrome, Airsonic, Gonic ou Jellyfin avec son greffon Subsonic. " +
                "Le serveur fournit déjà tags et pochettes : c'est le plus rapide à indexer."
    }
