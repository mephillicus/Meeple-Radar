package com.meepleradar.ui.incoming

import androidx.compose.foundation.background
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.alerts.AlertsViewModel
import com.meepleradar.ui.components.AlphabetFastScroller
import com.meepleradar.ui.components.CompactSearchBar
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.IgnoredGamesSheet
import com.meepleradar.ui.components.ListingCard
import com.meepleradar.ui.components.ListingFilterDrawerOverlay
import com.meepleradar.ui.components.ListingSortOption
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.MarkAllSeenButton
import com.meepleradar.ui.components.MarkAllSeenConfirmDialog
import com.meepleradar.ui.components.MeepleVector
import com.meepleradar.ui.components.ScreenContentInsets
import com.meepleradar.ui.components.ScreenHeader
import com.meepleradar.ui.components.UnseenFilterPill
import com.meepleradar.ui.components.isAlphabetical

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingScreen(
    vm: IncomingViewModel,
    onOpen: (IncomingItem) -> Unit,
    imageLookup: BoardGameImageLookup,
    modifier: Modifier = Modifier,
    unreadAlertCount: Int = 0,
    onOpenAlerts: (() -> Unit)? = null,
    alertsVm: AlertsViewModel? = null,
    onOpenAlertItem: ((String) -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val isRefreshing by vm.isRefreshing.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedSources by vm.selectedSources.collectAsStateWithLifecycle()
    val hideUndated by vm.hideUndated.collectAsStateWithLifecycle()
    val hidePriceless by vm.hidePriceless.collectAsStateWithLifecycle()
    val hideSeen by vm.hideSeen.collectAsStateWithLifecycle()
    val onlyDiscounted by vm.onlyDiscounted.collectAsStateWithLifecycle()
    val selectedAvailability by vm.selectedAvailability.collectAsStateWithLifecycle()
    val sortOption by vm.sortOption.collectAsStateWithLifecycle()
    val syncProgress by vm.syncProgress.collectAsStateWithLifecycle()
    val searchQueryState by vm.searchQuery.collectAsStateWithLifecycle()
    val ignoredItems by vm.ignoredItems.collectAsStateWithLifecycle()
    val newlyAddedCount by vm.newlyAddedCountFlow.collectAsStateWithLifecycle()
    val unseenCount by vm.unseenCount.collectAsStateWithLifecycle()

    var isFilterDrawerOpen by remember { mutableStateOf(false) }
    var showMarkAllSeenDialog by remember { mutableStateOf(false) }
    var showIgnoredSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshSeenStatus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = ScreenContentInsets,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val selSources = selectedSources
            val hasActiveFilters = hideUndated || hidePriceless || hideSeen || onlyDiscounted ||
                sortOption != ListingSortOption.DEFAULT ||
                (selSources != null && selSources.size < sources.size) ||
                selectedAvailability.size < AvailabilityStatus.entries.size

            ScreenHeader(
                title = "Lista",
                onRefresh = { vm.refresh() },
                titleContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Lista",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                        )
                        if (newlyAddedCount > 0) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        imageVector = MeepleVector,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        text = "+$newlyAddedCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                            }
                        }
                    }
                },
                trailingContent = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                                onClick = { isFilterDrawerOpen = true },
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
                placeholder = "Cerca nella lista...",
                onQueryChange = vm::setSearchQuery,
            )

            if (syncProgress is SyncProgressState.Progress) {
                SyncProgressCard(progress = syncProgress as SyncProgressState.Progress)
            }

            when (val current = state) {
                IncomingUiState.Loading -> LoadingView()

                is IncomingUiState.Error -> ErrorView(
                    message = current.message,
                    onRetry = { vm.refresh() },
                )

                is IncomingUiState.Content -> PullToRefreshBox(
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
                            EmptyView("Nessuna uscita in arrivo con i filtri selezionati")
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
            onlyDiscounted = onlyDiscounted,
            selectedAvailability = selectedAvailability,
            sortOption = sortOption,
            onSortOptionChange = vm::setSortOption,
            ignoredCount = ignoredItems.size,
            onManageIgnored = {
                isFilterDrawerOpen = false
                vm.loadIgnoredItems()
                showIgnoredSheet = true
            },
            unreadAlertCount = unreadAlertCount,
            onOpenAlerts = onOpenAlerts?.let { open ->
                {
                    isFilterDrawerOpen = false
                    open()
                }
            },
            alertsVm = alertsVm,
            onOpenAlertItem = onOpenAlertItem,
            onApply = { newSelectedSources, newHideUndated, newHidePriceless, newHideSeen, newOnlyDiscounted, newAvailability, newSort ->
                vm.applyFilters(
                    sourceIds = newSelectedSources,
                    hideUndated = newHideUndated,
                    hidePriceless = newHidePriceless,
                    hideSeen = newHideSeen,
                    availability = newAvailability,
                    sortOption = newSort,
                    onlyDiscounted = newOnlyDiscounted,
                )
                isFilterDrawerOpen = false
            },
            onReset = {
                vm.resetFilters()
                isFilterDrawerOpen = false
            },
            onClose = { isFilterDrawerOpen = false },
        )
    }

    if (showIgnoredSheet) {
        IgnoredGamesSheet(
            ignoredItems = ignoredItems,
            onRestore = vm::restoreIgnored,
            onDismiss = { showIgnoredSheet = false },
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

@Composable
fun SyncProgressCard(progress: SyncProgressState.Progress, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = "${progress.percent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )
        }
    }
}
