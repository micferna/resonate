package io.github.micferna.resonate.ui.screens.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.micferna.resonate.data.db.entity.SecretKind
import io.github.micferna.resonate.data.db.entity.SourceEntity
import io.github.micferna.resonate.data.db.entity.SourceKind
import io.github.micferna.resonate.data.repo.SourceDraft
import io.github.micferna.resonate.data.repo.SourceRepository
import io.github.micferna.resonate.source.ProbeResult
import io.github.micferna.resonate.source.local.LocalConnector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** État de l'éditeur de source ; `null` quand il est fermé. */
data class EditorState(
    val draft: SourceDraft,
    val isNew: Boolean,
    val probing: Boolean = false,
    val probeResult: ProbeResult? = null,
    val saving: Boolean = false,
) {
    /**
     * Champs minimaux avant d'autoriser l'enregistrement. On ne bloque pas sur le
     * secret : un partage SMB public ou un WebDAV anonyme sont des cas légitimes.
     */
    val canSave: Boolean
        get() = when {
            // Une source locale n'a rien à renseigner : ni serveur, ni identifiants.
            draft.kind.isLocal -> true
            else -> draft.host.isNotBlank() &&
                draft.port in 1..65_535 &&
                (!draft.kind.requiresShare || draft.shareName.isNotBlank())
        }
}

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val repository: SourceRepository,
    private val localConnector: LocalConnector,
) : ViewModel() {

    /** Autorisation d'accès aux fichiers audio de l'appareil, pour la source locale. */
    private val _localAudioGranted = MutableStateFlow(localConnector.hasPermission())
    val localAudioGranted: StateFlow<Boolean> = _localAudioGranted.asStateFlow()

    /** Nom système de l'autorisation à demander, qui varie selon la version d'Android. */
    val localAudioPermission: String get() = localConnector.requiredPermission

    fun refreshLocalAudioPermission() {
        _localAudioGranted.value = localConnector.hasPermission()
    }

    val sources: StateFlow<List<SourceEntity>> = repository.observeSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _editor = MutableStateFlow<EditorState?>(null)
    val editor: StateFlow<EditorState?> = _editor.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    fun startCreate(kind: SourceKind = SourceKind.SFTP) {
        _editor.value = EditorState(
            draft = SourceDraft(kind = kind, port = kind.defaultPort),
            isNew = true,
        )
    }

    fun startEdit(source: SourceEntity) {
        _editor.value = EditorState(
            draft = SourceDraft(
                id = source.id,
                kind = source.kind,
                displayName = source.displayName,
                host = source.host,
                port = source.port,
                username = source.username,
                // Le secret enregistré n'est jamais réaffiché : laissé à `null`, il
                // sera conservé tel quel si l'utilisateur ne le remplace pas.
                secret = null,
                secretKind = source.secretKind,
                rootPath = source.rootPath,
                shareName = source.shareName.orEmpty(),
                useTls = source.useTls,
                enabled = source.enabled,
            ),
            isNew = false,
        )
    }

    fun closeEditor() {
        _editor.value = null
    }

    fun updateDraft(transform: (SourceDraft) -> SourceDraft) {
        _editor.update { current ->
            current?.copy(draft = transform(current.draft), probeResult = null)
        }
    }

    /** Changer de protocole réinitialise le port par défaut, sauf s'il a été modifié. */
    fun changeKind(kind: SourceKind) {
        _editor.update { current ->
            if (current == null) return@update null
            val previous = current.draft
            val portWasDefault = previous.port == previous.kind.defaultPort
            current.copy(
                draft = previous.copy(
                    kind = kind,
                    port = if (portWasDefault) kind.defaultPort else previous.port,
                    secretKind = if (kind == SourceKind.SFTP) previous.secretKind else SecretKind.PASSWORD,
                ),
                probeResult = null,
            )
        }
    }

    fun probe() {
        val current = _editor.value ?: return
        _editor.value = current.copy(probing = true, probeResult = null)
        viewModelScope.launch {
            val result = runCatching { repository.probe(current.draft) }
                .getOrElse { ProbeResult.Failure(it.message ?: "Test impossible.", it) }
            _editor.update { it?.copy(probing = false, probeResult = result) }
        }
    }

    fun save() {
        val current = _editor.value ?: return
        if (!current.canSave) return
        _editor.value = current.copy(saving = true)
        viewModelScope.launch {
            runCatching { repository.save(current.draft) }
                .onSuccess {
                    _editor.value = null
                    _messages.tryEmit("Source enregistrée. L'analyse démarre.")
                }
                .onFailure { error ->
                    _editor.update { it?.copy(saving = false) }
                    _messages.tryEmit(error.message ?: "Enregistrement impossible.")
                }
        }
    }

    fun setEnabled(source: SourceEntity, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(source, enabled) }
    }

    fun delete(source: SourceEntity) {
        viewModelScope.launch {
            repository.delete(source)
            _messages.tryEmit("« ${source.displayName} » et ses morceaux ont été supprimés.")
        }
    }

    fun forgetHostKey(source: SourceEntity) {
        viewModelScope.launch {
            repository.forgetHostKey(source)
            _messages.tryEmit("Clé d'hôte oubliée. Elle sera réapprise à la prochaine connexion.")
        }
    }

    fun rescan(source: SourceEntity) {
        repository.rescan(source.id)
        _messages.tryEmit("Analyse de « ${source.displayName} » lancée.")
    }

    private fun MutableStateFlow<EditorState?>.update(transform: (EditorState?) -> EditorState?) {
        value = transform(value)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
