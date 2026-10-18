package com.meepleradar.ui.incoming

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.Source
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
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val selectedSources by vm.selectedSources.collectAsStateWithLifecycle()
    val hideUndated by vm.hideUndated.collectAsStateWithLifecycle()

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
                    onApply = { newSelectedSources, newHideUndated ->
                        vm.applyFilters(newSelectedSources, newHideUndated)
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
                    hasActiveFilters = hideUndated || (selSources != null && selSources.size < sources.size),
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
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IncomingTopBar(
    hasActiveFilters: Boolean,
    onOpenFilters: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("In arrivo", style = MaterialTheme.typography.headlineSmall)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                Text(if (hasActiveFilters) "Filtri (Attivi)" else "Filtri")
            }

            TextButton(onClick = onRefresh) {
                Text("Aggiorna")
            }
        }
    }
}

@Composable
private fun FilterDrawerContent(
    sources: List<Source>,
    selectedSources: Set<String>?,
    hideUndated: Boolean,
    onApply: (Set<String>?, Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var localHideUndated by remember(hideUndated) { mutableStateOf(hideUndated) }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Opzioni di Visualizzazione",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Nascondi senza data",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "Mostra solo i giochi con data di uscita definita",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = localHideUndated,
                        onCheckedChange = { localHideUndated = it },
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Sorgenti (${localSelectedSources.size}/${sources.size})",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            localSelectedSources = sources.map { it.id }.toMutableSet()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Tutti", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            localSelectedSources = mutableSetOf()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Nessuno", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            items(sources, key = { it.id }) { source ->
                val isChecked = localSelectedSources.contains(source.id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = localSelectedSources.toMutableSet()
                            if (isChecked) next.remove(source.id) else next.add(source.id)
                            localSelectedSources = next
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = isChecked,
                        onCheckedChange = { checked ->
                            val next = localSelectedSources.toMutableSet()
                            if (checked) next.add(source.id) else next.remove(source.id)
                            localSelectedSources = next
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(source.name, style = MaterialTheme.typography.bodyMedium)
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
                onClick = { onApply(localSelectedSources.toSet(), localHideUndated) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Applica")
            }
        }
    }
}
