package com.meepleradar.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.remote.BoardGameImageLookup
import com.meepleradar.ui.incoming.bestPriceLabel
import com.meepleradar.ui.incoming.availabilityColor
import com.meepleradar.ui.incoming.bestPricedOffer
import com.meepleradar.ui.incoming.dateLabel
import com.meepleradar.ui.incoming.offerPriceColor
import com.meepleradar.ui.incoming.orderedOffersForDisplay
import com.meepleradar.ui.theme.BggGoldBackground
import com.meepleradar.ui.theme.BggGoldDarkBackground
import com.meepleradar.ui.theme.BggGoldDarkText
import com.meepleradar.ui.theme.BggGoldText
import java.util.Locale

@Composable
fun ListingCard(
    item: IncomingItem,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onIgnore: () -> Unit,
    imageLookup: BoardGameImageLookup,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Image / Thumbnail
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                GameCover(
                    imageUrl = item.imageUrl,
                    title = item.title,
                    bggId = item.game?.bggId,
                    imageLookup = imageLookup,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Details Column (Center)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Date Chip
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = dateLabel(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Price, Rating Badge, and Small Action Buttons (Favorite & Ignore)
                val bestOfferForDisplay = bestPricedOffer(item.offers)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = bestPriceLabel(item.offers),
                        style = MaterialTheme.typography.titleMedium,
                        color = bestOfferForDisplay?.let { offerPriceColor(it, item.offers) }
                            ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.ExtraBold,
                    )

                    item.game?.bggRating?.let { RatingBadge(it) }

                    Spacer(Modifier.weight(1f))

                    // Small Favorite Button
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Preferito",
                            tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    // Small Ignore Button
                    IconButton(
                        onClick = onIgnore,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Ignora",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Right Column: Shopping Cart + Sources (without "Fonte")
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.width(96.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "${item.offers.size} store",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                val displayedOffers = orderedOffersForDisplay(item.offers).take(2)
                displayedOffers.forEach { offer ->
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = offer.sourceName,
                            style = MaterialTheme.typography.labelSmall,
                            color = availabilityColor(offer),
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (item.offers.size > displayedOffers.size) {
                    Text(
                        text = "+${item.offers.size - displayedOffers.size} altri",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

@Composable
fun RatingBadge(rating: Double, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (isDark) BggGoldDarkBackground else BggGoldBackground,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = if (isDark) BggGoldDarkText else BggGoldText,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "%.1f".format(Locale.US, rating),
                style = MaterialTheme.typography.labelSmall,
                color = if (isDark) BggGoldDarkText else BggGoldText,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
