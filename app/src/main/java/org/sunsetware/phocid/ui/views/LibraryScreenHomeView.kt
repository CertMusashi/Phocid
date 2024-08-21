@file:OptIn(ExperimentalMaterial3Api::class)

package org.sunsetware.phocid.ui.views

import android.graphics.Bitmap
import androidx.collection.LruCache
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID
import kotlin.collections.plus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.Strings
import org.sunsetware.phocid.data.Album
import org.sunsetware.phocid.data.Artist
import org.sunsetware.phocid.data.ArtworkColorPreference
import org.sunsetware.phocid.data.Folder
import org.sunsetware.phocid.data.Genre
import org.sunsetware.phocid.data.InvalidTrack
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.PlaylistManager
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.data.RealizedPlaylist
import org.sunsetware.phocid.data.SortingOption
import org.sunsetware.phocid.data.Track
import org.sunsetware.phocid.data.albumKey
import org.sunsetware.phocid.data.search
import org.sunsetware.phocid.data.sorted
import org.sunsetware.phocid.data.sortedBy
import org.sunsetware.phocid.ui.components.Artwork
import org.sunsetware.phocid.ui.components.ArtworkImage
import org.sunsetware.phocid.ui.components.DefaultPagerState
import org.sunsetware.phocid.ui.components.EmptyListIndicator
import org.sunsetware.phocid.ui.components.LibraryListItemCard
import org.sunsetware.phocid.ui.components.LibraryListItemHorizontal
import org.sunsetware.phocid.ui.components.MenuItem
import org.sunsetware.phocid.ui.components.OverflowMenu
import org.sunsetware.phocid.ui.components.SingleLineText
import org.sunsetware.phocid.ui.components.TabIndicator
import org.sunsetware.phocid.ui.components.collectionMenuItems
import org.sunsetware.phocid.ui.components.playlistCollectionMenuItems
import org.sunsetware.phocid.ui.components.playlistCollectionMultiSelectMenuItems
import org.sunsetware.phocid.ui.components.scrollbar
import org.sunsetware.phocid.ui.components.trackMenuItems
import org.sunsetware.phocid.ui.theme.hashColor
import org.sunsetware.phocid.utils.MultiSelectManager
import org.sunsetware.phocid.utils.MultiSelectState
import org.sunsetware.phocid.utils.Nullable
import org.sunsetware.phocid.utils.SelectableList
import org.sunsetware.phocid.utils.combine
import org.sunsetware.phocid.utils.multiSelectClickable
import org.sunsetware.phocid.utils.toShortString

@Immutable
data class LibraryScreenHomeViewItem(
    val key: Any,
    val title: String,
    val subtitle: String,
    val artwork: Artwork,
    val tracks: List<Track>,
    val menuItems: (MainViewModel) -> List<MenuItem>,
    val multiSelectMenuItems:
        (
            others: List<LibraryScreenHomeViewItem>,
            viewModel: MainViewModel,
            continuation: () -> Unit,
        ) -> List<MenuItem.Button>,
    val onClick: (MainViewModel) -> Unit,
) : LibraryScreenItem<LibraryScreenHomeViewItem> {
    override fun getMultiSelectMenuItems(
        others: List<LibraryScreenHomeViewItem>,
        viewModel: MainViewModel,
        continuation: () -> Unit,
    ): List<MenuItem.Button> {
        return multiSelectMenuItems(others, viewModel, continuation)
    }
}

class LibraryScreenHomeViewState(
    coroutineScope: CoroutineScope,
    preferences: StateFlow<Preferences>,
    libraryIndex: StateFlow<LibraryIndex>,
    playlistManager: PlaylistManager,
    searchQuery: StateFlow<String>,
) : AutoCloseable {
    val pagerState = DefaultPagerState { preferences.value.tabs.size }
    val tabStates =
        TabType.entries.associateWith { tabType ->
            val items =
                if (tabType != TabType.PLAYLISTS) {
                    preferences.combine(
                        coroutineScope,
                        libraryIndex,
                        searchQuery,
                        transform =
                            when (tabType) {
                                TabType.TRACKS -> ::trackItems
                                TabType.ALBUMS -> ::albumItems
                                TabType.ARTISTS -> ::artistItems
                                TabType.GENRES -> ::genreItems
                                TabType.FOLDERS -> ::folderItems
                                TabType.PLAYLISTS -> throw Error() // Impossible
                            },
                    )
                } else {
                    preferences.combine(
                        coroutineScope,
                        playlistManager.playlists,
                        searchQuery,
                        transform = ::playlistItems,
                    )
                }

            LibraryScreenHomeViewTabState(MultiSelectState(coroutineScope, items))
        }
    val tabRowScrollState = ScrollState(0)
    private val _activeMultiSelectState =
        MutableStateFlow(null as MultiSelectState<LibraryScreenHomeViewItem>?)
    val activeMultiSelectState = _activeMultiSelectState.asStateFlow()
    private val activeMultiSelectStateJobs =
        tabStates.map { (tabType, tabState) ->
            coroutineScope.launch {
                tabState.multiSelectState.items
                    .onEach { items ->
                        if (items.selection.isNotEmpty()) {
                            tabStates
                                .filterKeys { it != tabType }
                                .values
                                .forEach { it.multiSelectState.clearSelection() }
                            _activeMultiSelectState.update { tabState.multiSelectState }
                        }
                    }
                    .collect()
            }
        }

    override fun close() {
        activeMultiSelectStateJobs.forEach { it.cancel() }
        tabStates.values.forEach { it.close() }
    }

    private fun trackItems(
        preferences: Preferences,
        libraryIndex: LibraryIndex,
        searchQuery: String,
    ): List<LibraryScreenHomeViewItem> {
        val tab = preferences.tabSettings[TabType.TRACKS]!!
        val tracks =
            libraryIndex.tracks.values
                .search(searchQuery, preferences.searchCollator)
                .sorted(preferences.sortCollator, tab.sortingKeys, tab.sortAscending)
        return tracks.mapIndexed { index, track ->
            LibraryScreenHomeViewItem(
                key = track.id,
                title = track.displayTitle,
                subtitle = track.displayArtistWithAlbum,
                artwork = Artwork.Track(track),
                tracks = listOf(track),
                menuItems = { trackMenuItems(track, it.playerWrapper, it.uiManager) },
                multiSelectMenuItems = { others, viewModel, continuation ->
                    collectionMenuItems(
                        { listOf(track) + others.flatMap { it.tracks } },
                        viewModel.playerWrapper,
                        viewModel.uiManager,
                        continuation,
                    )
                },
            ) {
                it.playerWrapper.setTracks(tracks, index)
            }
        }
    }

    private fun albumItems(
        preferences: Preferences,
        libraryIndex: LibraryIndex,
        searchQuery: String,
    ): List<LibraryScreenHomeViewItem> {
        val tab = preferences.tabSettings[TabType.ALBUMS]!!
        val albums =
            libraryIndex.albums
                .asIterable()
                .search(searchQuery, preferences.searchCollator) { it.value }
                .sortedBy(preferences.sortCollator, tab.sortingKeys, tab.sortAscending) { it.value }
        return albums.map { (key, album) ->
            LibraryScreenHomeViewItem(
                key = key.composeKey,
                title = album.name,
                subtitle = album.displayAlbumArtist,
                artwork = Artwork.Track(album.tracks.firstOrNull() ?: InvalidTrack),
                tracks = album.tracks,
                menuItems = {
                    collectionMenuItems({ album.tracks }, it.playerWrapper, it.uiManager)
                },
                multiSelectMenuItems = { others, viewModel, continuation ->
                    collectionMenuItems(
                        { album.tracks + others.flatMap { it.tracks } },
                        viewModel.playerWrapper,
                        viewModel.uiManager,
                        continuation,
                    )
                },
            ) {
                it.uiManager.openAlbumCollectionView(album.albumKey)
            }
        }
    }

    private fun artistItems(
        preferences: Preferences,
        libraryIndex: LibraryIndex,
        searchQuery: String,
    ): List<LibraryScreenHomeViewItem> {
        val tab = preferences.tabSettings[TabType.ARTISTS]!!
        val artists =
            libraryIndex.artists.values
                .search(searchQuery, preferences.searchCollator)
                .sorted(preferences.sortCollator, tab.sortingKeys, tab.sortAscending)
        return artists.map { artist ->
            LibraryScreenHomeViewItem(
                key = artist.name,
                title = artist.name,
                subtitle = artist.displayStatistics,
                artwork = Artwork.Track(artist.tracks.firstOrNull() ?: InvalidTrack),
                tracks = artist.tracks,
                menuItems = {
                    collectionMenuItems({ artist.tracks }, it.playerWrapper, it.uiManager)
                },
                multiSelectMenuItems = { others, viewModel, continuation ->
                    collectionMenuItems(
                        { artist.tracks + others.flatMap { it.tracks } },
                        viewModel.playerWrapper,
                        viewModel.uiManager,
                        continuation,
                    )
                },
            ) {
                it.uiManager.openArtistCollectionView(artist.name)
            }
        }
    }

    private fun genreItems(
        preferences: Preferences,
        libraryIndex: LibraryIndex,
        searchQuery: String,
    ): List<LibraryScreenHomeViewItem> {
        val tab = preferences.tabSettings[TabType.GENRES]!!
        val genres =
            libraryIndex.genres.values
                .search(searchQuery, preferences.searchCollator)
                .sorted(preferences.sortCollator, tab.sortingKeys, tab.sortAscending)
        return genres.map { genre ->
            LibraryScreenHomeViewItem(
                key = genre.name,
                title = genre.name,
                subtitle = genre.displayStatistics,
                artwork = Artwork.Track(genre.tracks.firstOrNull() ?: InvalidTrack),
                tracks = genre.tracks,
                menuItems = {
                    collectionMenuItems({ genre.tracks }, it.playerWrapper, it.uiManager)
                },
                multiSelectMenuItems = { others, viewModel, continuation ->
                    collectionMenuItems(
                        { genre.tracks + others.flatMap { it.tracks } },
                        viewModel.playerWrapper,
                        viewModel.uiManager,
                        continuation,
                    )
                },
            ) {
                it.uiManager.openGenreCollectionView(genre.name)
            }
        }
    }

    private fun folderItems(
        preferences: Preferences,
        libraryIndex: LibraryIndex,
        searchQuery: String,
    ): List<LibraryScreenHomeViewItem> {
        val tab = preferences.tabSettings[TabType.FOLDERS]!!
        val rootFolder = libraryIndex.folders[libraryIndex.rootFolder]!!
        val filteredSortedChildFolders =
            rootFolder.childFolders
                .map { libraryIndex.folders[it]!! }
                .search(searchQuery, preferences.searchCollator)
                .sorted(preferences.sortCollator, tab.sortingKeys, tab.sortAscending)
        val filteredSortedChildTracks =
            rootFolder.childTracks
                .search(searchQuery, preferences.searchCollator)
                .sorted(preferences.sortCollator, tab.sortingKeys, tab.sortAscending)
        val folderItems =
            filteredSortedChildFolders.map { folder ->
                folder to
                    LibraryScreenHomeViewItem(
                        key = folder.path,
                        title = folder.fileName,
                        subtitle = folder.displayStatistics,
                        artwork = Artwork.Icon(Icons.Outlined.Folder, folder.path.hashColor()),
                        tracks = folder.childTracks,
                        menuItems = {
                            collectionMenuItems(
                                { folder.childTracks },
                                it.playerWrapper,
                                it.uiManager,
                            )
                        },
                        multiSelectMenuItems = { others, viewModel, continuation ->
                            collectionMenuItems(
                                { folder.childTracks + others.flatMap { it.tracks } },
                                viewModel.playerWrapper,
                                viewModel.uiManager,
                                continuation,
                            )
                        },
                    ) {
                        it.uiManager.openFolderCollectionView(folder.path)
                    }
            }
        val trackItems =
            filteredSortedChildTracks.mapIndexed { index, track ->
                track to
                    LibraryScreenHomeViewItem(
                        key = track.id,
                        title = track.fileName,
                        subtitle = track.duration.toShortString(),
                        artwork = Artwork.Track(track),
                        tracks = listOf(track),
                        menuItems = { trackMenuItems(track, it.playerWrapper, it.uiManager) },
                        multiSelectMenuItems = { others, viewModel, continuation ->
                            collectionMenuItems(
                                { listOf(track) + others.flatMap { it.tracks } },
                                viewModel.playerWrapper,
                                viewModel.uiManager,
                                continuation,
                            )
                        },
                    ) {
                        it.playerWrapper.setTracks(filteredSortedChildTracks, index)
                    }
            }
        return (folderItems + trackItems)
            .sortedBy(preferences.sortCollator, tab.sortingKeys, tab.sortAscending) { it.first }
            .map { it.second }
    }

    private fun playlistItems(
        preferences: Preferences,
        playlists: Map<UUID, RealizedPlaylist>,
        searchQuery: String,
    ): List<LibraryScreenHomeViewItem> {
        val tab = preferences.tabSettings[TabType.PLAYLISTS]!!
        val filteredSortedPlaylists =
            playlists
                .asIterable()
                .search(searchQuery, preferences.searchCollator) { it.value }
                .sortedBy(preferences.sortCollator, tab.sortingKeys, tab.sortAscending) { it.value }
        return filteredSortedPlaylists.map { (key, playlist) ->
            LibraryScreenHomeViewItem(
                key = key,
                title = playlist.displayName,
                subtitle = playlist.displayStatistics,
                artwork =
                    playlist.specialType?.let { Artwork.Icon(it.icon, it.color) }
                        ?: Artwork.Track(playlist.entries.firstOrNull()?.track ?: InvalidTrack),
                tracks = playlist.validTracks,
                menuItems = {
                    collectionMenuItems({ playlist.validTracks }, it.playerWrapper, it.uiManager) +
                        MenuItem.Divider +
                        playlistCollectionMenuItems(key, it.uiManager)
                },
                multiSelectMenuItems = { others, viewModel, continuation ->
                    collectionMenuItems(
                        { playlist.validTracks + others.flatMap { it.tracks } },
                        viewModel.playerWrapper,
                        viewModel.uiManager,
                        continuation,
                    ) +
                        playlistCollectionMultiSelectMenuItems(
                            { setOf(key) + others.map { it.key as UUID } },
                            viewModel.uiManager,
                            continuation,
                        )
                },
            ) {
                it.uiManager.openPlaylistCollectionView(key)
            }
        }
    }
}

@Stable
data class LibraryScreenHomeViewTabState(
    val multiSelectState: MultiSelectState<LibraryScreenHomeViewItem>,
    val lazyGridState: LazyGridState = LazyGridState(0, 0),
) : AutoCloseable {
    override fun close() {
        multiSelectState.close()
    }
}

@Composable
fun LibraryScreenHomeView(
    state: LibraryScreenHomeViewState,
    viewModel: MainViewModel = viewModel(),
) {
    val artworkCache = viewModel.artworkCache
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    val pagerState = state.pagerState

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            ViewTabRow(preferences, state)
            HorizontalPager(state = pagerState, beyondViewportPageCount = Int.MAX_VALUE) { i ->
                if (state.tabStates.size > i) {
                    val tab = preferences.tabs[i]
                    val (multiSelectState, lazyGridState) = state.tabStates[tab.type]!!
                    val items by multiSelectState.items.collectAsStateWithLifecycle()
                    LibraryList(
                        gridState = lazyGridState,
                        gridSize = tab.gridSize,
                        items = items,
                        multiSelectManager = multiSelectState,
                        artworkCache = artworkCache,
                        artworkColorPreference = preferences.artworkColorPreference,
                        coloredCards = preferences.coloredCards,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewTabRow(preferences: Preferences, state: LibraryScreenHomeViewState) {
    val coroutineScope = rememberCoroutineScope()
    val currentTabIndex = state.pagerState.targetPage.coerceIn(0, preferences.tabs.size - 1)

    Box(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth())
        PrimaryScrollableTabRow(
            scrollState = state.tabRowScrollState,
            selectedTabIndex = currentTabIndex,
            indicator = { TabIndicator(state.pagerState) },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            preferences.tabs.forEachIndexed { i, tab ->
                Tab(
                    selected = i == currentTabIndex,
                    onClick = {
                        if (state.pagerState.targetPage == i) {
                            coroutineScope.launch {
                                state.tabStates[tab.type]?.lazyGridState?.animateScrollToItem(0)
                            }
                        } else {
                            coroutineScope.launch { state.pagerState.animateScrollToPage(i) }
                        }
                    },
                    text = {
                        SingleLineText(
                            Strings[tab.type.stringId],
                            color =
                                if (i == currentTabIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryList(
    gridState: LazyGridState,
    gridSize: Int,
    items: SelectableList<LibraryScreenHomeViewItem>,
    multiSelectManager: MultiSelectManager,
    artworkCache: LruCache<Long, Nullable<Bitmap>>,
    artworkColorPreference: ArtworkColorPreference,
    coloredCards: Boolean,
) {
    val viewModel = viewModel<MainViewModel>()
    val haptics = LocalHapticFeedback.current

    if (items.isEmpty()) {
        EmptyListIndicator()
    } else if (gridSize == 0) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize().scrollbar(gridState),
        ) {
            items.forEachIndexed { index, (info, selected) ->
                item(info.key) {
                    with(info) {
                        LibraryListItemHorizontal(
                            title = title,
                            subtitle = subtitle,
                            lead = {
                                ArtworkImage(
                                    cache = artworkCache,
                                    artwork = artwork,
                                    artworkColorPreference = artworkColorPreference,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            actions = { OverflowMenu(menuItems(viewModel)) },
                            modifier =
                                Modifier.multiSelectClickable(
                                        items,
                                        index,
                                        multiSelectManager,
                                        haptics,
                                    ) {
                                        info.onClick(viewModel)
                                    }
                                    .animateItem(),
                            selected = selected,
                        )
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(gridSize),
            contentPadding = PaddingValues(2.dp),
            modifier = Modifier.fillMaxSize().scrollbar(gridState),
        ) {
            items.forEachIndexed { index, (info, selected) ->
                item(info.key) {
                    with(info) {
                        LibraryListItemCard(
                            title = title,
                            subtitle = subtitle,
                            color =
                                if (coloredCards) artwork.getColor(artworkColorPreference)
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            image = {
                                ArtworkImage(
                                    cache = artworkCache,
                                    artwork = artwork,
                                    artworkColorPreference = artworkColorPreference,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            },
                            menuItems = menuItems(viewModel),
                            modifier =
                                Modifier.padding(2.dp)
                                    .multiSelectClickable(
                                        items,
                                        index,
                                        multiSelectManager,
                                        haptics,
                                    ) {
                                        info.onClick(viewModel)
                                    }
                                    .animateItem(),
                            selected = selected,
                        )
                    }
                }
            }
        }
    }

    // https://issuetracker.google.com/issues/209652366#comment35
    SideEffect {
        gridState.requestScrollToItem(
            index = gridState.firstVisibleItemIndex,
            scrollOffset = gridState.firstVisibleItemScrollOffset,
        )
    }
}

@Immutable
@Serializable
data class TabInfo(
    val type: TabType,
    val gridSize: Int = 0,
    val sortingOptionId: String = type.sortingOptions.keys.first(),
    val sortAscending: Boolean = true,
) {
    val sortingKeys
        get() = (type.sortingOptions[sortingOptionId] ?: type.sortingOptions.values.first()).keys
}

@Immutable
@Serializable
enum class TabType(val stringId: Int, val sortingOptions: Map<String, SortingOption>) {
    TRACKS(R.string.tab_tracks, Track.SortingOptions),
    ALBUMS(R.string.tab_albums, Album.CollectionSortingOptions),
    ARTISTS(R.string.tab_artists, Artist.CollectionSortingOptions),
    GENRES(R.string.tab_genres, Genre.CollectionSortingOptions),
    PLAYLISTS(R.string.tab_playlists, RealizedPlaylist.CollectionSortingOptions),
    FOLDERS(R.string.tab_folders, Folder.SortingOptions),
}