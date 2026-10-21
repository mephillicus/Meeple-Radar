package com.meepleradar.data.repo

import com.meepleradar.data.model.Game
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import java.time.LocalDate

fun keyOf(listing: Listing): String = when {
    listing.gameId != null -> "game:${listing.gameId}"
    listing.productId != null -> "product:${listing.productId}"
    else -> "listing:${listing.id}"
}

fun bestEurPrice(offers: List<Offer>): Price? =
    offers.mapNotNull { it.price }
        .filter { it.currency == "EUR" }
        .minByOrNull { it.amount }

fun buildIncomingItems(
    listings: List<Listing>,
    games: Map<Long, Game>,
    sources: Map<String, Source>,
    ignoredKeys: Set<String>,
    today: LocalDate,
    sourceIds: Set<String>? = null,
    includeUndated: Boolean = true,
    includePriceless: Boolean = true,
    products: Map<Long, Product> = emptyMap(),
): List<IncomingItem> =
    listings
        .filter { sourceIds == null || it.sourceId in sourceIds }
        .groupBy { keyOf(it) }
        .filter { (key, groupListings) ->
            key !in ignoredKeys && groupListings.none { "listing:${it.id}" in ignoredKeys }
        }
        .map { (key, groupListings) ->
            val game = groupListings.firstNotNullOfOrNull { listing ->
                listing.gameId?.let { games[it] }
            }
            val product = groupListings.firstNotNullOfOrNull { listing ->
                listing.productId?.let { products[it] }
            }
            val releaseDate = product?.releaseDate ?: groupListings.mapNotNull { it.releaseDate }.minOrNull()
            val offers = groupListings.map { listing ->
                Offer(
                    sourceId = listing.sourceId,
                    sourceName = sources[listing.sourceId]?.name ?: listing.sourceId,
                    price = if (listing.price != null && listing.currency != null) {
                        Price(listing.price, listing.currency)
                    } else {
                        null
                    },
                    url = listing.url,
                    availability = listing.availability,
                )
            }
            val customDescription = game?.description?.takeIf { it.isNotBlank() }
                ?: product?.description?.takeIf { it.isNotBlank() }
                ?: groupListings.firstNotNullOfOrNull { it.description?.takeIf(String::isNotBlank) }
            IncomingItem(
                key = key,
                title = game?.name ?: product?.title ?: groupListings.first().title,
                imageUrl = game?.imageUrl ?: product?.imageUrl ?: groupListings.firstNotNullOfOrNull { it.imageUrl },
                releaseDate = releaseDate,
                kind = if (releaseDate != null) ItemKind.UPCOMING else ItemKind.NEW_UNDATED,
                game = game,
                offers = offers,
                customDescription = customDescription,
            )
        }
        .filter {
            val isNotPast = it.releaseDate == null || !it.releaseDate.isBefore(today)
            val isUndatedAllowed = includeUndated || it.releaseDate != null
            val isPricelessAllowed = includePriceless || it.offers.any { offer -> offer.price != null }
            isNotPast && isUndatedAllowed && isPricelessAllowed
        }
        .sortedWith(
            compareBy<IncomingItem> { if (it.kind == ItemKind.UPCOMING) 0 else 1 }
                .thenBy { it.releaseDate ?: LocalDate.MAX }
                .thenBy { it.title },
        )
