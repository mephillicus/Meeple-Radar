import type { GameFinder, RawProduct, Source } from '../types.js'
import { extractReleaseDate, normalizeTitle } from '../normalize.js'
import { htmlToText } from '../text.js'
import { buildProviders, type AiOptions, type AiProvider, type AiResult } from './ai-providers.js'
import { parseUpcomingJson } from './gemini.js'
import { type ArticleGame, buildArticlePrompt, isCandidateHeadline, parseArticleGames, verifyQuote } from './articles.js'
import { createRelayFetcher } from './relay.js'
import type { TextFetcher } from './http.js'

// Fonte "novità da Google News": scarica i titoli recenti dal feed RSS di Google News per alcune
// ricerche in italiano, poi chiede a Gemini (SENZA ricerca web, quindi anche col tier gratuito) di
// estrarre dai titoli i giochi da tavolo di cui si annuncia l'uscita.
// I titoli bastano per scoprire i giochi nuovi; prezzo e data arrivano raramente.

export type NewsItem = {
  title: string
  source: string
  link: string
  publishedAt: Date
  body?: string
}

// L'operatore `when:` limita l'età delle notizie (when:7d, when:14d, ...). Le virgolette
// obbligano la frase esatta. Aggiungi o togli ricerche a piacere.
export const NEWS_QUERIES = [
  '"gioco da tavolo" in uscita when:14d',
  '"giochi da tavolo" novità uscita when:14d',
  '"gioco da tavolo" "edizione italiana" when:30d',
  '"giochi da tavolo" annuncio nuova uscita when:14d',
]

export type NewsSource = { label: string; url: string }

// Feed RSS diretti di siti. Non sono negozi e non vanno estratti come scraper: un loro articolo
// di riepilogo può contenere venti giochi in un solo post, quindi ha senso solo passandolo
// all'LLM, che sa spezzettarlo in altrettante uscite. Le categorie non pertinenti (videogames,
// magic, ...) le scarta il prompt.
export const SITE_FEEDS: NewsSource[] = [
  { label: 'House of Games', url: 'https://www.houseofgames.it/feed/' },
]

export function buildNewsUrl(query: string): string {
  const params = new URLSearchParams({ q: query, hl: 'it', gl: 'IT', ceid: 'IT:it' })
  return `https://news.google.com/rss/search?${params.toString()}`
}

// Dove prendere i titoli: le query Google News più i feed dei siti.
export function buildNewsSources(queries: string[] = NEWS_QUERIES): NewsSource[] {
  return [
    ...queries.map((query) => ({ label: query, url: buildNewsUrl(query) })),
    ...SITE_FEEDS,
  ]
}

const DEFAULT_MAX_AGE_DAYS = 30
const DEFAULT_MAX_ITEMS = 80
const DEFAULT_TIMEOUT_MS = 30_000
const DELAY_BETWEEN_FEEDS_MS = 1000

// Cap di articoli per run, contato sui CORPI analizzati con successo: un fetch fallito o un
// corpo troppo corto non brucia un posto e resta sul percorso solo-titoli.
export const MAX_ARTICLE_BODIES = 12
const MIN_ARTICLE_CHARS = 300

function decodeEntities(text: string): string {
  return text
    .replace(/&#x([0-9a-f]+);/gi, (_, h: string) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d: string) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&')
}

function unwrap(raw: string): string {
  const cdata = raw.match(/^\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*$/)
  return (cdata ? cdata[1] : decodeEntities(raw)).trim()
}

function tag(xml: string, name: string): string | undefined {
  const m = xml.match(new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`))
  return m ? unwrap(m[1]) : undefined
}

// Come tag(), ma ignora il contenuto dei CDATA. Serve per <source>: Google News lo mette come
// figlio diretto dell'<item>, mentre in un feed WordPress il corpo dell'articolo può contenere un
// <source> HTML (dentro <video>, <picture>) che altrimenti finirebbe scambiato per la testata.
function tagOutsideCdata(xml: string, name: string): string | undefined {
  return tag(xml.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, ''), name)
}

// Estrae gli <item> di un feed RSS 2.0. Google News scrive i titoli come "Titolo - Testata": la
// testata (tag <source>) viene separata dal titolo.
export function parseRss(xml: string): NewsItem[] {
  const out: NewsItem[] = []
  for (const block of xml.match(/<item>[\s\S]*?<\/item>/g) ?? []) {
    let title = tag(block, 'title')
    const link = tag(block, 'link')
    const pub = tag(block, 'pubDate')
    if (!title || !link) continue

    const source = tagOutsideCdata(block, 'source') ?? ''
    if (source !== '' && title.endsWith(` - ${source}`)) {
      title = title.slice(0, -(source.length + 3)).trim()
    }

    const publishedAt = pub ? new Date(pub) : new Date(NaN)
    if (Number.isNaN(publishedAt.getTime())) continue
    const body = tag(block, 'content:encoded')
    out.push(body === undefined ? { title, source, link, publishedAt } : { title, source, link, publishedAt, body })
  }
  return out
}

export type NewsOptions = {
  // Override del set di feed (default: buildNewsSources()).
  sources?: NewsSource[]
  maxAgeDays?: number
  maxItems?: number
  timeoutMs?: number
  // Solo per i test.
  fetchImpl?: typeof fetch
  sleep?: (ms: number) => Promise<void>
  // Solo per i test: fetch del corpo articolo (default: relay Jina, poi fetch diretto).
  articleFetch?: TextFetcher
}

// Scarica i feed, scarta le notizie troppo vecchie, elimina i doppioni (la stessa notizia compare
// spesso in più ricerche e su più testate) e ordina dalla più recente.
export async function fetchNewsHeadlines(options: NewsOptions = {}): Promise<NewsItem[]> {
  const sources = options.sources ?? buildNewsSources()
  const maxAgeMs = (options.maxAgeDays ?? DEFAULT_MAX_AGE_DAYS) * 24 * 60 * 60 * 1000
  const maxItems = options.maxItems ?? DEFAULT_MAX_ITEMS
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const doFetch = options.fetchImpl ?? fetch
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))

  const seen = new Map<string, number>()
  const items: NewsItem[] = []
  const errors: string[] = []
  let okFeeds = 0

  for (let i = 0; i < sources.length; i++) {
    const feed = sources[i]
    if (i > 0) await sleep(DELAY_BETWEEN_FEEDS_MS)
    try {
      const res = await doFetch(feed.url, {
        headers: { 'User-Agent': 'Mozilla/5.0 (compatible; MeepleRadar/1.0)' },
        signal: AbortSignal.timeout(timeoutMs),
      })
      if (!res.ok) {
        errors.push(`"${feed.label}": HTTP ${res.status}`)
        console.error(`[news] "${feed.label}": HTTP ${res.status}`)
        continue
      }
      okFeeds++
      const parsed = parseRss(await res.text())
      console.error(`[news] "${feed.label}": ${parsed.length} notizie`)

      for (const item of parsed) {
        if (Date.now() - item.publishedAt.getTime() > maxAgeMs) continue
        const key = normalizeTitle(item.title)
        if (key === '') continue
        const idx = seen.get(key)
        if (idx !== undefined) {
          // A parità di titolo vince la copia con il corpo dell'articolo.
          if (items[idx].body === undefined && item.body !== undefined) items[idx] = item
          continue
        }
        seen.set(key, items.length)
        items.push(item)
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      errors.push(`"${feed.label}": ${msg}`)
      console.error(`[news] "${feed.label}": ${msg}`)
    }
  }

  // Se nessun feed ha risposto è un errore tecnico; se hanno risposto ma non c'è nulla, è vuoto.
  if (okFeeds === 0 && sources.length > 0) {
    throw new Error(`Feed RSS non raggiungibili: ${errors.join(' | ')}`)
  }

  items.sort((a, b) => b.publishedAt.getTime() - a.publishedAt.getTime())
  return items.slice(0, maxItems)
}

export function buildNewsPrompt(items: NewsItem[], now: Date = new Date()): string {
  const oggi = now.toLocaleDateString('it-IT', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'Europe/Rome',
  })
  const righe = items.map((item) => {
    const data = item.publishedAt.toISOString().slice(0, 10)
    return `- [${data}] ${item.title}${item.source ? ` (${item.source})` : ''}`
  })

  return `Oggi è ${oggi}. Qui sotto trovi i titoli di notizie recenti sui giochi da tavolo in Italia.

Estrai i giochi da tavolo (da tavolo e di carte; NON videogiochi, film, serie TV, fiere o eventi) di cui un titolo annuncia un'uscita, una ristampa, un'espansione o un'edizione italiana in arrivo o appena uscita.

Regole:
- Usa SOLO giochi il cui nome compare esplicitamente in un titolo. Non aggiungere giochi che conosci da altre fonti.
- Non completare con editore, prezzo, codice articolo o data se non sono scritti nei titoli: ometti quei campi.
- Se un titolo nomina più giochi, crea un elemento per ciascuno. Se non nomina nessun gioco specifico, ignoralo.
- Se la stessa uscita compare in più titoli, restituiscila una sola volta.
- Se nessun titolo nomina un gioco con un'uscita, restituisci {"releases": []}.

Formato della risposta: SOLO un oggetto JSON valido, senza testo prima o dopo e senza markdown. Una sola chiave, "releases", con un array di elementi con questi campi:
- title (stringa, obbligatorio): nome del gioco come scritto nel titolo
- publisher (stringa, opzionale): editore, solo se citato
- price (stringa, opzionale): prezzo, solo se citato
- releaseDate (stringa, opzionale): data di uscita, solo se citata
- description (stringa, opzionale): una breve frase presa dal contenuto del titolo

Titoli:
${righe.join('\n')}
`
}

// Corpo dell'articolo quando il feed non lo contiene: di default relay Jina (raggiunge anche
// le fonti dietro Cloudflare) e, se il relay non risponde, fetch diretto del link. Con
// articleFetch iniettato (solo test) si usa quello e basta, senza fallback reali.
async function fetchArticleBody(item: NewsItem, articleFetch?: TextFetcher): Promise<string | null> {
  if (articleFetch) return articleFetch(item.link)
  const relayed = await createRelayFetcher()(item.link)
  if (relayed !== null) return relayed
  try {
    const res = await fetch(item.link, {
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; MeepleRadar/1.0)' },
      signal: AbortSignal.timeout(DEFAULT_TIMEOUT_MS),
    })
    if (!res.ok) return null
    return (await res.text()) || null
  } catch {
    return null
  }
}

// Una chiamata LLM per articolo, a cascata come il percorso titoli. Ritorna null se nessun
// provider ha saputo analizzare l'articolo: l'articolo resta sui titoli, senza errori fatali.
async function analyzeArticleGames(
  body: string,
  providers: AiProvider[],
  title: string,
): Promise<ArticleGame[] | null> {
  const prompt = buildArticlePrompt(body, new Date())
  for (const provider of providers) {
    let result: AiResult
    try {
      result = await provider.call(prompt)
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      console.error(`[news] "${title}": ${provider.id}: ${msg}`)
      continue
    }
    if (!result.ok) {
      console.error(`[news] "${title}": ${provider.id}: ${result.reason}`)
      continue
    }
    const { ok, games } = parseArticleGames(result.text)
    if (!ok) {
      console.error(`[news] "${title}": ${provider.id}: risposta non interpretabile: ${result.text.slice(0, 300)}`)
      continue
    }
    return games
  }
  return null
}

// Percorso solo-titoli (quello di sempre): un'unica chiamata LLM con tutti i titoli.
async function fetchHeadlineProducts(
  items: NewsItem[],
  providers: AiProvider[],
  gameFinder?: GameFinder,
  options?: AiOptions,
): Promise<RawProduct[]> {
  const prompt = buildNewsPrompt(items)
  const errors: string[] = []
  let anyResponded = false

  for (const provider of providers) {
    let result: AiResult
    try {
      result = await provider.call(prompt)
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      errors.push(`${provider.id}: ${msg}`)
      console.error(`[llm] ${provider.id}: ${msg}`)
      continue
    }

    anyResponded = true
    if (!result.ok) {
      errors.push(`${provider.id}: ${result.reason}`)
      console.error(`[llm] ${provider.id}: ${result.reason}`)
      continue
    }

    const { ok, products } = await parseUpcomingJson(result.text, gameFinder)
    if (!ok) {
      errors.push(`${provider.id}: risposta non interpretabile`)
      console.error(`[llm] ${provider.id}: risposta non interpretabile: ${result.text.slice(0, 300)}`)
      continue
    }
    if (products.length === 0) {
      // Con titoli reali un elenco vuoto è plausibile: non serve insistere con un altro modello.
      console.error(`[llm] ${provider.id}: nessun gioco nei titoli`)
      return []
    }
    console.error(`[llm] ${products.length} prodotti da ${provider.id} (notizie)`)
    return products
  }

  if (!anyResponded) {
    throw new Error(`nessun provider LLM ha funzionato: ${errors.join(' | ')}`)
  }
  return []
}

// Stessa firma di fetchGeminiProducts, quindi si registra come qualsiasi altra fonte. I titoli
// arrivano già dentro il prompt: Gemini non deve cercare sul web, quindi funziona anche col tier
// gratuito. I titoli candidati (annunci di uscite, NON recensioni) vengono prima analizzati per
// intero quando l'articolo è disponibile; il resto resta sul percorso solo-titoli.
export async function fetchNewsProducts(
  _source: Source,
  gameFinder?: GameFinder,
  options?: AiOptions & { news?: NewsOptions; articleFetch?: TextFetcher },
): Promise<RawProduct[]> {
  const items = await fetchNewsHeadlines(options?.news)
  if (items.length === 0) {
    console.error('[news] nessuna notizia recente')
    return []
  }
  console.error(`[news] ${items.length} titoli da analizzare`)

  const providers = buildProviders(process.env, options)
  if (providers.length === 0) {
    throw new Error('nessuna chiave LLM: imposta GEMINI_API_KEY')
  }

  // Percorso articoli: i candidati con il corpo già nel feed per primi, poi i più recenti.
  // Un articolo non raggiungibile o una risposta inutilizzabile non butta giù la fonte:
  // resta tra i titoli (log + skip).
  const articleFetch = options?.articleFetch ?? options?.news?.articleFetch
  const candidates = items
    .filter((i) => isCandidateHeadline(i.title, !!i.body))
    .sort((a, b) =>
      Number(a.body === undefined) - Number(b.body === undefined)
      || b.publishedAt.getTime() - a.publishedAt.getTime())
  console.error(`[news] ${candidates.length} articoli candidati`)

  const articleProducts: RawProduct[] = []
  const analyzedTitles = new Set<string>()
  let analyzedBodies = 0

  for (const item of candidates) {
    if (analyzedBodies >= MAX_ARTICLE_BODIES) break
    try {
      const html = item.body ?? await fetchArticleBody(item, articleFetch)
      const body = htmlToText(html ?? undefined)
      if (body === null || body.length < MIN_ARTICLE_CHARS) {
        console.error(`[news] "${item.title}": corpo mancante o troppo corto, resta tra i titoli`)
        continue
      }
      const games = await analyzeArticleGames(body, providers, item.title)
      if (games === null) {
        console.error(`[news] "${item.title}": articolo non analizzato, resta tra i titoli`)
        continue
      }
      const mapped: RawProduct[] = []
      for (const g of games) {
        const product: RawProduct = {
          externalId: normalizeTitle(g.title).replace(/\s+/g, '-'),
          title: g.title,
          url: item.link,
        }
        const iso = extractReleaseDate(g.releaseDate)
        if (iso !== null) product.releaseDateRaw = iso
        if (g.descriptionQuote !== undefined) {
          const verified = verifyQuote(g.descriptionQuote, body)
          if (verified !== null) product.articleDescription = verified
        }
        // Come in parseUpcomingJson: i dati di catalogo (il negozio) vincono sull'articolo.
        if (gameFinder) {
          const match = await gameFinder(normalizeTitle(g.title), g.publisher)
          if (match) {
            if (match.price !== null) product.priceRaw = String(match.price)
            if (match.currency !== null) product.currencyRaw = match.currency
            if (match.releaseDate !== null) product.releaseDateRaw = match.releaseDate
            if (match.imageUrl !== null) product.imageUrl = match.imageUrl
            if (match.gameId !== null) product.gameId = match.gameId
          }
        }
        mapped.push(product)
      }
      articleProducts.push(...mapped)
      analyzedTitles.add(normalizeTitle(item.title))
      analyzedBodies++
      console.error(`[news] "${item.title}": ${mapped.length} giochi dall'articolo`)
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err)
      console.error(`[news] "${item.title}": ${msg}, resta tra i titoli`)
    }
  }
  if (articleProducts.length > 0) {
    console.error(`[news] ${articleProducts.length} prodotti dagli articoli`)
  }

  // Percorso solo-titoli sui titoli non già analizzati per intero; se non ne restano, la
  // chiamata LLM per i titoli non parte.
  const rest = items.filter((i) => !analyzedTitles.has(normalizeTitle(i.title)))
  const headlineProducts = rest.length > 0
    ? await fetchHeadlineProducts(rest, providers, gameFinder, options)
    : []

  // Alla deduplica finale vince l'articolo (più ricco del singolo titolo).
  const fromArticles = new Set(articleProducts.map((p) => normalizeTitle(p.title)))
  return [...articleProducts, ...headlineProducts.filter((p) => !fromArticles.has(normalizeTitle(p.title)))]
}
