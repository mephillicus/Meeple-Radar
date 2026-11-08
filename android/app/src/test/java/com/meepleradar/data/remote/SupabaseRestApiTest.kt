package com.meepleradar.data.remote

import com.meepleradar.data.model.Action
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
    ) : HttpTransport {
        data class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

        val calls = mutableListOf<Call>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            calls += Call("GET", url, headers, null)
            return getResponses[url] ?: "[]"
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: String): String {
            calls += Call("POST", url, headers, body)
            return ""
        }

        override suspend fun patch(url: String, headers: Map<String, String>, body: String): String {
            calls += Call("PATCH", url, headers, body)
            return ""
        }
    }

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
    fun `linkListingToBgg salva i dati BGG e distingue espansioni e giochi base`() = runTest {
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
        val transport = FakeTransport()

        SupabaseRestApi(base, anon, transport, json).linkListingToBgg(
            listingId = 7,
            currentGameId = 5,
            details = details,
            fallbackTitle = "Titolo alternativo",
        )
        val gamePatch = transport.calls.first()
        val gameBody = Json.parseToJsonElement(gamePatch.body!!).jsonObject

        assertEquals("PATCH", gamePatch.method)
        assertEquals("$base/rest/v1/game?id=eq.5", gamePatch.url)
        assertEquals(JsonPrimitive("Titolo alternativo"), gameBody["name"])
        assertEquals(JsonPrimitive("expansion"), gameBody["kind"])
        assertEquals(JsonPrimitive(99), gameBody["bgg_id"])
        assertEquals(JsonPrimitive(7.5), gameBody["bgg_rating"])
        assertEquals(JsonPrimitive(42), gameBody["bgg_rank"])
        assertEquals(JsonPrimitive("https://bgg.example/cover.jpg"), gameBody["image_url"])
        assertTrue(gameBody.containsKey("name"))
        assertTrue(gameBody.containsKey("kind"))
        assertTrue(gameBody.containsKey("bgg_id"))
        assertTrue(gameBody.containsKey("bgg_rating"))
        assertTrue(gameBody.containsKey("bgg_rank"))
        assertTrue(gameBody.containsKey("image_url"))
        assertEquals("$base/rest/v1/listing?id=eq.7", transport.calls[1].url)

        val baseGame = details.copy(bggId = 100, expands = emptyList())
        val baseGameTransport = FakeTransport()
        SupabaseRestApi(base, anon, baseGameTransport, json).linkListingToBgg(
            listingId = 0,
            currentGameId = 5,
            details = baseGame,
            fallbackTitle = "Gioco base",
        )
        val baseGameBody = Json.parseToJsonElement(baseGameTransport.calls.single().body!!).jsonObject
        assertEquals(JsonPrimitive("base"), baseGameBody["kind"])
    }

    @Test
    fun `aggiorna solo voto e rank BGG di un game`() = runTest {
        val transport = FakeTransport()

        SupabaseRestApi(base, anon, transport, json).updateGameBggStats(
            bggId = 99,
            bggRating = 7.25,
            bggRank = 314,
        )

        val call = transport.calls.single()
        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals("PATCH", call.method)
        assertEquals("$base/rest/v1/game?bgg_id=eq.99", call.url)
        assertEquals(JsonPrimitive(7.25), body["bgg_rating"])
        assertEquals(JsonPrimitive(314), body["bgg_rank"])
        assertEquals(2, body.size)
    }

    @Test
    fun `associazione BGG azzera valori precedenti non disponibili`() = runTest {
        val transport = FakeTransport()
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

        val body = Json.parseToJsonElement(transport.calls.single().body!!).jsonObject
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
        }
    }
}
