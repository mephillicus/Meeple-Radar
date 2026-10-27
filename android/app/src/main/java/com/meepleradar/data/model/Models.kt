package com.meepleradar.data.model

import java.time.LocalDate

enum class Action { FAVORITE, UNFAVORITED, IGNORED }

enum class ItemKind { UPCOMING, NEW_UNDATED }

data class Source(val id: String, val name: String)

data class Game(
    val id: Long,
    val name: String,
    val kind: String,
    val bggId: Int?,
    val bggRating: Double?,
    val bggRank: Int?,
    val imageUrl: String?,
    val description: String? = null,
)

data class Listing(
    val id: Long,
    val sourceId: String,
    val title: String,
    val url: String,
    val price: Double?,
    val currency: String?,
    val availability: String?,
    val releaseDate: LocalDate?,
    val imageUrl: String?,
    val gameId: Long?,
    val matchConfidence: Double?,
    val productId: Long? = null,
    val description: String? = null,
)

data class Product(
    val id: Long,
    val title: String?,
    val imageUrl: String?,
    val releaseDate: LocalDate?,
    val description: String? = null,
    val url: String? = null,
    val price: Double? = null,
    val currency: String? = null,
    val publisher: String? = null,
)

data class Price(val amount: Double, val currency: String)

data class Offer(
    val sourceId: String,
    val sourceName: String,
    val price: Price?,
    val url: String,
    val availability: String?,
    val releaseDate: LocalDate? = null,
)

data class IncomingItem(
    val key: String,
    val title: String,
    val imageUrl: String?,
    val releaseDate: LocalDate?,
    val kind: ItemKind,
    val game: Game?,
    val offers: List<Offer>,
    val customDescription: String? = null,
    val lookupTitle: String? = null,
    val bggSelectionKeys: List<String> = emptyList(),
)
