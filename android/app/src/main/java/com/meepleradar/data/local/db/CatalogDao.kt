package com.meepleradar.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface CatalogDao {

    @Query("SELECT * FROM listings ORDER BY id ASC")
    fun getAllListings(): List<ListingEntity>

    @Query("SELECT * FROM games ORDER BY id ASC")
    fun getAllGames(): List<GameEntity>

    @Query("SELECT * FROM sources ORDER BY id ASC")
    fun getAllSources(): List<SourceEntity>

    @Query("SELECT * FROM products ORDER BY id ASC")
    fun getAllProducts(): List<ProductEntity>

    @Query("SELECT * FROM sync_meta WHERE id = :id LIMIT 1")
    fun getSyncMeta(id: Int = SyncMetaEntity.SINGLETON_ID): SyncMetaEntity?

    @Upsert
    fun upsertListings(listings: List<ListingEntity>)

    @Upsert
    fun upsertGame(game: GameEntity)

    @Upsert
    fun upsertGames(games: List<GameEntity>)

    @Upsert
    fun upsertSources(sources: List<SourceEntity>)

    @Upsert
    fun upsertProducts(products: List<ProductEntity>)

    @Upsert
    fun upsertSyncMeta(meta: SyncMetaEntity)

    @Query("DELETE FROM listings")
    fun clearListings()

    @Query("DELETE FROM games")
    fun clearGames()

    @Query("DELETE FROM sources")
    fun clearSources()

    @Query("DELETE FROM products")
    fun clearProducts()

    @Query("UPDATE listings SET game_id = :gameId, product_id = :productId WHERE id = :listingId")
    fun updateListingLink(listingId: Long, gameId: Long, productId: Long?)

    @Transaction
    fun replaceAllCatalog(
        listings: List<ListingEntity>,
        games: List<GameEntity>,
        sources: List<SourceEntity>,
        products: List<ProductEntity>,
        meta: SyncMetaEntity,
    ) {
        clearListings()
        clearGames()
        clearSources()
        clearProducts()
        if (listings.isNotEmpty()) upsertListings(listings)
        if (games.isNotEmpty()) upsertGames(games)
        if (sources.isNotEmpty()) upsertSources(sources)
        if (products.isNotEmpty()) upsertProducts(products)
        upsertSyncMeta(meta)
    }

    @Transaction
    fun applyDelta(
        deltaListings: List<ListingEntity>,
        deltaGames: List<GameEntity>,
        sources: List<SourceEntity>,
        deltaProducts: List<ProductEntity>,
        meta: SyncMetaEntity,
    ) {
        if (deltaListings.isNotEmpty()) upsertListings(deltaListings)
        if (deltaGames.isNotEmpty()) upsertGames(deltaGames)
        if (sources.isNotEmpty()) {
            clearSources()
            upsertSources(sources)
        }
        if (deltaProducts.isNotEmpty()) upsertProducts(deltaProducts)
        upsertSyncMeta(meta)
    }

    @Transaction
    fun upsertGameAndOptionalListing(
        game: GameEntity,
        linkedListingId: Long?,
        linkedProductId: Long?,
        meta: SyncMetaEntity,
    ) {
        upsertGame(game)
        if (linkedListingId != null) {
            updateListingLink(linkedListingId, game.id, linkedProductId)
        }
        upsertSyncMeta(meta)
    }
}
