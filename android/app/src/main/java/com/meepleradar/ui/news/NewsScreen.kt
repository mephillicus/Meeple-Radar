package com.meepleradar.ui.news

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.components.AlphabetFastScroller
import com.meepleradar.ui.components.CompactSearchBar
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.ListingCard
import com.meepleradar.ui.components.ListingFilterDrawerOverlay
import com.meepleradar.ui.components.ListingSortOption
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.MarkAllSeenButton
import com.meepleradar.ui.components.MarkAllSeenConfirmDialog
import com.meepleradar.ui.components.ScreenContentInsets
import com.meepleradar.ui.components.ScreenHeader
import com.meepleradar.ui.components.UnseenFilterPill
import com.meepleradar.ui.components.isAlphabetical

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(
    vm: NewsViewModel,
    onOpen: (IncomingItem) -> Unit,
    imageLookup: BoardGameImageLookup,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedSources by vm.selectedSources.collectAsStateWithLifecycle()
    val hideUndated by vm.hideUndated.collectAsStateWithLifecycle()
    val hidePriceless by vm.hidePriceless.collectAsStateWithLifecycle()
    val hideSeen by vm.hideSeen.collectAsStateWithLifecycle()
    val selectedAvailability by vm.selectedAvailability.collectAsStateWithLifecycle()
    val sortOption by vm.sortOption.collectAsStateWithLifecycle()
    val searchQueryState by vm.searchQuery.collectAsStateWithLifecycle()
    val showOnlyNewlyAdded by vm.showOnlyNewlyAdded.collectAsStateWithLifecycle()
    val newlyAddedCount by vm.newlyAddedCount.collectAsStateWithLifecycle()
    val unseenCount by vm.unseenCount.collectAsStateWithLifecycle()

    var isFilterDrawerOpen by remember { mutableStateOf(false) }
    var showMarkAllSeenDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshSeenStatus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = ScreenContentInsets,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val selSources = selectedSources
            val hasActiveFilters = hideUndated || hidePriceless || hideSeen ||
                sortOption != ListingSortOption.DEFAULT ||
                (selSources != null && selSources.size < sources.size) ||
                selectedAvailability.size < AvailabilityStatus.entries.size

            ScreenHeader(
                title = "Novità",
                onRefresh = { vm.refresh() },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (showOnlyNewlyAdded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                1.dp,
                                if (showOnlyNewlyAdded) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                            ),
                            modifier = Modifier
                                .height(34.dp)
                                .clickable { vm.toggleNewlyAddedFilter() },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = if (newlyAddedCount > 0) "✨ +$newlyAddedCount" else "✨ 0",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (showOnlyNewlyAdded) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }

                        UnseenFilterPill(
                            unseenCount = unseenCount,
                            hideSeen = hideSeen,
                            onToggle = { vm.toggleHideSeen() },
                        )

                        MarkAllSeenButton(
                            unseenCount = unseenCount,
                            onClick = { showMarkAllSeenDialog = true },
                        )

                        Row(
                            modifier = Modifier
                                .height(34.dp)
                                .background(
                                    if (hasActiveFilters) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            IconButton(
                                onClick = { vm.refresh() },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Aggiorna",
                                    tint = if (hasActiveFilters) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp),
                                )
                            }

                            IconButton(
                                onClick = { isFilterDrawerOpen = !isFilterDrawerOpen },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Settings,
                                    contentDescription = "Filtri",
                                    tint = if (hasActiveFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }
                },
            )

            CompactSearchBar(
                query = searchQueryState,
                placeholder = "Cerca nelle novità...",
                onQueryChange = vm::setSearchQuery,
            )

            when (val current = state) {
                NewsUiState.Loading -> LoadingView()
                is NewsUiState.Error -> ErrorView(message = current.message, onRetry = { vm.refresh() })
                is NewsUiState.Content -> PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { vm.refresh(showLoadingState = false) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    if (current.items.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            EmptyView("Nessuna novità recente con i filtri selezionati")
                        }
                    } else {
                        val lazyListState = rememberLazyListState()
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = lazyListState,
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(current.items.size, key = { current.items[it].key }) { index ->
                                    val item = current.items[index]
                                    ListingCard(
                                        item = item,
                                        isFavorite = item.key in current.favorites,
                                        onOpen = { onOpen(item) },
                                        onToggleFavorite = { vm.toggleFavorite(item) },
                                        onIgnore = { vm.ignore(item) },
                                        imageLookup = imageLookup,
                                    )
                                }
                            }

                            if (sortOption.isAlphabetical) {
                                AlphabetFastScroller(
                                    items = current.items,
                                    lazyListState = lazyListState,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (isFilterDrawerOpen) {
        ListingFilterDrawerOverlay(
            sources = sources,
            selectedSources = selectedSources,
            hideUndated = hideUndated,
            hidePriceless = hidePriceless,
            hideSeen = hideSeen,
            selectedAvailability = selectedAvailability,
            sortOption = sortOption,
            onSortOptionChange = vm::setSortOption,
            onApply = { newSources, newHideUndated, newHidePriceless, newHideSeen, newAvail, newSort ->
                vm.applyFilters(newSources, newHideUndated, newHidePriceless, newHideSeen, newAvail, newSort)
                isFilterDrawerOpen = false
            },
            onReset = {
                vm.resetFilters()
                isFilterDrawerOpen = false
            },
            onClose = { isFilterDrawerOpen = false },
        )
    }

    if (showMarkAllSeenDialog) {
        MarkAllSeenConfirmDialog(
            unseenCount = unseenCount,
            onConfirm = {
                showMarkAllSeenDialog = false
                vm.markAllAsSeen()
            },
            onDismiss = { showMarkAllSeenDialog = false },
        )
    }
}
