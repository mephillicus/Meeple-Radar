package com.meepleradar.data.model

import java.time.LocalDate

enum class Action { FAVORITE, UNFAVORITED, IGNORED, SEEN }

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
    val updatedAt: String? = null,
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
    val firstSeenAt: String? = null,
    val updatedAt: String? = null,
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
    val gameId: Long? = null,
    val updatedAt: String? = null,
)

data class Price(val amount: Double, val currency: String)

data class Offer(
    val sourceId: String,
    val sourceName: String,
    val price: Price?,
    val url: String,
    val availability: String?,
    val releaseDate: LocalDate? = null,
    val listingId: Long? = null,
    val priceDropPct: Double? = null,
)

data class ListingBestPrice(
    val listingId: Long,
    val currentPrice: Double?,
    val currency: String?,
    val currentAvailability: String?,
    val minPrice: Double?,
    val previousPrice: Double?,
    val priceDropPct: Double?,
    val recordedAt: String?,
)

data class PriceHistoryEntry(
    val id: Long,
    val listingId: Long,
    val price: Double?,
    val currency: String?,
    val availability: String?,
    val recordedAt: String,
)

enum class UserAlertType {
    BACK_IN_STOCK,
    PRICE_DROP,
    NEW_EXPANSION,
}

data class UserAlert(
    val id: Long,
    val alertType: UserAlertType,
    val gameId: Long?,
    val productId: Long?,
    val listingId: Long?,
    val title: String,
    val detailText: String? = null,
    val dedupeKey: String,
    val readAt: String? = null,
    val createdAt: String,
) {
    val isUnread: Boolean get() = readAt.isNullOrBlank()

    fun itemKeyOrNull(): String? = when {
        productId != null -> "product:$productId"
        gameId != null -> "game:$gameId"
        listingId != null -> "listing:$listingId"
        else -> null
    }
}

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
    val isNewlyAdded: Boolean = false,
    val isSeen: Boolean = false,
)

data class GameSavePreview(
    val listings: List<CandidateListingLink> = emptyList(),
)

data class CandidateListingLink(
    val id: Long,
    val sourceId: String,
    val sourceName: String,
    val title: String,
    val price: Double?,
    val currency: String?,
    val currentGameId: Long?,
    val currentGameName: String?,
    val isUnlinked: Boolean,
    val isSelected: Boolean = isUnlinked,
)
