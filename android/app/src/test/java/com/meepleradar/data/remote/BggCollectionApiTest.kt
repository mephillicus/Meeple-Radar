package com.meepleradar.data.remote

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BggCollectionApiTest {

    private class FakeTransport(private val responses: List<String>) : HttpTransport {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            requests += url to headers
            return responses[minOf(requests.lastIndex, responses.lastIndex)]
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: String): String = ""
    }

    @Test
    fun `carica solo i giochi posseduti con i relativi dettagli`() = runTest {
        val xml = """
            <items totalitems="2">
              <item objecttype="thing" objectid="42" subtype="boardgame">
                <name sortindex="1">The Old King's Crown</name>
                <yearpublished value="2024"/>
                <image>https://img.example/game.jpg</image>
                <stats><rating><average value="7.8"/></rating></stats>
                <status own="1"/>
              </item>
              <item objecttype="thing" objectid="43" subtype="boardgameexpansion">
                <name>Un posseduto senza titolo</name>
                <status own="0"/>
              </item>
            </items>
        """.trimIndent()
        val transport = FakeTransport(listOf(xml))

        val games = BggXmlCollectionApi(transport).ownedGames("  example user ", "application-token")

        assertEquals(1, games.size)
        assertEquals(42, games.single().bggId)
        assertEquals("The Old King's Crown", games.single().name)
        assertEquals("boardgame", games.single().kind)
        assertEquals(2024, games.single().yearPublished)
        assertEquals("https://img.example/game.jpg", games.single().imageUrl)
        assertEquals(7.8, games.single().rating!!, 0.001)
        assertTrue(transport.requests.single().first.contains("username=example+user"))
        assertTrue(transport.requests.single().first.contains("&own=1&stats=1"))
        assertEquals("application/xml", transport.requests.single().second["Accept"])
        assertEquals("Bearer application-token", transport.requests.single().second["Authorization"])
    }

    @Test
    fun `ritenta la risposta temporanea BGG prima di parsare la collezione`() = runTest {
        val transport = FakeTransport(
            listOf(
                "Your request is being processed.",
                """<items totalitems="0"></items>""",
            ),
        )

        assertTrue(BggXmlCollectionApi(transport).ownedGames("utente", "token").isEmpty())
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `401 viene spiegato come token applicazione rifiutato`() = runTest {
        val transport = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>): String {
                assertEquals("Bearer token-errato", headers["Authorization"])
                throw IOException("HTTP 401")
            }

            override suspend fun post(url: String, headers: Map<String, String>, body: String): String = ""
        }

        try {
            BggXmlCollectionApi(transport).ownedGames("utente", "token-errato")
            fail("atteso un errore per token non valido")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("token"))
            assertTrue(e.message!!.contains("401"))
        }
    }

    @Test
    fun `rifiuta doctype prima di passare la risposta al parser`() = runTest {
        val xml = """<?xml version="1.0"?><!DOCTYPE items [<!ENTITY x "external">]><items totalitems="0"></items>"""
        val api = BggXmlCollectionApi(FakeTransport(listOf(xml)))

        try {
            api.ownedGames("utente", "token")
            fail("atteso il rifiuto del DOCTYPE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("DOCTYPE"))
        }
    }

    @Test
    fun `carica espansioni BGG con id nome e relazione e filtra altri link`() = runTest {
        val xml = """
            <items>
              <item id="42" type="boardgame">
                <link type="boardgameexpansion" id="101" value="Prima espansione"/>
                <link type="boardgameexpansion" id="102" value="Seconda espansione"/>
                <link type="boardgame" id="9" value="Altro gioco"/>
              </item>
            </items>
        """.trimIndent()
        val transport = FakeTransport(listOf(xml))

        val expansions = BggXmlCollectionApi(transport).expansions(listOf(42), "application-token")

        assertEquals(listOf(101, 102), expansions.map { it.bggId })
        assertEquals(listOf("Prima espansione", "Seconda espansione"), expansions.map { it.name })
        assertEquals(listOf(42, 42), expansions.map { it.expandsBggId })
        assertTrue(transport.requests.single().first.endsWith("/thing?id=42&type=boardgame,boardgameexpansion"))
        assertEquals("Bearer application-token", transport.requests.single().second["Authorization"])
    }

    @Test
    fun `carica espansioni di piu giochi base in batch e conserva le relazioni`() = runTest {
        val xml = """
            <items>
              <item id="173346" type="boardgame">
                <link type="boardgameexpansion" id="309116" value="7 Wonders Duel: Agora"/>
              </item>
              <item id="42" type="boardgame">
                <link type="boardgameexpansion" id="501" value="Prima espansione"/>
              </item>
            </items>
        """.trimIndent()
        val transport = FakeTransport(listOf(xml))

        val expansions = BggXmlCollectionApi(transport).expansions(listOf(173346, 42), "token")

        assertEquals(listOf(309116, 501), expansions.map { it.bggId })
        assertEquals(listOf(173346, 42), expansions.map { it.expandsBggId })
        assertTrue(
            transport.requests.single().first.endsWith("/thing?id=173346,42&type=boardgame,boardgameexpansion"),
        )
    }

    @Test
    fun `risale dal dettaglio di una espansione al suo gioco base`() = runTest {
        val xml = """
            <items>
              <item type="boardgameexpansion" id="309116">
                <name type="primary" value="7 Wonders Duel: Agora"/>
                <link type="boardgameexpansion" id="173346" value="7 Wonders Duel" inbound="true"/>
              </item>
            </items>
        """.trimIndent()
        val expansions = BggXmlCollectionApi(FakeTransport(listOf(xml)))
            .expansions(listOf(309116), "token")

        assertEquals(1, expansions.size)
        assertEquals(309116, expansions.single().bggId)
        assertEquals(173346, expansions.single().expandsBggId)
    }
}
