package com.meepleradar.ui.news

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _selectedSources = MutableStateFlow<Set<String>?>(null)
    val selectedSources: StateFlow<Set<String>?> = _selectedSources.asStateFlow()

    private val _hideUndated = MutableStateFlow(false)
    val hideUndated: StateFlow<Boolean> = _hideUndated.asStateFlow()

    private val _hidePriceless = MutableStateFlow(false)
    val hidePriceless: StateFlow<Boolean> = _hidePriceless.asStateFlow()

    private val _selectedAvailability = MutableStateFlow(AvailabilityStatus.entries.toSet())
    val selectedAvailability: StateFlow<Set<AvailabilityStatus>> = _selectedAvailability.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var rawItems: List<IncomingItem> = emptyList()
    private var rawFavorites: Set<String> = emptySet()

    init {
        loadSources()
        loadNews()
    }

    fun loadNews() {
        _state.value = NewsUiState.Loading
        viewModelScope.launch {
            try {
                rawItems = repo.newsItems(today)
                rawFavorites = repo.favorites(today).map { it.key }.toSet()
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NewsUiState.Error(e.message ?: "Errore di caricamento")
            }
        }
    }

    fun refresh() {
        _state.value = NewsUiState.Loading
        viewModelScope.launch {
            try {
                repo.incoming(today, forceRefresh = true)
                rawItems = repo.newsItems(today)
                rawFavorites = repo.favorites(today).map { it.key }.toSet()
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NewsUiState.Error(e.message ?: "Errore di rete")
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

    fun applyFilters(
        sourceIds: Set<String>?,
        hideUndated: Boolean,
        hidePriceless: Boolean = false,
        availability: Set<AvailabilityStatus> = AvailabilityStatus.entries.toSet(),
    ) {
        _selectedSources.value = sourceIds
        _hideUndated.value = hideUndated
        _hidePriceless.value = hidePriceless
        _selectedAvailability.value = availability
        updateFilteredState()
    }

    fun resetFilters() {
        _selectedSources.value = null
        _hideUndated.value = false
        _hidePriceless.value = false
        _selectedAvailability.value = AvailabilityStatus.entries.toSet()
        _searchQuery.value = ""
        updateFilteredState()
    }

    fun toggleFavorite(item: IncomingItem) {
        val content = _state.value as? NewsUiState.Content ?: return
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
                _state.value = NewsUiState.Error(e.message ?: "Errore di rete")
            }
        }
    }

    fun ignore(item: IncomingItem) {
        viewModelScope.launch {
            try {
                repo.setAction(item.key, Action.IGNORED)
                rawItems = rawItems.filterNot { it.key == item.key }
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = NewsUiState.Error(e.message ?: "Errore di rete")
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

        val filtered = rawItems.filter { item ->
            val sourceMatch = sourceIds == null || item.offers.any { it.sourceId in sourceIds }
            val dateMatch = !hideUndated || item.releaseDate != null
            val priceMatch = !hidePriceless || item.offers.any { it.price != null }
            val availabilityMatch = selectedAvailability == AvailabilityStatus.entries.toSet() ||
                item.offers.any { availabilityStatus(it.availability, it.releaseDate) in selectedAvailability }

            val searchMatch = if (queryWords.isEmpty()) {
                true
            } else {
                val titleLower = item.title.lowercase(Locale.ROOT)
                queryWords.all { word -> titleLower.contains(word) }
            }

            sourceMatch && dateMatch && priceMatch && availabilityMatch && searchMatch
        }

        _state.value = NewsUiState.Content(filtered, rawFavorites)
    }
}
