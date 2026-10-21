package com.meepleradar.ui.incoming

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.components.AlphabetFastScroller
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.ListingCard
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.MeepleVector
import com.meepleradar.ui.components.ScreenContentInsets
import com.meepleradar.ui.components.ScreenHeader

@Composable
fun IncomingScreen(
    vm: IncomingViewModel,
    onOpen: (IncomingItem) -> Unit,
    imageLookup: BoardGameImageLookup,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedSources by vm.selectedSources.collectAsStateWithLifecycle()
    val hideUndated by vm.hideUndated.collectAsStateWithLifecycle()
    val hidePriceless by vm.hidePriceless.collectAsStateWithLifecycle()
    val lastModifiedTimestamp by vm.lastModifiedTimestamp.collectAsStateWithLifecycle()
    val syncProgress by vm.syncProgress.collectAsStateWithLifecycle()
    val searchQueryState by vm.searchQuery.collectAsStateWithLifecycle()

    var isFilterDrawerOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = ScreenContentInsets,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val selSources = selectedSources
            val hasActiveFilters = hideUndated || hidePriceless || (selSources != null && selSources.size < sources.size)

            ScreenHeader(
                title = "In arrivo",
                onRefresh = { vm.refresh() },
                timestampText = "Aggiornato: ${formatTimestamp(lastModifiedTimestamp)}",
                titleContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "In arrivo",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        if (vm.newlyAddedCount > 0) {
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
                                        text = "+${vm.newlyAddedCount}",
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
                        // Compact Search Box
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .width(115.dp)
                                .height(34.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                BasicTextField(
                                    value = searchQueryState,
                                    onValueChange = { vm.setSearchQuery(it) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { innerTextField ->
                                        if (searchQueryState.isEmpty()) {
                                            Text(
                                                "Cerca...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            )
                                        }
                                        innerTextField()
                                    },
                                )

                                Box(
                                    modifier = Modifier.size(20.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Crossfade(
                                        targetState = searchQueryState.isNotEmpty(),
                                        animationSpec = tween(durationMillis = 180),
                                        label = "searchAction",
                                    ) { showClear ->
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .then(
                                                    if (showClear) {
                                                        Modifier.clickable(
                                                            onClickLabel = "Cancella",
                                                            role = Role.Button,
                                                        ) { vm.setSearchQuery("") }
                                                    } else {
                                                        Modifier
                                                    },
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (showClear) {
                                                Icon(
                                                    Icons.Filled.Close,
                                                    contentDescription = "Cancella",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Filled.Search,
                                                    contentDescription = "Cerca",
                                                    modifier = Modifier.size(13.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Compact Grouped Refresh + Filter Buttons (No text "Filtri")
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

            AnimatedVisibility(
                visible = isFilterDrawerOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilterInlineContent(
                        sources = sources,
                        selectedSources = selectedSources,
                        hideUndated = hideUndated,
                        hidePriceless = hidePriceless,
                        onApply = { newSelectedSources, newHideUndated, newHidePriceless ->
                            vm.applyFilters(newSelectedSources, newHideUndated, newHidePriceless)
                            isFilterDrawerOpen = false
                        },
                        onReset = {
                            vm.resetFilters()
                            isFilterDrawerOpen = false
                        },
                        onClose = { isFilterDrawerOpen = false },
                    )
                }
            }

            if (syncProgress is SyncProgressState.Progress) {
                SyncProgressCard(progress = syncProgress as SyncProgressState.Progress)
            }

            when (val current = state) {
                IncomingUiState.Loading -> LoadingView()

                is IncomingUiState.Error -> ErrorView(
                    message = current.message,
                    onRetry = { vm.refresh() },
                )

                is IncomingUiState.Content -> if (current.items.isEmpty()) {
                    EmptyView("Nessuna uscita in arrivo con i filtri selezionati")
                } else {
                    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
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

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "in attesa..."
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val localDateTime = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
    val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm", java.util.Locale.ITALIAN)
    return localDateTime.format(formatter)
}

@Composable
private fun FilterInlineContent(
    sources: List<Source>,
    selectedSources: Set<String>?,
    hideUndated: Boolean,
    hidePriceless: Boolean,
    onApply: (Set<String>?, Boolean, Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var localHideUndated by remember(hideUndated) { mutableStateOf(hideUndated) }
    var localHidePriceless by remember(hidePriceless) { mutableStateOf(hidePriceless) }
    var localSelectedSources by remember(selectedSources, sources) {
        mutableStateOf(
            selectedSources?.toMutableSet() ?: sources.map { it.id }.toMutableSet(),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filtri e Opzioni", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Chiudi", modifier = Modifier.size(18.dp))
            }
        }

        HorizontalDivider()

        Text(
            text = "Nascondi",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "Senza data",
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = localHideUndated,
                    onCheckedChange = { localHideUndated = it },
                    modifier = Modifier.scale(0.7f),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "Senza prezzo",
                    style = MaterialTheme.typography.bodySmall,
                )
                Switch(
                    checked = localHidePriceless,
                    onCheckedChange = { localHidePriceless = it },
                    modifier = Modifier.scale(0.7f),
                )
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Negozi (${localSelectedSources.size}/${sources.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        localSelectedSources = sources.map { it.id }.toMutableSet()
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("Tutti", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        localSelectedSources = mutableSetOf()
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("Nessuno", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.height(140.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(sources.chunked(2), key = { row -> row.first().id }) { rowSources ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowSources.forEach { source ->
                        val isChecked = localSelectedSources.contains(source.id)
                        FilterChip(
                            selected = isChecked,
                            onClick = {
                                val next = localSelectedSources.toMutableSet()
                                if (isChecked) next.remove(source.id) else next.add(source.id)
                                localSelectedSources = next
                            },
                            label = {
                                Text(
                                    text = source.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                            leadingIcon = if (isChecked) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowSources.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Text("Ripristina", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { onApply(localSelectedSources.toSet(), localHideUndated, localHidePriceless) },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Text("Applica", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
