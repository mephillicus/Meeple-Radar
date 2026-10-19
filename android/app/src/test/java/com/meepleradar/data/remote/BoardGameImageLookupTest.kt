package com.meepleradar.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardGameImageLookupTest {

    private class FakeTransport(
        private val responseFor: (String) -> String,
    ) : HttpTransport {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String, headers: Map<String, String>): String {
            urls += url
            return responseFor(url)
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: String): String =
            error("POST non previsto")
    }

    @Test
    fun `cerca prima con ID BGG e riusa il risultato dalla cache`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("api.geekdo.com") -> ""
                url.contains("/thing?") ->
                    """<items><item id="13"><image>https://cf.geekdo-images.com/catan.jpg</image></item></items>"""
                else -> error("Non deve cercare per nome se l'ID restituisce una copertina: $url")
            }
        }
        val lookup = BoardGameGeekImageLookup(transport)

        assertEquals("https://cf.geekdo-images.com/catan.jpg", lookup.findImage("Catan", 13))
        assertEquals("https://cf.geekdo-images.com/catan.jpg", lookup.findImage("Catan", 13))
        val xmlUrls = transport.urls.filter { it.contains("/thing?") }
        assertEquals(1, xmlUrls.size)
        assertTrue(xmlUrls.single().contains("id=13"))
    }

    @Test
    fun `cerca per nome solo una corrispondenza esatta e poi recupera la copertina`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("api.geekdo.com") -> ""
                url.contains("/search?") -> """
                    <items>
                      <item type="boardgame" id="99"><name type="primary" value="Catan Junior"/></item>
                      <item type="boardgame" id="13"><name type="primary" value="Catan"/></item>
                    </items>
                """.trimIndent()
                url.contains("/thing?") ->
                    """<items><item id="13"><image>https://cf.geekdo-images.com/catan.jpg</image></item></items>"""
                else -> error("URL BGG non prevista: $url")
            }
        }

        val image = BoardGameGeekImageLookup(transport).findImage("Catan", null)

        assertEquals("https://cf.geekdo-images.com/catan.jpg", image)
        val nonApiUrls = transport.urls.filter { !it.contains("api.geekdo.com") }
        assertEquals(2, nonApiUrls.size)
        assertTrue(nonApiUrls[0].contains("query=Catan"))
        assertTrue(nonApiUrls[1].contains("id=13"))
    }

    @Test
    fun `non considera valide immagini non HTTPS`() = runTest {
        val transport = FakeTransport {
            """<items><item id="13"><image>http://example.com/catan.jpg</image></item></items>"""
        }

        assertEquals(null, BoardGameGeekImageLookup(transport).findImage("Catan", 13))
    }

    @Test
    fun `recupera specifiche e descrizione da BoardGameGeek`() = runTest {
        val transport = FakeTransport {
            """
              <items>
                <item type="boardgame" id="13">
                  <yearpublished value="1995"/>
                  <minplayers value="3"/>
                  <maxplayers value="4"/>
                  <playingtime value="90"/>
                  <minage value="10"/>
                  <image>https://cf.geekdo-images.com/catan.jpg</image>
                  <description>Build settlements and roads.</description>
                  <link type="boardgamedesigner" id="1" value="Klaus Teuber"/>
                  <link type="boardgamecategory" id="2" value="Negotiation"/>
                  <link type="boardgamemechanic" id="3" value="Trading"/>
                </item>
              </items>
            """.trimIndent()
        }

        val details = BoardGameGeekImageLookup(transport).findDetails("Catan", 13)

        assertEquals(13, details?.bggId)
        assertEquals(1995, details?.yearPublished)
        assertEquals(3, details?.minPlayers)
        assertEquals(4, details?.maxPlayers)
        assertEquals(90, details?.playingTime)
        assertEquals(10, details?.minAge)
        assertEquals("Build settlements and roads.", details?.description)
        assertEquals(listOf("Klaus Teuber"), details?.designers)
        assertEquals(listOf("Negotiation"), details?.categories)
        assertEquals(listOf("Trading"), details?.mechanics)
    }

    @Test
    fun `ricerca fuzzy con accenti e nomi alternativi trova il gioco corretto`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("/search?") -> """
                    <items>
                      <item type="boardgame" id="389407">
                        <name type="primary" value="Aye, Dark Overlord! Red Box"/>
                        <name type="alternate" value="Sì, Oscuro Signore! Set Rosso"/>
                      </item>
                    </items>
                """.trimIndent()
                url.contains("api.geekdo.com") || url.contains("/thing?") -> """
                    {
                      "item": {
                        "id": 389407,
                        "imageurl": "https://cf.geekdo-images.com/luxastra.jpg",
                        "description": "Luxastra edition.",
                        "yearpublished": 2023,
                        "minplayers": 4,
                        "maxplayers": 6
                      }
                    }
                """.trimIndent()
                else -> error("URL non previsto: $url")
            }
        }

        val details = BoardGameGeekImageLookup(transport).findDetails("Sì, Oscuro Signore! – Set Rosso (Luxastra Edition)", null)

        assertEquals(389407, details?.bggId)
        assertEquals("https://cf.geekdo-images.com/luxastra.jpg", details?.imageUrl)
        assertEquals(2023, details?.yearPublished)
        assertEquals(4, details?.minPlayers)
    }
}
