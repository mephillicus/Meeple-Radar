package com.meepleradar.data.local.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import java.time.LocalDate
import java.time.format.DateTimeParseException

@Entity(
    tableName = "listings",
    indices = [
        Index(value = ["game_id"]),
        Index(value = ["product_id"]),
        Index(value = ["source_id"]),
        Index(value = ["release_date"]),
    ],
)
data class ListingEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    val url: String,
    val price: Double?,
    val currency: String?,
    val availability: String?,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "game_id") val gameId: Long?,
    @ColumnInfo(name = "match_confidence") val matchConfidence: Double?,
    @ColumnInfo(name = "product_id") val productId: Long?,
    val description: String?,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: String?,
    @ColumnInfo(name = "reprint_date") val reprintDate: String? = null,
) {
    fun toDomain() = Listing(
        id = id,
        sourceId = sourceId,
        title = title,
        url = url,
        price = price,
        currency = currency,
        availability = availability,
        releaseDate = releaseDate.asLocalDateOrNull(),
        imageUrl = imageUrl,
        gameId = gameId,
        matchConfidence = matchConfidence,
        productId = productId,
        description = description,
        firstSeenAt = firstSeenAt,
        updatedAt = updatedAt,
        reprintDate = reprintDate.asLocalDateOrNull(),
    )

    companion object {
        fun from(listing: Listing) = ListingEntity(
            id = listing.id,
            sourceId = listing.sourceId,
            title = listing.title,
            url = listing.url,
            price = listing.price,
            currency = listing.currency,
            availability = listing.availability,
            releaseDate = listing.releaseDate?.toString(),
            imageUrl = listing.imageUrl,
            gameId = listing.gameId,
            matchConfidence = listing.matchConfidence,
            productId = listing.productId,
            description = listing.description,
            firstSeenAt = listing.firstSeenAt,
            updatedAt = listing.updatedAt,
            reprintDate = listing.reprintDate?.toString(),
        )
    }
}

@Entity(
    tableName = "games",
    indices = [
        Index(value = ["bgg_id"]),
    ],
)
data class GameEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val kind: String,
    @ColumnInfo(name = "bgg_id") val bggId: Int?,
    @ColumnInfo(name = "bgg_rating") val bggRating: Double?,
    @ColumnInfo(name = "bgg_rank") val bggRank: Int?,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    val description: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: String?,
) {
    fun toDomain() = Game(
        id = id,
        name = name,
        kind = kind,
        bggId = bggId,
        bggRating = bggRating,
        bggRank = bggRank,
        imageUrl = imageUrl,
        description = description,
        updatedAt = updatedAt,
    )

    companion object {
        fun from(game: Game) = GameEntity(
            id = game.id,
            name = game.name,
            kind = game.kind,
            bggId = game.bggId,
            bggRating = game.bggRating,
            bggRank = game.bggRank,
            imageUrl = game.imageUrl,
            description = game.description,
            updatedAt = game.updatedAt,
        )
    }
}

@Entity(
    tableName = "products",
    indices = [
        Index(value = ["game_id"]),
    ],
)
data class ProductEntity(
    @PrimaryKey val id: Long,
    val title: String?,
    @ColumnInfo(name = "image_url") val imageUrl: String?,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    val description: String?,
    val url: String?,
    val price: Double?,
    val currency: String?,
    val publisher: String?,
    @ColumnInfo(name = "game_id") val gameId: Long?,
    @ColumnInfo(name = "updated_at") val updatedAt: String?,
    @ColumnInfo(name = "reprint_date") val reprintDate: String? = null,
) {
    fun toDomain() = Product(
        id = id,
        title = title,
        imageUrl = imageUrl,
        releaseDate = releaseDate.asLocalDateOrNull(),
        description = description,
        url = url,
        price = price,
        currency = currency,
        publisher = publisher,
        gameId = gameId,
        updatedAt = updatedAt,
        reprintDate = reprintDate.asLocalDateOrNull(),
    )

    companion object {
        fun from(product: Product) = ProductEntity(
            id = product.id,
            title = product.title,
            imageUrl = product.imageUrl,
            releaseDate = product.releaseDate?.toString(),
            description = product.description,
            url = product.url,
            price = product.price,
            currency = product.currency,
            publisher = product.publisher,
            gameId = product.gameId,
            updatedAt = product.updatedAt,
            reprintDate = product.reprintDate?.toString(),
        )
    }
}

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
) {
    fun toDomain() = Source(id = id, name = name)

    companion object {
        fun from(source: Source) = SourceEntity(id = source.id, name = source.name)
    }
}

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    @ColumnInfo(name = "last_modified_timestamp") val lastModifiedTimestamp: Long = 0L,
    @ColumnInfo(name = "newly_added_count") val newlyAddedCount: Int = 0,
    @ColumnInfo(name = "newly_added_listing_ids") val newlyAddedListingIdsCsv: String = "",
    @ColumnInfo(name = "last_incremental_sync_at") val lastIncrementalSyncAt: String? = null,
    @ColumnInfo(name = "last_full_sync_at") val lastFullSyncAt: Long = 0L,
) {
    fun newlyAddedIds(): Set<Long> =
        if (newlyAddedListingIdsCsv.isBlank()) {
            emptySet()
        } else {
            newlyAddedListingIdsCsv.split(',').mapNotNull { it.toLongOrNull() }.toSet()
        }

    companion object {
        const val SINGLETON_ID = 1
    }
}

private fun String?.asLocalDateOrNull(): LocalDate? {
    if (isNullOrBlank()) return null
    return try {
        LocalDate.parse(this)
    } catch (_: DateTimeParseException) {
        null
    }
}
