package com.meepleradar.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.availabilityStatus
import com.meepleradar.data.repo.ListingRepository
import com.meepleradar.ui.incoming.IncomingUiState
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

enum class FavoriteAvailabilityFilter(val label: String) {
    ALL("Tutti"),
    AVAILABLE("Disponibili"),
    PREORDER("Preordini"),
    UNAVAILABLE("Non disp."),
}

class FavoritesViewModel(
    private val repo: ListingRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<IncomingUiState>(IncomingUiState.Loading)
    val state: StateFlow<IncomingUiState> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedAvailabilityFilter = MutableStateFlow(FavoriteAvailabilityFilter.ALL)
    val selectedAvailabilityFilter: StateFlow<FavoriteAvailabilityFilter> = _selectedAvailabilityFilter.asStateFlow()

    private val _ignoredItems = MutableStateFlow<List<IncomingItem>>(emptyList())
    val ignoredItems: StateFlow<List<IncomingItem>> = _ignoredItems.asStateFlow()

    private var rawItems: List<IncomingItem> = emptyList()
    private var hasLoaded: Boolean = false
    private var lastObservedRevision: Long = repo.catalogRevisionFlow().value

    init {
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

    private fun reloadFromLocalCache(revision: Long) {
        viewModelScope.launch {
            try {
                rawItems = repo.favorites(today, forceRefresh = false)
                _ignoredItems.value = runCatching { repo.ignoredItems(today) }.getOrDefault(_ignoredItems.value)
                lastObservedRevision = revision
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun refresh(showLoadingState: Boolean = true, forceRefresh: Boolean = false) {
        if (showLoadingState) {
            _state.value = IncomingUiState.Loading
        }
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                rawItems = repo.favorites(today, forceRefresh = forceRefresh)
                _ignoredItems.value = runCatching { repo.ignoredItems(today) }.getOrDefault(emptyList())
                lastObservedRevision = repo.catalogRevisionFlow().value
                hasLoaded = true
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (_state.value is IncomingUiState.Content) {
            updateFilteredState()
        }
    }

    fun setAvailabilityFilter(filter: FavoriteAvailabilityFilter) {
        _selectedAvailabilityFilter.value = filter
        if (_state.value is IncomingUiState.Content) {
            updateFilteredState()
        }
    }

    fun toggleFavorite(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.setAction(item.key, Action.UNFAVORITED)
                lastObservedRevision = repo.catalogRevisionFlow().value
                remove(item.key)
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
                remove(item.key)
                if (_ignoredItems.value.none { it.key == item.key }) {
                    _ignoredItems.value = (_ignoredItems.value + item).sortedBy { it.title.lowercase(Locale.ROOT) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    fun restoreIgnored(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.restoreIgnored(item.key)
                lastObservedRevision = repo.catalogRevisionFlow().value
                _ignoredItems.value = _ignoredItems.value.filterNot { it.key == item.key }
                rawItems = repo.favorites(today)
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    private fun remove(key: String) {
        rawItems = rawItems.filterNot { it.key == key }
        if (_state.value is IncomingUiState.Content) {
            updateFilteredState()
        }
    }

    private fun updateFilteredState() {
        val query = _searchQuery.value.trim()
        val queryWords = if (query.isNotEmpty()) {
            query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        } else {
            emptyList()
        }
        val filter = _selectedAvailabilityFilter.value

        val filtered = rawItems.filter { item ->
            val searchMatch = if (queryWords.isEmpty()) {
                true
            } else {
                val titleLower = item.title.lowercase(Locale.ROOT)
                queryWords.all { word -> titleLower.contains(word) }
            }
            val availabilityMatch = matchesAvailabilityFilter(item, filter)
            searchMatch && availabilityMatch
        }

        _state.value = IncomingUiState.Content(
            items = filtered,
            favorites = rawItems.map { it.key }.toSet(),
        )
    }

    private fun matchesAvailabilityFilter(
        item: IncomingItem,
        filter: FavoriteAvailabilityFilter,
    ): Boolean = when (filter) {
        FavoriteAvailabilityFilter.ALL -> true
        FavoriteAvailabilityFilter.AVAILABLE -> item.offers.any {
            availabilityStatus(it.availability, it.releaseDate, today, it.reprintDate) == AvailabilityStatus.AVAILABLE
        }
        FavoriteAvailabilityFilter.PREORDER -> (item.releaseDate != null && !item.releaseDate.isBefore(today)) ||
            item.offers.any { availabilityStatus(it.availability, it.releaseDate, today, it.reprintDate) == AvailabilityStatus.PREORDER }
        FavoriteAvailabilityFilter.UNAVAILABLE -> {
            val statuses = item.offers.map { availabilityStatus(it.availability, it.releaseDate, today, it.reprintDate) }
            val isAvailable = AvailabilityStatus.AVAILABLE in statuses
            val isPreorder = (item.releaseDate != null && !item.releaseDate.isBefore(today)) ||
                AvailabilityStatus.PREORDER in statuses
            !isAvailable && !isPreorder &&
                statuses.any { it == AvailabilityStatus.UNAVAILABLE || it == AvailabilityStatus.DISCONTINUED }
        }
    }

    private companion object {
        const val DEFAULT_ERROR = "Errore di rete"
    }
}
