package com.meepleradar.ui.detail

import android.text.Html
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.Offer
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.GameCover
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.RatingBadge
import com.meepleradar.ui.components.ScreenContentInsets
import com.meepleradar.ui.incoming.dateLabel
import com.meepleradar.ui.incoming.priceLabel

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
        contentWindowInsets = ScreenContentInsets,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Top Navigation Row
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
            }

            when (val current = state) {
                DetailUiState.Loading -> LoadingView()

                DetailUiState.NotFound -> EmptyView("Gioco non trovato")

                is DetailUiState.Error -> ErrorView(
                    message = current.message,
                    onRetry = { vm.refresh() },
                )

                is DetailUiState.Content -> DetailContent(current, imageLookup)
            }
        }
    }
}

@Composable
private fun DetailContent(content: DetailUiState.Content, imageLookup: BoardGameImageLookup) {
    val uriHandler = LocalUriHandler.current
    val bestOffer = content.offers.firstOrNull { it.price?.currency == "EUR" }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            DetailHeader(content, imageLookup)
        }

        item {
            BoardGameSpecs(content)
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
                isBestOffer = offer == bestOffer,
                onOpen = { runCatching { uriHandler.openUri(offer.url) } },
            )
        }
    }
}

@Composable
private fun BoardGameSpecs(content: DetailUiState.Content) {
    val uriHandler = LocalUriHandler.current
    val details = content.boardGameDetails
    val game = content.item.game

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
                Text("Specifiche", style = MaterialTheme.typography.titleLarge)
                (details?.bggId ?: game?.bggId)?.let { id ->
                    Text(
                        text = "BoardGameGeek",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                uriHandler.openUri("https://boardgamegeek.com/boardgame/$id")
                            }
                            .padding(6.dp),
                    )
                }
            }

            if (content.isLoadingDetails) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Recupero dettagli da BoardGameGeek…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (content.detailsError != null) {
                Text(
                    content.detailsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (details == null) {
                Text(
                    "Specifiche non disponibili per questo gioco.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                GameSpecificationRows(details)
                details.description?.let { description ->
                    val plainText = Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT)
                        .toString()
                        .trim()
                    if (plainText.isNotBlank()) {
                        HorizontalDivider()
                        Text(
                            plainText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameSpecificationRows(details: BoardGameDetails) {
    val values = buildList {
        details.yearPublished?.let { add("Anno" to it.toString()) }
        val players = when {
            details.minPlayers != null && details.maxPlayers != null ->
                if (details.minPlayers == details.maxPlayers) "${details.minPlayers}" else "${details.minPlayers}–${details.maxPlayers}"
            details.minPlayers != null -> "da ${details.minPlayers}"
            details.maxPlayers != null -> "fino a ${details.maxPlayers}"
            else -> null
        }
        players?.let { add("Giocatori" to it) }
        details.playingTime?.let { add("Durata" to "$it min") }
        details.minAge?.let { add("Età" to "$it+") }
    }

    values.forEachIndexed { index, (label, value) ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (index < values.lastIndex) HorizontalDivider()
    }

    listOf(
        "Autori" to details.designers,
        "Categorie" to details.categories,
        "Meccaniche" to details.mechanics,
    ).filter { it.second.isNotEmpty() }.forEach { (label, entries) ->
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                entries.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailHeader(content: DetailUiState.Content, imageLookup: BoardGameImageLookup) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                GameCover(
                    imageUrl = content.item.imageUrl,
                    title = content.item.title,
                    bggId = content.item.game?.bggId,
                    imageLookup = imageLookup,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )

                Spacer(Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = content.item.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = dateLabel(content.item),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    content.item.game?.bggRating?.let { RatingBadge(it) }
                }
            }
        }
    }
}

@Composable
private fun OfferRow(offer: Offer, isBestOffer: Boolean, onOpen: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isBestOffer) {
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            1.dp,
            if (isBestOffer) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = offer.sourceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isBestOffer) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = "PREZZO MIGLIORE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                offer.availability?.let {
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = priceLabel(offer),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isBestOffer) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onOpen,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ShoppingCart,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Apri", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
