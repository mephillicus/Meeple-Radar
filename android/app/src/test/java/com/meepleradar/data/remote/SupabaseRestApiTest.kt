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
    }

    private fun listingJson(vararg rows: String) = rows.joinToString(prefix = "[", postfix = "]", separator = ",")

    @Test
    fun `listings costruisce url select star con header apikey e bearer`() = runTest {
        val transport = FakeTransport()
        SupabaseRestApi(base, anon, transport, json).listings()

        val call = transport.calls.single()
        assertEquals("GET", call.method)
        assertEquals("$base/rest/v1/listing?select=*", call.url)
        assertEquals(anon, call.headers["apikey"])
        assertEquals("Bearer $anon", call.headers["Authorization"])
        assertEquals("application/json", call.headers["Accept"])
    }

    @Test
    fun `listing con release_date null e price null viene mappato senza crash`() = runTest {
        val body = listingJson(
            """{"id":1,"source_id":"s1","title":"T","url":"https://u","price":null,"currency":null,""" +
                """"availability":null,"release_date":null,"image_url":null,"game_id":null,"match_confidence":null}""",
        )
        val transport = FakeTransport(mapOf("$base/rest/v1/listing?select=*" to body))

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
                """"availability":null,"release_date":"2026-11-01","image_url":null,"game_id":5,"match_confidence":0.9}""",
            """{"id":2,"source_id":"s1","title":"B","url":"https://b","price":"12.50","currency":"EUR",""" +
                """"availability":null,"release_date":null,"image_url":null,"game_id":null,"match_confidence":null}""",
        )
        val transport = FakeTransport(mapOf("$base/rest/v1/listing?select=*" to body))

        val listings = SupabaseRestApi(base, anon, transport, json).listings()

        assertEquals(39.9, listings[0].price!!, 1e-9)
        assertEquals(12.5, listings[1].price!!, 1e-9)
        assertEquals(0.9, listings[0].matchConfidence!!, 1e-9)
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
        assertEquals("return=minimal", call.headers["Prefer"])
        assertEquals(anon, call.headers["apikey"])

        val body = Json.parseToJsonElement(call.body!!).jsonObject
        assertEquals(JsonPrimitive(5), body["game_id"])
        assertEquals(JsonNull, body["listing_id"])
        assertEquals(JsonPrimitive("favorite"), body["action"])
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
