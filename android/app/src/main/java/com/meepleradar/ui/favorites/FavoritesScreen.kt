package com.meepleradar.ui.favorites

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.components.BlockVector
import com.meepleradar.ui.components.CompactSearchBar
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.IgnoredGamesSheet
import com.meepleradar.ui.components.ListingCard
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.ScreenContentInsets
import com.meepleradar.ui.components.ScreenHeader
import com.meepleradar.ui.incoming.IncomingUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    vm: FavoritesViewModel,
    onOpen: (IncomingItem) -> Unit,
    imageLookup: BoardGameImageLookup,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val selectedFilter by vm.selectedAvailabilityFilter.collectAsStateWithLifecycle()
    val ignoredItems by vm.ignoredItems.collectAsStateWithLifecycle()

    var showIgnoredSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = ScreenContentInsets,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ScreenHeader(
                title = "Preferiti",
                onRefresh = { vm.refresh(showLoadingState = false, forceRefresh = true) },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IgnoredCountPill(
                            count = ignoredItems.size,
                            onClick = { showIgnoredSheet = true },
                        )

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(34.dp),
                        ) {
                            IconButton(
                                onClick = { vm.refresh(showLoadingState = false, forceRefresh = true) },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Aggiorna preferiti",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                },
            )

            CompactSearchBar(
                query = searchQuery,
                placeholder = "Cerca nei preferiti...",
                onQueryChange = vm::setSearchQuery,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FavoriteAvailabilityFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { vm.setAvailabilityFilter(filter) },
                        label = {
                            Text(
                                text = filter.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }
            }

            when (val current = state) {
                IncomingUiState.Loading -> LoadingView()

                is IncomingUiState.Error -> ErrorView(
                    message = current.message,
                    onRetry = { vm.refresh() },
                )

                is IncomingUiState.Content -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.refresh(showLoadingState = false, forceRefresh = true) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (current.items.isEmpty()) {
                        val emptyMessage = if (searchQuery.isNotBlank() || selectedFilter != FavoriteAvailabilityFilter.ALL) {
                            "Nessun preferito corrisponde ai filtri"
                        } else {
                            "Nessun preferito"
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            EmptyView(emptyMessage)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(current.items, key = { it.key }) { item ->
                                ListingCard(
                                    item = item,
                                    isFavorite = true,
                                    onOpen = { onOpen(item) },
                                    onToggleFavorite = { vm.toggleFavorite(item) },
                                    onIgnore = { vm.ignore(item) },
                                    imageLookup = imageLookup,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showIgnoredSheet) {
        IgnoredGamesSheet(
            ignoredItems = ignoredItems,
            onRestore = vm::restoreIgnored,
            onDismiss = { showIgnoredSheet = false },
        )
    }
}

@Composable
private fun IgnoredCountPill(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = BlockVector,
                contentDescription = "Giochi ignorati",
                tint = if (count > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                color = if (count > 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
