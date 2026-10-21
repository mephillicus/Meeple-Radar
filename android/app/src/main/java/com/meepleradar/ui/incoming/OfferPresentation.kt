package com.meepleradar.ui.incoming

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import com.meepleradar.data.model.AvailabilityStatus
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.availabilityStatus
import com.meepleradar.ui.theme.BggGoldDarkText
import com.meepleradar.ui.theme.BggGoldText

private val UnknownAvailabilityDark = Color(0xFF38BDF8)
private val UnknownAvailabilityLight = Color(0xFF0284C7)

fun availabilityLabel(offer: Offer): String? = when (availabilityStatus(offer.availability)) {
    AvailabilityStatus.AVAILABLE -> "Disponibile"
    AvailabilityStatus.UNAVAILABLE -> "Non disponibile"
    AvailabilityStatus.PREORDER -> "In preordine"
    AvailabilityStatus.BACKORDER -> "Ordinabile su richiesta"
    AvailabilityStatus.DISCONTINUED -> "Fuori produzione"
    AvailabilityStatus.UNKNOWN -> offer.availability
        ?.takeIf { it.isNotBlank() }
        ?.let { "Disponibilità da verificare" }
}

fun lowestComparableEuroPrice(offers: List<Offer>): Double? = offers
    .asSequence()
    .filter { it.price?.currency == "EUR" }
    .filter { availabilityStatus(it.availability) !in setOf(AvailabilityStatus.UNAVAILABLE, AvailabilityStatus.DISCONTINUED) }
    .mapNotNull { it.price?.amount?.takeIf { amount -> amount.isFinite() && amount >= 0.0 } }
    .minOrNull()

fun bestComparableEuroOffers(offers: List<Offer>): Set<Offer> {
    val lowestPrice = lowestComparableEuroPrice(offers) ?: return emptySet()
    return offers.filter { offer ->
        offer.price?.currency == "EUR" &&
            offer.price.amount == lowestPrice &&
            availabilityStatus(offer.availability) !in setOf(AvailabilityStatus.UNAVAILABLE, AvailabilityStatus.DISCONTINUED)
    }.toSet()
}

fun orderedOffersForDisplay(offers: List<Offer>): List<Offer> = offers.sortedWith(
    compareBy<Offer> {
        when (availabilityStatus(it.availability)) {
            AvailabilityStatus.UNAVAILABLE, AvailabilityStatus.DISCONTINUED -> 1
            else -> 0
        }
    }
        .thenBy { if (it.price?.currency == "EUR") 0 else 1 }
        .thenBy { it.price?.amount ?: Double.MAX_VALUE }
        .thenBy { it.sourceName },
)

fun bestPricedOffer(offers: List<Offer>): Offer? {
    val eligible = offers.filter { offer ->
        offer.price?.amount?.let { it.isFinite() && it >= 0.0 } == true
    }
    val euroOffers = eligible.filter { it.price?.currency == "EUR" }
    val candidates = euroOffers.ifEmpty {
        val currency = eligible.firstNotNullOfOrNull { it.price?.currency } ?: return null
        eligible.filter { it.price?.currency == currency }
    }
    return candidates.minByOrNull { it.price?.amount ?: Double.MAX_VALUE }
}

@Composable
fun availabilityColor(offer: Offer): Color = when (availabilityStatus(offer.availability)) {
    AvailabilityStatus.AVAILABLE -> MaterialTheme.colorScheme.tertiary
    AvailabilityStatus.UNAVAILABLE, AvailabilityStatus.DISCONTINUED -> MaterialTheme.colorScheme.error
    AvailabilityStatus.PREORDER -> MaterialTheme.colorScheme.primary
    AvailabilityStatus.BACKORDER -> MaterialTheme.colorScheme.secondary
    AvailabilityStatus.UNKNOWN -> unknownAvailabilityColor(offer)
}

@Composable
fun offerPriceColor(offer: Offer, offers: List<Offer>): Color {
    return when (availabilityStatus(offer.availability)) {
        AvailabilityStatus.UNAVAILABLE, AvailabilityStatus.DISCONTINUED -> MaterialTheme.colorScheme.error
        AvailabilityStatus.PREORDER -> MaterialTheme.colorScheme.primary
        AvailabilityStatus.BACKORDER -> MaterialTheme.colorScheme.secondary
        AvailabilityStatus.UNKNOWN -> unknownAvailabilityColor(offer)
        else -> {
            val lowestPrice = lowestComparableEuroPrice(offers)
            when {
                offer.price?.currency == "EUR" && lowestPrice != null && offer.price.amount == lowestPrice ->
                    MaterialTheme.colorScheme.tertiary
                offer.price?.currency == "EUR" && lowestPrice != null ->
                    if (androidx.compose.foundation.isSystemInDarkTheme()) BggGoldDarkText else BggGoldText
                availabilityStatus(offer.availability) == AvailabilityStatus.AVAILABLE ->
                    MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    }
}

@Composable
private fun unknownAvailabilityColor(offer: Offer): Color {
    if (offer.availability.isNullOrBlank()) return MaterialTheme.colorScheme.onSurfaceVariant
    return if (isSystemInDarkTheme()) UnknownAvailabilityDark else UnknownAvailabilityLight
}
