package com.meepleradar.ui.detail

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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

private sealed interface ExternalSitePanelState {
    data object Loading : ExternalSitePanelState
    data class Loaded(val result: ExternalScrapeResult) : ExternalSitePanelState
}

private sealed interface KeepaPanelState {
    data object Loading : KeepaPanelState
    data class Loaded(val result: KeepaHistoryResult) : KeepaPanelState
}

@Composable
internal fun ExternalPriceCompareCard(
    title: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    fallbackTitle: String? = null,
    scraperOverride: ExternalSiteScraper? = null,
    initialSelectedSite: ExternalPriceSite? = null,
    initialResults: Map<ExternalPriceSite, ExternalScrapeResult> = emptyMap(),
    initialKeepaResult: KeepaHistoryResult? = null,
) {
    val sanitizedQuery = remember(title, fallbackTitle) {
        sanitizeSearchQuery(title, fallbackTitle)
    }
    val alternateQuery = remember(title, fallbackTitle, sanitizedQuery) {
        fallbackTitle?.let { sanitizeSearchQuery(it) }
            ?.takeIf { it.isNotBlank() && !it.equals(sanitizedQuery, ignoreCase = true) }
    }
    val links = remember(title, fallbackTitle) {
        buildExternalPriceSearchLinks(title, fallbackTitle)
    }
    if (links.isEmpty()) return

    val context = LocalContext.current
    val scraper = remember(context, scraperOverride) {
        scraperOverride ?: WebViewExternalSiteScraper(context)
    }
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()

    var selectedSite by remember(sanitizedQuery) { mutableStateOf(initialSelectedSite) }
    val panelStates = remember(sanitizedQuery) {
        mutableStateMapOf<ExternalPriceSite, ExternalSitePanelState>().apply {
            initialResults.forEach { (site, res) -> put(site, ExternalSitePanelState.Loaded(res)) }
        }
    }
    var selectedKeepaAsin by remember(sanitizedQuery) {
        val initialAsin = (initialResults[ExternalPriceSite.AMAZON_IT] as? ExternalScrapeResult.Success)
            ?.items?.firstNotNullOfOrNull { it.asin }
        mutableStateOf(initialAsin)
    }
    var keepaRange by remember(sanitizedQuery) { mutableStateOf(KeepaRange.DAYS_90) }
    val keepaStates = remember(sanitizedQuery) {
        mutableStateMapOf<Pair<String, KeepaRange>, KeepaPanelState>().apply {
            val initialAsin = (initialResults[ExternalPriceSite.AMAZON_IT] as? ExternalScrapeResult.Success)
                ?.items?.firstNotNullOfOrNull { it.asin }
            if (initialAsin != null && initialKeepaResult != null) {
                put(initialAsin to KeepaRange.DAYS_90, KeepaPanelState.Loaded(initialKeepaResult))
            }
        }
    }

    fun fetchKeepa(asin: String, range: KeepaRange, force: Boolean = false) {
        val key = asin to range
        if (!force && keepaStates[key] is KeepaPanelState.Loaded) return
        if (keepaStates[key] is KeepaPanelState.Loading) return

        keepaStates[key] = KeepaPanelState.Loading
        scope.launch {
            val res = scraper.scrapeKeepaHistory(asin, range)
            keepaStates[key] = KeepaPanelState.Loaded(res)
        }
    }

    fun fetchSite(site: ExternalPriceSite, force: Boolean = false) {
        val searchUrl = links.firstOrNull { it.site == site }?.url ?: return
        if (!force && panelStates[site] is ExternalSitePanelState.Loaded) return
        if (panelStates[site] is ExternalSitePanelState.Loading) return

        panelStates[site] = ExternalSitePanelState.Loading
        scope.launch {
            val result = scraper.scrape(site, searchUrl, sanitizedQuery, alternateQuery)
            panelStates[site] = ExternalSitePanelState.Loaded(result)
            if (site == ExternalPriceSite.AMAZON_IT && result is ExternalScrapeResult.Success) {
                val firstAsin = result.items.firstNotNullOfOrNull { it.asin }
                if (firstAsin != null && (force || selectedKeepaAsin == null)) {
                    selectedKeepaAsin = firstAsin
                    fetchKeepa(firstAsin, keepaRange, force = force)
                }
            }
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Confronta altrove",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                links.forEach { link ->
                    val isSelected = selectedSite == link.site
                    val accent = externalSiteAccentColor(link.site, isDark)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) {
                            accent.copy(alpha = if (isDark) 0.20f else 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        },
                        border = BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) accent else MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .semantics {
                                contentDescription = "Cerca su ${link.site.label}"
                            }
                            .clickable(role = Role.Button) {
                                if (isSelected) {
                                    selectedSite = null
                                } else {
                                    selectedSite = link.site
                                    fetchSite(link.site, force = false)
                                }
                            },
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            ExternalSiteBrandLogo(site = link.site, isDark = isDark)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = selectedSite != null,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                ) + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                val activeSite = selectedSite ?: return@AnimatedVisibility
                val activeSearchUrl = links.firstOrNull { it.site == activeSite }?.url.orEmpty()
                val activeState = panelStates[activeSite] ?: ExternalSitePanelState.Loading
                val activeKeepaState = selectedKeepaAsin?.let { asin ->
                    keepaStates[asin to keepaRange] ?: KeepaPanelState.Loading
                } ?: KeepaPanelState.Loading

                ExternalSiteResultsPanel(
                    site = activeSite,
                    panelState = activeState,
                    isDark = isDark,
                    selectedKeepaAsin = selectedKeepaAsin,
                    keepaRange = keepaRange,
                    keepaState = activeKeepaState,
                    onSelectKeepaAsin = { asin ->
                        selectedKeepaAsin = asin
                        fetchKeepa(asin, keepaRange, force = false)
                    },
                    onSelectKeepaRange = { range ->
                        keepaRange = range
                        selectedKeepaAsin?.let { asin -> fetchKeepa(asin, range, force = false) }
                    },
                    onRetryKeepa = {
                        selectedKeepaAsin?.let { asin -> fetchKeepa(asin, keepaRange, force = true) }
                    },
                    onRetry = { fetchSite(activeSite, force = true) },
                    onOpenSearch = { onOpenUrl(activeSearchUrl) },
                    onOpenUrl = onOpenUrl,
                )
            }
        }
    }
}

@Composable
private fun ExternalSiteResultsPanel(
    site: ExternalPriceSite,
    panelState: ExternalSitePanelState,
    isDark: Boolean,
    selectedKeepaAsin: String?,
    keepaRange: KeepaRange,
    keepaState: KeepaPanelState,
    onSelectKeepaAsin: (String) -> Unit,
    onSelectKeepaRange: (KeepaRange) -> Unit,
    onRetryKeepa: () -> Unit,
    onRetry: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val accent = externalSiteAccentColor(site, isDark)

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = site.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Riprova estrazione da ${site.label}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                TextButton(
                    onClick = onOpenSearch,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Apri",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            when (panelState) {
                ExternalSitePanelState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = accent,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Estrazione offerte da ${site.label}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                is ExternalSitePanelState.Loaded -> {
                    when (val result = panelState.result) {
                        is ExternalScrapeResult.Success -> {
                            val activeAsin = if (site == ExternalPriceSite.AMAZON_IT) {
                                selectedKeepaAsin?.takeIf { asin -> result.items.any { it.asin == asin } }
                                    ?: result.items.firstNotNullOfOrNull { it.asin }
                            } else {
                                null
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                result.items.forEachIndexed { index, item ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                        )
                                    }
                                    ScrapedOfferRow(
                                        item = item,
                                        isDark = isDark,
                                        showKeepaSelector = site == ExternalPriceSite.AMAZON_IT &&
                                            item.asin != null &&
                                            result.items.count { it.asin != null } > 1,
                                        isKeepaSelected = item.asin != null && item.asin == activeAsin,
                                        onSelectKeepa = { item.asin?.let(onSelectKeepaAsin) },
                                        onOpen = { onOpenUrl(item.url) },
                                    )
                                }
                            }

                            if (site == ExternalPriceSite.AMAZON_IT && activeAsin != null) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                )
                                KeepaInlineChartSection(
                                    asin = activeAsin,
                                    range = keepaRange,
                                    keepaState = keepaState,
                                    onRangeChange = onSelectKeepaRange,
                                    onRetry = onRetryKeepa,
                                    onOpenKeepa = { onOpenUrl(buildKeepaProductUrl(activeAsin)) },
                                    onOpenCamel = { onOpenUrl(buildCamelProductUrl(activeAsin)) },
                                )
                            }
                        }

                        is ExternalScrapeResult.Blocked -> {
                            ExternalSiteFallbackMessage(
                                message = result.reason,
                                onRetry = onRetry,
                                onOpenBrowser = onOpenSearch,
                            )
                        }

                        is ExternalScrapeResult.Empty -> {
                            ExternalSiteFallbackMessage(
                                message = result.message,
                                onRetry = onRetry,
                                onOpenBrowser = onOpenSearch,
                            )
                        }

                        is ExternalScrapeResult.Error -> {
                            ExternalSiteFallbackMessage(
                                message = result.message,
                                onRetry = onRetry,
                                onOpenBrowser = onOpenSearch,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrapedOfferRow(
    item: ScrapedOfferItem,
    isDark: Boolean,
    showKeepaSelector: Boolean,
    isKeepaSelected: Boolean,
    onSelectKeepa: () -> Unit,
    onOpen: () -> Unit,
) {
    val primeBg = if (isDark) Color(0xFF0C4A6E) else Color(0xFFE0F2FE)
    val primeFg = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                item.price?.let { price ->
                    Text(
                        text = price,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                item.originalPrice?.let { orig ->
                    Text(
                        text = orig,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
                if (showKeepaSelector) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isKeepaSelected) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isKeepaSelected) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onSelectKeepa),
                    ) {
                        Text(
                            text = if (isKeepaSelected) "Grafico attivo" else "Grafico",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isKeepaSelected) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            val detailLine = listOfNotNull(item.sellerOrMeta, item.deliveryOrAvailability)
                .joinToString(" · ")
            if (item.isPrime || detailLine.isNotBlank()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.isPrime) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = primeBg,
                        ) {
                            Text(
                                text = "Prime",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = primeFg,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (detailLine.isNotBlank()) {
                        Text(
                            text = if (item.isPrime) "· $detailLine" else detailLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Apri offerta",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun KeepaInlineChartSection(
    asin: String,
    range: KeepaRange,
    keepaState: KeepaPanelState,
    onRangeChange: (KeepaRange) -> Unit,
    onRetry: () -> Unit,
    onOpenKeepa: () -> Unit,
    onOpenCamel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Storico Keepa",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "ASIN $asin",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeepaRange.entries.forEach { option ->
                    val selected = option == range
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        border = BorderStroke(
                            1.dp,
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onRangeChange(option) },
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        when (keepaState) {
            KeepaPanelState.Loading -> {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Caricamento grafico Keepa…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            is KeepaPanelState.Loaded -> {
                when (val result = keepaState.result) {
                    is KeepaHistoryResult.Success -> {
                        val chartBitmap = remember(result.chartPngBytes) {
                            result.chartPngBytes?.let { bytes ->
                                runCatching {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                }.getOrNull()
                            }
                        }

                        if (chartBitmap != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(
                                        (chartBitmap.width.toFloat() / chartBitmap.height.coerceAtLeast(1).toFloat())
                                            .coerceIn(1.4f, 2.5f),
                                    ),
                            ) {
                                Image(
                                    bitmap = chartBitmap,
                                    contentDescription = "Grafico storico prezzi Keepa per $asin",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(4.dp),
                                )
                            }
                        }

                        val stats = listOfNotNull(
                            result.lowestPrice?.let { "Min" to it },
                            result.averagePrice?.let { "Medio" to it },
                            result.highestPrice?.let { "Max" to it },
                        )
                        if (stats.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                stats.forEach { (label, value) ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    ) {
                                        Text(
                                            text = "$label: $value",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    is KeepaHistoryResult.Unavailable -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = result.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = onRetry,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("Riprova", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeepaLegendDot(color = Color(0xFFFF9900), label = "Amazon")
                KeepaLegendDot(color = Color(0xFF3B82F6), label = "Nuovo")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    onClick = onOpenKeepa,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Keepa",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                TextButton(
                    onClick = onOpenCamel,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "Camel",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeepaLegendDot(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExternalSiteFallbackMessage(
    message: String,
    onRetry: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onRetry,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Riprova", style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = onOpenBrowser,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Apri nel browser", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ExternalSiteBrandLogo(
    site: ExternalPriceSite,
    isDark: Boolean,
) {
    when (site) {
        ExternalPriceSite.AMAZON_IT -> {
            val letterColor = if (isDark) Color(0xFFF8FAFC) else Color(0xFF111827)
            val smileColor = Color(0xFFFF9900)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "a",
                    fontSize = 19.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = letterColor,
                )
                Canvas(
                    modifier = Modifier
                        .width(24.dp)
                        .height(8.dp),
                ) {
                    val stroke = Stroke(
                        width = 2.2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                    val smilePath = Path().apply {
                        moveTo(size.width * 0.08f, size.height * 0.25f)
                        quadraticTo(
                            size.width * 0.48f,
                            size.height * 1.10f,
                            size.width * 0.88f,
                            size.height * 0.30f,
                        )
                    }
                    drawPath(smilePath, color = smileColor, style = stroke)
                    val arrowHead = Path().apply {
                        moveTo(size.width * 0.74f, size.height * 0.16f)
                        lineTo(size.width * 0.92f, size.height * 0.26f)
                        lineTo(size.width * 0.84f, size.height * 0.72f)
                    }
                    drawPath(arrowHead, color = smileColor, style = stroke)
                }
            }
        }

        ExternalPriceSite.TROVAPREZZI -> {
            val blue = if (isDark) Color(0xFF60A5FA) else Color(0xFF1D63ED)
            val orange = Color(0xFFF97316)
            Box(
                modifier = Modifier
                    .size(29.dp)
                    .background(blue.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = blue,
                        radius = size.minDimension * 0.42f,
                        style = Stroke(width = 1.8.dp.toPx()),
                    )
                    drawCircle(
                        color = orange,
                        radius = 2.8.dp.toPx(),
                        center = Offset(size.width * 0.78f, size.height * 0.24f),
                    )
                }
                Text(
                    text = "tp",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = blue,
                )
            }
        }

        ExternalPriceSite.IDEALO -> {
            val badgeBg = if (isDark) Color(0xFF1E3A8A) else Color(0xFF0A3761)
            val dotYellow = Color(0xFFFACC15)
            Box(
                modifier = Modifier
                    .size(29.dp)
                    .background(badgeBg, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(
                        color = dotYellow,
                        radius = 2.8.dp.toPx(),
                        center = Offset(size.width * 0.50f, size.height * 0.24f),
                    )
                }
                Text(
                    text = "ı",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }

        ExternalPriceSite.DUNGEONDICE -> {
            val ddGreen = if (isDark) Color(0xFF4ADE80) else Color(0xFF56A832)
            val ddOrange = if (isDark) Color(0xFFFB923C) else Color(0xFFEA7A1E)
            Box(
                modifier = Modifier.size(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val leftHalf = Path().apply {
                        moveTo(w * 0.50f, h * 0.06f)
                        lineTo(w * 0.10f, h * 0.28f)
                        lineTo(w * 0.10f, h * 0.72f)
                        lineTo(w * 0.50f, h * 0.94f)
                        close()
                    }
                    val rightHalf = Path().apply {
                        moveTo(w * 0.50f, h * 0.06f)
                        lineTo(w * 0.90f, h * 0.28f)
                        lineTo(w * 0.90f, h * 0.72f)
                        lineTo(w * 0.50f, h * 0.94f)
                        close()
                    }
                    drawPath(leftHalf, color = ddGreen.copy(alpha = 0.16f))
                    drawPath(rightHalf, color = ddOrange.copy(alpha = 0.16f))

                    val leftOutline = Path().apply {
                        moveTo(w * 0.50f, h * 0.06f)
                        lineTo(w * 0.10f, h * 0.28f)
                        lineTo(w * 0.10f, h * 0.72f)
                        lineTo(w * 0.50f, h * 0.94f)
                    }
                    val rightOutline = Path().apply {
                        moveTo(w * 0.50f, h * 0.06f)
                        lineTo(w * 0.90f, h * 0.28f)
                        lineTo(w * 0.90f, h * 0.72f)
                        lineTo(w * 0.50f, h * 0.94f)
                    }
                    val stroke = Stroke(
                        width = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                    drawPath(leftOutline, color = ddGreen, style = stroke)
                    drawPath(rightOutline, color = ddOrange, style = stroke)
                }
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "D",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = ddGreen,
                    )
                    Text(
                        text = "D",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        color = ddOrange,
                    )
                }
            }
        }
    }
}

private fun externalSiteAccentColor(site: ExternalPriceSite, isDark: Boolean): Color = when (site) {
    ExternalPriceSite.AMAZON_IT -> if (isDark) Color(0xFFFFB74D) else Color(0xFFD97706)
    ExternalPriceSite.TROVAPREZZI -> if (isDark) Color(0xFF60A5FA) else Color(0xFF1D63ED)
    ExternalPriceSite.IDEALO -> if (isDark) Color(0xFFFACC15) else Color(0xFF0A3761)
    ExternalPriceSite.DUNGEONDICE -> if (isDark) Color(0xFF4ADE80) else Color(0xFF56A832)
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
                ExternalPriceCompareCard(
                    title = "7 Wonders: Dice",
                    onOpenUrl = {},
                    initialSelectedSite = ExternalPriceSite.AMAZON_IT,
                    initialResults = mapOf(
                        ExternalPriceSite.AMAZON_IT to ExternalScrapeResult.Success(
                            listOf(
                                ScrapedOfferItem(
                                    title = "7 Wonders: Dice",
                                    price = "19,99 €",
                                    originalPrice = "29,99 €",
                                    url = "https://www.amazon.it/dp/B0FSKP6GL4",
                                    asin = "B0FSKP6GL4",
                                    isPrime = true,
                                    deliveryOrAvailability = "domani 23 nov",
                                ),
                            ),
                        ),
                    ),
                    initialKeepaResult = KeepaHistoryResult.Success(
                        chartPngBytes = null,
                        lowestPrice = "19,99 €",
                        averagePrice = "24,50 €",
                        highestPrice = "29,99 €",
                    ),
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
