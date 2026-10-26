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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meepleradar.data.model.Offer
import com.meepleradar.data.remote.BggLink
import com.meepleradar.data.remote.BoardGameDetails
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.components.EmptyView
import com.meepleradar.ui.components.ErrorView
import com.meepleradar.ui.components.GameCover
import com.meepleradar.ui.components.LoadingView
import com.meepleradar.ui.components.RatingBadge
import com.meepleradar.ui.components.ScreenContentInsets
import com.meepleradar.ui.incoming.availabilityColor
import com.meepleradar.ui.incoming.availabilityLabel
import com.meepleradar.ui.incoming.bestComparableEuroOffers
import com.meepleradar.ui.incoming.dateLabel
import com.meepleradar.ui.incoming.offerPriceColor
import com.meepleradar.ui.incoming.priceLabel
import java.util.Locale

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

                is DetailUiState.Content -> DetailContent(current, imageLookup)
            }
        }
    }
}

@Composable
private fun DetailContent(content: DetailUiState.Content, imageLookup: BoardGameImageLookup) {
    val uriHandler = LocalUriHandler.current
    val bestOffers = remember(content.offers) { bestComparableEuroOffers(content.offers) }

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
                onOpen = { runCatching { uriHandler.openUri(offer.url) } },
            )
        }
    }
}

@Composable
private fun DetailHeader(content: DetailUiState.Content, imageLookup: BoardGameImageLookup) {
    val uriHandler = LocalUriHandler.current
    val game = content.item.game
    val details = content.boardGameDetails
    val bggId = details?.bggId ?: game?.bggId

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
                    bggId = bggId,
                    imageLookup = imageLookup,
                    modifier = Modifier
                        .size(108.dp)
                        .clip(RoundedCornerShape(20.dp)),
                )

                Spacer(Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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

                    // Rating and Rank
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        game?.bggRating?.let { RatingBadge(it) }

                        game?.bggRank?.let { rank ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = "Rank #$rank",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }

                    bggId?.let { id ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.clickable {
                                uriHandler.openUri("https://boardgamegeek.com/boardgame/$id")
                            },
                        ) {
                            Text(
                                text = "🆔 BGG #$id",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoardGameSpecs(content: DetailUiState.Content) {
    val uriHandler = LocalUriHandler.current
    val details = content.boardGameDetails
    val game = content.item.game
    var showFullDescription by remember { mutableStateOf(false) }

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
                (details?.bggId ?: game?.bggId)?.let { id ->
                    TextButton(
                        onClick = { uriHandler.openUri("https://boardgamegeek.com/boardgame/$id") },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Apri su BGG ↗", style = MaterialTheme.typography.labelSmall)
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
            } else if (details == null && game?.bggRating == null) {
                Text(
                    "Specifiche BGG non trovate per questo gioco.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Quick Spec Stat Badges
                SpecQuickGrid(game = game, details = details)

                if (details != null) {
                    GameSpecificationRows(details)

                    details.description?.let { description ->
                        val plainText = Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT)
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
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCard(content: DetailUiState.Content) {
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
private fun BggLinkList(links: List<BggLink>, onOpen: (String) -> Unit) {
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
private fun SpecQuickGrid(game: com.meepleradar.data.model.Game?, details: BoardGameDetails?) {
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
private fun GameSpecificationRows(details: BoardGameDetails) {
    listOf(
        "Autori" to details.designers,
        "Categorie" to details.categories,
        "Meccaniche" to details.mechanics,
    ).filter { it.second.isNotEmpty() }.forEach { (label, entries) ->
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
private fun OfferRow(offer: Offer, allOffers: List<Offer>, isBestOffer: Boolean, onOpen: () -> Unit) {
    val priceColor = offerPriceColor(offer, allOffers)
    val stockColor = availabilityColor(offer)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (priceColor == MaterialTheme.colorScheme.onSurfaceVariant) {
                MaterialTheme.colorScheme.surface
            } else {
                priceColor.copy(alpha = 0.08f)
            },
        ),
        border = BorderStroke(
            1.dp,
            if (priceColor == MaterialTheme.colorScheme.onSurfaceVariant) priceColor.copy(alpha = 0.25f) else priceColor,
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
                availabilityLabel(offer)?.let { status ->
                    Spacer(Modifier.height(2.dp))
                    Surface(
                        shape = CircleShape,
                        color = stockColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelSmall,
                            color = stockColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = priceLabel(offer),
                    style = MaterialTheme.typography.titleLarge,
                    color = priceColor,
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
