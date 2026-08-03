package io.github.micferna.resonate.ui.screens.library

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.micferna.resonate.data.db.dao.AlbumSummary
import io.github.micferna.resonate.data.db.dao.ArtistSummary
import io.github.micferna.resonate.data.db.dao.FolderSummary
import io.github.micferna.resonate.data.db.dao.GenreSummary
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
    GENRES("Genres"),
    FOLDERS("Dossiers"),
}

/** Critères de tri proposés sur les listes de morceaux. */
enum class TrackSort(val label: String) {
    ARTIST("Artiste"),
    TITLE("Titre"),
    RECENT("Ajout récent"),
    DURATION("Durée"),
}

/** Regroupement en cours de consultation : `null` signifie « la liste complète ». */
sealed interface LibraryFocus {
    data object None : LibraryFocus
    data class Artist(val name: String) : LibraryFocus
    data class Album(val name: String, val albumArtist: String) : LibraryFocus
    data class Genre(val name: String) : LibraryFocus
    data class Folder(val sourceId: Long, val path: String) : LibraryFocus
}

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.TRACKS,
    val focus: LibraryFocus = LibraryFocus.None,
    val tracks: List<TrackEntity> = emptyList(),
    val artists: List<ArtistSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val genres: List<GenreSummary> = emptyList(),
    val folders: List<FolderSummary> = emptyList(),
    val sort: TrackSort = TrackSort.ARTIST,
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
    private val _sort = MutableStateFlow(TrackSort.ARTIST)

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
                is LibraryFocus.Genre -> library.observeTracksOfGenre(focus.name)
                is LibraryFocus.Folder -> library.observeTracksOfFolder(focus.sourceId, focus.path)
                LibraryFocus.None -> when (tab) {
                    LibraryTab.LIKED -> library.observeLiked()
                    LibraryTab.OFFLINE -> library.observeDownloaded()
                    LibraryTab.RECENT -> library.observeRecentlyAdded()
                    LibraryTab.MOST_PLAYED -> library.observeMostPlayed()
                    else -> library.observeAllTracks()
                }
            }
        }

    /**
     * Le tri est appliqué en mémoire plutôt qu'en base.
     *
     * Multiplier les requêtes par le nombre de critères alourdirait le DAO sans
     * bénéfice : les listes affichées sont déjà bornées, et trier quelques milliers
     * d'objets déjà chargés est instantané. Le tri par défaut, lui, reste celui de
     * la requête SQL, qui profite des index.
     */
    private val sortedTracks = combine(visibleTracks, _sort) { tracks, sort ->
        when (sort) {
            TrackSort.ARTIST -> tracks
            TrackSort.TITLE -> tracks.sortedBy { it.title.lowercase() }
            TrackSort.RECENT -> tracks.sortedByDescending { it.addedAt }
            TrackSort.DURATION -> tracks.sortedBy { it.durationMs }
        }
    }

    private val groupings = combine(
        library.observeArtists(),
        library.observeAlbums(),
        library.observeGenres(),
        library.observeFolders(),
    ) { artists, albums, genres, folders -> listOf(artists, albums, genres, folders) }

    val uiState: StateFlow<LibraryUiState> = combine(
        _tab,
        _focus,
        sortedTracks,
        _sort,
        groupings,
    ) { tab, focus, tracks, sort, grouped ->
        @Suppress("UNCHECKED_CAST")
        LibraryUiState(
            tab = tab,
            focus = focus,
            tracks = tracks,
            artists = grouped[0] as List<ArtistSummary>,
            albums = grouped[1] as List<AlbumSummary>,
            genres = grouped[2] as List<GenreSummary>,
            folders = grouped[3] as List<FolderSummary>,
            sort = sort,
            hasAnySource = true,
            isEmpty = tracks.isEmpty(),
        )
    }.combine(sources.observeSources()) { state, sourceList ->
        state.copy(hasAnySource = sourceList.isNotEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState())

    fun selectSort(sort: TrackSort) {
        _sort.value = sort
    }

    fun openGenre(name: String) {
        _focus.value = LibraryFocus.Genre(name)
    }

    fun openFolder(folder: FolderSummary) {
        _focus.value = LibraryFocus.Folder(folder.sourceId, folder.folder)
    }

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
