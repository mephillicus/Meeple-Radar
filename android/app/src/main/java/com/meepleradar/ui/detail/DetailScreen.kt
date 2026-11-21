package com.meepleradar.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.incoming.bestComparableEuroOffers

@Composable
fun DetailScreen(
    vm: DetailViewModel,
    onBack: () -> Unit,
    imageLookup: BoardGameImageLookup,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Indietro",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Dettaglio Gioco",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.weight(1f))
                (state as? DetailUiState.Content)?.let { content ->
                    IconButton(onClick = { vm.toggleFavorite() }) {
                        Icon(
                            imageVector = if (content.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (content.isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
                            tint = if (content.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.ignore(onBack) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Ignora gioco",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (val current = state) {
                DetailUiState.Loading -> LoadingView()

                DetailUiState.NotFound -> EmptyView("Gioco non trovato")

                is DetailUiState.Error -> ErrorView(
                    message = current.message,
                    onRetry = { vm.refresh() },
                )

                is DetailUiState.Content -> DetailContent(
                    content = current,
                    imageLookup = imageLookup,
                    onSelectBggCandidate = vm::selectBggCandidate,
                    onRemoveManualBggSelection = vm::removeManualBggSelection,
                    onSearchBggCandidates = vm::searchBggCandidates,
                    onSaveToDb = vm::saveToDatabase,
                    onUpdateMissingBggStats = vm::updateMissingBggStats,
                    onToggleManualProductEditing = vm::toggleManualProductEditing,
                    onSaveManualProduct = vm::saveManualProduct,
                    onUpdateManualProductField = vm::updateManualProductField,
                )
            }
        }
    }
}

@Composable
private fun DetailContent(
    content: DetailUiState.Content,
    imageLookup: BoardGameImageLookup,
    onSelectBggCandidate: (BoardGameDetails) -> Unit,
    onRemoveManualBggSelection: () -> Unit,
    onSearchBggCandidates: (String) -> Unit,
    onSaveToDb: () -> Unit,
    onUpdateMissingBggStats: () -> Unit,
    onToggleManualProductEditing: () -> Unit,
    onSaveManualProduct: () -> Unit,
    onUpdateManualProductField: (
        title: String?,
        url: String?,
        price: Double?,
        currency: String?,
    ) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val bestOffers = remember(content.offers) { bestComparableEuroOffers(content.offers) }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content.actionError?.let { error ->
            item {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            DetailHeader(content, imageLookup)
        }

        item {
            BoardGameSpecs(
                content = content,
                onSelectBggCandidate = onSelectBggCandidate,
                onRemoveManualBggSelection = onRemoveManualBggSelection,
                onSearchBggCandidates = onSearchBggCandidates,
                onAddToDatabase = onSaveToDb,
            )
        }

        item {
            SeriesCard(content)
        }

        item {
            Text(
                text = "Offerte negli Store (${content.offers.size})",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        items(content.offers) { offer ->
            OfferRow(
                offer = offer,
                allOffers = content.offers,
                isBestOffer = offer in bestOffers,
                bestPrice = offer.listingId?.let { content.bestPricesByListingId[it] },
                onOpen = { runCatching { uriHandler.openUri(offer.url) } },
            )
        }

        if (content.priceHistory.size >= 2) {
            item {
                PriceHistoryCard(
                    entries = content.priceHistory,
                    offers = content.offers,
                    bestPrices = content.bestPricesByListingId,
                )
            }
        }

        val game = content.item.game
        val bggId = game?.bggId ?: content.boardGameDetails?.bggId
        if (content.isFromDatabase && game?.bggId != null && bggId != null &&
            (game.bggRating == null || game.bggRank == null || game.description.isNullOrBlank())
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onUpdateMissingBggStats,
                        enabled = !content.isUpdatingBggStats,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (content.isUpdatingBggStats) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Aggiornamento dati BGG…")
                        } else {
                            Text("Aggiorna voto, rank e descrizione BGG")
                        }
                    }
                    content.updateBggStatsError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (!content.isFromDatabase && content.boardGameDetails == null) {
            item {
                BggAssociationCard(
                    content = content,
                    onSearch = onSearchBggCandidates,
                    onSelect = onSelectBggCandidate,
                    onSaveToDb = onSaveToDb,
                )
            }
        } else if (!content.isFromDatabase || content.isPendingDbSave || content.saveToDbSuccess || content.saveToDbError != null) {
            item {
                SaveToDatabaseCard(
                    isPending = content.isPendingDbSave,
                    isSaving = content.isSavingToDb,
                    saveSuccess = content.saveToDbSuccess,
                    saveError = content.saveToDbError,
                    onSaveToDb = onSaveToDb,
                )
            }
        }

        if (content.isEditingManualProduct) {
            item {
                ManualProductForm(
                    content = content,
                    onToggleEditing = onToggleManualProductEditing,
                    onUpdateField = onUpdateManualProductField,
                    onSave = onSaveManualProduct,
                )
            }
        }
    }
}
