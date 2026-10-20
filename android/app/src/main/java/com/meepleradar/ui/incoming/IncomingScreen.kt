package com.meepleradar.ui.incoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.ListingCard
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.ScreenContentInsets
import kotlinx.coroutines.launch

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

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(320.dp),
            ) {
                FilterDrawerContent(
                    sources = sources,
                    selectedSources = selectedSources,
                    hideUndated = hideUndated,
                    hidePriceless = hidePriceless,
                    onApply = { newSelectedSources, newHideUndated, newHidePriceless ->
                        vm.applyFilters(newSelectedSources, newHideUndated, newHidePriceless)
                        scope.launch { drawerState.close() }
                    },
                    onReset = {
                        vm.resetFilters()
                        scope.launch { drawerState.close() }
                    },
                    onClose = {
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = ScreenContentInsets,
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                val selSources = selectedSources
                IncomingTopBar(
                    lastModifiedTimestamp = lastModifiedTimestamp,
                    hasActiveFilters = hideUndated || hidePriceless || (selSources != null && selSources.size < sources.size),
                    onOpenFilters = { scope.launch { drawerState.open() } },
                    onRefresh = { vm.refresh() },
                )

                when (val current = state) {
                    IncomingUiState.Loading -> LoadingView()

                    is IncomingUiState.Error -> ErrorView(
                        message = current.message,
                        onRetry = { vm.refresh() },
                    )

                    is IncomingUiState.Content -> if (current.items.isEmpty()) {
                        EmptyView("Nessuna uscita in arrivo con i filtri selezionati")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    }
                }
            }
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
private fun IncomingTopBar(
    lastModifiedTimestamp: Long,
    hasActiveFilters: Boolean,
    onOpenFilters: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "MEEPLE RADAR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("In arrivo", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Aggiornato: ${formatTimestamp(lastModifiedTimestamp)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Aggiorna",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onOpenFilters,
                colors = if (hasActiveFilters) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(if (hasActiveFilters) "Filtri attivi" else "Filtri")
            }
        }
    }
}

@Composable
private fun FilterDrawerContent(
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
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Filtri e Opzioni", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Chiudi")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = "Opzioni di visualizzazione",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Nascondi senza data",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Solo giochi con data definita",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = localHideUndated,
                        onCheckedChange = { localHideUndated = it },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Nascondi senza prezzo",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Solo giochi con prezzo disponibile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = localHidePriceless,
                        onCheckedChange = { localHidePriceless = it },
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Negozi (${localSelectedSources.size}/${sources.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            onClick = {
                                localSelectedSources = sources.map { it.id }.toMutableSet()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("Tutti", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                localSelectedSources = mutableSetOf()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("Nessuno", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            items(sources.chunked(2), key = { row -> row.first().id }) { rowSources ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                                        modifier = Modifier.size(14.dp),
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f),
            ) {
                Text("Ripristina")
            }
            Button(
                onClick = { onApply(localSelectedSources.toSet(), localHideUndated, localHidePriceless) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Applica")
            }
        }
    }
}
