package com.meepleradar.ui.favorites

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
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.ListingCard
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.ScreenHeader
import com.meepleradar.ui.incoming.IncomingUiState

@Composable
fun FavoritesScreen(
    vm: FavoritesViewModel,
    onOpen: (IncomingItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(title = "Preferiti", onRefresh = { vm.refresh() })
            when (val current = state) {
                IncomingUiState.Loading -> LoadingView()

                is IncomingUiState.Error -> ErrorView(
                    message = current.message,
                    onRetry = { vm.refresh() },
                )

                is IncomingUiState.Content -> if (current.items.isEmpty()) {
                    EmptyView("Nessun preferito")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(current.items, key = { it.key }) { item ->
                            ListingCard(
                                item = item,
                                isFavorite = true,
                                onOpen = { onOpen(item) },
                                onToggleFavorite = { vm.toggleFavorite(item) },
                                onIgnore = { vm.ignore(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}
