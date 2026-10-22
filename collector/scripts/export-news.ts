// Esporta in locale ciò che la fonte Google News scarica ed estrae, senza scrivere su Supabase.
// Uso (da collector/): powershell -ExecutionPolicy Bypass -File scripts\export-news.ps1
// Lo script ps1 lancia questo file (dati in export\google-news-latest.json) e poi monta l'xlsx
// con Excel COM. Le funzioni qui sotto sono pure e stanno in test\export-news.test.ts.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import type { RawProduct, Source } from '../src/types.js'
import type { NewsItem } from '../src/adapters/google-news.js'
import { fetchNewsHeadlines, fetchNewsProducts } from '../src/adapters/google-news.js'
import { isCandidateHeadline } from '../src/adapters/articles.js'
import { htmlToText } from '../src/text.js'
import { loadConfig } from '../src/config.js'
import { createSupabaseClient } from '../src/db.js'
import { createGameFinder } from '../src/adapters/gemini.js'
import { SOURCES } from '../src/sources.seed.js'

export function parseEnvFile(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim()
    if (line === '' || line.startsWith('#')) continue
    const eq = line.indexOf('=')
    if (eq < 0) continue
    out[line.slice(0, eq).trim()] = line.slice(eq + 1).trim()
  }
  return out
}

export function parseArticleCount(logLines: string[]): number {
  let count = 0
  for (const line of logLines) {
    const m = line.match(/^\[news\] (\d+) prodotti dagli articoli$/)
    if (m) count = Number(m[1])
  }
  return count
}

export function parseArticleEsiti(logLines: string[]): Record<string, string> {
  const out: Record<string, string> = {}
  for (const line of logLines) {
    const m = line.match(/^\[news\] "(.+)": (.+)$/)
    if (!m) continue
    const titolo = m[1]
    const resto = m[2]
    if (/^\d+ giochi dall'articolo$/.test(resto) || resto.startsWith('fetch corpo:')) {
      out[titolo] = resto
    } else if (resto.startsWith('corpo mancante o troppo corto')) {
      out[titolo] = 'corpo mancante o troppo corto'
    } else if (resto.startsWith('articolo non analizzato')) {
      out[titolo] = 'articolo non analizzato'
    } else {
      out[titolo] = resto.replace(/, resta tra i titoli$/, '')
    }
  }
  return out
}

const testo = (v: string | number | undefined): string => (v === undefined ? '' : String(v))

export function mapProductRow(p: RawProduct, index: number, articleCount: number): Record<string, string | number> {
  return {
    fonte: 'Google News',
    // fetchNewsProducts restituisce prima i prodotti dagli articoli, poi quelli dai titoli.
    percorso: index < articleCount ? 'articolo' : 'titoli',
    titolo: p.title,
    url: p.url,
    prezzo: testo(p.priceRaw),
    valuta: testo(p.currencyRaw),
    dataUscita: testo(p.releaseDateRaw),
    descrizione: p.articleDescription ?? p.descriptionRaw ?? '',
    gameId: testo(p.gameId),
    imageUrl: testo(p.imageUrl),
    externalId: p.externalId,
  }
}

export function mapNewsRow(item: NewsItem, esiti: Record<string, string>): Record<string, string | number> {
  const esito = esiti[item.title]
  const candidato = isCandidateHeadline(item.title, !!item.body)
  const analizzato = esito !== undefined && /^\d+ giochi dall'articolo$/.test(esito)
  const testoCorpo = item.body ? htmlToText(item.body) : null
  return {
    titolo: item.title,
    testata: item.source,
    link: item.link,
    pubblicato: item.publishedAt.toISOString(),
    corpoNelFeed: testoCorpo === null ? 'no' : `sì (${testoCorpo.length} caratteri)`,
    percorso: analizzato ? 'articolo' : 'titoli',
    esito: esito ?? (candidato ? 'in coda ai titoli' : 'solo titoli'),
  }
}

export function applyEnvFile(entries: Record<string, string>, env: NodeJS.ProcessEnv): void {
  for (const [k, v] of Object.entries(entries)) {
    // Un valore vuoto (GEMINI_MODEL=) significa "non impostato": non deve finire
    // nell'ambiente a sovrascrivere i default con una stringa vuota.
    if (v !== '' && !env[k]) env[k] = v
  }
}

// Il payload dell'export è assemblato qui (puro, testato): un errore totale dell'estrazione
// registra il fallimento nel riepilogo e nel log invece di cancellare ciò che la run ha già
// scaricato — le notizie e il log restano nel file anche senza prodotti.
export function buildPayload(args: {
  items: NewsItem[]
  products: RawProduct[]
  log: string[]
  errore?: string
}): {
  riepilogo: {
    titoliScaricati: number
    candidatiArticolo: number
    prodottiEstratti: number
    prodottiDaArticoli: number
    righeLog: number
    errore?: string
  }
  notizie: Record<string, string | number>[]
  estrazioni: Record<string, string | number>[]
  log: string[]
} {
  const log = [...args.log]
  const esiti = parseArticleEsiti(log)
  const articleCount = parseArticleCount(log)
  if (args.errore !== undefined) log.push(`[export] estrazione incompleta: ${args.errore}`)
  return {
    riepilogo: {
      titoliScaricati: args.items.length,
      candidatiArticolo: args.items.filter((i) => isCandidateHeadline(i.title, !!i.body)).length,
      prodottiEstratti: args.products.length,
      prodottiDaArticoli: articleCount,
      righeLog: log.length,
      ...(args.errore === undefined ? {} : { errore: args.errore }),
    },
    notizie: args.items.map((i) => mapNewsRow(i, esiti)),
    estrazioni: args.products.map((p, i) => mapProductRow(p, i, articleCount)),
    log,
  }
}

async function main(): Promise<number> {
  const qui = dirname(fileURLToPath(import.meta.url))
  const envText = readFileSync(join(qui, '..', 'supabase.env'), 'utf8')
  applyEnvFile(parseEnvFile(envText), process.env)

  const { supabaseUrl, supabaseServiceKey } = loadConfig(process.env)
  const store = createSupabaseClient(supabaseUrl, supabaseServiceKey)
  const gameFinder = createGameFinder(store)
  const newsSource: Source | undefined = SOURCES.find((s) => s.kind === 'news')
  if (!newsSource) throw new Error('fonte news non trovata in sources.seed')

  const log: string[] = []
  const consoleError = console.error
  console.error = (...args: unknown[]) => {
    log.push(args.map(String).join(' '))
    consoleError(...args)
  }

  let items: NewsItem[] = []
  let products: RawProduct[] = []
  let errore: string | undefined
  let chiamata = 'fetchNewsHeadlines'
  try {
    items = await fetchNewsHeadlines()
    chiamata = 'fetchNewsProducts'
    products = await fetchNewsProducts(newsSource, gameFinder)
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err)
    errore = `${chiamata}: ${msg}`
    consoleError(`[export] estrazione incompleta: ${errore}`)
  } finally {
    console.error = consoleError
  }

  const payload = { generatoIl: new Date().toISOString(), ...buildPayload({ items, products, log, errore }) }

  const outDir = join(qui, '..', 'export')
  mkdirSync(outDir, { recursive: true })
  const jsonPath = join(outDir, 'google-news-latest.json')
  writeFileSync(jsonPath, JSON.stringify(payload, null, 2), 'utf8')
  console.log(`JSON: ${jsonPath}`)
  console.log(JSON.stringify(payload.riepilogo))
  return 0
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main()
    .then((code) => {
      process.exitCode = code
    })
    .catch((err: unknown) => {
      console.error(err instanceof Error ? err.message : String(err))
      process.exitCode = 1
    })
}
