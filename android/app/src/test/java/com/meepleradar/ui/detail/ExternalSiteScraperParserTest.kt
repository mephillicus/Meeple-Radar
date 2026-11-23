package com.meepleradar.ui.detail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSiteScraperParserTest {

    @Test
    fun `cleanScrapedGameTitle rimuove marca, coda dopo pipe e descrittori generici mantenendo sottotitolo ed edizione`() {
        assertEquals(
            "7 Wonders: Dice",
            ScrapedOfferNormalizer.cleanScrapedGameTitle("Asmodee – 7 Wonders: Dice – Gioco da Tavolo Roll & Write"),
        )
        assertEquals(
            "7 WONDERS DUEL",
            ScrapedOfferNormalizer.cleanScrapedGameTitle("Asmodee – 7 WONDERS DUEL – Gioco da Tavolo di Strategia per 2 Giocatori"),
        )
        assertEquals(
            "7 Wonders: Nuova Edizione, Edizione Italiana",
            ScrapedOfferNormalizer.cleanScrapedGameTitle("Asmodee 7 Wonders: Nuova Edizione, Edizione Italiana"),
        )
        assertEquals(
            "Barrage – Edizione Italiana",
            ScrapedOfferNormalizer.cleanScrapedGameTitle("Cranio Creations - Barrage - Edizione Italiana - Gioco da Tavolo di Strategia, 1-4 Giocatori"),
        )
        assertEquals(
            "Star Wars: The Deckbuilding Game – Mandaloriani (Edizione Italiana)",
            ScrapedOfferNormalizer.cleanScrapedGameTitle(
                "Asmodee - Star Wars: The Deckbuilding Game - Espansione Mandaloriani | Pack Fazione per Modalità Testa a Testa Gioco da Tavolo Star Wars - 2-3 Giocatori - 12+ Anni - 30 Minuti - Edizione Italiana",
            ),
        )
    }

    @Test
    fun `matchesSearchedGameTitle esclude giochi diversi o spin-off mantenendo edizioni e varianti dello stesso titolo`() {
        assertTrue(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders Dice", "7 Wonders: Dice"))
        assertTrue(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders: Dice", "7 Wonders: Dice, Edizione Italiana"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders Dice", "7 WONDERS DUEL"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders Dice", "7 Wonders: Nuova Edizione, Edizione Italiana"))

        assertTrue(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders", "7 Wonders: Nuova Edizione, Edizione Italiana"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders", "7 Wonders: Dice"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders", "7 Wonders Duel"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders", "7 Wonders – Espansione"))

        val cleanedMandaloriani = ScrapedOfferNormalizer.cleanScrapedGameTitle(
            "Asmodee - Star Wars: The Deckbuilding Game - Espansione Mandaloriani | Pack Fazione per Modalità Testa a Testa Gioco da Tavolo Star Wars - 2-3 Giocatori - 12+ Anni - 30 Minuti - Edizione Italiana",
        )
        assertTrue(
            ScrapedOfferNormalizer.matchesSearchedGameTitle(
                "Star Wars Deckbuilding Game - Mandaloriani",
                cleanedMandaloriani,
            ),
        )
        assertTrue(
            ScrapedOfferNormalizer.matchesSearchedGameTitle(
                "Star Wars Deckbuilding Game - Mandaloriani",
                "Star Wars: The Deckbuilding Game – The Mandalorian Faction Pack",
            ),
        )
        assertFalse(
            ScrapedOfferNormalizer.matchesSearchedGameTitle(
                "Star Wars Deckbuilding Game - Mandaloriani",
                "Star Wars: The Deckbuilding Game – Clone Wars",
            ),
        )
    }

    @Test
    fun `parse trova Star Wars Mandaloriani su Amazon e privilegia Edizione Italiana su Tedesco`() {
        val raw = """
            {
              "status": "ok",
              "items": [
                {
                  "title": "Asmodee, Star Wars: The Deckbuilding Game - Mandalorian, Espansione del pacchetto frazionario, FFG, Gioco di carte, 2-3 giocatori, 12+ anni, 30 minuti, Tedesco",
                  "price": "17,53 €",
                  "url": "/dp/B0DE123456",
                  "asin": "B0DE123456",
                  "isPrime": false,
                  "delivery": "Consegna GRATUITA ven, 27 nov"
                },
                {
                  "title": "Asmodee - Star Wars: The Deckbuilding Game - Espansione Mandaloriani | Pack Fazione per Modalità Testa a Testa Gioco da Tavolo Star Wars - 2-3 Giocatori - 12+ Anni - 30 Minuti - Edizione Italiana",
                  "price": "17,89 €",
                  "url": "/dp/B0IT123456",
                  "asin": "B0IT123456",
                  "isPrime": true,
                  "delivery": "Consegna GRATUITA domani, 23 nov"
                },
                {
                  "title": "Asmodee - Star Wars: The Deckbuilding Game - Clone Wars Edition",
                  "price": "34,90 €",
                  "url": "/dp/B0CW123456",
                  "asin": "B0CW123456",
                  "isPrime": true
                }
              ]
            }
        """.trimIndent()

        val parsed = ScraperJsonParser.parse(
            site = ExternalPriceSite.AMAZON_IT,
            rawJsResult = raw,
            searchQuery = "Star Wars Deckbuilding Game - Mandaloriani",
        ) as ParsedScriptStatus.Done
        val items = (parsed.result as ExternalScrapeResult.Success).items

        assertEquals(2, items.size)
        assertEquals("B0IT123456", items[0].asin)
        assertEquals("Star Wars: The Deckbuilding Game – Mandaloriani (Edizione Italiana)", items[0].title)
        assertEquals("B0DE123456", items[1].asin)
    }

    @Test
    fun `formatDeliverySummary estrae Prime e data concisa da consegna Amazon`() {
        val primeFast = ScrapedOfferNormalizer.formatDeliverySummary(
            site = ExternalPriceSite.AMAZON_IT,
            rawDelivery = "Consegna GRATUITA ven, 27 nov sul tuo primo ordine idoneo · oppure consegna più rapida domani, 23 nov",
            isPrime = false,
        )
        assertTrue(primeFast.isPrime)
        assertEquals("domani 23 nov", primeFast.text)

        val freeStd = ScrapedOfferNormalizer.formatDeliverySummary(
            site = ExternalPriceSite.AMAZON_IT,
            rawDelivery = "Consegna GRATUITA ven, 27 nov sul tuo primo ordine idoneo",
            isPrime = false,
        )
        assertFalse(freeStd.isPrime)
        assertEquals("Gratuita · ven 27 nov", freeStd.text)

        val preorder = ScrapedOfferNormalizer.formatDeliverySummary(
            site = ExternalPriceSite.AMAZON_IT,
            rawDelivery = "In uscita il 15 dic 2026 · Prenota ora",
            isPrime = true,
        )
        assertTrue(preorder.isPrime)
        assertEquals("In uscita · 15 dic 2026", preorder.text)
    }

    @Test
    fun `parse filtra i giochi diversi e pulisce titolo e consegna su Amazon`() {
        val raw = """
            {
              "status": "ok",
              "items": [
                {
                  "title": "Asmodee – 7 Wonders: Dice – Gioco da Tavolo Roll & Write",
                  "price": "19,99 €",
                  "originalPrice": "29,99 €",
                  "url": "/dp/B0FSKP6GL4",
                  "asin": "B0FSKP6GL4",
                  "isPrime": false,
                  "delivery": "Consegna GRATUITA ven, 27 nov sul tuo primo ordine idoneo · oppure consegna più rapida domani, 23 nov"
                },
                {
                  "title": "Asmodee – 7 WONDERS DUEL – Gioco da Tavolo di Strategia per 2 Giocatori",
                  "price": "17,49 €",
                  "url": "/dp/B014DMSTXK",
                  "asin": "B014DMSTXK",
                  "isPrime": true,
                  "delivery": "Consegna GRATUITA domani, 23 nov"
                },
                {
                  "title": "Asmodee 7 Wonders: Nuova Edizione, Edizione Italiana",
                  "price": "43,19 €",
                  "url": "/dp/B08B7N4S2Q",
                  "asin": "B08B7N4S2Q",
                  "isPrime": true,
                  "delivery": "Consegna GRATUITA gio, 26 nov"
                }
              ]
            }
        """.trimIndent()

        val parsed = ScraperJsonParser.parse(ExternalPriceSite.AMAZON_IT, raw, "7 Wonders: Dice") as ParsedScriptStatus.Done
        val items = (parsed.result as ExternalScrapeResult.Success).items

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("7 Wonders: Dice", item.title)
        assertEquals("19,99 €", item.price)
        assertEquals("29,99 €", item.originalPrice)
        assertEquals("B0FSKP6GL4", item.asin)
        assertTrue(item.isPrime)
        assertEquals("domani 23 nov", item.deliveryOrAvailability)
    }

    @Test
    fun `parse gestisce risultati DungeonDice e blocco Cloudflare`() {
        val raw = """
            {
              "status": "ok",
              "items": [
                {
                  "title": "Star Wars: The Deckbuilding Game - Mandaloriani - DungeonDice.it",
                  "price": "17,99 €",
                  "originalPrice": "19,99 €",
                  "url": "/42991-star-wars-the-deckbuilding-game-mandaloriani.html",
                  "delivery": "Disponibile"
                },
                {
                  "title": "Star Wars: The Deckbuilding Game",
                  "price": "34,99 €",
                  "url": "/31000-star-wars-the-deckbuilding-game.html",
                  "delivery": "Disponibile"
                }
              ]
            }
        """.trimIndent()

        val parsed = ScraperJsonParser.parse(
            site = ExternalPriceSite.DUNGEONDICE,
            rawJsResult = raw,
            searchQuery = "Star Wars Deckbuilding Game - Mandaloriani",
        ) as ParsedScriptStatus.Done
        val items = (parsed.result as ExternalScrapeResult.Success).items

        assertEquals(1, items.size)
        assertEquals("Star Wars: The Deckbuilding Game – Mandaloriani", items[0].title)
        assertEquals("17,99 €", items[0].price)
        assertEquals("19,99 €", items[0].originalPrice)
        assertEquals("https://www.dungeondice.it/42991-star-wars-the-deckbuilding-game-mandaloriani.html", items[0].url)
        assertEquals("Disponibile", items[0].deliveryOrAvailability)

        val blocked = ScraperJsonParser.parse(
            site = ExternalPriceSite.DUNGEONDICE,
            rawJsResult = """{"status":"blocked","reason":"DungeonDice richiede verifica Cloudflare."}""",
            searchQuery = "7 Wonders",
        ) as ParsedScriptStatus.Done
        assertEquals(
            ExternalScrapeResult.Blocked("DungeonDice richiede verifica Cloudflare."),
            blocked.result,
        )
    }

    @Test
    fun `parsa payload bloccato da anti-bot`() {
        val raw = """{"status":"blocked","reason":"Trovaprezzi richiede verifica Cloudflare."}"""

        val parsed = ScraperJsonParser.parse(ExternalPriceSite.TROVAPREZZI, raw, "7 Wonders")

        assertTrue(parsed is ParsedScriptStatus.Done)
        val result = (parsed as ParsedScriptStatus.Done).result
        assertEquals(
            ExternalScrapeResult.Blocked("Trovaprezzi richiede verifica Cloudflare."),
            result,
        )
    }

    @Test
    fun `parseKeepa decodifica PNG base64 e statistiche`() {
        val raw = """{"status":"ok","chartDataUrl":"data:image/png;base64,AQIDBA==","lowestPrice":"19,99 €","averagePrice":"24,50 €","highestPrice":"29,99 €"}"""

        val parsed = ScraperJsonParser.parseKeepa(raw) as ParsedKeepaStatus.Done
        val success = parsed.result as KeepaHistoryResult.Success

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), success.chartPngBytes)
        assertEquals("19,99 €", success.lowestPrice)
        assertEquals("24,50 €", success.averagePrice)
        assertEquals("29,99 €", success.highestPrice)
    }

    @Test
    fun `parseKeepa gestisce blocco e loading`() {
        assertEquals(ParsedKeepaStatus.Loading, ScraperJsonParser.parseKeepa(null))
        assertEquals(ParsedKeepaStatus.Loading, ScraperJsonParser.parseKeepa("""{"status":"loading"}"""))
        val blocked = ScraperJsonParser.parseKeepa("""{"status":"blocked","reason":"Login richiesto"}""") as ParsedKeepaStatus.Done
        assertEquals(KeepaHistoryResult.Unavailable("Login richiesto"), blocked.result)
    }
}
