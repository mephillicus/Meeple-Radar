package com.meepleradar.ui.preorders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
import com.meepleradar.data.model.availabilityStatus
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PreordersUiState {
    object Loading : PreordersUiState
    data class Error(val message: String) : PreordersUiState
    data class Content(val items: List<IncomingItem>, val favorites: Set<String>) : PreordersUiState
}

class PreordersViewModel(
    private val repo: ListingRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<PreordersUiState>(PreordersUiState.Loading)
    val state: StateFlow<PreordersUiState> = _state.asStateFlow()

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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _showOnlyNewlyAdded = MutableStateFlow(false)
    val showOnlyNewlyAdded: StateFlow<Boolean> = _showOnlyNewlyAdded.asStateFlow()

    private val _newlyAddedCount = MutableStateFlow(0)
    val newlyAddedCount: StateFlow<Int> = _newlyAddedCount.asStateFlow()

    private val _unseenCount = MutableStateFlow(0)
    val unseenCount: StateFlow<Int> = _unseenCount.asStateFlow()

    fun toggleNewlyAddedFilter() {
        _showOnlyNewlyAdded.value = !_showOnlyNewlyAdded.value
        updateFilteredState()
    }

    fun toggleHideSeen() {
        _hideSeen.value = !_hideSeen.value
        updateFilteredState()
    }

    private var rawItems: List<IncomingItem> = emptyList()
    private var rawFavorites: Set<String> = emptySet()
    private var hasLoaded: Boolean = false
    private var loadedCacheTimestamp: Long = -1L
    private var loadJob: Job? = null

    init {
        loadSources()
        loadPreorders()
    }

    fun loadPreorders() {
        loadJob?.cancel()
        _state.value = PreordersUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                rawItems = repo.preorderItems(today)
                rawFavorites = repo.favoriteKeys(today)
                loadedCacheTimestamp = repo.lastDbModifiedTimestamp()
                hasLoaded = true
                _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PreordersUiState.Error(e.message ?: "Errore di caricamento")
            }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        _state.value = PreordersUiState.Loading
        loadJob = viewModelScope.launch {
            try {
                rawItems = repo.refreshPreorderItems(today)
                rawFavorites = repo.favoriteKeys(today)
                if (_sources.value.isEmpty()) {
                    _sources.value = runCatching { repo.sources() }.getOrDefault(emptyList())
                }
                loadedCacheTimestamp = repo.lastDbModifiedTimestamp()
                hasLoaded = true
                _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PreordersUiState.Error(e.message ?: "Errore di rete")
            }
        }
    }

    fun loadSources() {
        viewModelScope.launch {
            try {
                _sources.value = repo.sources()
            } catch (e: Exception) {
                _sources.value = emptyList()
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        updateFilteredState()
    }

    fun refreshSeenStatus() {
        if (!hasLoaded) return
        viewModelScope.launch {
            try {
                val currentTimestamp = repo.lastDbModifiedTimestamp()
                if (currentTimestamp != loadedCacheTimestamp) {
                    val updated = repo.preorderItems(today)
                    rawItems = updated
                    loadedCacheTimestamp = currentTimestamp
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
    ) {
        _selectedSources.value = sourceIds
        _hideUndated.value = hideUndated
        _hidePriceless.value = hidePriceless
        _hideSeen.value = hideSeen
        _selectedAvailability.value = availability
        updateFilteredState()
    }

    fun resetFilters() {
        _selectedSources.value = null
        _hideUndated.value = false
        _hidePriceless.value = false
        _hideSeen.value = false
        _selectedAvailability.value = AvailabilityStatus.entries.toSet()
        _searchQuery.value = ""
        _showOnlyNewlyAdded.value = false
        updateFilteredState()
    }

    fun toggleFavorite(item: IncomingItem) {
        val content = _state.value as? PreordersUiState.Content ?: return
        val wasFavorite = item.key in content.favorites
        val action = if (wasFavorite) Action.UNFAVORITED else Action.FAVORITE

        viewModelScope.launch {
            try {
                repo.setAction(item.key, action)
                rawFavorites = if (wasFavorite) {
                    rawFavorites - item.key
                } else {
                    rawFavorites + item.key
                }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PreordersUiState.Error(e.message ?: "Errore di rete")
            }
        }
    }

    fun ignore(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.setAction(item.key, Action.IGNORED)
                rawItems = rawItems.filterNot { it.key == item.key }
                _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PreordersUiState.Error(e.message ?: "Errore di rete")
            }
        }
    }

    private fun updateFilteredState() {
        val sourceIds = _selectedSources.value
        val hideUndated = _hideUndated.value
        val hidePriceless = _hidePriceless.value
        val selectedAvailability = _selectedAvailability.value
        val query = _searchQuery.value.trim()

        val queryWords = if (query.length >= 3) {
            query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        } else {
            emptyList()
        }

        val showNewlyAdded = _showOnlyNewlyAdded.value
        val hideSeen = _hideSeen.value

        val filtered = rawItems.filter { item ->
            val sourceMatch = sourceIds == null || item.offers.any { it.sourceId in sourceIds }
            val dateMatch = !hideUndated || item.releaseDate != null
            val priceMatch = !hidePriceless || item.offers.any { it.price != null }
            val seenMatch = !hideSeen || !item.isSeen
            val isItemPreorder = item.releaseDate != null && !item.releaseDate.isBefore(today)
            val availabilityMatch = selectedAvailability == AvailabilityStatus.entries.toSet() ||
                (AvailabilityStatus.PREORDER in selectedAvailability && isItemPreorder) ||
                item.offers.any { availabilityStatus(it.availability, it.releaseDate) in selectedAvailability }

            val searchMatch = if (queryWords.isEmpty()) {
                true
            } else {
                val titleLower = item.title.lowercase(Locale.ROOT)
                queryWords.all { word -> titleLower.contains(word) }
            }

            val newlyAddedMatch = !showNewlyAdded || item.isNewlyAdded

            sourceMatch && dateMatch && priceMatch && seenMatch && availabilityMatch && searchMatch && newlyAddedMatch
        }

        _newlyAddedCount.value = rawItems.count { it.isNewlyAdded }
        _unseenCount.value = rawItems.count { !it.isSeen }
        _state.value = PreordersUiState.Content(filtered, rawFavorites)
    }
}
