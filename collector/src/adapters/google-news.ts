import type { GameFinder, RawProduct, Source } from '../types.js'
import { normalizeTitle } from '../normalize.js'
import { buildProviders, type AiOptions, type AiResult } from './ai-providers.js'
import { parseUpcomingJson } from './gemini.js'

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

// Stessa firma di fetchGeminiProducts, quindi si registra come qualsiasi altra fonte. I titoli
// arrivano già dentro il prompt: Gemini non deve cercare sul web, quindi funziona anche col tier
// gratuito.
export async function fetchNewsProducts(
  _source: Source,
  gameFinder?: GameFinder,
  options?: AiOptions & { news?: NewsOptions },
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
