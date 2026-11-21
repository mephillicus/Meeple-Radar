package com.meepleradar.ui.detail

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meepleradar.data.model.ListingBestPrice
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import com.meepleradar.data.model.PriceHistoryEntry
import com.meepleradar.ui.incoming.availabilityColor
import com.meepleradar.ui.incoming.availabilityLabel
import com.meepleradar.ui.incoming.offerPriceColor
import com.meepleradar.ui.incoming.priceLabel
import com.meepleradar.ui.theme.MeepleRadarTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private const val MIN_VISIBLE_PRICE_DROP_PCT = 5.0
private val HISTORY_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd/MM", Locale.ITALY)

@Composable
internal fun OfferRow(
    offer: Offer,
    allOffers: List<Offer>,
    isBestOffer: Boolean,
    onOpen: () -> Unit,
    bestPrice: ListingBestPrice? = null,
) {
    val priceColor = offerPriceColor(offer, allOffers)
    val stockColor = availabilityColor(offer)
    val isDark = isSystemInDarkTheme()
    val dropBadgeBg = if (isDark) Color(0xFF14532D) else Color(0xFFDCFCE7)
    val dropBadgeFg = if (isDark) Color(0xFF86EFAC) else Color(0xFF15803D)

    val effectiveDropPct = (bestPrice?.priceDropPct ?: offer.priceDropPct)
        ?.takeIf { it >= MIN_VISIBLE_PRICE_DROP_PCT }
    val previousPrice = bestPrice?.previousPrice
    val currentPrice = bestPrice?.currentPrice ?: offer.price?.amount
    val hasLowerPriceThanPrevious = previousPrice != null &&
        currentPrice != null &&
        previousPrice > currentPrice + 0.005
    val isHistoricalMin = bestPrice?.minPrice != null &&
        currentPrice != null &&
        hasLowerPriceThanPrevious &&
        currentPrice <= bestPrice.minPrice + 0.005

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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = offer.sourceName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (isBestOffer) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = "PREZZO MIGLIORE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    if (isHistoricalMin) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = dropBadgeBg,
                        ) {
                            Text(
                                text = "MINIMO STORICO",
                                style = MaterialTheme.typography.labelSmall,
                                color = dropBadgeFg,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    availabilityLabel(offer)?.let { status ->
                        Surface(
                            shape = CircleShape,
                            color = stockColor.copy(alpha = 0.15f),
                        ) {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = stockColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (hasLowerPriceThanPrevious || effectiveDropPct != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (hasLowerPriceThanPrevious) {
                            Text(
                                text = formatMoney(previousPrice, bestPrice.currency ?: offer.price?.currency),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textDecoration = TextDecoration.LineThrough,
                            )
                        }
                        effectiveDropPct?.let { pct ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = dropBadgeBg,
                            ) {
                                Text(
                                    text = "-${pct.roundToInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = dropBadgeFg,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
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

@Composable
internal fun PriceHistoryCard(
    entries: List<PriceHistoryEntry>,
    offers: List<Offer>,
    bestPrices: Map<Long, ListingBestPrice>,
    modifier: Modifier = Modifier,
) {
    val storeNameByListingId = remember(offers) {
        offers.mapNotNull { offer -> offer.listingId?.let { it to offer.sourceName } }.toMap()
    }
    val pricedEntries = remember(entries) {
        entries.filter { it.price != null }.take(6)
    }
    if (pricedEntries.size < 2) return

    val historicalMin = remember(bestPrices, entries) {
        val fromView = bestPrices.values.mapNotNull { it.minPrice }.minOrNull()
        val fromEntries = entries.mapNotNull { it.price }.minOrNull()
        listOfNotNull(fromView, fromEntries).minOrNull()
    }
    val minCurrency = remember(bestPrices, pricedEntries) {
        bestPrices.values.firstNotNullOfOrNull { it.currency } ?: pricedEntries.firstNotNullOfOrNull { it.currency }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
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
                    text = "Storico prezzi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                historicalMin?.let { min ->
                    Text(
                        text = "Min storico: ${formatMoney(min, minCurrency)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            pricedEntries.forEachIndexed { index, entry ->
                val storeName = storeNameByListingId[entry.listingId] ?: "Store #${entry.listingId}"
                val dateLabel = formatHistoryDate(entry.recordedAt)
                val priceValue = entry.price ?: return@forEachIndexed
                val isMin = historicalMin != null && priceValue <= historicalMin + 0.005

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp),
                    )
                    Text(
                        text = storeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatMoney(priceValue, entry.currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isMin || index == 0) FontWeight.Bold else FontWeight.Medium,
                        color = if (isMin) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun formatMoney(amount: Double, currency: String?): String =
    if (currency.isNullOrBlank() || currency.equals("EUR", ignoreCase = true)) {
        "%.2f €".format(Locale.ITALY, amount)
    } else {
        "%.2f %s".format(Locale.US, amount, currency)
    }

private fun formatHistoryDate(iso: String): String {
    val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }
        .recoverCatching { Instant.parse(iso) }
        .getOrNull() ?: return iso.take(10)
    val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
    return localDateTime.format(HISTORY_DATE_FORMATTER)
}

@Composable
internal fun ManualProductForm(
    content: DetailUiState.Content,
    onToggleEditing: () -> Unit,
    onUpdateField: (
        title: String?,
        url: String?,
        price: Double?,
        currency: String?,
    ) -> Unit,
    onSave: () -> Unit,
) {
    var title by remember { mutableStateOf(content.manualProductTitle ?: "") }
    var url by remember { mutableStateOf(content.manualProductUrl ?: "") }
    var price by remember { mutableStateOf(content.manualProductPrice?.toString() ?: "") }
    var currency by remember { mutableStateOf(content.manualProductCurrency ?: "") }

    LaunchedEffect(
        content.manualProductTitle,
        content.manualProductUrl,
        content.manualProductPrice,
        content.manualProductCurrency,
    ) {
        title = content.manualProductTitle ?: ""
        url = content.manualProductUrl ?: ""
        price = content.manualProductPrice?.toString() ?: ""
        currency = content.manualProductCurrency ?: ""
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Inserimento prodotto manuale",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = title,
                onValueChange = { onUpdateField(it, url, price.toDoubleOrNull(), currency) },
                label = { Text("Titolo prodotto") },
                placeholder = { Text("Es. Catan") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = url,
                onValueChange = { onUpdateField(title, it, price.toDoubleOrNull(), currency) },
                label = { Text("URL prodotto") },
                placeholder = { Text("Es. https://example.com/prodotto") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { p -> onUpdateField(title, url, p.toDoubleOrNull(), currency) },
                    label = { Text("Prezzo") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )

                OutlinedTextField(
                    value = currency,
                    onValueChange = { c -> onUpdateField(title, url, price.toDoubleOrNull(), c) },
                    label = { Text("Valuta") },
                    placeholder = { Text("EUR") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            content.manualProductError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (content.isSavingToDb) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "Salvataggio in corso…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onToggleEditing() },
                    enabled = !content.isSavingToDb,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Annulla")
                }

                Button(
                    onClick = { onSave() },
                    enabled = !content.isSavingToDb && title.isNotBlank() && url.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Salva prodotto")
                }
            }
        }
    }
}

@Preview(name = "Offer & Price History — Light", showBackground = true)
@Preview(
    name = "Offer & Price History — Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun OfferRowAndHistoryPreview() {
    val sampleOffer = Offer(
        sourceId = "dungeondice",
        sourceName = "DungeonDice",
        price = Price(39.90, "EUR"),
        url = "https://example.com",
        availability = "Disponibile",
        listingId = 101L,
        priceDropPct = 15.0,
    )
    val sampleBestPrice = ListingBestPrice(
        listingId = 101L,
        currentPrice = 39.90,
        currency = "EUR",
        currentAvailability = "Disponibile",
        minPrice = 39.90,
        previousPrice = 46.90,
        priceDropPct = 14.9,
        recordedAt = "2026-04-19T08:00:00Z",
    )
    val sampleHistory = listOf(
        PriceHistoryEntry(3L, 101L, 39.90, "EUR", "Disponibile", "2026-04-19T08:00:00Z"),
        PriceHistoryEntry(2L, 101L, 46.90, "EUR", "Disponibile", "2026-04-10T08:00:00Z"),
        PriceHistoryEntry(1L, 101L, 49.90, "EUR", "Preordine", "2026-03-28T08:00:00Z"),
    )
    MeepleRadarTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OfferRow(
                    offer = sampleOffer,
                    allOffers = listOf(sampleOffer),
                    isBestOffer = true,
                    bestPrice = sampleBestPrice,
                    onOpen = {},
                )
                PriceHistoryCard(
                    entries = sampleHistory,
                    offers = listOf(sampleOffer),
                    bestPrices = mapOf(101L to sampleBestPrice),
                )
            }
        }
    }
}
