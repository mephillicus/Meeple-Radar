package com.meepleradar.ui.detail

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSiteScraperParserTest {

    @Test
    fun `cleanScrapedGameTitle rimuove marca e descrittori generici mantenendo sottotitolo ed edizione`() {
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
    }

    @Test
    fun `matchesSearchedGameTitle esclude giochi diversi o spin-off mantenendo edizioni dello stesso gioco`() {
        assertTrue(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders Dice", "7 Wonders: Dice"))
        assertTrue(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders: Dice", "7 Wonders: Dice, Edizione Italiana"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders Dice", "7 WONDERS DUEL"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders Dice", "7 Wonders: Nuova Edizione, Edizione Italiana"))

        assertTrue(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders", "7 Wonders: Nuova Edizione, Edizione Italiana"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders", "7 Wonders: Dice"))
        assertFalse(ScrapedOfferNormalizer.matchesSearchedGameTitle("7 Wonders", "7 Wonders Duel"))
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
