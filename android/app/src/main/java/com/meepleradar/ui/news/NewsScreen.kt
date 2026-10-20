package com.meepleradar.ui.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ListingCard
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.ScreenContentInsets
import com.meepleradar.ui.components.ScreenHeader

@Composable
fun NewsScreen(
    vm: NewsViewModel,
    onOpen: (IncomingItem) -> Unit,
    imageLookup: BoardGameImageLookup,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadNews() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = ScreenContentInsets,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(title = "Novità DB", onRefresh = { vm.loadNews() })

            when (val current = state) {
                NewsUiState.Loading -> LoadingView()
                is NewsUiState.Content -> if (current.items.isEmpty()) {
                    EmptyView("Nessuna novità recente nell'ultimo aggiornamento DB")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(current.items, key = { it.key }) { item ->
                            ListingCard(
                                item = item,
                                isFavorite = false,
                                onOpen = { onOpen(item) },
                                onToggleFavorite = {},
                                onIgnore = {},
                                imageLookup = imageLookup,
                            )
                        }
                    }
                }
            }
        }
    }
}
