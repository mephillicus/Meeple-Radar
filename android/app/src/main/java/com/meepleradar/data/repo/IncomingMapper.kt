package com.meepleradar.data.repo

import com.meepleradar.data.model.Game
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Offer
import com.meepleradar.data.model.Price
import com.meepleradar.data.model.Source
import java.time.LocalDate

fun keyOf(listing: Listing): String =
    if (listing.gameId != null) "game:${listing.gameId}" else "listing:${listing.id}"

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
): List<IncomingItem> =
    listings
        .groupBy { keyOf(it) }
        .filterKeys { it !in ignoredKeys }
        .map { (key, groupListings) ->
            val game = groupListings.firstNotNullOfOrNull { listing ->
                listing.gameId?.let { games[it] }
            }
            val releaseDate = groupListings.mapNotNull { it.releaseDate }.minOrNull()
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
            IncomingItem(
                key = key,
                title = game?.name ?: groupListings.first().title,
                imageUrl = game?.imageUrl ?: groupListings.firstNotNullOfOrNull { it.imageUrl },
                releaseDate = releaseDate,
                kind = if (releaseDate != null) ItemKind.UPCOMING else ItemKind.NEW_UNDATED,
                game = game,
                offers = offers,
            )
        }
        .filter { it.releaseDate == null || !it.releaseDate.isBefore(today) }
        .sortedWith(
            compareBy<IncomingItem> { if (it.kind == ItemKind.UPCOMING) 0 else 1 }
                .thenBy { it.releaseDate ?: LocalDate.MAX }
                .thenBy { it.title },
        )
