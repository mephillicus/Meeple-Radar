package com.meepleradar.ui.detail

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

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

internal fun interface ExternalSiteScraper {
    suspend fun scrape(site: ExternalPriceSite, searchUrl: String): ExternalScrapeResult
}

internal object ScraperJsonParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val asinRegex = Regex("""/(?:dp|gp/product)/([A-Z0-9]{10})""", RegexOption.IGNORE_CASE)
    private val validAsinRegex = Regex("""^[A-Z0-9]{10}$""")
    private val whitespaceRegex = Regex("""\s+""")

    fun parse(site: ExternalPriceSite, rawJsResult: String?): ParsedScriptStatus {
        if (rawJsResult.isNullOrBlank() || rawJsResult == "null" || rawJsResult == "undefined") {
            return ParsedScriptStatus.Loading
        }
        val unwrapped = runCatching {
            val trimmed = rawJsResult.trim()
            if (trimmed.startsWith("\"")) {
                json.decodeFromString<String>(trimmed)
            } else {
                trimmed
            }
        }.getOrElse {
            return ParsedScriptStatus.Done(ExternalScrapeResult.Error("Risposta non leggibile da ${site.label}."))
        }

        val root = runCatching { json.parseToJsonElement(unwrapped).jsonObject }.getOrElse {
            return ParsedScriptStatus.Done(ExternalScrapeResult.Error("Formato dati non valido da ${site.label}."))
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
                    val title = cleanText(obj.stringField("title")) ?: return@mapNotNull null
                    val rawUrl = obj.stringField("url").orEmpty().trim()
                    val asin = normalizeAsin(obj.stringField("asin"), rawUrl)
                    val resolvedUrl = resolveOfferUrl(site, rawUrl, asin)
                    ScrapedOfferItem(
                        title = title,
                        price = cleanText(obj.stringField("price")),
                        originalPrice = cleanText(obj.stringField("originalPrice")),
                        url = resolvedUrl,
                        asin = asin,
                        isPrime = obj.booleanField("isPrime"),
                        deliveryOrAvailability = cleanText(obj.stringField("delivery")),
                        sellerOrMeta = cleanText(obj.stringField("meta")),
                    )
                }.take(4)

                if (parsedItems.isEmpty()) {
                    ParsedScriptStatus.Done(
                        ExternalScrapeResult.Empty("Nessun risultato trovato su ${site.label}."),
                    )
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

internal class WebViewExternalSiteScraper(context: Context) : ExternalSiteScraper {
    private val appContext = context.applicationContext

    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun scrape(site: ExternalPriceSite, searchUrl: String): ExternalScrapeResult {
        return try {
            withTimeoutOrNull(SCRAPE_TIMEOUT) {
                withContext(Dispatchers.Main) {
                    runHeadlessWebView(site, searchUrl)
                }
            } ?: ExternalScrapeResult.Error("Timeout durante il caricamento di ${site.label}.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExternalScrapeResult.Error(e.message ?: "Errore durante l'estrazione da ${site.label}.")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runHeadlessWebView(
        site: ExternalPriceSite,
        searchUrl: String,
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
                when (val parsed = ScraperJsonParser.parse(site, rawResult)) {
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
    }
}

private fun extractionScriptFor(site: ExternalPriceSite): String = when (site) {
    ExternalPriceSite.AMAZON_IT -> AMAZON_EXTRACTION_JS
    ExternalPriceSite.TROVAPREZZI -> TROVAPREZZI_EXTRACTION_JS
    ExternalPriceSite.IDEALO -> IDEALO_EXTRACTION_JS
}

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
        for (var i = 0; i < list.length && items.length < 4; i++) {
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
          var isPrime = !!c.querySelector('i.a-icon-prime, [aria-label*="Prime"], .s-prime');
          var deliveryParts = [];
          var deliveryNodes = c.querySelectorAll('[data-cy="delivery-recipe"] span, .a-color-base, .a-color-secondary, span[aria-label]');
          for (var d = 0; d < deliveryNodes.length && deliveryParts.length < 2; d++) {
            var t = (deliveryNodes[d].innerText || '').replace(/\s+/g, ' ').trim();
            if (!t || t.length < 6 || t.length > 90) continue;
            if (/consegna|in uscita|disponibil|preordin|spedito da amazon|solo \d+/i.test(t)) {
              if (deliveryParts.indexOf(t) === -1) deliveryParts.push(t);
            }
          }
          items.push({
            title: title,
            price: price || null,
            originalPrice: orig || null,
            url: url,
            asin: asin,
            isPrime: isPrime,
            delivery: deliveryParts.join(' · ') || null,
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
        for (var i = 0; i < nodes.length && items.length < 4; i++) {
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
        for (var i = 0; i < nodes.length && items.length < 4; i++) {
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
