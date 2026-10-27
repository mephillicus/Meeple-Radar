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
        val nonApiUrls = transport.urls.filter { !it.contains("api.geekdo.com") && !it.contains("search/boardgame") }
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
    fun `findSeries per gioco base estrae le espansioni da geekitems`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("objectid=173346") -> """
                    {
                      "item": {
                        "id": 173346,
                        "name": "7 Wonders Duel",
                        "links": {
                          "boardgameexpansion": [
                            {
                              "objecttype": "thing",
                              "objectid": 2001,
                              "name": "7 Wonders Duel: Pantheon",
                              "canonical_link": "/boardgameexpansion/2001/7-wonders-duel-pantheon"
                            },
                            {
                              "objecttype": "thing",
                              "objectid": 2002,
                              "name": "7 Wonders Duel: Agora",
                              "canonical_link": "/boardgameexpansion/2002/7-wonders-duel-agora"
                            }
                          ]
                        }
                      }
                    }
                """.trimIndent()
                else -> error("URL inaspettata: $url")
            }
        }

        val lookup = BoardGameGeekImageLookup(transport)
        val series = lookup.findSeries(173346)

        assertTrue(series != null)
        assertEquals(0, series!!.bases.size)
        assertEquals(2, series.expansions.size)
        assertEquals(2001, series.expansions[0].bggId)
        assertEquals("7 Wonders Duel: Pantheon", series.expansions[0].name)
        assertEquals("https://boardgamegeek.com/boardgameexpansion/2001/7-wonders-duel-pantheon", series.expansions[0].url)
    }

    @Test
    fun `findSeries per espansione recupera gioco base e siblings`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("objectid=2002") -> """
                    {
                      "item": {
                        "id": 2002,
                        "name": "7 Wonders Duel: Agora",
                        "links": {
                          "expandsboardgame": [
                            {
                              "objecttype": "thing",
                              "objectid": 173346,
                              "name": "7 Wonders Duel",
                              "canonical_link": "/boardgame/173346/7-wonders-duel"
                            }
                          ]
                        }
                      }
                    }
                """.trimIndent()
                url.contains("objectid=173346") -> """
                    {
                      "item": {
                        "id": 173346,
                        "name": "7 Wonders Duel",
                        "links": {
                          "boardgameexpansion": [
                            {
                              "objecttype": "thing",
                              "objectid": 2001,
                              "name": "7 Wonders Duel: Pantheon",
                              "canonical_link": "/boardgameexpansion/2001/7-wonders-duel-pantheon"
                            },
                            {
                              "objecttype": "thing",
                              "objectid": 2002,
                              "name": "7 Wonders Duel: Agora",
                              "canonical_link": "/boardgameexpansion/2002/7-wonders-duel-agora"
                            }
                          ]
                        }
                      }
                    }
                """.trimIndent()
                else -> error("URL inaspettata: $url")
            }
        }

        val lookup = BoardGameGeekImageLookup(transport)
        val series = lookup.findSeries(2002)

        assertTrue(series != null)
        assertEquals(1, series!!.bases.size)
        assertEquals("7 Wonders Duel", series.bases[0].name)
        assertEquals(1, series.siblings.size)
        assertEquals("7 Wonders Duel: Pantheon", series.siblings[0].name)
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
                        "name": "Aye, Dark Overlord! Red Box",
                        "imageurl": "https://cf.geekdo-images.com/luxastra.jpg",
                        "description": "Luxastra edition.",
                        "yearpublished": 2023,
                        "minplayers": 4,
                        "maxplayers": 6
                      },
                      "alternatenames": [
                        {"name": "Sì, Oscuro Signore! Set Rosso"}
                      ]
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

    @Test
    fun `la ricerca include espansioni BGG e preferisce il titolo localizzato esatto`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("/search/boardgame?") -> """
                    <a href="/boardgame/283355/dune">Dune</a>
                    <a href="/boardgame/367150/dune-war-for-arrakis">Dune: War for Arrakis</a>
                    <a href="/boardgameexpansion/421183/dune-war-for-arrakis-desert-war">Desert War</a>
                """.trimIndent()
                url.contains("objectid=283355") -> """{"item":{"name":"Dune","description":"Dune."}}"""
                url.contains("objectid=367150") -> """
                    {"item":{"name":"Dune: War for Arrakis","description":"Dune: War for Arrakis."}}
                """.trimIndent()
                url.contains("objectid=421183") -> """
                    {"item":{"name":"Dune: War for Arrakis – Desert War","description":"Dune expansion.","subtype":"boardgameexpansion","links":{"expandsboardgame":[{"name":"Dune: War for Arrakis","objectid":367150}]}},"alternatenames":[{"name":"Dune: Guerre sur Arrakis – La Guerre du désert"}]}
                """.trimIndent()
                else -> error("URL BGG non prevista: $url")
            }
        }

        val details = BoardGameGeekImageLookup(transport)
            .findDetails("Dune: La Guerra per Arrakis – La Guerra del Deserto", null)

        assertEquals(421183, details?.bggId)
    }

    @Test
    fun `ricerca manuale BGG trova espansione tramite query dedicata`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("/search?query=Desert%20War") -> """
                    <items>
                      <item type="boardgameexpansion" id="421183">
                        <name type="primary" value="Dune: War for Arrakis – Desert War"/>
                      </item>
                    </items>
                """.trimIndent()
                url.contains("/search/boardgame?") -> ""
                url.contains("objectid=421183") ->
                    """{"item":{"name":"Dune: War for Arrakis – Desert War","description":"Expansion.","yearpublished":"2024"}}"""
                else -> error("URL BGG non prevista: $url")
            }
        }

        val candidates = BoardGameGeekImageLookup(transport).searchCandidates("Desert War")

        assertEquals(1, candidates.size)
        assertEquals(421183, candidates.single().bggId)
        assertEquals("Dune: War for Arrakis – Desert War", candidates.single().primaryName)
    }

    @Test
    fun `trova Desert War passando dalla lista espansioni del gioco base`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("/search/boardgame?") -> """
                    <a href="/boardgame/283355/dune">Dune</a>
                """.trimIndent()
                url.contains("/search?query=Dune%20La%20Guerra%20per%20Arrakis") -> """
                    <items>
                      <item type="boardgame" id="367150">
                        <name type="primary" value="Dune: War for Arrakis"/>
                        <name type="alternate" value="Dune: La Guerra per Arrakis"/>
                      </item>
                    </items>
                """.trimIndent()
                url.contains("/search?") -> "<items/>"
                url.contains("objectid=283355") -> """{"item":{"name":"Dune","description":"The Dune game.","yearpublished":"2019"}}"""
                url.contains("objectid=367150") -> """
                    {"item":{"name":"Dune: War for Arrakis","description":"Base game.","links":{"boardgameexpansion":[{"name":"Dune: War for Arrakis – The Spacing Guild","objectid":"370796"},{"name":"Dune: War for Arrakis – Desert War","objectid":"421183"}]}},"alternatenames":[{"name":"Dune: La Guerra per Arrakis"}]}
                """.trimIndent()
                url.contains("objectid=370796") -> """
                    {"item":{"name":"Dune: War for Arrakis – The Spacing Guild","description":"Expansion.","subtype":"boardgameexpansion","links":{"expandsboardgame":[{"name":"Dune: War for Arrakis","objectid":"367150"}]}},"alternatenames":[{"name":"Dune: La Guerra per Arrakis – La Gilda Spaziale"}]}
                """.trimIndent()
                url.contains("objectid=421183") -> """
                    {"item":{"name":"Dune: War for Arrakis – Desert War","description":"Expansion.","subtype":"boardgameexpansion","links":{"expandsboardgame":[{"name":"Dune: War for Arrakis","objectid":"367150"}]}},"alternatenames":[{"name":"Dune: Guerre sur Arrakis – La Guerre du désert"}]}
                """.trimIndent()
                else -> error("URL BGG non prevista: $url")
            }
        }

        val lookup = BoardGameGeekImageLookup(transport)
        val title = "Dune: La Guerra per Arrakis – La Guerra del Deserto"
        val details = lookup.findDetails(title, 283355)
        val candidates = lookup.findCandidates(title, 283355)
        val candidatesFromBaseId = lookup.findCandidates(title, 367150)

        assertEquals(421183, details?.bggId)
        assertTrue(candidates.any { it.bggId == 421183 })
        assertTrue(candidates.none { it.bggId == 283355 })
        assertTrue(candidatesFromBaseId.any { it.bggId == 421183 })
    }

    @Test
    fun `non accetta un ID BGG gia associato se il titolo non corrisponde`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("objectid=283355") -> """
                    {"item":{"name":"Dune","description":"The Dune game.","yearpublished":"2019"}}
                """.trimIndent()
                url.contains("/search/boardgame?") -> """
                    <a href="/boardgame/283355/dune">Dune</a>
                    <a href="/boardgame/367150/dune-war-for-arrakis">Dune: War for Arrakis</a>
                    <a href="/boardgameexpansion/421183/dune-war-for-arrakis-desert-war">Desert War</a>
                """.trimIndent()
                url.contains("objectid=367150") -> """
                    {"item":{"name":"Dune: War for Arrakis","description":"Base game."}}
                """.trimIndent()
                url.contains("objectid=421183") -> """
                    {"item":{"name":"Dune: War for Arrakis – Desert War","description":"Expansion.","subtype":"boardgameexpansion","links":{"expandsboardgame":[{"name":"Dune: War for Arrakis","objectid":"367150"}]}},"alternatenames":[{"name":"Dune: Guerre sur Arrakis – La Guerre du désert"}]}
                """.trimIndent()
                else -> error("URL BGG non prevista: $url")
            }
        }

        val details = BoardGameGeekImageLookup(transport).findDetails(
            "Dune: La Guerra per Arrakis – La Guerra del Deserto",
            283355,
        )

        assertEquals(421183, details?.bggId)
    }

    @Test
    fun `la ricerca sceglie La Gilda Spaziale invece del gioco base`() = runTest {
        val transport = FakeTransport { url ->
            when {
                url.contains("/search/boardgame?") -> """
                    <a href="/boardgame/367150/dune-war-for-arrakis">Dune: War for Arrakis</a>
                    <a href="/boardgame/370796/dune-war-for-arrakis-the-spacing-guild">The Spacing Guild</a>
                """.trimIndent()
                url.contains("objectid=367150") -> """{"item":{"name":"Dune: War for Arrakis","description":"Dune: War for Arrakis."}}"""
                url.contains("objectid=370796") -> """
                    {"item":{"name":"Dune: War for Arrakis – The Spacing Guild","description":"Dune expansion.","subtype":"boardgameexpansion","links":{"expandsboardgame":[{"name":"Dune: War for Arrakis","objectid":367150}]},"alternatenames":[{"name":"Dune: La Guerra per Arrakis – La Gilda Spaziale"}]}}
                """.trimIndent()
                else -> error("URL BGG non prevista: $url")
            }
        }

        val details = BoardGameGeekImageLookup(transport)
            .findDetails("Dune: La Guerra per Arrakis – La Gilda Spaziale", null)

        assertEquals(370796, details?.bggId)
    }

    @Test
    fun `prova reale live BGG per item 389407`() = runTest {
        val transport = OkHttpTransport(okhttp3.OkHttpClient())
        val lookup = BoardGameGeekImageLookup(transport)

        val details = lookup.findDetails("Sì, Oscuro Signore!", 389407)
        println("LIVE BGG DETAILS: $details")
        println("DESCRIPTION: ${details?.description}")
    }

    @Test
    fun `prova reale live BGG senza ID per Si Oscuro Signore Luxastra`() = runTest {
        val transport = OkHttpTransport(okhttp3.OkHttpClient())
        val lookup = BoardGameGeekImageLookup(transport)

        val details = lookup.findDetails("Sì, Oscuro Signore! – Set Rosso (Luxastra Edition)", null)
        println("LIVE SEARCH WITHOUT ID DETAILS: $details")
        println("DESCRIPTION: ${details?.description}")
    }
}
