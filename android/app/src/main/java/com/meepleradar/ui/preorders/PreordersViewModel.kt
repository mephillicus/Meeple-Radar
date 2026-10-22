package com.meepleradar.ui.preorders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PreordersUiState {
    object Loading : PreordersUiState
    data class Content(val items: List<IncomingItem>) : PreordersUiState
}

class PreordersViewModel(
    private val repo: ListingRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<PreordersUiState>(PreordersUiState.Loading)
    val state: StateFlow<PreordersUiState> = _state.asStateFlow()

    init {
        loadPreorders()
    }

    fun loadPreorders() {
        viewModelScope.launch {
            _state.value = PreordersUiState.Loading
            val items = runCatching { repo.preorderItems(today) }.getOrDefault(emptyList())
            _state.value = PreordersUiState.Content(items)
        }
    }
}
