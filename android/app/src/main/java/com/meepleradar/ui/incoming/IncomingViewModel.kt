package com.meepleradar.ui.incoming

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

sealed interface IncomingUiState {
    object Loading : IncomingUiState

    data class Error(val message: String) : IncomingUiState

    data class Content(val items: List<IncomingItem>, val favorites: Set<String>) : IncomingUiState
}

sealed interface SyncProgressState {
    object Idle : SyncProgressState
    data class Progress(val percent: Int, val message: String) : SyncProgressState
    object Finished : SyncProgressState
}

class IncomingViewModel(
    private val repo: ListingRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<IncomingUiState>(IncomingUiState.Loading)
    val state: StateFlow<IncomingUiState> = _state.asStateFlow()

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

    private val _onlyDiscounted = MutableStateFlow(false)
    val onlyDiscounted: StateFlow<Boolean> = _onlyDiscounted.asStateFlow()

    private val _selectedAvailability = MutableStateFlow(AvailabilityStatus.entries.toSet())
    val selectedAvailability: StateFlow<Set<AvailabilityStatus>> = _selectedAvailability.asStateFlow()

    private val _sortOption = MutableStateFlow(ListingSortOption.DEFAULT)
    val sortOption: StateFlow<ListingSortOption> = _sortOption.asStateFlow()

    private val _lastModifiedTimestamp = MutableStateFlow<Long>(repo.lastDbModifiedTimestamp())
    val lastModifiedTimestamp: StateFlow<Long> = _lastModifiedTimestamp.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgressState>(SyncProgressState.Idle)
    val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _ignoredItems = MutableStateFlow<List<IncomingItem>>(emptyList())
    val ignoredItems: StateFlow<List<IncomingItem>> = _ignoredItems.asStateFlow()

    val newlyAddedCountFlow: StateFlow<Int> = repo.newlyAddedCountFlow()
    val newlyAddedCount: Int get() = repo.newlyAddedCount()

    private val _unseenCount = MutableStateFlow(0)
    val unseenCount: StateFlow<Int> = _unseenCount.asStateFlow()

    private var rawItems: List<IncomingItem> = emptyList()
    private var rawFavorites: Set<String> = emptySet()
    private var hasLoaded: Boolean = false
    private var loadedCacheTimestamp: Long = -1L
    private var lastObservedRevision: Long = repo.catalogRevisionFlow().value

    init {
        loadSources()
        loadLocal()
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

    fun toggleHideSeen() {
        _hideSeen.value = !_hideSeen.value
        updateFilteredState()
    }

    fun setSortOption(option: ListingSortOption) {
        _sortOption.value = option
        updateFilteredState()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        updateFilteredState()
    }

    fun loadIgnoredItems() {
        viewModelScope.launch {
            _ignoredItems.value = runCatching { repo.ignoredItems(today) }.getOrDefault(emptyList())
        }
    }

    fun restoreIgnored(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.restoreIgnored(item.key)
                _ignoredItems.value = _ignoredItems.value.filterNot { it.key == item.key }
                rawItems = repo.incoming(
                    today = today,
                    sourceIds = null,
                    includeUndated = true,
                    includePriceless = true,
                    forceRefresh = false,
                )
                lastObservedRevision = repo.catalogRevisionFlow().value
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    private fun reloadFromLocalCache(revision: Long) {
        viewModelScope.launch {
            try {
                rawItems = repo.incoming(
                    today = today,
                    sourceIds = null,
                    includeUndated = true,
                    includePriceless = true,
                    forceRefresh = false,
                )
                rawFavorites = repo.favoriteKeys(today)
                _ignoredItems.value = runCatching { repo.ignoredItems(today) }.getOrDefault(_ignoredItems.value)
                _sources.value = runCatching { repo.sources() }.getOrDefault(_sources.value)
                loadedCacheTimestamp = repo.lastDbModifiedTimestamp()
                _lastModifiedTimestamp.value = loadedCacheTimestamp
                lastObservedRevision = revision
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
                    val updated = repo.incoming(today, includeUndated = true, includePriceless = true, forceRefresh = false)
                    rawItems = updated
                    loadedCacheTimestamp = currentTimestamp
                    _lastModifiedTimestamp.value = currentTimestamp
                    lastObservedRevision = currentRevision
                    _sources.value = runCatching { repo.sources() }.getOrDefault(_sources.value)
                }
                rawFavorites = repo.favoriteKeys(today)
                _ignoredItems.value = runCatching { repo.ignoredItems(today) }.getOrDefault(_ignoredItems.value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            } finally {
                val seenKeys = repo.seenKeys()
                rawItems = rawItems.map { item ->
                    val isSeen = item.isSeen || item.key in seenKeys || item.bggSelectionKeys.any { it in seenKeys }
                    if (item.isSeen == isSeen) item else item.copy(isSeen = isSeen)
                }
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
        onlyDiscounted: Boolean = false,
    ) {
        _selectedSources.value = sourceIds
        _hideUndated.value = hideUndated
        _hidePriceless.value = hidePriceless
        _hideSeen.value = hideSeen
        _onlyDiscounted.value = onlyDiscounted
        _selectedAvailability.value = availability
        _sortOption.value = sortOption
        updateFilteredState()
    }

    fun resetFilters() {
        _selectedSources.value = null
        _hideUndated.value = false
        _hidePriceless.value = false
        _hideSeen.value = false
        _onlyDiscounted.value = false
        _selectedAvailability.value = AvailabilityStatus.entries.toSet()
        _sortOption.value = ListingSortOption.DEFAULT
        _searchQuery.value = ""
        updateFilteredState()
    }

    private fun loadLocal() {
        _state.value = IncomingUiState.Loading
        viewModelScope.launch {
            try {
                rawItems = repo.incoming(
                    today = today,
                    sourceIds = null,
                    includeUndated = true,
                    includePriceless = true,
                    forceRefresh = false,
                )
                rawFavorites = repo.favoriteKeys(today)
                _ignoredItems.value = runCatching { repo.ignoredItems(today) }.getOrDefault(emptyList())

                loadedCacheTimestamp = repo.lastDbModifiedTimestamp()
                _lastModifiedTimestamp.value = loadedCacheTimestamp
                lastObservedRevision = repo.catalogRevisionFlow().value
                hasLoaded = true
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    fun refresh(showLoadingState: Boolean = true) {
        if (showLoadingState) {
            _state.value = IncomingUiState.Loading
        }
        _isRefreshing.value = true
        _syncProgress.value = SyncProgressState.Progress(0, "Avvio sincronizzazione…")
        viewModelScope.launch {
            try {
                rawItems = repo.incoming(
                    today = today,
                    sourceIds = null,
                    includeUndated = true,
                    includePriceless = true,
                    forceRefresh = true,
                    onProgress = { percent, message ->
                        _syncProgress.value = SyncProgressState.Progress(percent, message)
                    },
                )
                rawFavorites = repo.favoriteKeys(today)
                _ignoredItems.value = runCatching { repo.ignoredItems(today) }.getOrDefault(emptyList())
                _sources.value = runCatching { repo.sources() }.getOrDefault(_sources.value)

                loadedCacheTimestamp = repo.lastDbModifiedTimestamp()
                _lastModifiedTimestamp.value = loadedCacheTimestamp
                lastObservedRevision = repo.catalogRevisionFlow().value
                hasLoaded = true
                _syncProgress.value = SyncProgressState.Finished
                updateFilteredState()
            } catch (e: CancellationException) {
                _syncProgress.value = SyncProgressState.Idle
                throw e
            } catch (e: Exception) {
                _syncProgress.value = SyncProgressState.Idle
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            } finally {
                _isRefreshing.value = false
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
                onlyDiscounted = _onlyDiscounted.value,
                selectedAvailability = _selectedAvailability.value,
                searchQuery = _searchQuery.value,
                showOnlyNewlyAdded = false,
                sortOption = _sortOption.value,
            ),
            today = today,
            matchItemReleaseDateAsPreorder = true,
        )

        _unseenCount.value = rawItems.count { !it.isSeen }
        _state.value = IncomingUiState.Content(items = filtered, favorites = rawFavorites)
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

    fun toggleFavorite(item: IncomingItem) {
        val content = _state.value as? IncomingUiState.Content ?: return
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
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    fun ignore(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.setAction(item.key, Action.IGNORED)
                lastObservedRevision = repo.catalogRevisionFlow().value
                rawItems = rawItems.filterNot { it.key == item.key }
                if (_ignoredItems.value.none { it.key == item.key }) {
                    _ignoredItems.value = (_ignoredItems.value + item).sortedBy { it.title.lowercase() }
                }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    private companion object {
        const val DEFAULT_ERROR = "Errore di rete"
    }
}
