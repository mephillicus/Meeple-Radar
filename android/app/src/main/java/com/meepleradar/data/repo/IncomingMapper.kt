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
                    releaseDate = listing.releaseDate ?: releaseDate,
                )
            } + listOfNotNull(product?.publisherOffer(releaseDate))
            val customDescription = game?.description?.takeIf { it.isNotBlank() }
                ?: product?.description?.takeIf { it.isNotBlank() }
                ?: groupListings.firstNotNullOfOrNull { it.description?.takeIf(String::isNotBlank) }
            val bggSelectionKeys = buildList {
                add(key)
                groupListings.forEach { listing ->
                    listing.gameId?.let { add("game:$it") }
                    listing.productId?.let { add("product:$it") }
                    add("listing:${listing.id}")
                }
            }.distinct()
            IncomingItem(
                key = key,
                title = game?.name ?: product?.title ?: groupListings.first().title,
                imageUrl = game?.imageUrl ?: product?.imageUrl ?: groupListings.firstNotNullOfOrNull { it.imageUrl },
                releaseDate = releaseDate,
                kind = if (releaseDate != null) ItemKind.UPCOMING else ItemKind.NEW_UNDATED,
                game = game,
                offers = offers,
                customDescription = customDescription,
                lookupTitle = product?.title?.takeIf(String::isNotBlank) ?: groupListings.first().title,
                bggSelectionKeys = bggSelectionKeys,
            )
        }
        .filter {
            val isUndatedAllowed = includeUndated || it.releaseDate != null
            val isPricelessAllowed = includePriceless || it.offers.any { offer -> offer.price != null }
            isUndatedAllowed && isPricelessAllowed
        }
        .sortedBy { it.title.lowercase(java.util.Locale.ROOT) }

private fun Product.publisherOffer(releaseDate: LocalDate?): Offer? {
    val offerUrl = url ?: return null
    val offerPrice = price ?: return null
    val offerCurrency = currency?.takeIf(String::isNotBlank) ?: return null
    val offerPublisher = publisher?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (!offerPrice.isFinite()) return null
    val host = try {
        java.net.URI(offerUrl).host
    } catch (_: java.net.URISyntaxException) {
        null
    }?.removePrefix("www.")?.lowercase(java.util.Locale.ROOT) ?: return null
    val publisherTokens = offerPublisher.lowercase(java.util.Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 4 }
    if (publisherTokens.none { token -> host.contains(token) }) return null
    return Offer(
        sourceId = "product:$id",
        sourceName = offerPublisher,
        price = Price(offerPrice, offerCurrency),
        url = offerUrl,
        availability = null,
        releaseDate = releaseDate,
    )
}
