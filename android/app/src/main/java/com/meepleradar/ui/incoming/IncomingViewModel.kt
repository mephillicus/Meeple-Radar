package com.meepleradar.ui.incoming

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

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources.asStateFlow()

    private val _selectedSources = MutableStateFlow<Set<String>?>(null)
    val selectedSources: StateFlow<Set<String>?> = _selectedSources.asStateFlow()

    private val _hideUndated = MutableStateFlow<Boolean>(false)
    val hideUndated: StateFlow<Boolean> = _hideUndated.asStateFlow()

    private val _hidePriceless = MutableStateFlow<Boolean>(false)
    val hidePriceless: StateFlow<Boolean> = _hidePriceless.asStateFlow()

    private val _selectedAvailability = MutableStateFlow(AvailabilityStatus.entries.toSet())
    val selectedAvailability: StateFlow<Set<AvailabilityStatus>> = _selectedAvailability.asStateFlow()

    private val _lastModifiedTimestamp = MutableStateFlow<Long>(repo.lastDbModifiedTimestamp())
    val lastModifiedTimestamp: StateFlow<Long> = _lastModifiedTimestamp.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgressState>(SyncProgressState.Idle)
    val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val newlyAddedCount: Int get() = repo.newlyAddedCount()

    private var rawItems: List<IncomingItem> = emptyList()
    private var rawFavorites: Set<String> = emptySet()

    init {
        loadSources()
        loadLocal() // Load from local cache on startup without hitting Supabase network
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
                rawFavorites = repo.favorites(
                    today = today,
                    sourceIds = null,
                    includeUndated = true,
                    includePriceless = true,
                    forceRefresh = false,
                ).map { it.key }.toSet()

                _lastModifiedTimestamp.value = repo.lastDbModifiedTimestamp()
                updateFilteredState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    fun refresh() {
        _state.value = IncomingUiState.Loading
        _syncProgress.value = SyncProgressState.Progress(0, "Avvio sincronizzazione…")
        viewModelScope.launch {
            try {
                rawItems = repo.incoming(
                    today = today,
                    sourceIds = null,
                    includeUndated = true,
                    includePriceless = true,
                    forceRefresh = true, // Force refresh from Supabase via manual button
                    onProgress = { percent, message ->
                        _syncProgress.value = SyncProgressState.Progress(percent, message)
                    },
                )
                rawFavorites = repo.favorites(
                    today = today,
                    sourceIds = null,
                    includeUndated = true,
                    includePriceless = true,
                    forceRefresh = false,
                ).map { it.key }.toSet()

                _lastModifiedTimestamp.value = repo.lastDbModifiedTimestamp()
                _syncProgress.value = SyncProgressState.Finished
                updateFilteredState()
            } catch (e: CancellationException) {
                _syncProgress.value = SyncProgressState.Idle
                throw e
            } catch (e: Exception) {
                _syncProgress.value = SyncProgressState.Idle
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
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
                item.offers.any { availabilityStatus(it.availability) in selectedAvailability }

            val searchMatch = if (queryWords.isEmpty()) {
                true
            } else {
                val titleLower = item.title.lowercase(Locale.ROOT)
                queryWords.all { word -> titleLower.contains(word) }
            }

            sourceMatch && dateMatch && priceMatch && availabilityMatch && searchMatch
        }

        _state.value = IncomingUiState.Content(items = filtered, favorites = rawFavorites)
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

    fun toggleFavorite(item: IncomingItem) {
        val content = _state.value as? IncomingUiState.Content ?: return
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
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
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
                _state.value = IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
            }
        }
    }

    private companion object {
        const val DEFAULT_ERROR = "Errore di rete"
    }
}
