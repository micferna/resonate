package io.github.micferna.resonate.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.micferna.resonate.BuildConfig
import io.github.micferna.resonate.data.db.dao.LibraryStats
import io.github.micferna.resonate.data.prefs.NetworkPolicy
import io.github.micferna.resonate.data.prefs.Settings
import io.github.micferna.resonate.data.prefs.SettingsStore
import io.github.micferna.resonate.data.prefs.ThemeMode
import io.github.micferna.resonate.data.repo.LibraryRepository
import io.github.micferna.resonate.sync.WorkScheduler
import io.github.micferna.resonate.update.AvailableUpdate
import io.github.micferna.resonate.update.UpdateChecker
import io.github.micferna.resonate.update.UpdateInstaller
import io.github.micferna.resonate.update.UpdateProgress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val checking: Boolean = false,
    val available: AvailableUpdate? = null,
    val progress: UpdateProgress = UpdateProgress.Idle,
    val installedVersion: String = BuildConfig.VERSION_NAME,
    val canInstall: Boolean = true,
    val lastCheckedAt: Long = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val library: LibraryRepository,
    private val workScheduler: WorkScheduler,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsStore.settings
        // Toute modification d'un réglage qui influe sur les tâches de fond doit être
        // répercutée sur leur planification : sans cela, « Wi-Fi uniquement » resterait
        // sans effet sur une indexation déjà programmée.
        .onEach { current ->
            workScheduler.schedulePeriodicScan(current)
            workScheduler.scheduleTagResolution(current)
            workScheduler.scheduleUpdateCheck(current)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Settings())

    val stats: StateFlow<LibraryStats> = library.observeStats()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            LibraryStats(0, 0, 0, 0, 0, 0),
        )

    private val _update = MutableStateFlow(UpdateUiState())
    val update: StateFlow<UpdateUiState> = _update.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            updateInstaller.progress.collect { progress ->
                _update.value = _update.value.copy(progress = progress)
            }
        }
    }

    fun offlineBytes(): Long = library.offlineBytes()

    // ------------------------------------------------------------------ réglages

    fun setThemeMode(mode: ThemeMode) = edit { settingsStore.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = edit { settingsStore.setDynamicColor(enabled) }

    fun setStreamingPolicy(policy: NetworkPolicy) = edit { settingsStore.setStreamingPolicy(policy) }

    fun setDownloadPolicy(policy: NetworkPolicy) = edit { settingsStore.setDownloadPolicy(policy) }

    fun setCacheSize(bytes: Long) = edit { settingsStore.setCacheSizeBytes(bytes) }

    fun setAutoScan(enabled: Boolean) = edit { settingsStore.setAutoScanEnabled(enabled) }

    fun setScanInterval(hours: Long) = edit { settingsStore.setAutoScanIntervalHours(hours) }

    fun setAutoUpdateCheck(enabled: Boolean) = edit { settingsStore.setAutoUpdateCheckEnabled(enabled) }

    fun setIncludePrereleases(enabled: Boolean) = edit { settingsStore.setIncludePrereleases(enabled) }

    fun setSkipDisliked(enabled: Boolean) = edit { settingsStore.setSkipDislikedTracks(enabled) }

    fun setAutoDownloadLiked(enabled: Boolean) = edit { settingsStore.setAutoDownloadLiked(enabled) }

    fun rescanAll() {
        workScheduler.scanNow(null)
        _messages.tryEmit("Analyse de toutes les sources lancée.")
    }

    // ------------------------------------------------------------------ mise à jour

    fun checkForUpdate() {
        _update.value = _update.value.copy(checking = true)
        viewModelScope.launch {
            val result = runCatching { updateChecker.check(includeDismissed = true) }
            _update.value = _update.value.copy(
                checking = false,
                available = result.getOrNull(),
                canInstall = updateInstaller.canRequestInstall(),
                lastCheckedAt = System.currentTimeMillis(),
            )
            result.onFailure { _messages.tryEmit(it.message ?: "Vérification impossible.") }
                .onSuccess { available ->
                    if (available == null) _messages.tryEmit("Resonate est à jour.")
                }
        }
    }

    fun installUpdate() {
        val target = _update.value.available ?: return
        if (!updateInstaller.canRequestInstall()) {
            _update.value = _update.value.copy(canInstall = false)
            _messages.tryEmit(
                "Autorisez d'abord Resonate à installer des applications dans les réglages Android.",
            )
            return
        }
        viewModelScope.launch {
            updateInstaller.install(target).onFailure {
                _messages.tryEmit(it.message ?: "Installation impossible.")
            }
        }
    }

    fun dismissUpdate() {
        val target = _update.value.available ?: return
        viewModelScope.launch { settingsStore.dismissUpdate(target.tag) }
        _update.value = _update.value.copy(available = null)
        updateInstaller.resetProgress()
    }

    fun installPermissionIntent() = updateInstaller.installPermissionIntent()

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
