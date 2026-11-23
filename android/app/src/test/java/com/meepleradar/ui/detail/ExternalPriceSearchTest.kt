package com.meepleradar.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPriceSearchTest {

    @Test
    fun `sanitizeSearchQuery rimuove suffissi edizione lingua e preordine`() {
        assertEquals(
            "7 Wonders Duel",
            sanitizeSearchQuery("7 Wonders Duel (Edizione Italiana) [Preordine]"),
        )
        assertEquals(
            "Brass: Birmingham",
            sanitizeSearchQuery("Brass: Birmingham - Edizione Italiana"),
        )
        assertEquals(
            "Ark Nova",
            sanitizeSearchQuery("Ark Nova (ITA) - Gioco da Tavolo"),
        )
    }

    @Test
    fun `sanitizeSearchQuery usa il fallback se il titolo primario e vuoto`() {
        assertEquals(
            "Dune: Imperium",
            sanitizeSearchQuery("   ", fallbackTitle = "Dune: Imperium (ITA)"),
        )
        assertEquals("", sanitizeSearchQuery("   ", fallbackTitle = null))
    }

    @Test
    fun `buildExternalPriceSearchLinks genera tutti i 4 siti affiancati con codifica corretta`() {
        val links = buildExternalPriceSearchLinks("7 Wonders Duel (Edizione Italiana)")

        assertEquals(
            listOf(
                ExternalPriceSite.AMAZON_IT,
                ExternalPriceSite.TROVAPREZZI,
                ExternalPriceSite.IDEALO,
                ExternalPriceSite.DUNGEONDICE,
            ),
            links.map { it.site },
        )

        val bySite = links.associate { it.site to it.url }
        assertEquals(
            "https://www.amazon.it/s?k=7+Wonders+Duel+gioco+da+tavolo",
            bySite[ExternalPriceSite.AMAZON_IT],
        )
        assertEquals(
            "https://www.trovaprezzi.it/categoria.aspx?libera=7+Wonders+Duel",
            bySite[ExternalPriceSite.TROVAPREZZI],
        )
        assertEquals(
            "https://www.idealo.it/risultati.html?q=7+Wonders+Duel",
            bySite[ExternalPriceSite.IDEALO],
        )
        assertEquals(
            "https://www.dungeondice.it/ricerca?controller=search&s=7+Wonders+Duel",
            bySite[ExternalPriceSite.DUNGEONDICE],
        )
    }

    @Test
    fun `buildExternalPriceSearchLinks non duplica gioco da tavolo su Amazon e gestisce titolo vuoto`() {
        val links = buildExternalPriceSearchLinks("Risiko Gioco da Tavolo Classico")
        val amazonUrl = links.single { it.site == ExternalPriceSite.AMAZON_IT }.url

        assertEquals(
            "https://www.amazon.it/s?k=Risiko+Gioco+da+Tavolo+Classico",
            amazonUrl,
        )
        assertTrue(buildExternalPriceSearchLinks("   ").isEmpty())
    }

    @Test
    fun `buildKeepaProductUrl e buildCamelProductUrl normalizzano ASIN`() {
        assertEquals(
            "https://keepa.com/#!product/8-B0FSKP6GL4",
            buildKeepaProductUrl(" b0fskp6gl4 "),
        )
        assertEquals(
            "https://it.camelcamelcamel.com/product/B0FSKP6GL4",
            buildCamelProductUrl(" b0fskp6gl4 "),
        )
    }
}
