package com.meepleradar.data.local

import com.meepleradar.data.local.db.GameEntity
import com.meepleradar.data.local.db.ListingEntity
import com.meepleradar.data.local.db.ProductEntity
import com.meepleradar.data.local.db.SourceEntity
import com.meepleradar.data.local.db.SyncMetaEntity
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.Listing
import com.meepleradar.data.model.Product
import com.meepleradar.data.model.Source
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogEntitiesAndCacheTest {

    @Test
    fun `ProductEntity preserva tutti i campi inclusi url price currency publisher e updatedAt`() {
        val original = Product(
            id = 42L,
            title = "Citadels",
            imageUrl = "https://img.example/citadels.jpg",
            releaseDate = LocalDate.of(2026, 12, 1),
            description = "Gioco di carte",
            url = "https://www.asmodee.it/citadels",
            price = 29.90,
            currency = "EUR",
            publisher = "Asmodee",
            gameId = 7L,
            updatedAt = "2026-11-20T12:00:00Z",
            reprintDate = LocalDate.of(2027, 2, 15),
        )

        val roundTrip = ProductEntity.from(original).toDomain()

        assertEquals(original, roundTrip)
    }

    @Test
    fun `ListingEntity GameEntity SourceEntity e SyncMetaEntity eseguono round-trip completo`() {
        val listing = Listing(
            id = 10L,
            sourceId = "dungeondice",
            title = "Brass: Birmingham",
            url = "https://dungeondice.it/brass",
            price = 64.90,
            currency = "EUR",
            availability = "in_stock",
            releaseDate = LocalDate.of(2026, 11, 25),
            imageUrl = "https://img/brass.jpg",
            gameId = 3L,
            matchConfidence = 0.95,
            productId = 19L,
            description = "Classico gestionale",
            firstSeenAt = "2026-11-18T08:00:00Z",
            updatedAt = "2026-11-20T09:00:00Z",
            reprintDate = LocalDate.of(2027, 4, 10),
        )
        val game = Game(
            id = 3L,
            name = "Brass: Birmingham",
            kind = "base",
            bggId = 224517,
            bggRating = 8.6,
            bggRank = 1,
            imageUrl = "https://img/brass.jpg",
            description = "Descrizione",
            updatedAt = "2026-11-20T09:05:00Z",
        )
        val source = Source("dungeondice", "DungeonDice")
        val meta = SyncMetaEntity(
            lastModifiedTimestamp = 123456L,
            newlyAddedCount = 2,
            newlyAddedListingIdsCsv = "10,20",
            lastIncrementalSyncAt = "2026-11-20T09:05:00Z",
            lastFullSyncAt = 123000L,
        )

        assertEquals(listing, ListingEntity.from(listing).toDomain())
        assertEquals(game, GameEntity.from(game).toDomain())
        assertEquals(source, SourceEntity.from(source).toDomain())
        assertEquals(setOf(10L, 20L), meta.newlyAddedIds())
    }

    @Test
    fun `mergeById aggiorna record esistenti e aggiunge i nuovi in ordine crescente di id`() {
        val existing = listOf(
            Game(1L, "Uno", "base", null, null, null, null),
            Game(3L, "Tre", "base", null, null, null, null),
        )
        val delta = listOf(
            Game(3L, "Tre Aggiornato", "base", 33, 7.5, 100, null),
            Game(2L, "Due", "expansion", 22, null, null, null),
        )

        val merged = mergeById(existing, delta) { it.id }

        assertEquals(listOf(1L, 2L, 3L), merged.map { it.id })
        assertEquals("Tre Aggiornato", merged.last().name)
        assertEquals("Due", merged[1].name)
    }
}
