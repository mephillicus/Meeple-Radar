package com.meepleradar.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSiteScraperParserTest {

    @Test
    fun `parsa payload JSON di Amazon codificato come stringa da evaluateJavascript`() {
        val jsReturned = """"{\"status\":\"ok\",\"items\":[{\"title\":\"7 Wonders Duel   ITA\",\"price\":\"24,90 €\",\"originalPrice\":\"29,90 €\",\"url\":\"/7-Wonders-Duel/dp/b014dmstxk/ref=sr_1_1\",\"asin\":\"b014dmstxk\",\"isPrime\":true,\"delivery\":\"Consegna domani\",\"meta\":null}]}""""

        val parsed = ScraperJsonParser.parse(ExternalPriceSite.AMAZON_IT, jsReturned)

        assertTrue(parsed is ParsedScriptStatus.Done)
        val result = (parsed as ParsedScriptStatus.Done).result
        assertTrue(result is ExternalScrapeResult.Success)
        val item = (result as ExternalScrapeResult.Success).items.single()
        assertEquals("7 Wonders Duel ITA", item.title)
        assertEquals("24,90 €", item.price)
        assertEquals("29,90 €", item.originalPrice)
        assertEquals("B014DMSTXK", item.asin)
        assertEquals("https://www.amazon.it/dp/B014DMSTXK", item.url)
        assertTrue(item.isPrime)
        assertEquals("Consegna domani", item.deliveryOrAvailability)
    }

    @Test
    fun `parsa payload bloccato da anti-bot`() {
        val raw = """{"status":"blocked","reason":"Trovaprezzi richiede verifica Cloudflare."}"""

        val parsed = ScraperJsonParser.parse(ExternalPriceSite.TROVAPREZZI, raw)

        assertTrue(parsed is ParsedScriptStatus.Done)
        val result = (parsed as ParsedScriptStatus.Done).result
        assertEquals(
            ExternalScrapeResult.Blocked("Trovaprezzi richiede verifica Cloudflare."),
            result,
        )
    }

    @Test
    fun `risolve URL relativi per Trovaprezzi e Idealo e limita a 4 risultati`() {
        val raw = """
            {
              "status": "ok",
              "items": [
                {"title": "Gioco 1", "price": "20,00 €", "url": "/offerta/1", "delivery": "+ 4,90 €", "meta": "Negozio A"},
                {"title": "Gioco 2", "price": "21,00 €", "url": "//www.idealo.it/offerta/2", "meta": "10 offerte"},
                {"title": "Gioco 3", "price": "22,00 €", "url": "https://www.idealo.it/offerta/3"},
                {"title": "Gioco 4", "price": "23,00 €", "url": "offerta/4"},
                {"title": "Gioco 5", "price": "24,00 €", "url": "/offerta/5"}
              ]
            }
        """.trimIndent()

        val parsed = ScraperJsonParser.parse(ExternalPriceSite.IDEALO, raw) as ParsedScriptStatus.Done
        val items = (parsed.result as ExternalScrapeResult.Success).items

        assertEquals(4, items.size)
        assertEquals("https://www.idealo.it/offerta/1", items[0].url)
        assertEquals("https://www.idealo.it/offerta/2", items[1].url)
        assertEquals("https://www.idealo.it/offerta/3", items[2].url)
        assertEquals("https://www.idealo.it/offerta/4", items[3].url)
        assertFalse(items[0].isPrime)
    }

    @Test
    fun `ritorna Loading su null o status loading`() {
        assertEquals(ParsedScriptStatus.Loading, ScraperJsonParser.parse(ExternalPriceSite.AMAZON_IT, null))
        assertEquals(ParsedScriptStatus.Loading, ScraperJsonParser.parse(ExternalPriceSite.AMAZON_IT, "null"))
        assertEquals(
            ParsedScriptStatus.Loading,
            ScraperJsonParser.parse(ExternalPriceSite.AMAZON_IT, """{"status":"loading"}"""),
        )
    }
}
