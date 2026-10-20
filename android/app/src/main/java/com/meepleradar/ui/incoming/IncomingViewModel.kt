package com.meepleradar.ui.incoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.Action
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
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

    private val _lastModifiedTimestamp = MutableStateFlow<Long>(repo.lastDbModifiedTimestamp())
    val lastModifiedTimestamp: StateFlow<Long> = _lastModifiedTimestamp.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgressState>(SyncProgressState.Idle)
    val syncProgress: StateFlow<SyncProgressState> = _syncProgress.asStateFlow()

    init {
        loadSources()
    }

    fun applyFilters(sourceIds: Set<String>?, hideUndated: Boolean, hidePriceless: Boolean = false) {
        _selectedSources.value = sourceIds
        _hideUndated.value = hideUndated
        _hidePriceless.value = hidePriceless
        refresh()
    }

    fun resetFilters() {
        _selectedSources.value = null
        _hideUndated.value = false
        _hidePriceless.value = false
        refresh()
    }

    fun refresh() {
        _state.value = IncomingUiState.Loading
        _syncProgress.value = SyncProgressState.Progress(0, "Avvio sincronizzazione…")
        viewModelScope.launch {
            _state.value = try {
                val items = repo.incoming(
                    today = today,
                    sourceIds = _selectedSources.value,
                    includeUndated = !_hideUndated.value,
                    includePriceless = !_hidePriceless.value,
                    onProgress = { percent, message ->
                        _syncProgress.value = SyncProgressState.Progress(percent, message)
                    },
                )
                val favorites = repo.favorites(
                    today = today,
                    sourceIds = _selectedSources.value,
                    includeUndated = !_hideUndated.value,
                    includePriceless = !_hidePriceless.value,
                ).map { it.key }.toSet()

                _lastModifiedTimestamp.value = repo.lastDbModifiedTimestamp()
                _syncProgress.value = SyncProgressState.Finished
                IncomingUiState.Content(items = items, favorites = favorites)
            } catch (e: CancellationException) {
                _syncProgress.value = SyncProgressState.Idle
                throw e
            } catch (e: Exception) {
                _syncProgress.value = SyncProgressState.Idle
                IncomingUiState.Error(e.message ?: DEFAULT_ERROR)
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

    fun toggleFavorite(item: IncomingItem) {
        val content = _state.value as? IncomingUiState.Content ?: return
        val wasFavorite = item.key in content.favorites
        val action = if (wasFavorite) Action.UNFAVORITED else Action.FAVORITE

        viewModelScope.launch {
            try {
                repo.setAction(item.key, action)
                val current = _state.value as? IncomingUiState.Content ?: return@launch
                val favorites = if (wasFavorite) {
                    current.favorites - item.key
                } else {
                    current.favorites + item.key
                }
                _state.value = current.copy(favorites = favorites)
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
                val content = _state.value as? IncomingUiState.Content ?: return@launch
                _state.value = content.copy(items = content.items.filterNot { it.key == item.key })
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
