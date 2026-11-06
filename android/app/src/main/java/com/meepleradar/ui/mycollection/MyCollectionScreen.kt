package com.meepleradar.ui.mycollection

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.meepleradar.data.model.BggCollectionGame
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.data.remote.BggLink
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.GameCover
import com.meepleradar.ui.components.ListingCard
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.RatingBadge
import com.meepleradar.ui.components.ScreenContentInsets
import com.meepleradar.ui.components.ScreenHeader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MyCollectionScreen(
    vm: BggCollectionViewModel,
    imageLookup: BoardGameImageLookup,
    onOpen: (IncomingItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var showBaseGames by remember { mutableStateOf(true) }
    var showExpansionPills by remember { mutableStateOf(true) }

    androidx.compose.material3.Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = ScreenContentInsets,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val isContent = state is BggCollectionUiState.Content
            ScreenHeader(
                title = "Miei",
                onRefresh = { vm.refresh() },
                trailingContent = {
                    if (isContent) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SearchBox(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                            )
                            Row(
                                modifier = Modifier
                                    .height(34.dp)
                                    .background(
                                        if (!showBaseGames || !showExpansionPills) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        },
                                        RoundedCornerShape(10.dp),
                                    )
                                    .padding(horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                IconButton(
                                    onClick = { vm.refresh() },
                                    enabled = (state as? BggCollectionUiState.Content)?.refreshing != true,
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Aggiorna collezione BGG",
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { showFilters = true },
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Settings,
                                        contentDescription = "Filtri",
                                        tint = if (!showBaseGames || !showExpansionPills) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                            }
                        }
                    }
                },
            )

            when (val current = state) {
                is BggCollectionUiState.Setup -> SetupContent(
                    username = current.username,
                    apiToken = current.apiToken,
                    error = current.error,
                    onConnect = vm::connect,
                )

                is BggCollectionUiState.Loading -> LoadingView()

                is BggCollectionUiState.Content -> {
                    val normalizedQuery = searchQuery.trim().lowercase(Locale.ROOT)
                    val ownedExpansionIds = current.games
                        .filter { it.isExpansionGame() }
                        .map { it.bggId }
                        .toSet()
                    val visibleGames = current.games.filter { game ->
                        !game.isExpansionGame() &&
                            showBaseGames &&
                            (normalizedQuery.isEmpty() || game.name.lowercase(Locale.ROOT).contains(normalizedQuery))
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            current.error?.let { message ->
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = message,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(10.dp),
                                    )
                                }
                            }
                            if (visibleGames.isEmpty()) {
                                EmptyView(
                                    if (current.games.none { !it.isExpansionGame() }) "La collezione BGG non contiene giochi base posseduti"
                                    else "Nessun gioco corrisponde alla ricerca o ai filtri",
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                ) {
                                    items(visibleGames, key = { it.bggId }) { game ->
                                        BggCollectionGameGroup(
                                            game = game,
                                            listing = current.listingsByBggId[game.bggId],
                                            isFavorite = current.favoriteActionKeysByBggId[game.bggId]
                                                .orEmpty().any { it in current.favoriteKeys },
                                            imageLookup = imageLookup,
                                            onOpen = onOpen,
                                            onToggleFavorite = { vm.toggleFavorite(game.bggId) },
                                            onIgnore = {
                                                current.listingsByBggId[game.bggId]?.let { vm.ignore(it.key) }
                                            },
                                            showExpansionPills = showExpansionPills,
                                            ownedExpansionIds = ownedExpansionIds,
                                            expansionAvailabilityById = current.expansionAvailabilityById,
                                            expansionState = current.expansionsByGame[game.bggId],
                                            expandedSections = current.expandedSections[game.bggId].orEmpty(),
                                            onToggleExpansion = { ownership -> vm.toggleExpansionSection(game.bggId, ownership) },
                                            onRetryExpansions = { vm.retryExpansions(game.bggId) },
                                            onOpenExpansion = onOpen,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    CollectionFooter(
                        updatedAt = current.updatedAt,
                        refreshing = current.refreshing,
                        syncMessage = current.syncMessage,
                        onRefresh = vm::refresh,
                        onLogout = { showLogoutConfirmation = true },
                    )
                }
            }
        }
    }

    if (showFilters) {
        CollectionFilterDialog(
            showBaseGames = showBaseGames,
            showExpansionPills = showExpansionPills,
            onShowBaseGamesChange = { showBaseGames = it },
            onShowExpansionPillsChange = { showExpansionPills = it },
            onDismiss = { showFilters = false },
        )
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text("Disconnettere BGG?") },
            text = { Text("Il nome utente e la copia locale della collezione verranno rimossi.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmation = false
                        searchQuery = ""
                        vm.logout()
                    },
                ) { Text("Esci") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun SetupContent(
    username: String,
    apiToken: String,
    error: String?,
    onConnect: (String, String) -> Unit,
) {
    var input by remember(username) { mutableStateOf(username) }
    var tokenInput by remember(apiToken) { mutableStateOf(apiToken) }
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Collega la tua collezione BGG", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "BGG richiede un token applicazione per accedere all'API. Non inserire la password del tuo account. " +
                "La collezione deve essere pubblica.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Nome utente BGG") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Token applicazione BGG") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { uriHandler.openUri("https://boardgamegeek.com/applications") }) {
            Text("Ottieni un token su BoardGameGeek")
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onConnect(input, tokenInput) }, enabled = input.isNotBlank() && tokenInput.isNotBlank()) {
            Text("Collega collezione")
        }
    }
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.width(115.dp).height(34.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
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
                modifier = Modifier.size(20.dp).clickable { onValueChange(if (value.isEmpty()) value else "") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (value.isEmpty()) Icons.Filled.Search else Icons.Filled.Close,
                    contentDescription = if (value.isEmpty()) "Cerca" else "Cancella ricerca",
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BggCollectionGameGroup(
    game: BggCollectionGame,
    listing: IncomingItem?,
    isFavorite: Boolean,
    imageLookup: BoardGameImageLookup,
    onOpen: (IncomingItem) -> Unit,
    onToggleFavorite: () -> Unit,
    onIgnore: () -> Unit,
    showExpansionPills: Boolean,
    ownedExpansionIds: Set<Int>,
    expansionAvailabilityById: Map<Int, ExpansionAvailability>,
    expansionState: ExpansionLoadState?,
    expandedSections: Set<ExpansionOwnership>,
    onToggleExpansion: (ExpansionOwnership) -> Unit,
    onRetryExpansions: () -> Unit,
    onOpenExpansion: (IncomingItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (listing != null) {
            ListingCard(
                item = listing,
                isFavorite = isFavorite,
                onOpen = { onOpen(listing) },
                onToggleFavorite = onToggleFavorite,
                onIgnore = onIgnore,
                imageLookup = imageLookup,
                showIgnoreAction = false,
            )
        } else {
            BggCollectionCard(game = game, imageLookup = imageLookup)
        }

        if (showExpansionPills) {
            val expansions = (expansionState as? ExpansionLoadState.Loaded)?.items.orEmpty()
            val ownedCount = expansions.count { it.bggId in ownedExpansionIds }
            val notOwnedCount = expansions.size - ownedCount
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ExpansionPill(
                    label = "Possedute",
                    count = ownedCount.takeIf { expansionState is ExpansionLoadState.Loaded },
                    indicators = expansions.filter { it.bggId in ownedExpansionIds }
                        .mapNotNull { expansionAvailabilityById[it.bggId] },
                    selected = ExpansionOwnership.OWNED in expandedSections,
                    modifier = Modifier.weight(1f),
                    onClick = { onToggleExpansion(ExpansionOwnership.OWNED) },
                    onOpenExpansion = onOpenExpansion,
                )
                ExpansionPill(
                    label = "Non possedute",
                    count = notOwnedCount.takeIf { expansionState is ExpansionLoadState.Loaded },
                    indicators = expansions.filterNot { it.bggId in ownedExpansionIds }
                        .mapNotNull { expansionAvailabilityById[it.bggId] },
                    selected = ExpansionOwnership.NOT_OWNED in expandedSections,
                    modifier = Modifier.weight(1f),
                    onClick = { onToggleExpansion(ExpansionOwnership.NOT_OWNED) },
                    onOpenExpansion = onOpenExpansion,
                )
            }

            if (expandedSections.isNotEmpty()) {
                when (val state = expansionState) {
                    null, ExpansionLoadState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }

                    is ExpansionLoadState.Error -> Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.clickable(onClick = onRetryExpansions).padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            Text("Riprova", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    is ExpansionLoadState.Loaded -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExpansionListColumn(
                            title = "Possedute",
                            expansions = state.items.filter { it.bggId in ownedExpansionIds },
                            expansionAvailabilityById = expansionAvailabilityById,
                            modifier = Modifier.weight(1f),
                            onOpenExpansion = onOpenExpansion,
                        )
                        ExpansionListColumn(
                            title = "Non possedute",
                            expansions = state.items.filterNot { it.bggId in ownedExpansionIds },
                            expansionAvailabilityById = expansionAvailabilityById,
                            modifier = Modifier.weight(1f),
                            onOpenExpansion = onOpenExpansion,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpansionPill(
    label: String,
    count: Int?,
    indicators: List<ExpansionAvailability>,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onOpenExpansion: (IncomingItem) -> Unit,
) {
    val displayIndicators = indicators.distinctBy { it.type }
    Surface(
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (count == null) label else "$label ($count)",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (displayIndicators.isNotEmpty()) {
                Spacer(Modifier.width(2.dp))
                displayIndicators.forEach { indicator ->
                    ExpansionAvailabilityDot(indicator, onClick = { onOpenExpansion(indicator.item) })
                }
            }
        }
    }
}

@Composable
private fun ExpansionListColumn(
    title: String,
    expansions: List<BggLink>,
    expansionAvailabilityById: Map<Int, ExpansionAvailability>,
    modifier: Modifier = Modifier,
    onOpenExpansion: (IncomingItem) -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        if (expansions.isEmpty()) {
            Text("Nessuna", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val uriHandler = LocalUriHandler.current
            expansions.forEach { expansion ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    expansionAvailabilityById[expansion.bggId]?.let { availability ->
                        ExpansionAvailabilityDot(
                            availability = availability,
                            onClick = { onOpenExpansion(availability.item) },
                        )
                    }
                    Text(
                        text = expansion.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                uriHandler.openUri(
                                    expansion.url ?: "https://boardgamegeek.com/boardgameexpansion/${expansion.bggId}",
                                )
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpansionAvailabilityDot(
    availability: ExpansionAvailability,
    onClick: () -> Unit,
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val color = when (availability.type) {
        ExpansionAvailabilityType.PREORDER -> if (isDark) Color(0xFF90CAF9) else Color(0xFF1976D2)
        ExpansionAvailabilityType.NEWS -> if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
    }
    Box(
        modifier = Modifier.size(22.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
    }
}

@Composable
private fun BggCollectionCard(game: BggCollectionGame, imageLookup: BoardGameImageLookup) {
    val uriHandler = LocalUriHandler.current
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable {
            uriHandler.openUri("https://boardgamegeek.com/boardgame/${game.bggId}")
        },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(16.dp)),
            ) {
                GameCover(
                    imageUrl = game.imageUrl,
                    title = game.name,
                    bggId = game.bggId,
                    imageLookup = imageLookup,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    game.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        if (game.isExpansionGame()) "Espansione" else "Gioco base",
                        game.yearPublished?.toString(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            game.rating?.takeIf { it > 0.0 }?.let { RatingBadge(it) }
        }
    }
}

@Composable
private fun CollectionFooter(
    updatedAt: Long,
    refreshing: Boolean,
    syncMessage: String?,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (refreshing || syncMessage != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = syncMessage ?: "Aggiornamento in corso...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "BGG: ${formatTimestamp(updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna collezione BGG")
            }
            IconButton(onClick = onLogout, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Disconnetti BGG")
            }
        }
    }
}

@Composable
private fun CollectionFilterDialog(
    showBaseGames: Boolean,
    showExpansionPills: Boolean,
    onShowBaseGamesChange: (Boolean) -> Unit,
    onShowExpansionPillsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtri") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = showBaseGames,
                    onClick = { onShowBaseGamesChange(!showBaseGames) },
                    label = { Text("Giochi base posseduti") },
                )
                FilterChip(
                    selected = showExpansionPills,
                    onClick = { onShowExpansionPillsChange(!showExpansionPills) },
                    label = { Text("Sotto-pillole espansioni") },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Applica") } },
        dismissButton = {
            TextButton(
                onClick = {
                    onShowBaseGamesChange(true)
                    onShowExpansionPillsChange(true)
                    onDismiss()
                },
            ) { Text("Azzera") }
        },
    )
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "mai"
    val dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
    return dateTime.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ITALIAN))
}

private const val EXPANSION_KIND = "boardgameexpansion"

private fun BggCollectionGame.isExpansionGame(): Boolean =
    expandsBggId != null || kind.equals("expansion", ignoreCase = true) ||
        kind.equals(EXPANSION_KIND, ignoreCase = true)
