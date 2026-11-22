package com.meepleradar.ui.detail

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.text.Normalizer
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class ScrapedOfferItem(
    val title: String,
    val price: String?,
    val originalPrice: String? = null,
    val url: String,
    val asin: String? = null,
    val isPrime: Boolean = false,
    val deliveryOrAvailability: String? = null,
    val sellerOrMeta: String? = null,
)

sealed interface ExternalScrapeResult {
    data class Success(val items: List<ScrapedOfferItem>) : ExternalScrapeResult
    data class Blocked(val reason: String) : ExternalScrapeResult
    data class Empty(val message: String) : ExternalScrapeResult
    data class Error(val message: String) : ExternalScrapeResult
}

sealed interface KeepaHistoryResult {
    data class Success(
        val chartPngBytes: ByteArray?,
        val currentPrice: String? = null,
        val lowestPrice: String? = null,
        val highestPrice: String? = null,
        val averagePrice: String? = null,
    ) : KeepaHistoryResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return chartPngBytes.contentEquals(other.chartPngBytes) &&
                currentPrice == other.currentPrice &&
                lowestPrice == other.lowestPrice &&
                highestPrice == other.highestPrice &&
                averagePrice == other.averagePrice
        }

        override fun hashCode(): Int {
            var result = chartPngBytes?.contentHashCode() ?: 0
            result = 31 * result + (currentPrice?.hashCode() ?: 0)
            result = 31 * result + (lowestPrice?.hashCode() ?: 0)
            result = 31 * result + (highestPrice?.hashCode() ?: 0)
            result = 31 * result + (averagePrice?.hashCode() ?: 0)
            return result
        }
    }

    data class Unavailable(val reason: String) : KeepaHistoryResult
}

internal interface ExternalSiteScraper {
    suspend fun scrape(
        site: ExternalPriceSite,
        searchUrl: String,
        searchQuery: String,
    ): ExternalScrapeResult

    suspend fun scrapeKeepaHistory(
        asin: String,
        range: KeepaRange,
    ): KeepaHistoryResult = KeepaHistoryResult.Unavailable("Storico Keepa non disponibile.")
}

internal data class FormattedDelivery(
    val text: String?,
    val isPrime: Boolean,
)

internal object ScrapedOfferNormalizer {
    private val whitespaceRegex = Regex("""\s+""")
    private val diacriticsRegex = Regex("""\p{Mn}+""")
    private val nonAlnumRegex = Regex("""[^a-z0-9]+""")

    private val leadingBrandRegex = Regex(
        pattern = """^(?:asmod[eé]e|cranio\s+creations|cranio|giochi\s+uniti|dv\s+games|dv\s+giochi|ghenos\s+games|ghenos|ms\s+edizioni|pendragon\s+game\s+studio|pendragon|ravensburger|hasbro\s+gaming|hasbro|mattel\s+games|mattel|clementoni|spin\s+master|kosmos|czech\s+games\s+edition|cge|stonemaier\s+games|fantasy\s+flight\s+games|z-man\s+games|days\s+of\s+wonder|repos\s+production|devir|studio\s+supernova|need\s+games|gateongames|little\s+rocket\s+games|oliphante|tesla\s+games|mancalamaro|lucky\s+duck\s+games|cmon|horrible\s+guild|red\s+glove|ergo\s+ludo|creosamente|djeco|smartgames|tactic|dal\s+negro|editrice\s+giochi|yas!\s*games|exploding\s+kittens|board\s*&\s*dice|lookout\s+games|pegasus\s+spiele|iello|libellud|space\s+cowboys|next\s+move\s+games|plan\s+b\s+games|portal\s+games|awaken\s+realms|cephalofair|leder\s+games|capstone\s+games|hans\s+im\s+gl[uü]ck|rio\s+grande\s+games|queen\s+games|amigo|schmidt|haba|uplay\.it)\b(?:\s*[-–—:|,]\s*|\s+)""",
        option = RegexOption.IGNORE_CASE,
    )

    private val trailingBrandRegex = Regex(
        pattern = """\s*[-–—|,]\s*(?:by\s+)?(?:asmod[eé]e|cranio\s+creations|giochi\s+uniti|dv\s+games|ghenos\s+games|ms\s+edizioni|pendragon|ravensburger|hasbro|mattel|clementoni|devir|studio\s+supernova|mancalamaro|lucky\s+duck\s+games|repos\s+production)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val parentheticalNoiseRegex = Regex(
        pattern = """\s*[(\[]\s*(?:gioc[oi]\s+(?:da\s+tavolo|di\s+carte|di\s+societ[àa]|di\s+strategia|di\s+dadi|cooperativo|per\s+famiglie)|party\s+game|board\s*game|card\s+game|roll\s*(?:&|and)\s*write|flip\s*(?:&|and)\s*write|\d+\s*[-–]\s*\d+\s*giocatori|\d+\+?\s*anni|versione\s+in\s+italiano)[^)\]]*[)\]]""",
        option = RegexOption.IGNORE_CASE,
    )

    private val segmentSplitRegex = Regex("""\s+[-–—|]\s+""")

    private val marketingSegmentRegex = Regex(
        pattern = """^(?:gioc[oi]\s+(?:da\s+tavolo|di\s+carte|di\s+societ[àa]|di\s+strategia|di\s+dadi|di\s+ruolo|di\s+abilit[àa]|cooperativo|investigativo|educativo|per\s+famiglie|per\s+bambini|per\s+adulti|in\s+scatola)|party\s+game|board\s*game|card\s+game|roll\s*(?:&|and)\s*write|flip\s*(?:&|and)\s*write|deck\s*building|per\s+\d+(?:\s*[-–]\s*\d+)?\s*giocatori|\d+(?:\s*[-–]\s*\d+)?\s*giocatori|da\s+\d+\s*anni|\d+\+\s*anni|\d+\s*[-–]?\s*\d*\s*min(?:uti)?|idea\s+regalo|regalo\s+per|et[àa]\s*\d+|cod\.?\s*[a-z0-9]+|[a-z]{2,5}\d{3,}[a-z0-9]*)(?:\b.*)?$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val marketingInlinePrefixRegex = Regex(
        pattern = """(?:,\s*|\s+)(?:gioc[oi]\s+(?:da\s+tavolo|di\s+carte|di\s+societ[àa]|di\s+strategia|di\s+dadi|cooperativo|per\s+famiglie)|party\s+game|board\s*game|card\s+game|roll\s*(?:&|and)\s*write|per\s+\d+(?:\s*[-–]\s*\d+)?\s*giocatori|da\s+\d+\s*anni|\d+\+\s*anni).*$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val stopWords = setOf(
        "il", "lo", "la", "i", "gli", "le", "un", "uno", "una",
        "di", "del", "dello", "della", "dei", "degli", "delle",
        "da", "in", "per", "con", "su", "e", "ed", "a", "al",
        "the", "of", "and", "to", "for", "an",
    )

    private val editionNeutralTokens = setOf(
        "edizione", "edition", "ed", "italiana", "italiano", "ita", "it",
        "inglese", "english", "eng", "en", "nuova", "new", "seconda", "terza",
        "2a", "3a", "2nd", "3rd", "revised", "rev", "riveduta", "aggiornata",
        "base", "gioco", "versione", "version", "scatola", "standard", "retail",
        "classic", "classico", "original", "originale", "special", "speciale",
        "deluxe", "collector", "collectors", "anniversary", "anniversario",
        "preorder", "preordine", "lingua",
    )

    private val yearTokenRegex = Regex("""^(?:199\d|20[0-3]\d)$""")
    private val skuCodeRegex = Regex("""^[a-z]{2,5}\d{3,}[a-z0-9]*$""")

    private val releaseDateRegex = Regex(
        pattern = """(?:in\s+uscita(?:\s+il)?|disponibile\s+dal|data\s+di\s+uscita:?)\s+([a-z0-9 ,]{3,28}?)(?=\s*(?:·|\.|oppure|consegna|prenota|$))""",
        option = RegexOption.IGNORE_CASE,
    )
    private val fasterDeliveryRegex = Regex(
        pattern = """consegna\s+pi[uù]\s+rapida\s+([a-z0-9 ,]{3,28}?)(?=\s*(?:·|\.|se\s+ordini|entro|con\b|sul\b|per\b|$))""",
        option = RegexOption.IGNORE_CASE,
    )
    private val freeDeliveryRegex = Regex(
        pattern = """consegna\s+gratuita\s+([a-z0-9 ,]{3,28}?)(?=\s*(?:·|\.|sul\s+tuo|per\s+ordini|se\s+ordini|oppure|entro|$))""",
        option = RegexOption.IGNORE_CASE,
    )
    private val genericDeliveryRegex = Regex(
        pattern = """consegna(?:\s+prevista)?\s+([a-z0-9 ,]{3,28}?)(?=\s*(?:·|\.|sul\s+tuo|per\s+ordini|se\s+ordini|oppure|entro|$))""",
        option = RegexOption.IGNORE_CASE,
    )
    private val stockCountRegex = Regex(
        pattern = """solo\s+\d+\s+disponibil\w*""",
        option = RegexOption.IGNORE_CASE,
    )

    fun cleanScrapedGameTitle(rawTitle: String): String {
        val original = rawTitle.replace(whitespaceRegex, " ").trim()
        if (original.isBlank()) return ""

        val text = original
            .replace(leadingBrandRegex, "")
            .replace(trailingBrandRegex, "")
            .replace(parentheticalNoiseRegex, " ")
            .replace(whitespaceRegex, " ")
            .trim()

        val segments = text.split(segmentSplitRegex)
        val keptSegments = mutableListOf<String>()
        for (rawSeg in segments) {
            var seg = rawSeg.trim().trimEnd(',', ';', '-', '–', '—')
            if (seg.isEmpty()) continue
            if (keptSegments.isNotEmpty() && marketingSegmentRegex.matches(seg)) {
                continue
            }
            if (keptSegments.isNotEmpty()) {
                seg = seg.replace(marketingInlinePrefixRegex, "").trim().trimEnd(',', ';')
            } else {
                seg = seg.replace(marketingInlinePrefixRegex, "").trim().trimEnd(',', ';')
            }
            if (seg.isNotEmpty() && !(keptSegments.isNotEmpty() && marketingSegmentRegex.matches(seg))) {
                keptSegments += seg
            }
        }

        val joined = keptSegments.joinToString(" – ").replace(whitespaceRegex, " ").trim()
        return joined.ifBlank { original }
    }

    fun matchesSearchedGameTitle(searchQuery: String, cleanedCandidateTitle: String): Boolean {
        val queryTokens = significantTokens(searchQuery)
        if (queryTokens.isEmpty()) return true
        val candidateTokens = significantTokens(cleanedCandidateTitle)
        if (candidateTokens.isEmpty()) return false

        val candidateSet = candidateTokens.toSet()
        if (!queryTokens.all { it in candidateSet }) {
            return false
        }

        val querySet = queryTokens.toSet()
        val extraMeaningfulTokens = candidateTokens.filter { token ->
            token !in querySet &&
                token !in editionNeutralTokens &&
                !yearTokenRegex.matches(token) &&
                !skuCodeRegex.matches(token)
        }
        return extraMeaningfulTokens.isEmpty()
    }

    fun formatDeliverySummary(
        site: ExternalPriceSite,
        rawDelivery: String?,
        isPrime: Boolean,
    ): FormattedDelivery {
        val cleaned = rawDelivery?.replace(whitespaceRegex, " ")?.trim()
        if (site != ExternalPriceSite.AMAZON_IT) {
            return FormattedDelivery(text = cleaned?.takeIf { it.isNotEmpty() }, isPrime = isPrime)
        }
        if (cleaned.isNullOrBlank()) {
            return FormattedDelivery(text = null, isPrime = isPrime)
        }

        val hasFastOrPrimeHint = isPrime ||
            cleaned.contains("consegna più rapida", ignoreCase = true) ||
            cleaned.contains("consegna piu rapida", ignoreCase = true) ||
            cleaned.contains("prime", ignoreCase = true)

        releaseDateRegex.find(cleaned)?.groupValues?.getOrNull(1)?.let { dateRaw ->
            val formattedDate = normalizeDateFragment(dateRaw)
            if (formattedDate.isNotEmpty()) {
                return FormattedDelivery(
                    text = "In uscita · $formattedDate",
                    isPrime = hasFastOrPrimeHint,
                )
            }
        }

        fasterDeliveryRegex.find(cleaned)?.groupValues?.getOrNull(1)?.let { dateRaw ->
            val formattedDate = normalizeDateFragment(dateRaw)
            if (formattedDate.isNotEmpty()) {
                return FormattedDelivery(
                    text = formattedDate,
                    isPrime = true,
                )
            }
        }

        freeDeliveryRegex.find(cleaned)?.groupValues?.getOrNull(1)?.let { dateRaw ->
            val formattedDate = normalizeDateFragment(dateRaw)
            if (formattedDate.isNotEmpty()) {
                val label = if (hasFastOrPrimeHint) formattedDate else "Gratuita · $formattedDate"
                return FormattedDelivery(
                    text = label,
                    isPrime = hasFastOrPrimeHint,
                )
            }
        }

        genericDeliveryRegex.find(cleaned)?.groupValues?.getOrNull(1)?.let { dateRaw ->
            val formattedDate = normalizeDateFragment(dateRaw)
            if (formattedDate.isNotEmpty()) {
                val label = if (hasFastOrPrimeHint) formattedDate else "Consegna · $formattedDate"
                return FormattedDelivery(
                    text = label,
                    isPrime = hasFastOrPrimeHint,
                )
            }
        }

        stockCountRegex.find(cleaned)?.value?.let { stock ->
            return FormattedDelivery(
                text = stock.replaceFirstChar { it.uppercase() },
                isPrime = hasFastOrPrimeHint,
            )
        }

        if (cleaned.contains("spedito da amazon", ignoreCase = true)) {
            return FormattedDelivery(text = "Spedito da Amazon", isPrime = hasFastOrPrimeHint)
        }
        if (cleaned.contains("disponibil", ignoreCase = true)) {
            return FormattedDelivery(text = "Disponibile", isPrime = hasFastOrPrimeHint)
        }
        return FormattedDelivery(
            text = cleaned.take(48),
            isPrime = hasFastOrPrimeHint,
        )
    }

    private fun normalizeDateFragment(raw: String): String =
        raw.replace(",", " ")
            .replace(whitespaceRegex, " ")
            .trim()
            .trimEnd('.', ';', '-')

    private fun significantTokens(text: String): List<String> {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(diacriticsRegex, "")
            .replace(nonAlnumRegex, " ")
            .trim()
        if (normalized.isEmpty()) return emptyList()
        val all = normalized.split(" ").filter { it.isNotEmpty() }
        val filtered = all.filterNot { it in stopWords }
        return filtered.ifEmpty { all }
    }
}

internal object ScraperJsonParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val asinRegex = Regex("""/(?:dp|gp/product)/([A-Z0-9]{10})""", RegexOption.IGNORE_CASE)
    private val validAsinRegex = Regex("""^[A-Z0-9]{10}$""")
    private val whitespaceRegex = Regex("""\s+""")

    fun parse(
        site: ExternalPriceSite,
        rawJsResult: String?,
        searchQuery: String = "",
    ): ParsedScriptStatus {
        val root = unwrapJsonObject(rawJsResult) ?: return when {
            rawJsResult.isNullOrBlank() || rawJsResult == "null" || rawJsResult == "undefined" ->
                ParsedScriptStatus.Loading
            else -> ParsedScriptStatus.Done(
                ExternalScrapeResult.Error("Formato dati non valido da ${site.label}."),
            )
        }

        return when (root.stringField("status")) {
            "loading" -> ParsedScriptStatus.Loading
            "blocked" -> ParsedScriptStatus.Done(
                ExternalScrapeResult.Blocked(
                    root.stringField("reason")
                        ?: "${site.label} richiede una verifica anti-bot.",
                ),
            )
            "ok" -> {
                val rawItems = root["items"]?.jsonArray.orEmpty()
                val parsedItems = rawItems.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    val rawTitle = cleanText(obj.stringField("title")) ?: return@mapNotNull null
                    val cleanedTitle = ScrapedOfferNormalizer.cleanScrapedGameTitle(rawTitle)
                    if (searchQuery.isNotBlank() &&
                        !ScrapedOfferNormalizer.matchesSearchedGameTitle(searchQuery, cleanedTitle)
                    ) {
                        return@mapNotNull null
                    }
                    val rawUrl = obj.stringField("url").orEmpty().trim()
                    val asin = normalizeAsin(obj.stringField("asin"), rawUrl)
                    val resolvedUrl = resolveOfferUrl(site, rawUrl, asin)
                    val formattedDelivery = ScrapedOfferNormalizer.formatDeliverySummary(
                        site = site,
                        rawDelivery = obj.stringField("delivery"),
                        isPrime = obj.booleanField("isPrime"),
                    )
                    ScrapedOfferItem(
                        title = cleanedTitle,
                        price = cleanText(obj.stringField("price")),
                        originalPrice = cleanText(obj.stringField("originalPrice")),
                        url = resolvedUrl,
                        asin = asin,
                        isPrime = formattedDelivery.isPrime,
                        deliveryOrAvailability = formattedDelivery.text,
                        sellerOrMeta = cleanText(obj.stringField("meta")),
                    )
                }.take(4)

                if (parsedItems.isEmpty()) {
                    val emptyMsg = if (searchQuery.isNotBlank()) {
                        "Nessuna corrispondenza esatta per \"$searchQuery\" su ${site.label}."
                    } else {
                        "Nessun risultato trovato su ${site.label}."
                    }
                    ParsedScriptStatus.Done(ExternalScrapeResult.Empty(emptyMsg))
                } else {
                    ParsedScriptStatus.Done(ExternalScrapeResult.Success(parsedItems))
                }
            }
            "empty" -> ParsedScriptStatus.Done(
                ExternalScrapeResult.Empty(
                    root.stringField("reason") ?: "Nessun risultato trovato su ${site.label}.",
                ),
            )
            else -> ParsedScriptStatus.Loading
        }
    }

    fun parseKeepa(rawJsResult: String?): ParsedKeepaStatus {
        val root = unwrapJsonObject(rawJsResult) ?: return when {
            rawJsResult.isNullOrBlank() || rawJsResult == "null" || rawJsResult == "undefined" ->
                ParsedKeepaStatus.Loading
            else -> ParsedKeepaStatus.Done(
                KeepaHistoryResult.Unavailable("Impossibile leggere lo storico da Keepa."),
            )
        }

        return when (root.stringField("status")) {
            "loading" -> ParsedKeepaStatus.Loading
            "blocked" -> ParsedKeepaStatus.Done(
                KeepaHistoryResult.Unavailable(
                    root.stringField("reason") ?: "Keepa richiede accesso o verifica nel browser.",
                ),
            )
            "ok" -> {
                val dataUrl = root.stringField("chartDataUrl")
                val pngBytes = decodeDataUrlPng(dataUrl)
                val currentPrice = cleanText(root.stringField("currentPrice"))
                val lowestPrice = cleanText(root.stringField("lowestPrice"))
                val highestPrice = cleanText(root.stringField("highestPrice"))
                val averagePrice = cleanText(root.stringField("averagePrice"))
                if (pngBytes == null && currentPrice == null && lowestPrice == null) {
                    ParsedKeepaStatus.Done(
                        KeepaHistoryResult.Unavailable("Grafico Keepa non disponibile."),
                    )
                } else {
                    ParsedKeepaStatus.Done(
                        KeepaHistoryResult.Success(
                            chartPngBytes = pngBytes,
                            currentPrice = currentPrice,
                            lowestPrice = lowestPrice,
                            highestPrice = highestPrice,
                            averagePrice = averagePrice,
                        ),
                    )
                }
            }
            else -> ParsedKeepaStatus.Loading
        }
    }

    private fun decodeDataUrlPng(dataUrl: String?): ByteArray? {
        if (dataUrl.isNullOrBlank()) return null
        val marker = "base64,"
        val idx = dataUrl.indexOf(marker)
        val encoded = if (idx >= 0) dataUrl.substring(idx + marker.length) else dataUrl
        return runCatching { Base64.getDecoder().decode(encoded) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun unwrapJsonObject(rawJsResult: String?): JsonObject? {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null" || rawJsResult == "undefined") {
            return null
        }
        val unwrapped = runCatching {
            val trimmed = rawJsResult.trim()
            if (trimmed.startsWith("\"")) {
                json.decodeFromString<String>(trimmed)
            } else {
                trimmed
            }
        }.getOrNull() ?: return null

        return runCatching { json.parseToJsonElement(unwrapped).jsonObject }.getOrNull()
    }

    private fun normalizeAsin(rawAsin: String?, url: String): String? {
        val candidate = rawAsin?.trim()?.uppercase()
        if (candidate != null && validAsinRegex.matches(candidate)) {
            return candidate
        }
        return asinRegex.find(url)?.groupValues?.getOrNull(1)?.uppercase()
    }

    private fun resolveOfferUrl(site: ExternalPriceSite, rawUrl: String, asin: String?): String {
        if (site == ExternalPriceSite.AMAZON_IT && asin != null) {
            return "${site.baseUrl}/dp/$asin"
        }
        return when {
            rawUrl.startsWith("https://", ignoreCase = true) ||
                rawUrl.startsWith("http://", ignoreCase = true) -> rawUrl
            rawUrl.startsWith("//") -> "https:$rawUrl"
            rawUrl.startsWith("/") -> "${site.baseUrl}$rawUrl"
            rawUrl.isNotBlank() -> "${site.baseUrl}/$rawUrl"
            else -> site.baseUrl
        }
    }

    private fun cleanText(value: String?): String? =
        value?.replace(whitespaceRegex, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanField(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull == true
}

internal sealed interface ParsedScriptStatus {
    data object Loading : ParsedScriptStatus
    data class Done(val result: ExternalScrapeResult) : ParsedScriptStatus
}

internal sealed interface ParsedKeepaStatus {
    data object Loading : ParsedKeepaStatus
    data class Done(val result: KeepaHistoryResult) : ParsedKeepaStatus
}

internal class WebViewExternalSiteScraper(context: Context) : ExternalSiteScraper {
    private val appContext = context.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun scrape(
        site: ExternalPriceSite,
        searchUrl: String,
        searchQuery: String,
    ): ExternalScrapeResult {
        return try {
            withTimeoutOrNull(SCRAPE_TIMEOUT) {
                withContext(Dispatchers.Main) {
                    runHeadlessWebView(site, searchUrl, searchQuery)
                }
            } ?: ExternalScrapeResult.Error("Timeout durante il caricamento di ${site.label}.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExternalScrapeResult.Error(e.message ?: "Errore durante l'estrazione da ${site.label}.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun scrapeKeepaHistory(
        asin: String,
        range: KeepaRange,
    ): KeepaHistoryResult {
        return try {
            withTimeoutOrNull(KEEPA_TIMEOUT) {
                withContext(Dispatchers.Main) {
                    runHeadlessKeepaWebView(asin, range)
                }
            } ?: KeepaHistoryResult.Unavailable("Timeout nel caricamento del grafico Keepa.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KeepaHistoryResult.Unavailable(e.message ?: "Impossibile caricare lo storico Keepa.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runHeadlessWebView(
        site: ExternalPriceSite,
        searchUrl: String,
        searchQuery: String,
    ): ExternalScrapeResult {
        val webView = WebView(appContext)
        val pageLoaded = AtomicBoolean(false)
        val mainFrameError = AtomicBoolean(false)
        val latestErrorMessage = StringBuilder()

        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = false
                blockNetworkImage = true
                cacheMode = WebSettings.LOAD_DEFAULT
                val defaultUa = WebSettings.getDefaultUserAgent(appContext)
                userAgentString = defaultUa.replace("; wv)", ")").replace("; wv;", ";")
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded.set(true)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        mainFrameError.set(true)
                        if (latestErrorMessage.isEmpty()) {
                            latestErrorMessage.append(error?.description ?: "Errore di rete")
                        }
                    }
                }
            }

            webView.loadUrl(searchUrl)

            val script = extractionScriptFor(site)
            var emptyPollsAfterLoad = 0

            for (attempt in 0 until MAX_POLL_ATTEMPTS) {
                delay(if (attempt == 0) INITIAL_WAIT_MS else POLL_INTERVAL_MS)

                if (mainFrameError.get()) {
                    val reason = latestErrorMessage.toString().ifBlank { "Impossibile caricare ${site.label}." }
                    return ExternalScrapeResult.Error(reason)
                }

                val rawResult = evaluateJs(webView, script)
                when (val parsed = ScraperJsonParser.parse(site, rawResult, searchQuery)) {
                    ParsedScriptStatus.Loading -> Unit
                    is ParsedScriptStatus.Done -> {
                        val result = parsed.result
                        if (result is ExternalScrapeResult.Empty) {
                            if (pageLoaded.get()) {
                                emptyPollsAfterLoad++
                                if (emptyPollsAfterLoad >= 2) return result
                            }
                        } else {
                            return result
                        }
                    }
                }
            }

            return ExternalScrapeResult.Empty("Nessun dato leggibile trovato su ${site.label}.")
        } finally {
            destroyWebViewSafely(webView)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runHeadlessKeepaWebView(
        asin: String,
        range: KeepaRange,
    ): KeepaHistoryResult {
        val webView = WebView(appContext)
        val mainFrameError = AtomicBoolean(false)

        try {
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(KEEPA_VIEWPORT_WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(KEEPA_VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, KEEPA_VIEWPORT_WIDTH, KEEPA_VIEWPORT_HEIGHT)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
                val defaultUa = WebSettings.getDefaultUserAgent(appContext)
                userAgentString = defaultUa.replace("; wv)", ")").replace("; wv;", ";")
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        mainFrameError.set(true)
                    }
                }
            }

            webView.loadUrl(buildKeepaProductUrl(asin))
            val script = keepaExtractionScript(range.days)

            for (attempt in 0 until KEEPA_MAX_POLL_ATTEMPTS) {
                delay(if (attempt == 0) KEEPA_INITIAL_WAIT_MS else KEEPA_POLL_INTERVAL_MS)
                if (mainFrameError.get()) {
                    return KeepaHistoryResult.Unavailable("Errore di rete durante il caricamento di Keepa.")
                }

                val rawResult = evaluateJs(webView, script)
                when (val parsed = ScraperJsonParser.parseKeepa(rawResult)) {
                    ParsedKeepaStatus.Loading -> Unit
                    is ParsedKeepaStatus.Done -> return parsed.result
                }
            }

            return KeepaHistoryResult.Unavailable("Keepa non ha restituito il grafico in tempo utile.")
        } finally {
            destroyWebViewSafely(webView)
        }
    }

    private suspend fun destroyWebViewSafely(webView: WebView) {
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                webView.stopLoading()
                webView.webViewClient = WebViewClient()
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    private suspend fun evaluateJs(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { result ->
                if (cont.isActive) {
                    cont.resume(result)
                }
            }
        }

    companion object {
        private val SCRAPE_TIMEOUT = 12.seconds
        private const val INITIAL_WAIT_MS = 1_100L
        private const val POLL_INTERVAL_MS = 700L
        private const val MAX_POLL_ATTEMPTS = 14

        private val KEEPA_TIMEOUT = 13.seconds
        private const val KEEPA_INITIAL_WAIT_MS = 2_000L
        private const val KEEPA_POLL_INTERVAL_MS = 850L
        private const val KEEPA_MAX_POLL_ATTEMPTS = 12
        private const val KEEPA_VIEWPORT_WIDTH = 960
        private const val KEEPA_VIEWPORT_HEIGHT = 720
    }
}

private fun extractionScriptFor(site: ExternalPriceSite): String = when (site) {
    ExternalPriceSite.AMAZON_IT -> AMAZON_EXTRACTION_JS
    ExternalPriceSite.TROVAPREZZI -> TROVAPREZZI_EXTRACTION_JS
    ExternalPriceSite.IDEALO -> IDEALO_EXTRACTION_JS
}

private fun keepaExtractionScript(rangeDays: Int): String = """
    (function() {
      try {
        var rangeDays = $rangeDays;
        var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
        if (/access to price history blocked|too many requests|captcha|robot/i.test(bodyText)) {
          return JSON.stringify({ status: 'blocked', reason: 'Keepa ha limitato l\u2019accesso automatico.' });
        }
        var rangeId = rangeDays <= 31 ? 'range31' : (rangeDays <= 90 ? 'range90' : 'range365');
        var rangeEl = document.getElementById(rangeId) ||
          document.querySelector('[data-range="' + rangeDays + '"], input[value="' + rangeDays + '"]');
        if (rangeEl && !window.__mrRangeApplied) {
          window.__mrRangeApplied = rangeId;
          try { rangeEl.click(); } catch (_) {}
          return JSON.stringify({ status: 'loading' });
        }
        var graph = document.querySelector('#graph, .flot-base');
        if (!graph) {
          return JSON.stringify({ status: 'loading' });
        }
        var container = document.getElementById('graph') || graph.parentElement;
        if (!container) {
          return JSON.stringify({ status: 'loading' });
        }
        var canvases = Array.prototype.slice.call(container.querySelectorAll('canvas'));
        var baseCanvas = container.querySelector('canvas.flot-base') || canvases[0];
        if (!baseCanvas || baseCanvas.width < 120 || baseCanvas.height < 80) {
          return JSON.stringify({ status: 'loading' });
        }
        var baseCtx = baseCanvas.getContext('2d');
        var sample = baseCtx.getImageData(0, 0, baseCanvas.width, baseCanvas.height).data;
        var coloredPixels = 0;
        for (var p = 0; p < sample.length; p += 160) {
          var r = sample[p], g = sample[p + 1], b = sample[p + 2], a = sample[p + 3];
          if (a > 60 && (Math.abs(r - g) > 25 || Math.abs(g - b) > 25 || (r < 180 && g < 180 && b < 180))) {
            coloredPixels++;
            if (coloredPixels > 30) break;
          }
        }
        if (coloredPixels <= 30) {
          return JSON.stringify({ status: 'loading' });
        }

        var rect = container.getBoundingClientRect();
        var out = document.createElement('canvas');
        out.width = baseCanvas.width;
        out.height = baseCanvas.height;
        var ctx = out.getContext('2d');
        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, out.width, out.height);

        for (var i = 0; i < canvases.length; i++) {
          try {
            ctx.drawImage(canvases[i], 0, 0, out.width, out.height);
          } catch (_) {}
        }

        var scaleX = rect.width > 0 ? (out.width / rect.width) : 1;
        var scaleY = rect.height > 0 ? (out.height / rect.height) : 1;
        var labels = container.querySelectorAll('.tickLabel, .flot-tick-label');
        ctx.fillStyle = '#334155';
        ctx.font = 'bold 12px sans-serif';
        ctx.textBaseline = 'top';
        for (var l = 0; l < labels.length; l++) {
          var el = labels[l];
          var txt = (el.innerText || el.textContent || '').trim();
          if (!txt) continue;
          var lr = el.getBoundingClientRect();
          var x = (lr.left - rect.left) * scaleX;
          var y = (lr.top - rect.top) * scaleY;
          if (x >= 0 && y >= 0 && x < out.width && y < out.height) {
            ctx.fillText(txt, x, y);
          }
        }

        var dataUrl = out.toDataURL('image/png');
        var priceRegex = /€\s*\d+[.,]\d{2}|\d+[.,]\d{2}\s*€/;
        var currentPrice = null, lowestPrice = null, highestPrice = null, averagePrice = null;
        var rows = document.querySelectorAll('#statsTable tr, table tr');
        for (var rIdx = 0; rIdx < rows.length; rIdx++) {
          var rowTxt = (rows[rIdx].innerText || '').replace(/\s+/g, ' ').trim();
          if (!rowTxt) continue;
          var m = rowTxt.match(priceRegex);
          if (!m) continue;
          if (!lowestPrice && /lowest|minimo/i.test(rowTxt)) lowestPrice = m[0];
          else if (!highestPrice && /highest|massimo/i.test(rowTxt)) highestPrice = m[0];
          else if (!averagePrice && /average|medio|media/i.test(rowTxt)) averagePrice = m[0];
          else if (!currentPrice && /current|attuale/i.test(rowTxt)) currentPrice = m[0];
        }

        return JSON.stringify({
          status: 'ok',
          chartDataUrl: dataUrl,
          currentPrice: currentPrice,
          lowestPrice: lowestPrice,
          highestPrice: highestPrice,
          averagePrice: averagePrice
        });
      } catch (e) {
        return JSON.stringify({ status: 'blocked', reason: String(e) });
      }
    })();
""".trimIndent()

private val AMAZON_EXTRACTION_JS = """
    (function() {
      try {
        var textAll = (document.body && document.body.innerText) ? document.body.innerText : '';
        if (document.querySelector('#captchacharacters, form[action*="validateCaptcha"]') ||
            /digita i caratteri|type the characters|robot check/i.test(textAll)) {
          return JSON.stringify({ status: 'blocked', reason: 'Amazon richiede una verifica anti-bot (CAPTCHA).' });
        }
        var cards = Array.prototype.slice.call(document.querySelectorAll('[data-component-type="s-search-result"][data-asin]'));
        if (!cards.length) {
          if (document.readyState !== 'complete') return JSON.stringify({ status: 'loading' });
          return JSON.stringify({ status: 'empty', reason: 'Nessun risultato trovato su Amazon.it.' });
        }
        var organic = cards.filter(function(c) {
          var asin = (c.getAttribute('data-asin') || '').trim();
          if (!asin) return false;
          var isSponsored = !!c.querySelector('.puis-sponsored-label-text, [data-component-type="sp-sponsored-result"]') ||
            /\bSponsorizzato\b/i.test(c.innerText || '');
          return !isSponsored;
        });
        var list = organic.length ? organic : cards;
        var items = [];
        for (var i = 0; i < list.length && items.length < 12; i++) {
          var c = list[i];
          var asin = (c.getAttribute('data-asin') || '').trim();
          if (!asin) continue;
          var titleEl = c.querySelector('h2 a span, h2 span, .a-size-base-plus, .a-size-medium');
          var title = titleEl ? (titleEl.innerText || titleEl.textContent || '').trim() : '';
          if (!title) continue;
          var linkEl = c.querySelector('h2 a, a.a-link-normal[href*="/dp/"]');
          var url = linkEl ? (linkEl.getAttribute('href') || '') : ('/dp/' + asin);
          var priceEl = c.querySelector('.a-price:not([data-a-strike="true"]) .a-offscreen');
          var price = priceEl ? (priceEl.innerText || priceEl.textContent || '').trim() : '';
          if (!price) {
            var whole = c.querySelector('.a-price-whole');
            var frac = c.querySelector('.a-price-fraction');
            if (whole) {
              price = (whole.innerText || '').replace(/[^0-9]/g, '') + (frac ? (',' + (frac.innerText || '').replace(/[^0-9]/g, '')) : '') + ' €';
            }
          }
          var origEl = c.querySelector('.a-price[data-a-strike="true"] .a-offscreen, .a-text-price .a-offscreen');
          var orig = origEl ? (origEl.innerText || origEl.textContent || '').trim() : '';
          if (orig === price) orig = '';
          var deliveryBlock = c.querySelector('[data-cy="delivery-recipe"]');
          var deliveryText = deliveryBlock ? (deliveryBlock.innerText || '').replace(/\s+/g, ' ').trim() : '';
          var isPrime = !!c.querySelector('i.a-icon-prime, svg[aria-label*="Prime"], [aria-label*="Prime"], .s-prime') ||
            /consegna pi[uù] rapida/i.test(deliveryText);
          if (!deliveryText) {
            var deliveryParts = [];
            var deliveryNodes = c.querySelectorAll('.a-color-base, .a-color-secondary, span[aria-label]');
            for (var d = 0; d < deliveryNodes.length && deliveryParts.length < 2; d++) {
              var t = (deliveryNodes[d].innerText || '').replace(/\s+/g, ' ').trim();
              if (!t || t.length < 6 || t.length > 120) continue;
              if (/consegna|in uscita|disponibil|preordin|spedito da amazon|solo \d+/i.test(t)) {
                if (deliveryParts.indexOf(t) === -1) deliveryParts.push(t);
              }
            }
            deliveryText = deliveryParts.join(' · ');
          }
          items.push({
            title: title,
            price: price || null,
            originalPrice: orig || null,
            url: url,
            asin: asin,
            isPrime: isPrime,
            delivery: deliveryText || null,
            meta: null
          });
        }
        return JSON.stringify({ status: items.length ? 'ok' : 'empty', items: items });
      } catch (e) {
        return JSON.stringify({ status: 'empty', reason: String(e) });
      }
    })();
""".trimIndent()

private val TROVAPREZZI_EXTRACTION_JS = """
    (function() {
      try {
        var titleDoc = document.title || '';
        var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
        if (document.querySelector('#challenge-running, #cf-challenge-running, .cf-browser-verification') ||
            /just a moment|un momento|attention required|verify you are human|verifica che sei umano/i.test(titleDoc + ' ' + bodyText.slice(0, 600))) {
          return JSON.stringify({ status: 'blocked', reason: 'Trovaprezzi richiede verifica Cloudflare.' });
        }
        var nodes = Array.prototype.slice.call(document.querySelectorAll(
          'li.listing_item, .listing_item, li.item_product, .product_item, article.product, [data-offer-id]'
        ));
        if (!nodes.length) {
          if (document.readyState !== 'complete') return JSON.stringify({ status: 'loading' });
          return JSON.stringify({ status: 'empty', reason: 'Nessuna offerta trovata su Trovaprezzi.' });
        }
        var items = [];
        for (var i = 0; i < nodes.length && items.length < 12; i++) {
          var n = nodes[i];
          var titleEl = n.querySelector('.item_title, .product_title, h2, h3, a.item_name, a[title]');
          var title = titleEl ? (titleEl.innerText || titleEl.getAttribute('title') || '').trim() : '';
          if (!title) continue;
          var priceEl = n.querySelector('.item_basic_price, .item_total_price, .price, [class*="price"]');
          var price = priceEl ? (priceEl.innerText || '').trim() : '';
          var shipEl = n.querySelector('.item_delivery_price, .shipping, .delivery, .item_offers');
          var ship = shipEl ? (shipEl.innerText || '').trim() : '';
          var shopEl = n.querySelector('.merchant_name, .shop_name, .merchant_logo img');
          var shop = shopEl ? (shopEl.innerText || shopEl.getAttribute('alt') || '').trim() : '';
          var linkEl = n.querySelector('a[href]');
          var url = linkEl ? (linkEl.getAttribute('href') || '') : '';
          items.push({
            title: title,
            price: price || null,
            originalPrice: null,
            url: url,
            asin: null,
            isPrime: false,
            delivery: ship || null,
            meta: shop || null
          });
        }
        return JSON.stringify({ status: items.length ? 'ok' : 'empty', items: items });
      } catch (e) {
        return JSON.stringify({ status: 'empty', reason: String(e) });
      }
    })();
""".trimIndent()

private val IDEALO_EXTRACTION_JS = """
    (function() {
      try {
        var titleDoc = document.title || '';
        var bodyText = (document.body && document.body.innerText) ? document.body.innerText : '';
        if (/access denied|robot|captcha|blocked|verificare/i.test(titleDoc) ||
            /access denied.*reference #/i.test(bodyText.slice(0, 600))) {
          return JSON.stringify({ status: 'blocked', reason: 'Idealo ha richiesto una verifica anti-bot.' });
        }
        var nodes = Array.prototype.slice.call(document.querySelectorAll(
          '[data-testid="resultItem"], .sr-resultList__item, article[class*="resultItem"], li[class*="resultItem"], .offerList-item'
        ));
        if (!nodes.length) {
          if (document.readyState !== 'complete') return JSON.stringify({ status: 'loading' });
          return JSON.stringify({ status: 'empty', reason: 'Nessun risultato trovato su Idealo.' });
        }
        var items = [];
        for (var i = 0; i < nodes.length && items.length < 12; i++) {
          var n = nodes[i];
          var titleEl = n.querySelector('[data-testid="productSummary__title"], .sr-productSummary__title, h2, h3, a[class*="title"]');
          var title = titleEl ? (titleEl.innerText || '').trim() : '';
          if (!title) continue;
          var priceEl = n.querySelector('[data-testid="detailedPriceInfo"], .sr-detailedPriceInfo__price, [class*="price"]');
          var price = priceEl ? (priceEl.innerText || '').trim() : '';
          var metaEl = n.querySelector('[class*="offerCount"], [class*="offers"], [data-testid*="offer"]');
          var meta = metaEl ? (metaEl.innerText || '').trim() : '';
          var linkEl = n.querySelector('a[href]');
          var url = linkEl ? (linkEl.getAttribute('href') || '') : '';
          items.push({
            title: title,
            price: price || null,
            originalPrice: null,
            url: url,
            asin: null,
            isPrime: false,
            delivery: null,
            meta: meta || null
          });
        }
        return JSON.stringify({ status: items.length ? 'ok' : 'empty', items: items });
      } catch (e) {
        return JSON.stringify({ status: 'empty', reason: String(e) });
      }
    })();
""".trimIndent()
