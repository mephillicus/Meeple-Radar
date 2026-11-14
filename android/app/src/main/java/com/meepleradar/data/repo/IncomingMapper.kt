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
    listing.productId != null -> "product:${listing.productId}"
    listing.gameId != null -> "game:${listing.gameId}"
    else -> "listing:${listing.id}"
}

fun bestEurPrice(offers: List<Offer>): Price? =
    offers.mapNotNull { it.price }
        .filter { it.currency == "EUR" }
        .minByOrNull { it.amount }

private val ACCESSORY_TITLE_REGEX = Regex(
    "(?i)\\b(bustin[ae]|sleeves?|bigliett[oi]\\b.*augur|deck\\s*box|portamazzo|playmat|tappetino|dragon\\s*shield|ultimate\\s*guard|mayday|paladin|sapphire|arcane\\s*tinmen)\\b"
)

private fun Listing.isAccessory(): Boolean =
    ACCESSORY_TITLE_REGEX.containsMatchIn(title)

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
    newlyAddedListingIds: Set<Long> = emptySet(),
    seenKeys: Set<String> = emptySet(),
): List<IncomingItem> =
    listings
        .filter { (sourceIds == null || it.sourceId in sourceIds) && !it.isAccessory() }
        .groupBy { keyOf(it) }
        .filter { (key, groupListings) ->
            key !in ignoredKeys && groupListings.none { listing ->
                "listing:${listing.id}" in ignoredKeys ||
                    listing.gameId?.let { "game:$it" in ignoredKeys } == true ||
                    listing.productId?.let { "product:$it" in ignoredKeys } == true
            }
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
            val customDescription = product?.description?.takeIf { it.isNotBlank() }
                ?: game?.description?.takeIf { it.isNotBlank() }
                ?: groupListings.firstNotNullOfOrNull { it.description?.takeIf(String::isNotBlank) }
            val bggSelectionKeys = buildList {
                add(key)
                groupListings.forEach { listing ->
                    listing.gameId?.let { add("game:$it") }
                    listing.productId?.let { add("product:$it") }
                    add("listing:${listing.id}")
                }
                game?.bggId?.let { add("bgg:$it") }
            }.distinct()
            val preferredListing = groupListings.firstOrNull {
                it.sourceId != "meeple_app" &&
                    it.title.isNotBlank() &&
                    game?.name?.equals(it.title, ignoreCase = true) == false
            } ?: groupListings.firstOrNull {
                it.sourceId != "meeple_app" && it.title.isNotBlank()
            } ?: groupListings.first()
            val itemTitle = preferredListing.title.takeIf { it.isNotBlank() }
                ?: product?.title?.takeIf { it.isNotBlank() }
                ?: game?.name
                ?: ""
            val lookupTitle = product?.title?.takeIf(String::isNotBlank)
                ?: preferredListing.title.takeIf(String::isNotBlank)
                ?: groupListings.first().title
            val isNewlyAdded = groupListings.any { listing ->
                listing.id in newlyAddedListingIds || isWithin24Hours(listing.firstSeenAt, today)
            }
            val isSeen = key in seenKeys || bggSelectionKeys.any { it in seenKeys }
            IncomingItem(
                key = key,
                title = itemTitle,
                imageUrl = game?.imageUrl ?: product?.imageUrl ?: groupListings.firstNotNullOfOrNull { it.imageUrl },
                releaseDate = releaseDate,
                kind = if (releaseDate != null) ItemKind.UPCOMING else ItemKind.NEW_UNDATED,
                game = game,
                offers = offers,
                customDescription = customDescription,
                lookupTitle = lookupTitle,
                bggSelectionKeys = bggSelectionKeys,
                isNewlyAdded = isNewlyAdded,
                isSeen = isSeen,
            )
        }
        .filter {
            val isUndatedAllowed = includeUndated || it.releaseDate != null
            val isPricelessAllowed = includePriceless || it.offers.any { offer -> offer.price != null }
            isUndatedAllowed && isPricelessAllowed
        }
        .sortedBy { it.title.lowercase(java.util.Locale.ROOT) }

private fun isWithin24Hours(firstSeenAtStr: String?, today: LocalDate): Boolean {
    if (firstSeenAtStr.isNullOrBlank()) return false
    return runCatching {
        val dateStr = firstSeenAtStr.replace(" ", "T")
        val listingDate = if (dateStr.length >= 10) {
            LocalDate.parse(dateStr.take(10))
        } else {
            java.time.LocalDateTime.parse(dateStr.take(19)).toLocalDate()
        }
        !listingDate.isBefore(today.minusDays(1)) && !listingDate.isAfter(today)
    }.getOrDefault(false)
}

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
    if (host == "boardgamegeek.com" || host.endsWith(".boardgamegeek.com") || host == "bgg.cc") return null
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
