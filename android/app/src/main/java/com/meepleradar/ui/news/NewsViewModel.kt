package com.meepleradar.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.repo.ListingRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NewsUiState {
    object Loading : NewsUiState
    data class Content(val items: List<IncomingItem>) : NewsUiState
}

class NewsViewModel(
    private val repo: ListingRepository,
    private val today: LocalDate = LocalDate.now(),
) : ViewModel() {

    private val _state = MutableStateFlow<NewsUiState>(NewsUiState.Loading)
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    init {
        loadNews()
    }

    fun loadNews() {
        viewModelScope.launch {
            _state.value = NewsUiState.Loading
            val items = runCatching { repo.newsItems(today) }.getOrDefault(emptyList())
            _state.value = NewsUiState.Content(items)
        }
    }
}
