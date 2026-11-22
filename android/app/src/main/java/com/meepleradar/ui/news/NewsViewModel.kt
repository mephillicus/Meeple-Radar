package com.meepleradar.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.ui.components.ListingFilterState
import com.meepleradar.ui.components.ListingSortOption
import com.meepleradar.ui.components.filterAndSortListings
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

sealed interface NewsUiState {
    object Loading : NewsUiState
    data class Error(val message: String) : NewsUiState
    data class Content(val items: List<IncomingItem>, val favorites: Set<String>) : NewsUiState
}

class NewsViewModel(
    private val repo: ListingRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<NewsUiState>(NewsUiState.Loading)
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _selectedSources = MutableStateFlow<Set<String>?>(null)
    val selectedSources: StateFlow<Set<String>?> = _selectedSources.asStateFlow()

    private val _hideUndated = MutableStateFlow(false)
    val hideUndated: StateFlow<Boolean> = _hideUndated.asStateFlow()

    private val _hidePriceless = MutableStateFlow(false)
    val hidePriceless: StateFlow<Boolean> = _hidePriceless.asStateFlow()

    private val _hideSeen = MutableStateFlow(false)
    val hideSeen: StateFlow<Boolean> = _hideSeen.asStateFlow()

    private val _selectedAvailability = MutableStateFlow(AvailabilityStatus.entries.toSet())
    val selectedAvailability: StateFlow<Set<AvailabilityStatus>> = _selectedAvailability.asStateFlow()

    private val _sortOption = MutableStateFlow(ListingSortOption.DEFAULT)
    val sortOption: StateFlow<ListingSortOption> = _sortOption.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showOnlyNewlyAdded = MutableStateFlow(false)
    val showOnlyNewlyAdded: StateFlow<Boolean> = _showOnlyNewlyAdded.asStateFlow()

    private val _newlyAddedCount = MutableStateFlow(0)
    val newlyAddedCount: StateFlow<Int> = _newlyAddedCount.asStateFlow()

    private val _unseenCount = MutableStateFlow(0)
    val unseenCount: StateFlow<Int> = _unseenCount.asStateFlow()

    private var rawItems: List<IncomingItem> = emptyList()
    private var rawFavorites: Set<String> = emptySet()
    private var hasLoaded: Boolean = false
    private var loadedCacheTimestamp: Long = -1L
    private var lastObservedRevision: Long = repo.catalogRevisionFlow().value
    private var loadJob: Job? = null

    init {
        loadSources()
        loadNews()
        viewModelScope.launch {
            repo.catalogRevisionFlow()
                .drop(1)
                .collect { revision ->
                    if (hasLoaded && !_isRefreshing.value && revision != lastObservedRevision) {
                        reloadFromLocalCache(revision)
                    }
                }
        }
    }

    fun toggleNewlyAddedFilter() {
        _showOnlyNewlyAdded.value = !_showOnlyNewlyAdded.value
        updateFilteredState()
    }

    fun toggleHideSeen() {
        _hideSeen.value = !_hideSeen.value
        updateFilteredState()
    }

    fun setSortOption(option: ListingSortOption) {
        _sortOption.value = option
        updateFilteredState()
    }

    fun loadNews() {
        loadJob?.cancel()
        _state.value = NewsUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                rawItems = repo.newsItems(today)
                rawFavorites = repo.favoriteKeys(today)
                loadedCacheTimestamp = repo.lastDbModifiedTimestamp()
                lastObservedRevision = repo.catalogRevisionFlow().value
                hasLoaded = true
                _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NewsUiState.Error(e.message ?: "Errore di caricamento")
            }
        }
    }

    fun refresh(showLoadingState: Boolean = true) {
        loadJob?.cancel()
        if (showLoadingState) {
            _state.value = NewsUiState.Loading
        }
        _isRefreshing.value = true
        loadJob = viewModelScope.launch {
            try {
                rawItems = repo.refreshNewsItems(today)
                rawFavorites = repo.favoriteKeys(today)
                _sources.value = runCatching { repo.sources() }.getOrDefault(_sources.value)
                loadedCacheTimestamp = repo.lastDbModifiedTimestamp()
                lastObservedRevision = repo.catalogRevisionFlow().value
                hasLoaded = true
                _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NewsUiState.Error(e.message ?: "Errore di rete")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadSources() {
        viewModelScope.launch {
            try {
                _sources.value = repo.sources()
            } catch (_: Exception) {
                _sources.value = emptyList()
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        updateFilteredState()
    }

    private fun reloadFromLocalCache(revision: Long) {
        viewModelScope.launch {
            try {
                rawItems = repo.newsItems(today)
                rawFavorites = repo.favoriteKeys(today)
                _sources.value = runCatching { repo.sources() }.getOrDefault(_sources.value)
                loadedCacheTimestamp = repo.lastDbModifiedTimestamp()
                lastObservedRevision = revision
                _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun refreshSeenStatus() {
        if (!hasLoaded) return
        viewModelScope.launch {
            try {
                val currentTimestamp = repo.lastDbModifiedTimestamp()
                val currentRevision = repo.catalogRevisionFlow().value
                if (currentTimestamp != loadedCacheTimestamp || currentRevision != lastObservedRevision) {
                    val updated = repo.newsItems(today)
                    rawItems = updated
                    loadedCacheTimestamp = currentTimestamp
                    lastObservedRevision = currentRevision
                    _sources.value = runCatching { repo.sources() }.getOrDefault(_sources.value)
                }
                rawFavorites = repo.favoriteKeys(today)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                val seenKeys = repo.seenKeys()
                rawItems = rawItems.map { item ->
                    val isSeen = item.isSeen || item.key in seenKeys || item.bggSelectionKeys.any { it in seenKeys }
                    if (item.isSeen == isSeen) item else item.copy(isSeen = isSeen)
                }
                _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
                updateFilteredState()
            }
        }
    }

    fun markAllAsSeen() {
        val unseenItems = rawItems.filterNot { it.isSeen }
        if (unseenItems.isEmpty()) return
        val keysToMark = buildList {
            unseenItems.forEach { item ->
                add(item.key)
                addAll(item.bggSelectionKeys)
            }
        }
        repo.markAllLocalSeen(keysToMark)
        rawItems = rawItems.map { item -> if (item.isSeen) item else item.copy(isSeen = true) }
        updateFilteredState()

        viewModelScope.launch(Dispatchers.IO) {
            unseenItems.forEach { item ->
                runCatching { repo.setAction(item.key, Action.SEEN) }
            }
        }
    }

    fun applyFilters(
        sourceIds: Set<String>?,
        hideUndated: Boolean,
        hidePriceless: Boolean = false,
        hideSeen: Boolean = false,
        availability: Set<AvailabilityStatus> = AvailabilityStatus.entries.toSet(),
        sortOption: ListingSortOption = _sortOption.value,
    ) {
        _selectedSources.value = sourceIds
        _hideUndated.value = hideUndated
        _hidePriceless.value = hidePriceless
        _hideSeen.value = hideSeen
        _selectedAvailability.value = availability
        _sortOption.value = sortOption
        updateFilteredState()
    }

    fun resetFilters() {
        _selectedSources.value = null
        _hideUndated.value = false
        _hidePriceless.value = false
        _hideSeen.value = false
        _selectedAvailability.value = AvailabilityStatus.entries.toSet()
        _sortOption.value = ListingSortOption.DEFAULT
        _searchQuery.value = ""
        _showOnlyNewlyAdded.value = false
        updateFilteredState()
    }

    fun toggleFavorite(item: IncomingItem) {
        val content = _state.value as? NewsUiState.Content ?: return
        val wasFavorite = item.key in content.favorites
        val action = if (wasFavorite) Action.UNFAVORITED else Action.FAVORITE

        viewModelScope.launch {
            try {
                repo.setAction(item.key, action)
                lastObservedRevision = repo.catalogRevisionFlow().value
                rawFavorites = if (wasFavorite) {
                    rawFavorites - item.key
                } else {
                    rawFavorites + item.key
                }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NewsUiState.Error(e.message ?: "Errore di rete")
            }
        }
    }

    fun ignore(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.setAction(item.key, Action.IGNORED)
                lastObservedRevision = repo.catalogRevisionFlow().value
                rawItems = rawItems.filterNot { it.key == item.key }
                _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NewsUiState.Error(e.message ?: "Errore di rete")
            }
        }
    }

    private fun updateFilteredState() {
        val filtered = filterAndSortListings(
            items = rawItems,
            state = ListingFilterState(
                selectedSources = _selectedSources.value,
                hideUndated = _hideUndated.value,
                hidePriceless = _hidePriceless.value,
                hideSeen = _hideSeen.value,
                selectedAvailability = _selectedAvailability.value,
                searchQuery = _searchQuery.value,
                showOnlyNewlyAdded = _showOnlyNewlyAdded.value,
                sortOption = _sortOption.value,
            ),
            today = today,
            matchItemReleaseDateAsPreorder = false,
        )

        _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
        _unseenCount.value = rawItems.count { !it.isSeen }
        _state.value = NewsUiState.Content(filtered, rawFavorites)
    }
}
