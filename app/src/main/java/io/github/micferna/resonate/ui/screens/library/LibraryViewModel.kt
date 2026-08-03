package io.github.micferna.resonate.ui.screens.library

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.micferna.resonate.data.db.dao.AlbumSummary
import io.github.micferna.resonate.data.db.dao.ArtistSummary
import io.github.micferna.resonate.data.db.entity.TrackEntity
import io.github.micferna.resonate.data.repo.LibraryRepository
import io.github.micferna.resonate.data.repo.PlaylistRepository
import io.github.micferna.resonate.data.repo.SourceRepository
import io.github.micferna.resonate.player.PlayerConnection
import io.github.micferna.resonate.ui.TrackActionsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Onglets de l'écran Bibliothèque. */
enum class LibraryTab(val label: String) {
    TRACKS("Titres"),
    ARTISTS("Artistes"),
    ALBUMS("Albums"),
    LIKED("Aimés"),
    OFFLINE("Hors-ligne"),
    RECENT("Récents"),
    MOST_PLAYED("Plus écoutés"),
}

/** Regroupement en cours de consultation : `null` signifie « la liste complète ». */
sealed interface LibraryFocus {
    data object None : LibraryFocus
    data class Artist(val name: String) : LibraryFocus
    data class Album(val name: String, val albumArtist: String) : LibraryFocus
}

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.TRACKS,
    val focus: LibraryFocus = LibraryFocus.None,
    val tracks: List<TrackEntity> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val hasAnySource: Boolean = false,
    val isEmpty: Boolean = true,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    playerConnection: PlayerConnection,
    playlistRepo: PlaylistRepository,
    private val sources: SourceRepository,
) : TrackActionsViewModel(libraryRepository, playerConnection, playlistRepo) {

    private val _tab = MutableStateFlow(LibraryTab.TRACKS)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    private val _focus = MutableStateFlow<LibraryFocus>(LibraryFocus.None)

    /**
     * La liste affichée dépend à la fois de l'onglet et du regroupement ouvert.
     * `flatMapLatest` garantit qu'ouvrir un artiste puis revenir en arrière n'entretient
     * pas une requête devenue inutile sur la base.
     */
    private val visibleTracks = combine(_tab, _focus) { tab, focus -> tab to focus }
        .flatMapLatest { (tab, focus) ->
            when (focus) {
                is LibraryFocus.Artist -> library.observeTracksOfArtist(focus.name)
                is LibraryFocus.Album -> library.observeTracksOfAlbum(focus.name, focus.albumArtist)
                LibraryFocus.None -> when (tab) {
                    LibraryTab.LIKED -> library.observeLiked()
                    LibraryTab.OFFLINE -> library.observeDownloaded()
                    LibraryTab.RECENT -> library.observeRecentlyAdded()
                    LibraryTab.MOST_PLAYED -> library.observeMostPlayed()
                    else -> library.observeAllTracks()
                }
            }
        }

    val uiState: StateFlow<LibraryUiState> = combine(
        _tab,
        _focus,
        visibleTracks,
        library.observeArtists(),
        library.observeAlbums(),
    ) { tab, focus, tracks, artists, albums ->
        LibraryUiState(
            tab = tab,
            focus = focus,
            tracks = tracks,
            artists = artists,
            albums = albums,
            hasAnySource = true,
            isEmpty = tracks.isEmpty() && artists.isEmpty(),
        )
    }.combine(sources.observeSources()) { state, sourceList ->
        state.copy(hasAnySource = sourceList.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

    fun selectTab(tab: LibraryTab) {
        _tab.value = tab
        _focus.value = LibraryFocus.None
    }

    fun openArtist(name: String) {
        _focus.value = LibraryFocus.Artist(name)
    }

    fun openAlbum(album: AlbumSummary) {
        _focus.value = LibraryFocus.Album(album.album, album.albumArtist)
    }

    fun closeFocus() {
        _focus.value = LibraryFocus.None
    }

    /** Lecture aléatoire de toute la bibliothèque, sans avoir à la charger entièrement. */
    fun shuffleEverything() {
        viewModelScope.launch { playAll(library.shuffleSeed(), shuffled = false) }
    }

    fun rescanAll() = sources.rescanAll()
}
