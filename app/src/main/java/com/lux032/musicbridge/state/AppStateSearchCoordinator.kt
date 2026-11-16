package com.lux032.musicbridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032HttpException
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicArtist
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.SearchBestMatch
import com.lux032.musicbridge.music.SearchCategory
import com.lux032.musicbridge.music.UnifiedSearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SearchTab {
    All,
    Artists,
    Albums,
    Tracks,
    Composers;

    fun toCategory(): SearchCategory? = when (this) {
        All -> null
        Artists -> SearchCategory.Artists
        Albums -> SearchCategory.Albums
        Tracks -> SearchCategory.Tracks
        Composers -> SearchCategory.Composers
    }

    companion object {
        fun fromCategory(category: SearchCategory): SearchTab = when (category) {
            SearchCategory.Artists -> Artists
            SearchCategory.Albums -> Albums
            SearchCategory.Tracks -> Tracks
            SearchCategory.Composers -> Composers
        }
    }
}

data class SearchCategoryFeedState(
    val loadedQuery: String = "",
    val artists: List<MusicArtist> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val tracks: List<MusicTrack> = emptyList(),
    val total: Int = 0,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
) {
    fun itemCount(category: SearchCategory): Int = when (category) {
        SearchCategory.Artists, SearchCategory.Composers -> artists.size
        SearchCategory.Albums -> albums.size
        SearchCategory.Tracks -> tracks.size
    }

    fun hasMore(category: SearchCategory): Boolean = itemCount(category) < total
}

internal class AppStateSearchCoordinator(private val state: AppState) {
    var queryInput by mutableStateOf("")
        private set
    var activeQuery by mutableStateOf("")
        private set
    var selectedTab by mutableStateOf(SearchTab.All)
        private set
    var history by mutableStateOf<List<String>>(emptyList())
        private set
    var isOverviewLoading by mutableStateOf(false)
        private set
    var overviewError by mutableStateOf<String?>(null)
        private set
    var overview by mutableStateOf<UnifiedSearchResults?>(null)
        private set
    val categoryStates = mutableStateMapOf<SearchCategory, SearchCategoryFeedState>()

    private var debounceJob: Job? = null
    private var overviewJob: Job? = null
    private val categoryJobs = mutableMapOf<SearchCategory, Job>()
    private var overviewGeneration = 0L
    private val categoryGenerations = mutableMapOf<SearchCategory, Long>()

    fun loadHistory() {
        val sourceId = state.connectionPreferences.id
        history = if (sourceId.isBlank()) {
            emptyList()
        } else {
            state.appPreferences.loadSearchHistory(sourceId)
        }
    }

    fun reset() {
        debounceJob?.cancel()
        overviewJob?.cancel()
        categoryJobs.values.forEach { it.cancel() }
        categoryJobs.clear()
        overviewGeneration++
        SearchCategory.entries.forEach { cat ->
            categoryGenerations[cat] = (categoryGenerations[cat] ?: 0L) + 1L
        }
        queryInput = ""
        activeQuery = ""
        selectedTab = SearchTab.All
        isOverviewLoading = false
        overviewError = null
        overview = null
        categoryStates.clear()
    }

    fun onQueryInputChanged(newValue: String) {
        queryInput = newValue
        debounceJob?.cancel()
        val trimmed = normalizeQuery(newValue)
        if (trimmed.isEmpty()) {
            clearToRecent(clearInput = false)
            return
        }
        debounceJob = state.scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            executeOverviewSearch(trimmed, recordHistory = false)
        }
    }

    fun submitSearch(rawQuery: String = queryInput) {
        debounceJob?.cancel()
        val trimmed = normalizeQuery(rawQuery)
        queryInput = trimmed
        if (trimmed.isEmpty()) {
            clearToRecent(clearInput = true)
            return
        }
        executeOverviewSearch(trimmed, recordHistory = true)
    }

    fun selectHistoryQuery(query: String) {
        val trimmed = normalizeQuery(query)
        if (trimmed.isEmpty()) return
        debounceJob?.cancel()
        queryInput = trimmed
        executeOverviewSearch(trimmed, recordHistory = true)
    }

    fun removeHistoryQuery(query: String) {
        val sourceId = state.connectionPreferences.id
        val updated = history.filterNot { it == query }
        history = updated
        if (sourceId.isNotBlank()) {
            state.appPreferences.saveSearchHistory(sourceId, updated)
        }
    }

    fun clearHistory() {
        val sourceId = state.connectionPreferences.id
        history = emptyList()
        if (sourceId.isNotBlank()) {
            state.appPreferences.saveSearchHistory(sourceId, emptyList())
        }
    }

    fun retryOverview() {
        val q = activeQuery.ifBlank { normalizeQuery(queryInput) }
        if (q.isNotEmpty()) {
            executeOverviewSearch(q, recordHistory = false)
        }
    }

    fun selectTab(tab: SearchTab) {
        selectedTab = tab
        val category = tab.toCategory() ?: return
        val q = activeQuery
        if (q.isBlank()) return
        val current = categoryStates[category]
        if (current != null && current.loadedQuery == q && (current.isLoading || current.error == null)) {
            return
        }
        loadCategoryPage(category, reset = true)
    }

    fun retryCategory(category: SearchCategory) {
        val current = categoryStates[category]
        val shouldReset = current == null || current.loadedQuery != activeQuery || current.itemCount(category) == 0
        loadCategoryPage(category, reset = shouldReset)
    }

    fun loadMoreCategory(category: SearchCategory) {
        val q = activeQuery
        if (q.isBlank()) return
        val current = categoryStates[category] ?: return
        if (current.loadedQuery != q || current.isLoading || current.isLoadingMore || !current.hasMore(category)) {
            return
        }
        loadCategoryPage(category, reset = false)
    }

    /** Steps back from single-category list -> overview -> recent history. */
    fun navigateBackFromResults(): Boolean {
        if (selectedTab != SearchTab.All) {
            selectedTab = SearchTab.All
            return true
        }
        if (activeQuery.isNotEmpty() || queryInput.isNotEmpty() || overviewError != null || isOverviewLoading) {
            clearToRecent(clearInput = true)
            return true
        }
        return false
    }

    fun clearToRecent(clearInput: Boolean = true) {
        debounceJob?.cancel()
        overviewJob?.cancel()
        categoryJobs.values.forEach { it.cancel() }
        categoryJobs.clear()
        overviewGeneration++
        SearchCategory.entries.forEach { cat ->
            categoryGenerations[cat] = (categoryGenerations[cat] ?: 0L) + 1L
        }
        if (clearInput) queryInput = ""
        activeQuery = ""
        selectedTab = SearchTab.All
        isOverviewLoading = false
        overviewError = null
        overview = null
        categoryStates.clear()
    }

    fun recordHistoryIfActive() {
        val q = activeQuery.ifBlank { normalizeQuery(queryInput) }
        if (q.isNotEmpty()) recordHistory(q)
    }

    fun openArtist(artist: MusicArtist) {
        recordHistoryIfActive()
        state.openArtistAlbums(artist.toArtistSummary())
    }

    fun openAlbum(album: MusicAlbum) {
        recordHistoryIfActive()
        state.openAlbumDetail(album)
    }

    fun playTrack(tracks: List<MusicTrack>, index: Int) {
        val track = tracks.getOrNull(index) ?: return
        recordHistoryIfActive()
        val album = state.libraryCoordinator.resolveAlbumForTrack(track)
        state.startSingleTrackPlayback(album, tracks, index)
    }

    fun openBestMatch(best: SearchBestMatch) {
        recordHistoryIfActive()
        val currentOverview = overview
        when (best.category) {
            SearchCategory.Artists -> {
                val found = currentOverview?.artists?.items?.firstOrNull { it.ratingKey == best.id }
                if (found != null) {
                    state.openArtistAlbums(found.toArtistSummary())
                } else {
                    state.openArtistAlbums(best.toArtistSummary(state.connectionPreferences.id))
                }
            }
            SearchCategory.Composers -> {
                val found = currentOverview?.composers?.items?.firstOrNull { it.ratingKey == best.id }
                if (found != null) {
                    state.openArtistAlbums(found.toArtistSummary())
                } else {
                    state.openArtistAlbums(best.toArtistSummary(state.connectionPreferences.id))
                }
            }
            SearchCategory.Albums -> {
                val found = currentOverview?.albums?.items?.firstOrNull { it.ratingKey == best.id }
                if (found != null) {
                    state.openAlbumDetail(found)
                } else {
                    state.openAlbumDetail(best.toAlbum(state.connectionPreferences.id))
                }
            }
            SearchCategory.Tracks -> {
                val previewTracks = currentOverview?.tracks?.items.orEmpty()
                val idx = previewTracks.indexOfFirst { it.ratingKey == best.id }
                if (idx >= 0) {
                    playTrack(previewTracks, idx)
                } else {
                    state.scope.launch {
                        val client = activeClient() ?: return@launch
                        runCatching { client.fetchTrackStream(best.id) }
                            .onSuccess { fetched ->
                                if (fetched != null) {
                                    playTrack(listOf(fetched), 0)
                                }
                            }
                            .onFailure { state.errorMessage = errorMessageFor(it) }
                    }
                }
            }
        }
    }

    fun syncAlbumFavorite(updated: MusicAlbum) {
        val cur = overview
        if (cur != null && cur.albums.items.any { it.ratingKey == updated.ratingKey && it.sourceId == updated.sourceId }) {
            overview = cur.copy(
                albums = cur.albums.copy(
                    items = cur.albums.items.map {
                        if (it.ratingKey == updated.ratingKey && it.sourceId == updated.sourceId) updated else it
                    },
                ),
            )
        }
        val catState = categoryStates[SearchCategory.Albums]
        if (catState != null && catState.albums.any { it.ratingKey == updated.ratingKey && it.sourceId == updated.sourceId }) {
            categoryStates[SearchCategory.Albums] = catState.copy(
                albums = catState.albums.map {
                    if (it.ratingKey == updated.ratingKey && it.sourceId == updated.sourceId) updated else it
                },
            )
        }
    }

    fun syncTrackFavorite(updated: MusicTrack) {
        val cur = overview
        if (cur != null && cur.tracks.items.any { it.ratingKey == updated.ratingKey && it.sourceId == updated.sourceId }) {
            overview = cur.copy(
                tracks = cur.tracks.copy(
                    items = cur.tracks.items.map {
                        if (it.ratingKey == updated.ratingKey && it.sourceId == updated.sourceId) updated else it
                    },
                ),
            )
        }
        val catState = categoryStates[SearchCategory.Tracks]
        if (catState != null && catState.tracks.any { it.ratingKey == updated.ratingKey && it.sourceId == updated.sourceId }) {
            categoryStates[SearchCategory.Tracks] = catState.copy(
                tracks = catState.tracks.map {
                    if (it.ratingKey == updated.ratingKey && it.sourceId == updated.sourceId) updated else it
                },
            )
        }
    }

    fun syncArtistFavorite(sourceId: String, ratingKey: String, newRating: Float?) {
        val cur = overview
        if (cur != null) {
            var changed = false
            val newArtists = cur.artists.items.map {
                if (it.ratingKey == ratingKey && it.sourceId == sourceId) {
                    changed = true
                    it.copy(userRating = newRating)
                } else it
            }
            val newComposers = cur.composers.items.map {
                if (it.ratingKey == ratingKey && it.sourceId == sourceId) {
                    changed = true
                    it.copy(userRating = newRating)
                } else it
            }
            if (changed) {
                overview = cur.copy(
                    artists = cur.artists.copy(items = newArtists),
                    composers = cur.composers.copy(items = newComposers),
                )
            }
        }
        listOf(SearchCategory.Artists, SearchCategory.Composers).forEach { cat ->
            val catState = categoryStates[cat]
            if (catState != null && catState.artists.any { it.ratingKey == ratingKey && it.sourceId == sourceId }) {
                categoryStates[cat] = catState.copy(
                    artists = catState.artists.map {
                        if (it.ratingKey == ratingKey && it.sourceId == sourceId) it.copy(userRating = newRating) else it
                    },
                )
            }
        }
    }

    private fun executeOverviewSearch(query: String, recordHistory: Boolean) {
        if (recordHistory) recordHistory(query)
        overviewJob?.cancel()
        categoryJobs.values.forEach { it.cancel() }
        categoryJobs.clear()
        val generation = ++overviewGeneration
        SearchCategory.entries.forEach { cat ->
            categoryGenerations[cat] = (categoryGenerations[cat] ?: 0L) + 1L
        }
        val profile = state.connectionPreferences
        activeQuery = query
        selectedTab = SearchTab.All
        categoryStates.clear()
        overview = null
        overviewError = null

        val client = activeClient()
        if (client == null) {
            isOverviewLoading = false
            overviewError = Strings.searchNoServer
            return
        }

        isOverviewLoading = true
        overviewJob = state.scope.launch {
            runCatching { client.searchUnified(query) }
                .onSuccess { res ->
                    if (generation != overviewGeneration || state.connectionPreferences.id != profile.id) return@onSuccess
                    overview = res
                    overviewError = null
                    isOverviewLoading = false
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (generation != overviewGeneration || state.connectionPreferences.id != profile.id) return@onFailure
                    isOverviewLoading = false
                    overviewError = errorMessageFor(error)
                }
        }
    }

    private fun loadCategoryPage(category: SearchCategory, reset: Boolean) {
        val query = activeQuery
        if (query.isBlank()) return
        val profile = state.connectionPreferences
        val client = activeClient()
        if (client == null) {
            categoryStates[category] = SearchCategoryFeedState(
                loadedQuery = query,
                error = Strings.searchNoServer,
            )
            return
        }
        categoryJobs[category]?.cancel()
        val generation = (categoryGenerations[category] ?: 0L) + 1L
        categoryGenerations[category] = generation

        val existing = if (reset) SearchCategoryFeedState(loadedQuery = query) else (categoryStates[category] ?: SearchCategoryFeedState(loadedQuery = query))
        val offset = if (reset) 0 else existing.itemCount(category)
        val limit = if (category == SearchCategory.Tracks) TRACK_PAGE_SIZE else CARD_PAGE_SIZE

        categoryStates[category] = existing.copy(
            loadedQuery = query,
            isLoading = reset,
            isLoadingMore = !reset,
            error = null,
        )

        categoryJobs[category] = state.scope.launch {
            runCatching { client.searchUnified(query, category = category, offset = offset, limit = limit) }
                .onSuccess { res ->
                    if (categoryGenerations[category] != generation || state.connectionPreferences.id != profile.id) {
                        return@onSuccess
                    }
                    val current = categoryStates[category] ?: SearchCategoryFeedState(loadedQuery = query)
                    categoryStates[category] = when (category) {
                        SearchCategory.Artists -> current.copy(
                            loadedQuery = query,
                            artists = if (reset) res.artists.items else (current.artists + res.artists.items).distinctBy(MusicArtist::ratingKey),
                            total = res.artists.total,
                            isLoading = false,
                            isLoadingMore = false,
                            error = null,
                        )
                        SearchCategory.Albums -> current.copy(
                            loadedQuery = query,
                            albums = if (reset) res.albums.items else (current.albums + res.albums.items).distinctBy(MusicAlbum::ratingKey),
                            total = res.albums.total,
                            isLoading = false,
                            isLoadingMore = false,
                            error = null,
                        )
                        SearchCategory.Tracks -> current.copy(
                            loadedQuery = query,
                            tracks = if (reset) res.tracks.items else (current.tracks + res.tracks.items).distinctBy(MusicTrack::ratingKey),
                            total = res.tracks.total,
                            isLoading = false,
                            isLoadingMore = false,
                            error = null,
                        )
                        SearchCategory.Composers -> current.copy(
                            loadedQuery = query,
                            artists = if (reset) res.composers.items else (current.artists + res.composers.items).distinctBy(MusicArtist::ratingKey),
                            total = res.composers.total,
                            isLoading = false,
                            isLoadingMore = false,
                            error = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (categoryGenerations[category] != generation || state.connectionPreferences.id != profile.id) {
                        return@onFailure
                    }
                    val current = categoryStates[category] ?: SearchCategoryFeedState(loadedQuery = query)
                    categoryStates[category] = current.copy(
                        loadedQuery = query,
                        isLoading = false,
                        isLoadingMore = false,
                        error = errorMessageFor(error),
                    )
                }
        }
    }

    private fun recordHistory(query: String) {
        val sourceId = state.connectionPreferences.id
        val updated = (listOf(query) + history.filterNot { it == query }).take(MAX_HISTORY)
        history = updated
        if (sourceId.isNotBlank()) {
            state.appPreferences.saveSearchHistory(sourceId, updated)
        }
    }

    private fun activeClient(): Music032Client? {
        val profile = state.connectionPreferences
        if (profile.type != MusicServerType.Music032 || !profile.hasCredentials()) return null
        return runCatching { state.clientFor(profile.id) as? Music032Client }.getOrNull()
    }

    private fun normalizeQuery(raw: String): String = raw.trim().take(MAX_QUERY_LENGTH)

    private fun errorMessageFor(error: Throwable): String =
        (error as? Music032HttpException)?.serverMessage ?: error.message ?: Strings.searchFailedTitle

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 400L
        private const val MAX_QUERY_LENGTH = 200
        private const val MAX_HISTORY = 20
        private const val CARD_PAGE_SIZE = 50
        private const val TRACK_PAGE_SIZE = 100
    }
}

internal fun SearchBestMatch.toArtistSummary(sourceId: String): ArtistSummary = ArtistSummary(
    name = title,
    coverUrl = imageUrl,
    albumCount = 0,
    ratingKey = id,
    sourceId = sourceId,
)

internal fun SearchBestMatch.toAlbum(sourceId: String): MusicAlbum = MusicAlbum(
    ratingKey = id,
    title = title,
    artistName = subtitle.ifBlank { null },
    year = null,
    thumbUrl = imageUrl,
    userRating = null,
    addedAtEpochSeconds = null,
    lastViewedAtEpochSeconds = null,
    section = LibrarySection("", "", ""),
    sourceId = sourceId,
)

internal fun MusicTrack.toStubAlbum(): MusicAlbum? {
    val key = albumRatingKey?.takeIf { it.isNotBlank() } ?: return null
    return MusicAlbum(
        ratingKey = key,
        title = albumTitle ?: title,
        artistName = artistName,
        year = null,
        thumbUrl = thumbUrl,
        userRating = null,
        addedAtEpochSeconds = null,
        lastViewedAtEpochSeconds = null,
        section = LibrarySection("", "", ""),
        sourceId = sourceId,
    )
}

internal fun MusicTrack.toStubArtist(): MusicArtist? {
    val ref = artists.firstOrNull() ?: return null
    return MusicArtist(
        ratingKey = ref.id,
        title = ref.name,
        thumbUrl = null,
        sourceId = sourceId,
    )
}
