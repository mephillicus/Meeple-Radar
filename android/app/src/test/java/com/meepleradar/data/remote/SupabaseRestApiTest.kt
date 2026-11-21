package com.meepleradar.data.remote

import com.meepleradar.data.model.Action
import com.meepleradar.data.model.Game
import com.meepleradar.data.model.IncomingItem
import com.meepleradar.data.model.ItemKind
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SupabaseRestApiTest {

    private val base = "https://example.supabase.co"
    private val anon = "anon-key"
    private val json = Json { ignoreUnknownKeys = true }

    private class FakeTransport(
        private val getResponses: Map<String, String> = emptyMap(),
        private val patchResponse: String = "",
        private val postResponses: Map<String, String> = emptyMap(),
        private val getFailures: Map<String, IOException> = emptyMap(),
        private val postFailures: Map<String, IOException> = emptyMap(),
    ) : HttpTransport {
        data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

        val calls = mutableListOf<Call>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            calls += Call("GET", url, headers, null)
            getFailures[url]?.let { throw it }
            return getResponses[url] ?: "[]"
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            calls += Call("POST", url, headers, body)
            postFailures[url]?.let { throw it }
            return postResponses[url] ?: ""
        }

        override suspend fun patch(url: String, headers: Map<String, String>, body: String): String {
            calls += Call("PATCH", url, headers, body)
            return patchResponse
        }
    }

    private fun rpc404(rpcName: String) =
        "$base/rest/v1/rpc/$rpcName" to IOException("HTTP 404: PGRST202 Could not find the function public.$rpcName")

    private fun listingJson(vararg rows: String) = rows.joinToString(prefix = "[", postfix = "]", separator = ",")

    @Test
    fun `listings costruisce url ordinato e header apikey e bearer con range`() = runTest {
        val transport = FakeTransport()
        SupabaseRestApi(base, anon, transport, json).listings()

        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertEquals("$base/rest/v1/listing?select=*&order=id.asc", call.url)
        assertEquals(anon, call.headers["apikey"])
        assertEquals("Bearer $anon", call.headers["Authorization"])
        assertEquals("application/json", call.headers["Accept"])
        assertEquals("items", call.headers["Range-Unit"])
        assertEquals("0-999", call.headers["Range"])
    }

    @Test
    fun `query incrementali codificano updated_at e parsano il campo updatedAt`() = runTest {
        val since = "2026-11-20T10:15:30+00:00"
        val encoded = "2026-11-20T10%3A15%3A30%2B00%3A00"
        val listingUrl = "$base/rest/v1/listing?select=*&updated_at=gt.$encoded&order=id.asc"
        val gameUrl = "$base/rest/v1/game?select=*&updated_at=gt.$encoded&order=id.asc"
        val productUrl = "$base/rest/v1/product?select=*&updated_at=gt.$encoded&order=id.asc"

        val transport = FakeTransport(
            getResponses = mapOf(
                listingUrl to """[{"id":1,"source_id":"s1","title":"T","url":"https://u","updated_at":"2026-11-21T08:00:00+00:00"}]""",
                gameUrl to """[{"id":5,"name":"Dune","kind":"base","updated_at":"2026-11-21T08:01:00+00:00"}]""",
                productUrl to """[{"id":10,"title":"Dune","updated_at":"2026-11-21T08:02:00+00:00"}]""",
            ),
        )
        val api = SupabaseRestApi(base, anon, transport, json)

        val listings = api.listingsUpdatedSince(since)
        val games = api.gamesUpdatedSince(since)
        val products = api.productsUpdatedSince(since)

        assertEquals("2026-11-21T08:00:00+00:00", listings.single().updatedAt)
        assertEquals("2026-11-21T08:01:00+00:00", games.single().updatedAt)
        assertEquals("2026-11-21T08:02:00+00:00", products.single().updatedAt)
        assertEquals(listOf(listingUrl, gameUrl, productUrl), transport.calls.map { it.url })
    }

    @Test
    fun `listings recupera anche le pagine oltre il limite Supabase`() = runTest {
        val firstPage = (1..1000).joinToString(prefix = "[", postfix = "]") { id ->
            """{"id":$id,"source_id":"s1","title":"Gioco $id","url":"https://u/$id"}"""
        }
        val transport = object : HttpTransport {
            val ranges = mutableListOf<String>()

            override suspend fun get(url: String, headers: Map<String, String>): String {
                ranges += headers.getValue("Range")
                return if (headers.getValue("Range") == "0-999") {
                    firstPage
                } else {
                    """[{"id":1001,"source_id":"s1","title":"DVgames","url":"https://u/1001"}]"""
                }
            }

            override suspend fun post(url: String, headers: Map<String, String>, body: String): String = ""
        }

        val listings = SupabaseRestApi(base, anon, transport, json).listings()

        assertEquals(1001, listings.size)
        assertEquals("DVgames", listings.last().title)
        assertEquals(listOf("0-999", "1000-1999"), transport.ranges)
    }

    @Test
    fun `listing con release_date null e price null viene mappato senza crash`() = runTest {
        val body = listingJson(
            """{"id":1,"source_id":"s1","title":"T","url":"https://u","price":null,"currency":null,""" +
                """"availability":null,"release_date":null,"image_url":null,"game_id":null,"match_confidence":null}""",
        )
        val transport = FakeTransport(mapOf("$base/rest/v1/listing?select=*&order=id.asc" to body))

        val listing = SupabaseRestApi(base, anon, transport, json).listings().single()

        assertEquals(1L, listing.id)
        assertEquals("s1", listing.sourceId)
        assertNull(listing.price)
        assertNull(listing.currency)
        assertNull(listing.releaseDate)
        assertNull(listing.gameId)
    }

    @Test
    fun `price numerica e price stringa vengono entrambe lette`() = runTest {
        val body = listingJson(
            """{"id":1,"source_id":"s1","title":"A","url":"https://a","price":39.9,"currency":"EUR",""" +
                """"availability":null,"release_date":"2026-11-01","image_url":null,"game_id":5,"match_confidence":0.9,"product_id":42}""",
            """{"id":2,"source_id":"s1","title":"B","url":"https://b","price":"12.50","currency":"EUR",""" +
                """"availability":null,"release_date":null,"image_url":null,"game_id":null,"match_confidence":null}""",
        )
        val transport = FakeTransport(mapOf("$base/rest/v1/listing?select=*&order=id.asc" to body))

        val listings = SupabaseRestApi(base, anon, transport, json).listings()

        assertEquals(39.9, listings[0].price!!, 1e-9)
        assertEquals(12.5, listings[1].price!!, 1e-9)
        assertEquals(0.9, listings[0].matchConfidence!!, 1e-9)
        assertEquals(42L, listings[0].productId)
        assertNull(listings[1].productId)
    }

    @Test
    fun `products costruisce url select star con header apikey e bearer`() = runTest {
        val body = listingJson(
            """{"id":10,"title":"Citadels","url":"https://www.asmodee.it/citadels","price":"39.90","currency":"EUR","publisher":"Asmodee","image_url":"https://img","release_date":"2026-12-01"}""",
        )
        val transport = FakeTransport(mapOf("$base/rest/v1/product?select=*&order=id.asc" to body))
        val products = SupabaseRestApi(base, anon, transport, json).products()

        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertEquals("$base/rest/v1/product?select=*&order=id.asc", call.url)
        assertEquals(1, products.size)
        assertEquals(10L, products[0].id)
        assertEquals("Citadels", products[0].title)
        assertEquals(java.time.LocalDate.of(2026, 12, 1), products[0].releaseDate)
        assertEquals("https://www.asmodee.it/citadels", products[0].url)
        assertEquals(39.9, products[0].price!!, 1e-9)
        assertEquals("EUR", products[0].currency)
        assertEquals("Asmodee", products[0].publisher)
    }

    @Test
    fun `insertAction fa POST su user_action con Prefer return minimal e body corretto`() = runTest {
        val transport = FakeTransport()
        SupabaseRestApi(base, anon, transport, json)
            .insertAction(UserActionRow(gameId = 5, listingId = null, action = Action.FAVORITE))

        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("$base/rest/v1/user_action", call.url)
        assertEquals("application/json", call.headers["Content-Type"])
        assertEquals("resolution=merge-duplicates,return=minimal", call.headers["Prefer"])
        assertEquals(anon, call.headers["apikey"])

        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals(JsonPrimitive(5), body["game_id"])
        assertEquals(JsonNull, body["listing_id"])
        assertEquals(JsonPrimitive("favorite"), body["action"])
    }

    @Test
    fun `linkListingToBgg usa RPC atomica 0013 quando disponibile`() = runTest {
        val details = BoardGameDetails(
            bggId = 99,
            imageUrl = "https://bgg.example/cover.jpg",
            description = "Descrizione BGG",
            yearPublished = 2024,
            minPlayers = 1,
            maxPlayers = 4,
            playingTime = 90,
            minAge = 10,
            designers = listOf("Autore"),
            categories = listOf("Strategia"),
            mechanics = listOf("Carte"),
            expands = listOf(BggLink(10, "Gioco base", null)),
            bggRating = 7.5,
            bggRank = 42,
        )
        val transport = FakeTransport(
            postResponses = mapOf(
                "$base/rest/v1/rpc/link_listing_to_bgg" to """[{"game_id":5,"product_id":123}]""",
            ),
        )

        val (gameId, productId) = SupabaseRestApi(base, anon, transport, json).linkListingToBgg(
            listingId = 7,
            currentGameId = 5,
            details = details,
            fallbackTitle = "Titolo alternativo",
        )

        assertEquals(5L, gameId)
        assertEquals(123L, productId)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("$base/rest/v1/rpc/link_listing_to_bgg", call.url)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals(JsonPrimitive(7), body["p_listing_id"])
        assertEquals(JsonPrimitive(99), body["p_bgg_id"])
        assertEquals(JsonPrimitive("Titolo alternativo"), body["p_title"])
        assertEquals(JsonPrimitive(5), body["p_current_game_id"])
        assertEquals(JsonPrimitive("expansion"), body["p_kind"])
        assertEquals(JsonPrimitive(7.5), body["p_bgg_rating"])
        assertEquals(JsonPrimitive(42), body["p_bgg_rank"])
        assertEquals(JsonPrimitive("https://bgg.example/cover.jpg"), body["p_image_url"])
        assertEquals(JsonPrimitive("Descrizione BGG"), body["p_description"])
    }

    @Test
    fun `linkListingToBgg fallback legacy salva i dati BGG e distingue espansioni e giochi base`() = runTest {
        val details = BoardGameDetails(
            bggId = 99,
            imageUrl = "https://bgg.example/cover.jpg",
            description = "Descrizione BGG",
            yearPublished = 2024,
            minPlayers = 1,
            maxPlayers = 4,
            playingTime = 90,
            minAge = 10,
            designers = listOf("Autore"),
            categories = listOf("Strategia"),
            mechanics = listOf("Carte"),
            expands = listOf(BggLink(10, "Gioco base", null)),
            bggRating = 7.5,
            bggRank = 42,
        )
        val transport = FakeTransport(
            postResponses = mapOf("$base/rest/v1/rpc/upsert_product_for_game" to "123"),
            postFailures = mapOf(rpc404("link_listing_to_bgg")),
        )

        val (_, productId) = SupabaseRestApi(base, anon, transport, json).linkListingToBgg(
            listingId = 7,
            currentGameId = 5,
            details = details,
            fallbackTitle = "Titolo alternativo",
        )
        assertEquals(123L, productId)
        val gamePatch = transport.calls.first { it.url.contains("/game") }
        val gameBody = Json.parseToJsonElement(gamePatch.body!!).jsonObject

        assertEquals("PATCH", gamePatch.method)
        assertEquals("$base/rest/v1/game?id=eq.5", gamePatch.url)
        assertEquals(JsonPrimitive("Titolo alternativo"), gameBody["name"])
        assertEquals(JsonPrimitive("expansion"), gameBody["kind"])
        assertEquals(JsonPrimitive(99), gameBody["bgg_id"])
        assertEquals(JsonPrimitive(7.5), gameBody["bgg_rating"])
        assertEquals(JsonPrimitive(42), gameBody["bgg_rank"])
        assertEquals(JsonPrimitive("https://bgg.example/cover.jpg"), gameBody["image_url"])
        assertTrue(transport.calls.any { it.url.contains("product") })
        val listingPatch = transport.calls.single { it.url.contains("listing?id=eq.7") }
        val listingBody = Json.parseToJsonElement(listingPatch.body!!).jsonObject
        assertEquals(JsonPrimitive(5), listingBody["game_id"])
        assertEquals(JsonPrimitive(123), listingBody["product_id"])
        val productRpc = transport.calls.single { it.url.endsWith("/rpc/upsert_product_for_game") }
        val rpcBody = Json.parseToJsonElement(productRpc.body!!).jsonObject
        assertEquals(JsonNull, rpcBody["p_url"])
        assertEquals(JsonPrimitive("https://bgg.example/cover.jpg"), rpcBody["p_image_url"])
        assertEquals(JsonPrimitive("Descrizione BGG"), rpcBody["p_description"])

        val baseGame = details.copy(bggId = 100, expands = emptyList())
        val baseGameTransport = FakeTransport(
            postResponses = mapOf("$base/rest/v1/rpc/upsert_product_for_game" to "124"),
            postFailures = mapOf(rpc404("link_listing_to_bgg")),
        )
        SupabaseRestApi(base, anon, baseGameTransport, json).linkListingToBgg(
            listingId = 0,
            currentGameId = 5,
            details = baseGame,
            fallbackTitle = "Gioco base",
        )
        val baseGameBody = Json.parseToJsonElement(baseGameTransport.calls.first { it.url.contains("/game") }.body!!).jsonObject
        assertEquals(JsonPrimitive("base"), baseGameBody["kind"])
    }

    @Test
    fun `linkListingToBgg fallback non associa listing quando RPC product manca o non restituisce ID`() = runTest {
        val transport = FakeTransport(
            postFailures = mapOf(rpc404("link_listing_to_bgg")),
        )

        try {
            SupabaseRestApi(base, anon, transport, json).linkListingToBgg(
                listingId = 7,
                currentGameId = 5,
                details = BoardGameDetails(
                    bggId = 99,
                    imageUrl = null,
                    description = null,
                    yearPublished = null,
                    minPlayers = null,
                    maxPlayers = null,
                    playingTime = null,
                    minAge = null,
                    designers = emptyList(),
                    categories = emptyList(),
                    mechanics = emptyList(),
                    expands = emptyList(),
                    bggRating = null,
                    bggRank = null,
                ),
                fallbackTitle = "Gioco non presente",
            )
            fail("L'associazione deve fallire se l'RPC non restituisce un ID product")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("0010_add_upsert_product_for_game.sql"))
        }

        assertTrue(transport.calls.any { it.url.endsWith("/rpc/upsert_product_for_game") })
        assertTrue(transport.calls.none { it.url.contains("listing?id=eq.7") })
        val rpcBody = Json.parseToJsonElement(
            transport.calls.single { it.url.endsWith("/rpc/upsert_product_for_game") }.body!!,
        ).jsonObject
        assertEquals(JsonNull, rpcBody["p_image_url"])
        assertEquals(JsonNull, rpcBody["p_description"])
    }

    @Test
    fun `linkListingToBgg fallback crea listing MeepleApp completo senza scrivere la source`() = runTest {
        val details = BoardGameDetails(
            bggId = 99,
            imageUrl = "https://bgg.example/cover.jpg",
            description = "Descrizione BGG",
            yearPublished = 2024,
            minPlayers = 1,
            maxPlayers = 4,
            playingTime = 90,
            minAge = 10,
            designers = emptyList(),
            categories = emptyList(),
            mechanics = emptyList(),
            expands = emptyList(),
            bggRating = 7.5,
            bggRank = 42,
        )
        val transport = FakeTransport(
            postResponses = mapOf("$base/rest/v1/rpc/upsert_product_for_game" to "123"),
            postFailures = mapOf(rpc404("link_listing_to_bgg")),
        )

        SupabaseRestApi(base, anon, transport, json).linkListingToBgg(
            listingId = 0,
            currentGameId = 5,
            details = details,
            fallbackTitle = "Dune",
        )

        val createListing = transport.calls.single {
            it.url == "$base/rest/v1/listing?on_conflict=source_id,external_id"
        }
        val body = Json.parseToJsonElement(createListing.body!!).jsonObject
        assertEquals(JsonPrimitive("meeple_app"), body["source_id"])
        assertEquals(JsonPrimitive("bgg:99"), body["external_id"])
        assertEquals(JsonPrimitive("Dune"), body["title"])
        assertEquals(JsonPrimitive("https://boardgamegeek.com/boardgame/99"), body["url"])
        assertEquals(JsonPrimitive("https://bgg.example/cover.jpg"), body["image_url"])
        assertEquals(JsonPrimitive(5), body["game_id"])
        assertEquals(JsonPrimitive(123), body["product_id"])
        assertTrue(createListing.headers["Prefer"]!!.contains("resolution=merge-duplicates"))
        assertTrue(transport.calls.none { it.url.contains("/source") })
    }

    @Test
    fun `linkListingToBgg fallback normalizza listing MeepleApp legacy trovato per URL`() = runTest {
        val legacyUrl = "$base/rest/v1/listing?source_id=eq.meeple_app&url=eq.https%3A%2F%2Fboardgamegeek.com%2Fboardgame%2F99&select=*"
        val transport = FakeTransport(
            getResponses = mapOf(
                legacyUrl to """[{"id":8,"source_id":"meeple_app","title":"Dune vecchio","url":"https://boardgamegeek.com/boardgame/99"}]""",
            ),
            postResponses = mapOf("$base/rest/v1/rpc/upsert_product_for_game" to "123"),
            postFailures = mapOf(rpc404("link_listing_to_bgg")),
        )
        val details = BoardGameDetails(
            bggId = 99,
            imageUrl = null,
            description = null,
            yearPublished = null,
            minPlayers = null,
            maxPlayers = null,
            playingTime = null,
            minAge = null,
            designers = emptyList(),
            categories = emptyList(),
            mechanics = emptyList(),
            expands = emptyList(),
            bggRating = null,
            bggRank = null,
        )

        SupabaseRestApi(base, anon, transport, json).linkListingToBgg(
            listingId = 0,
            currentGameId = 5,
            details = details,
            fallbackTitle = "Dune",
        )

        val update = transport.calls.single { it.url == "$base/rest/v1/listing?id=eq.8" }
        val body = Json.parseToJsonElement(update.body!!).jsonObject
        assertEquals(JsonPrimitive("bgg:99"), body["external_id"])
        assertEquals(JsonPrimitive("Dune"), body["title"])
        assertTrue(transport.calls.none { it.url.contains("?on_conflict=") })
    }

    @Test
    fun `linkListingToBgg fallback propaga gli errori HTTP nel lookup listing`() = runTest {
        val listingUrl = "$base/rest/v1/listing?source_id=eq.meeple_app&url=eq.https%3A%2F%2Fboardgamegeek.com%2Fboardgame%2F99&select=*"
        val transport = FakeTransport(
            postResponses = mapOf("$base/rest/v1/rpc/upsert_product_for_game" to "123"),
            getFailures = mapOf(listingUrl to IOException("HTTP 400: external_id richiesto")),
            postFailures = mapOf(rpc404("link_listing_to_bgg")),
        )
        val details = BoardGameDetails(
            bggId = 99,
            imageUrl = null,
            description = null,
            yearPublished = null,
            minPlayers = null,
            maxPlayers = null,
            playingTime = null,
            minAge = null,
            designers = emptyList(),
            categories = emptyList(),
            mechanics = emptyList(),
            expands = emptyList(),
            bggRating = null,
            bggRank = null,
        )

        try {
            SupabaseRestApi(base, anon, transport, json).linkListingToBgg(
                listingId = 0,
                currentGameId = 5,
                details = details,
                fallbackTitle = "Dune",
            )
            fail("il lookup del listing non deve mascherare l'HTTP 400")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("HTTP 400"))
            assertTrue(e.message!!.contains("external_id richiesto"))
        }
        assertTrue(transport.calls.none { it.url.contains("/listing?on_conflict=") })
    }

    @Test
    fun `saveGameToDatabase usa RPC atomica 0013 quando disponibile`() = runTest {
        val transport = FakeTransport(
            postResponses = mapOf("$base/rest/v1/rpc/save_game_to_database" to "5"),
        )
        val item = IncomingItem(
            key = "bgg:99",
            title = "Dune",
            imageUrl = "https://bgg.example/cover.jpg",
            releaseDate = null,
            kind = ItemKind.NEW_UNDATED,
            game = Game(
                id = 0,
                name = "Dune",
                kind = "base",
                bggId = 99,
                bggRating = 7.5,
                bggRank = 42,
                imageUrl = "https://bgg.example/cover.jpg",
                description = "Descrizione BGG",
            ),
            offers = emptyList(),
        )

        val savedId = SupabaseRestApi(base, anon, transport, json).saveGameToDatabase(item, listOf(77, 77, 88))

        assertEquals(5L, savedId)
        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("$base/rest/v1/rpc/save_game_to_database", call.url)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals(JsonPrimitive(99), body["p_bgg_id"])
        assertEquals(JsonPrimitive("Dune"), body["p_title"])
        assertEquals(JsonPrimitive("base"), body["p_kind"])
        assertEquals(JsonPrimitive(7.5), body["p_bgg_rating"])
        assertEquals(JsonPrimitive(42), body["p_bgg_rank"])
        assertEquals(JsonArray(listOf(JsonPrimitive(77), JsonPrimitive(88))), body["p_listing_ids_to_link"])
    }

    @Test
    fun `saveGameToDatabase fallback crea listing BGG e collega le selezioni senza creare la source`() = runTest {
        val transport = FakeTransport(
            getResponses = mapOf(
                "$base/rest/v1/game?bgg_id=eq.99&select=*" to "[]",
            ),
            postResponses = mapOf(
                "$base/rest/v1/game" to """[{"id":5,"name":"Dune","kind":"base","bgg_id":99}]""",
                "$base/rest/v1/rpc/upsert_product_for_game" to "123",
            ),
            postFailures = mapOf(rpc404("save_game_to_database")),
        )
        val item = IncomingItem(
            key = "bgg:99",
            title = "Dune",
            imageUrl = "https://bgg.example/cover.jpg",
            releaseDate = null,
            kind = ItemKind.NEW_UNDATED,
            game = Game(
                id = 0,
                name = "Dune",
                kind = "base",
                bggId = 99,
                bggRating = 7.5,
                bggRank = 42,
                imageUrl = "https://bgg.example/cover.jpg",
                description = "Descrizione BGG",
            ),
            offers = emptyList(),
        )

        val savedId = SupabaseRestApi(base, anon, transport, json).saveGameToDatabase(item, listOf(77))

        assertEquals(5L, savedId)
        val rpcCall = transport.calls.single { it.url.endsWith("/rpc/upsert_product_for_game") }
        val rpcBody = Json.parseToJsonElement(rpcCall.body!!).jsonObject
        assertEquals(JsonPrimitive("https://boardgamegeek.com/boardgame/99"), rpcBody["p_url"])
        assertEquals(JsonPrimitive("Descrizione BGG"), rpcBody["p_description"])
        val selectedListing = transport.calls.single { it.url == "$base/rest/v1/listing?id=eq.77" }
        val selectedBody = Json.parseToJsonElement(selectedListing.body!!).jsonObject
        assertEquals(JsonPrimitive(5), selectedBody["game_id"])
        assertEquals(JsonPrimitive(123), selectedBody["product_id"])
        val createListing = transport.calls.single {
            it.url == "$base/rest/v1/listing?on_conflict=source_id,external_id"
        }
        val listingBody = Json.parseToJsonElement(createListing.body!!).jsonObject
        assertEquals(JsonPrimitive("bgg:99"), listingBody["external_id"])
        assertEquals(JsonPrimitive(5), listingBody["game_id"])
        assertEquals(JsonPrimitive(123), listingBody["product_id"])
        assertTrue(transport.calls.none { it.url.contains("/source") })
    }

    @Test
    fun `updateGameBggStats usa RPC atomica 0013 quando disponibile`() = runTest {
        val transport = FakeTransport(
            postResponses = mapOf("$base/rest/v1/rpc/update_game_bgg_stats" to "5"),
        )

        SupabaseRestApi(base, anon, transport, json).updateGameBggStats(
            bggId = 99,
            bggRating = 7.25,
            bggRank = 314,
            description = "Descrizione",
        )

        val call = transport.calls.single()
        assertEquals("POST", call.method)
        assertEquals("$base/rest/v1/rpc/update_game_bgg_stats", call.url)
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals(JsonPrimitive(99), body["p_bgg_id"])
        assertEquals(JsonPrimitive(7.25), body["p_bgg_rating"])
        assertEquals(JsonPrimitive(314), body["p_bgg_rank"])
        assertEquals(JsonPrimitive("Descrizione"), body["p_description"])
    }

    @Test
    fun `aggiorna solo voto e rank BGG di un game in fallback legacy`() = runTest {
        val transport = FakeTransport(
            patchResponse = """[{"id":5,"name":"Coffee Rush: Winter","kind":"base","bgg_id":99,"bgg_rating":7.25,"bgg_rank":314}]""",
            postFailures = mapOf(rpc404("update_game_bgg_stats")),
        )

        SupabaseRestApi(base, anon, transport, json).updateGameBggStats(
            bggId = 99,
            bggRating = 7.25,
            bggRank = 314,
        )

        val call = transport.calls.last()
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("PATCH", call.method)
        assertEquals("$base/rest/v1/game?bgg_id=eq.99&select=*", call.url)
        assertEquals(JsonPrimitive(7.25), body["bgg_rating"])
        assertEquals(JsonPrimitive(314), body["bgg_rank"])
        assertEquals(2, body.size)
    }

    @Test
    fun `aggiornamento voto e rank fallisce se l'id BGG non esiste nel database`() = runTest {
        val transport = FakeTransport(
            patchResponse = "[]",
            postFailures = mapOf(rpc404("update_game_bgg_stats")),
        )

        try {
            SupabaseRestApi(base, anon, transport, json).updateGameBggStats(999999, 7.0, 100)
            fail("attesa eccezione per gioco non trovato")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Nessun gioco associato"))
        }
    }

    @Test
    fun `associazione BGG azzera valori precedenti non disponibili`() = runTest {
        val transport = FakeTransport(
            postResponses = mapOf("$base/rest/v1/rpc/upsert_product_for_game" to "125"),
            postFailures = mapOf(rpc404("link_listing_to_bgg")),
        )
        val details = BoardGameDetails(
            bggId = 99,
            imageUrl = null,
            description = null,
            yearPublished = null,
            minPlayers = null,
            maxPlayers = null,
            playingTime = null,
            minAge = null,
            designers = emptyList(),
            categories = emptyList(),
            mechanics = emptyList(),
        )

        SupabaseRestApi(base, anon, transport, json).linkListingToBgg(
            listingId = 0,
            currentGameId = 5,
            details = details,
            fallbackTitle = "Gioco",
        )

        val body = Json.parseToJsonElement(transport.calls.first { it.url.contains("/game") }.body!!).jsonObject
        assertEquals(JsonNull, body["bgg_rating"])
        assertEquals(JsonNull, body["bgg_rank"])
        assertEquals(JsonNull, body["image_url"])
    }

    @Test
    fun `insertAction registra la rimozione del preferito`() = runTest {
        val transport = FakeTransport()
        SupabaseRestApi(base, anon, transport, json)
            .insertAction(UserActionRow(gameId = 5, listingId = null, action = Action.UNFAVORITED))

        val body = Json.parseToJsonElement(transport.calls.single().body!!).jsonObject
        assertEquals(JsonPrimitive("unfavorite"), body["action"])
    }

    @Test
    fun `userActions traduce le azioni minuscole e carica prima le piu recenti`() = runTest {
        val body = listingJson(
            """{"game_id":5,"listing_id":null,"action":"favorite"}""",
            """{"game_id":null,"listing_id":9,"action":"ignored"}""",
            """{"game_id":5,"listing_id":null,"action":"unfavorite"}""",
        )
        val transport = FakeTransport(
            mapOf("$base/rest/v1/user_action?select=*&order=created_at.desc,id.desc" to body),
        )

        val actions = SupabaseRestApi(base, anon, transport, json).userActions()

        assertEquals(UserActionRow(5, null, Action.FAVORITE), actions[0])
        assertEquals(UserActionRow(null, 9, Action.IGNORED), actions[1])
        assertEquals(UserActionRow(5, null, Action.UNFAVORITED), actions[2])
        assertEquals("0-999", transport.calls.single().headers["Range"])
    }

    @Test
    fun `http 4xx diventa un'eccezione con il codice`() {
        val response = Response.Builder()
            .request(Request.Builder().url("$base/rest/v1/listing?select=*").build())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("Not Found".toResponseBody(null))
            .build()

        try {
            bodyOrThrow(response)
            fail("attesa IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("404"))
            assertTrue(e.message!!.contains("Not Found"))
        }
    }

    @Test
    fun `listingBestPrices legge la vista 0015 con filtro opzionale e fallback silenzioso su 404`() = runTest {
        val body = """[
            {"listing_id":101,"current_price":39.9,"currency":"EUR","current_availability":"Disponibile","min_price":39.9,"previous_price":46.9,"price_drop_pct":14.93,"recorded_at":"2026-04-19T08:00:00Z"}
        ]"""
        val expectedUrl = "$base/rest/v1/listing_best_price?select=*&listing_id=in.(101,102)"
        val transport = FakeTransport(getResponses = mapOf(expectedUrl to body))

        val result = SupabaseRestApi(base, anon, transport, json).listingBestPrices(listOf(101L, 102L))

        assertEquals(1, result.size)
        assertEquals(101L, result[0].listingId)
        assertEquals(39.9, result[0].currentPrice!!, 0.001)
        assertEquals(39.9, result[0].minPrice!!, 0.001)
        assertEquals(46.9, result[0].previousPrice!!, 0.001)
        assertEquals(14.93, result[0].priceDropPct!!, 0.001)

        val missingViewTransport = FakeTransport(
            getFailures = mapOf(expectedUrl to IOException("HTTP 404: relation listing_best_price does not exist")),
        )
        assertTrue(SupabaseRestApi(base, anon, missingViewTransport, json).listingBestPrices(listOf(101L, 102L)).isEmpty())
    }

    @Test
    fun `listingPriceHistory restituisce gli snapshot ordinati e gestisce 404 silenziosamente`() = runTest {
        val expectedUrl = "$base/rest/v1/listing_price_history?select=*&listing_id=in.(101)&order=recorded_at.desc,id.desc&limit=40"
        val body = """[
            {"id":2,"listing_id":101,"price":39.9,"currency":"EUR","availability":"Disponibile","recorded_at":"2026-04-19T08:00:00Z"},
            {"id":1,"listing_id":101,"price":46.9,"currency":"EUR","availability":"Disponibile","recorded_at":"2026-04-10T08:00:00Z"}
        ]"""
        val transport = FakeTransport(getResponses = mapOf(expectedUrl to body))

        val history = SupabaseRestApi(base, anon, transport, json).listingPriceHistory(listOf(101L))

        assertEquals(2, history.size)
        assertEquals(39.9, history[0].price!!, 0.001)
        assertEquals(46.9, history[1].price!!, 0.001)

        val missingTableTransport = FakeTransport(
            getFailures = mapOf(expectedUrl to IOException("HTTP 404: Not Found")),
        )
        assertTrue(SupabaseRestApi(base, anon, missingTableTransport, json).listingPriceHistory(listOf(101L)).isEmpty())
    }

    @Test
    fun `userAlerts mappa tipi e payload e supporta markAlertRead e markAllAlertsRead`() = runTest {
        val alertsUrl = "$base/rest/v1/user_alert?select=*&order=created_at.desc,id.desc&limit=50"
        val body = """[
            {
                "id": 10,
                "alert_type": "price_drop",
                "game_id": 42,
                "product_id": null,
                "listing_id": 101,
                "title": "Ark Nova",
                "payload": {"source_name": "DungeonDice", "previous_price": 46.9, "current_price": 39.9, "price_drop_pct": 15.0, "currency": "EUR"},
                "dedupe_key": "drop:101",
                "read_at": null,
                "created_at": "2026-04-19T08:00:00Z"
            },
            {
                "id": 11,
                "alert_type": "back_in_stock",
                "game_id": 7,
                "product_id": null,
                "listing_id": 202,
                "title": "Brass",
                "payload": {"source_name": "Fantasia Store", "availability": "Disponibile", "price": 59.9, "currency": "EUR"},
                "dedupe_key": "stock:202",
                "read_at": "2026-04-19T09:00:00Z",
                "created_at": "2026-04-18T08:00:00Z"
            }
        ]"""
        val transport = FakeTransport(getResponses = mapOf(alertsUrl to body))
        val api = SupabaseRestApi(base, anon, transport, json)

        val alerts = api.userAlerts()

        assertEquals(2, alerts.size)
        assertEquals(com.meepleradar.data.model.UserAlertType.PRICE_DROP, alerts[0].alertType)
        assertTrue(alerts[0].isUnread)
        assertEquals("game:42", alerts[0].itemKeyOrNull())
        assertTrue(alerts[0].detailText!!.contains("39,90"))
        assertTrue(alerts[0].detailText!!.contains("-15%"))
        assertEquals(com.meepleradar.data.model.UserAlertType.BACK_IN_STOCK, alerts[1].alertType)
        assertTrue(!alerts[1].isUnread)

        api.markAlertRead(10L, "2026-04-19T10:00:00Z")
        val patchSingle = transport.calls.last()
        assertEquals("PATCH", patchSingle.method)
        assertEquals("$base/rest/v1/user_alert?id=eq.10", patchSingle.url)
        assertEquals(JsonPrimitive("2026-04-19T10:00:00Z"), Json.parseToJsonElement(patchSingle.body!!).jsonObject["read_at"])

        api.markAllAlertsRead(listOf(10L, 11L), "2026-04-19T10:05:00Z")
        val patchAll = transport.calls.last()
        assertEquals("PATCH", patchAll.method)
        assertEquals("$base/rest/v1/user_alert?id=in.(10,11)", patchAll.url)
    }
}
