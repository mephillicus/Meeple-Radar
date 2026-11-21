package com.meepleradar.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meepleradar.data.model.Game
import com.meepleradar.data.remote.BggLink
import com.meepleradar.data.remote.BoardGameDetails
import java.util.Locale

@Composable
internal fun BoardGameSpecs(
    content: DetailUiState.Content,
    onSelectBggCandidate: (BoardGameDetails) -> Unit,
    onRemoveManualBggSelection: () -> Unit,
    onSearchBggCandidates: (String) -> Unit,
    onAddToDatabase: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val details = content.boardGameDetails
    val game = content.item.game
    var showFullDescription by remember { mutableStateOf(false) }
    var isEditingBgg by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Scheda BoardGameGeek", style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { isEditingBgg = !isEditingBgg },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = if (isEditingBgg) "Chiudi modifica BGG" else "Modifica associazione BGG",
                            tint = if (isEditingBgg) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    (details?.bggId ?: game?.bggId)?.let { id ->
                        TextButton(
                            onClick = { uriHandler.openUri("https://boardgamegeek.com/boardgame/$id") },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text("Apri ↗", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (content.isLoadingDetails) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Caricamento dettagli BGG…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (details == null) {
                if (!content.isFromDatabase) {
                    NotInDatabaseCard(
                        item = content.item,
                        onAddToDatabase = onAddToDatabase,
                    )
                } else {
                    Text(
                        "Specifiche BGG non trovate per questo gioco.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BggCandidateSelector(
                        candidates = content.bggCandidates,
                        isLoading = content.isLoadingBggCandidates,
                        error = content.detailsError,
                        isSearching = content.isSearchingBgg,
                        searchError = content.bggSearchError,
                        onSelect = onSelectBggCandidate,
                        onSearch = onSearchBggCandidates,
                    )
                    if (game?.bggRating != null) SpecQuickGrid(game = game, details = null)
                }
            } else {
                if (isEditingBgg) {
                    BggCandidateSelector(
                        candidates = content.bggCandidates,
                        isLoading = content.isLoadingBggCandidates,
                        error = content.detailsError,
                        isSearching = content.isSearchingBgg,
                        searchError = content.bggSearchError,
                        onSelect = { candidate ->
                            onSelectBggCandidate(candidate)
                            isEditingBgg = false
                        },
                        onSearch = onSearchBggCandidates,
                    )
                }

                SpecQuickGrid(game = game, details = details)

                GameSpecificationRows(details, content.item.title)

                details.description?.let { description ->
                    val plainText = android.text.Html.fromHtml(description, android.text.Html.FROM_HTML_MODE_COMPACT)
                        .toString()
                        .trim()
                    if (plainText.isNotBlank()) {
                        HorizontalDivider()
                        Text(
                            text = "Descrizione",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = plainText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (showFullDescription) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (plainText.length > 180) {
                            TextButton(
                                onClick = { showFullDescription = !showFullDescription },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    text = if (showFullDescription) "Mostra meno" else "Leggi tutto...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    if (content.manualBggId != null) {
                        TextButton(onClick = onRemoveManualBggSelection) {
                            Text("Rimuovi selezione manuale")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SeriesCard(content: DetailUiState.Content) {
    val uriHandler = LocalUriHandler.current
    val series = content.series
    val hasData = series != null && (series.expansions.isNotEmpty() || series.bases.isNotEmpty() || series.siblings.isNotEmpty())

    if (!content.isLoadingSeries && !hasData) return

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Espansioni e serie", style = MaterialTheme.typography.titleLarge)

            if (content.isLoadingSeries) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Caricamento espansioni e serie…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (series != null) {
                if (series.expansions.isNotEmpty()) {
                    Text(
                        text = "Espansioni (${series.expansions.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    BggLinkList(links = series.expansions, onOpen = { url -> uriHandler.openUri(url) })
                }

                if (series.bases.isNotEmpty()) {
                    Text(
                        text = "Espande",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    BggLinkList(links = series.bases, onOpen = { url -> uriHandler.openUri(url) })
                }

                if (series.siblings.isNotEmpty()) {
                    if (series.bases.isNotEmpty()) {
                        HorizontalDivider()
                    }
                    Text(
                        text = "Altre espansioni della serie",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    BggLinkList(links = series.siblings, onOpen = { url -> uriHandler.openUri(url) })
                }
            }
        }
    }
}

@Composable
internal fun BggLinkList(links: List<BggLink>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        links.forEach { link ->
            val hasUrl = !link.url.isNullOrBlank()
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (hasUrl) {
                            Modifier.clickable { onOpen(link.url) }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = link.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (hasUrl) {
                        Text(
                            text = "↗",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SpecQuickGrid(game: Game?, details: BoardGameDetails?) {
    val bggId = details?.bggId ?: game?.bggId
    val rating = game?.bggRating
    val rank = game?.bggRank
    val year = details?.yearPublished
    val players = when {
        details?.minPlayers != null && details.maxPlayers != null ->
            if (details.minPlayers == details.maxPlayers) "${details.minPlayers}" else "${details.minPlayers}–${details.maxPlayers}"
        details?.minPlayers != null -> "da ${details.minPlayers}"
        details?.maxPlayers != null -> "fino a ${details.maxPlayers}"
        else -> null
    }
    val time = details?.playingTime?.let { "$it min" }
    val age = details?.minAge?.let { "$it+ anni" }

    val chips = buildList {
        bggId?.let { add("ID BGG" to "#$it") }
        rating?.let { add("Voto" to "⭐ %.1f".format(Locale.US, it)) }
        rank?.let { add("Classifica" to "🏆 #$it") }
        players?.let { add("Giocatori" to "👥 $it") }
        time?.let { add("Durata" to "⏱️ $it") }
        age?.let { add("Età" to "🔞 $it") }
        year?.let { add("Anno" to "📅 $it") }
    }

    if (chips.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.chunked(3).forEach { rowChips ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowChips.forEach { (label, value) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (rowChips.size < 3) {
                        repeat(3 - rowChips.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
internal fun GameSpecificationRows(details: BoardGameDetails, gameTitle: String) {
    details.englishName?.takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) != gameTitle.lowercase(Locale.ROOT) }?.let { engName ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Nome in inglese", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                engName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    val authorsText = details.designers.joinToString(" · ")
    val categoriesText = details.categories.joinToString(" · ")

    if (authorsText.isNotBlank() || categoriesText.isNotBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (authorsText.isNotBlank()) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Autori", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        authorsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (categoriesText.isNotBlank()) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Categorie", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        categoriesText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (details.mechanics.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Meccaniche", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                details.mechanics.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
