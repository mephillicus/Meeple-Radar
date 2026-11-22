package com.meepleradar.ui.detail

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class ExternalPriceSite(val label: String, val baseUrl: String) {
    AMAZON_IT("Amazon.it", "https://www.amazon.it"),
    TROVAPREZZI("Trovaprezzi", "https://www.trovaprezzi.it"),
    IDEALO("Idealo", "https://www.idealo.it"),
}

data class ExternalPriceSearchLink(
    val site: ExternalPriceSite,
    val url: String,
)

enum class KeepaRange(val days: Int, val label: String) {
    DAYS_30(30, "30g"),
    DAYS_90(90, "90g"),
    DAYS_365(365, "1a"),
}

private val NOISE_BRACKETS_REGEX = Regex(
    pattern = """\s*[(\[]\s*(?:preordine|preorder|edizione\s+italiana|ed\.?\s*it(?:aliana)?|italiano|ita|eng|en|nuova\s+edizione|gioco\s+da\s+tavolo|board\s*game)\s*[)\]]""",
    option = RegexOption.IGNORE_CASE,
)

private val TRAILING_NOISE_REGEX = Regex(
    pattern = """\s*[-–|]\s*(?:preordine|edizione\s+italiana|ed\.?\s*it(?:aliana)?|italiano|ita|gioco\s+da\s+tavolo)\s*$""",
    option = RegexOption.IGNORE_CASE,
)

private val MULTIPLE_SPACES_REGEX = Regex("""\s+""")

internal fun sanitizeSearchQuery(rawTitle: String, fallbackTitle: String? = null): String {
    val base = rawTitle.trim().ifBlank { fallbackTitle?.trim().orEmpty() }
    if (base.isBlank()) return ""

    var cleaned = base
    var previous: String
    do {
        previous = cleaned
        cleaned = cleaned
            .replace(NOISE_BRACKETS_REGEX, " ")
            .replace(TRAILING_NOISE_REGEX, "")
            .replace(MULTIPLE_SPACES_REGEX, " ")
            .trim()
    } while (cleaned != previous)

    return cleaned.ifBlank { base.replace(MULTIPLE_SPACES_REGEX, " ").trim() }
}

internal fun buildExternalPriceSearchLinks(
    rawTitle: String,
    fallbackTitle: String? = null,
): List<ExternalPriceSearchLink> {
    val query = sanitizeSearchQuery(rawTitle, fallbackTitle)
    if (query.isBlank()) return emptyList()

    val amazonQuery = if (query.contains("gioco da tavolo", ignoreCase = true)) {
        query
    } else {
        "$query gioco da tavolo"
    }

    val encodedQuery = encodeQueryParam(query)
    val encodedAmazonQuery = encodeQueryParam(amazonQuery)

    return listOf(
        ExternalPriceSearchLink(
            site = ExternalPriceSite.AMAZON_IT,
            url = "https://www.amazon.it/s?k=$encodedAmazonQuery",
        ),
        ExternalPriceSearchLink(
            site = ExternalPriceSite.TROVAPREZZI,
            url = "https://www.trovaprezzi.it/categoria.aspx?libera=$encodedQuery",
        ),
        ExternalPriceSearchLink(
            site = ExternalPriceSite.IDEALO,
            url = "https://www.idealo.it/risultati.html?q=$encodedQuery",
        ),
    )
}

internal fun buildKeepaProductUrl(asin: String): String {
    val cleanAsin = asin.trim().uppercase()
    return "https://keepa.com/#!product/8-$cleanAsin"
}

internal fun buildCamelProductUrl(asin: String): String {
    val cleanAsin = asin.trim().uppercase()
    return "https://it.camelcamelcamel.com/product/$cleanAsin"
}

private fun encodeQueryParam(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())
