import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import {
  SITE_FEEDS,
  buildNewsPrompt,
  buildNewsSources,
  buildNewsUrl,
  fetchNewsHeadlines,
  fetchNewsProducts,
  parseRss,
} from '../../src/adapters/google-news.js'
import type { Source } from '../../src/types.js'

const source: Source = { id: 'google-news', name: 'Google News', kind: 'news', baseUrl: 'https://news.google.com/rss/search', enabled: true }

// Solo le query Google News: i test di scaricamento non devono toccare SITE_FEEDS.
const newsSources = (...queries: string[]) => queries.map((label) => ({ label, url: buildNewsUrl(label) }))

const noSleep = async () => {}
const fetchMock = vi.fn()

const ENV_KEYS = ['GEMINI_API_KEY', 'GEMINI_MODEL', 'GEMINI_FALLBACK_MODEL', 'GEMINI_SEARCH']

const rss = (items: string) =>
  `<?xml version="1.0" encoding="UTF-8"?>\n<rss version="2.0"><channel><title>Google News</title>${items}</channel></rss>`

const item = (title: string, date: string, source = 'Repubblica') => `
<item>
  <title><![CDATA[${title} - ${source}]]></title>
  <link>https://news.google.com/rss/articles/xyz</link>
  <pubDate>${date}</pubDate>
  <source url="https://repubblica.it">${source}</source>
</item>`

const geminiOk = (text: string) => ({
  status: 200,
  ok: true,
  json: async () => ({ candidates: [{ content: { parts: [{ text }] } }] }),
  text: async () => text,
})

const rssOk = (body: string) => ({ ok: true, status: 200, text: async () => body })

beforeEach(() => {
  vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  fetchMock.mockClear()
  vi.stubGlobal('fetch', fetchMock)
  for (const k of ENV_KEYS) delete process.env[k]
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  for (const k of ENV_KEYS) delete process.env[k]
})

test('buildNewsUrl chiede il feed in italiano per l’Italia', () => {
  const url = buildNewsUrl('"gioco da tavolo" when:7d')
  const params = new URL(url).searchParams
  expect(new URL(url).pathname).toBe('/rss/search')
  expect(params.get('q')).toBe('"gioco da tavolo" when:7d')
  expect(params.get('hl')).toBe('it')
  expect(params.get('gl')).toBe('IT')
  expect(params.get('ceid')).toBe('IT:it')
})

test('buildNewsSources somma i feed dei siti alle query Google News', () => {
  const sources = buildNewsSources(['q1', 'q2'])
  expect(sources.map((s) => s.url)).toEqual([buildNewsUrl('q1'), buildNewsUrl('q2'), ...SITE_FEEDS.map((s) => s.url)])
  expect(sources.map((s) => s.label)).toContain('House of Games')
})

test('parseRss legge un feed WordPress che non ha il tag source', () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel><title>House of Games</title>
<item>
  <title><![CDATA[Giochi da tavolo: tutte le nuove uscite di ottobre]]></title>
  <link>https://www.houseofgames.it/nuove-uscite-ottobre/</link>
  <pubDate>Fri, 16 Oct 2026 13:16:06 +0000</pubDate>
  <category><![CDATA[Board Games]]></category>
  <content:encoded><![CDATA[<div>contenuto con <source>non una testata</source> dentro</div>]]></content:encoded>
</item></channel></rss>`
  const [entry] = parseRss(xml)
  // Senza <source> il titolo resta intatto: nessuna coda da staccare.
  expect(entry.title).toBe('Giochi da tavolo: tutte le nuove uscite di ottobre')
  expect(entry.source).toBe('')
  expect(entry.link).toBe('https://www.houseofgames.it/nuove-uscite-ottobre/')
})

test('parseRss separa la testata dal titolo', () => {
  const [first] = parseRss(rss(item('Carcassonne esce in nuova edizione', 'Thu, 15 Oct 2026 09:00:00 GMT')))
  expect(first.title).toBe('Carcassonne esce in nuova edizione')
  expect(first.source).toBe('Repubblica')
  expect(first.link).toBe('https://news.google.com/rss/articles/xyz')
  expect(first.publishedAt.toISOString()).toBe('2026-10-15T09:00:00.000Z')
})

test('parseRss decodifica le entità HTML quando non c’è CDATA', () => {
  const xml = rss(`
<item>
  <title>Carcassonne &amp; Cascadia: l&apos;uscita &quot;attesa&quot; &lt;2027&gt; - La Gazzetta</title>
  <link>https://e.com/a</link>
  <pubDate>Thu, 15 Oct 2026 09:00:00 GMT</pubDate>
  <source>La Gazzetta</source>
</item>`)
  expect(parseRss(xml)[0].title).toBe('Carcassonne & Cascadia: l\'uscita "attesa" <2027>')
})

test('parseRss tiene il contenuto del CDATA e lo spacchetta lo stesso', () => {
  const xml = rss(`
<item>
  <title><![CDATA[7 Wonders: nuova edizione italiana - Board Game Italia]]></title>
  <link>https://e.com/b</link>
  <pubDate>Thu, 15 Oct 2026 09:00:00 GMT</pubDate>
  <source>Board Game Italia</source>
</item>`)
  expect(parseRss(xml)[0].title).toBe('7 Wonders: nuova edizione italiana')
})

test('parseRss scarta gli item senza link o con data illeggibile', () => {
  const xml = rss(`
<item><title>Senza link - A</title><pubDate>Thu, 15 Oct 2026 09:00:00 GMT</pubDate><source>A</source></item>
<item><title>Senza data - B</title><link>https://e.com/b</link><source>B</source></item>
<item><title>Data rotta - C</title><link>https://e.com/c</link><pubDate>not-a-date</pubDate><source>C</source></item>`)
  expect(parseRss(xml)).toEqual([])
})

test('fetchNewsHeadlines scarica tutte le ricerche e scarta le notizie vecchie', async () => {
  const now = 'Thu, 15 Oct 2026 12:00:00 GMT'
  const vecchia = 'Fri, 01 Aug 2026 09:00:00 GMT'
  const items = await fetchNewsHeadlines({
    sources: newsSources('q1', 'q2'),
    maxAgeDays: 30,
    sleep: noSleep,
    fetchImpl: (async () => rssOk(rss(
      item('Recente', now) + item('Molto vecchia', vecchia),
    ))) as unknown as typeof fetch,
  })
  expect(items.map((i) => i.title)).toEqual(['Recente'])
})

test('fetchNewsHeadlines elimina i titoli doppi tra più ricerche', async () => {
  let chiamata = 0
  const items = await fetchNewsHeadlines({
    sources: newsSources('q1', 'q2'),
    sleep: noSleep,
    fetchImpl: (async () => {
      chiamata++
      // La stessa notizia compare in due ricerche e su due testate diverse.
      const fonte = chiamata === 1 ? 'Repubblica' : 'Corriere'
      return rssOk(rss(item('Carcassonne esce a dicembre', 'Thu, 15 Oct 2026 09:00:00 GMT', fonte)))
    }) as unknown as typeof fetch,
  })
  expect(items).toHaveLength(1)
  expect(items[0].title).toBe('Carcassonne esce a dicembre')
})

test('fetchNewsHeadlines ordina dalla più recente e taglia a maxItems', async () => {
  const items = await fetchNewsHeadlines({
    sources: newsSources('q'),
    maxItems: 2,
    sleep: noSleep,
    fetchImpl: (async () => rssOk(rss(
      item('Vecchia', 'Mon, 12 Oct 2026 09:00:00 GMT')
      + item('Nuova', 'Thu, 15 Oct 2026 09:00:00 GMT')
      + item('Di mezzo', 'Tue, 13 Oct 2026 09:00:00 GMT'),
    ))) as unknown as typeof fetch,
  })
  expect(items.map((i) => i.title)).toEqual(['Nuova', 'Di mezzo'])
})

test('fetchNewsHeadlines lancia se nessun feed risponde', async () => {
  await expect(fetchNewsHeadlines({
    sources: newsSources('q1', 'q2'),
    sleep: noSleep,
    fetchImpl: (async () => ({ ok: false, status: 403, text: async () => 'rifiutato' })) as unknown as typeof fetch,
  })).rejects.toThrow(/Feed RSS non raggiungibili/)
})

test('fetchNewsHeadlines restituisce [] se i feed rispondono ma sono vuoti', async () => {
  const items = await fetchNewsHeadlines({
    sources: newsSources('q'),
    sleep: noSleep,
    fetchImpl: (async () => rssOk(rss(''))) as unknown as typeof fetch,
  })
  expect(items).toEqual([])
})

test('buildNewsPrompt elenca i titoli con data e testata', () => {
  const prompt = buildNewsPrompt([
    {
      title: 'Carcassonne esce a dicembre',
      source: 'Repubblica',
      link: 'https://e.com/a',
      publishedAt: new Date('2026-10-15T09:00:00Z'),
    },
  ], new Date('2026-10-15T12:00:00Z'))
  expect(prompt).toContain('15 ottobre 2026')
  expect(prompt).toContain('- [2026-10-15] Carcassonne esce a dicembre (Repubblica)')
  // Senza ricerca web non si possono inventare editori o date.
  expect(prompt).toContain('Non completare con editore, prezzo, codice articolo o data')
})

test('fetchNewsProducts passa i titoli a Gemini SENZA ricerca web', async () => {
  process.env.GEMINI_API_KEY = 'g'
  fetchMock.mockResolvedValue(geminiOk('[{"title": "Carcassonne"}]'))
  await fetchNewsProducts(source, undefined, {
    sleep: noSleep,
    news: {
      sources: newsSources('q'),
      sleep: noSleep,
      fetchImpl: (async () => rssOk(rss(item('Carcassonne esce a dicembre', 'Thu, 15 Oct 2026 09:00:00 GMT')))) as unknown as typeof fetch,
    },
  })
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const body = JSON.parse(fetchMock.mock.calls[0][1].body as string)
  // Il tier gratuito non ha la quota per la ricerca web: l'estrazione dai titoli basta.
  expect(body.tools).toBeUndefined()
  expect(body.generationConfig.responseMimeType).toBe('application/json')
  expect(body.contents[0].parts[0].text).toContain('Carcassonne esce a dicembre')
})

test('fetchNewsProducts restituisce [] senza toccare l’LLM se non ci sono titoli', async () => {
  process.env.GEMINI_API_KEY = 'g'
  expect(await fetchNewsProducts(source, undefined, {
    sleep: noSleep,
    news: { sources: newsSources('q'), sleep: noSleep, fetchImpl: (async () => rssOk(rss(''))) as unknown as typeof fetch },
  })).toEqual([])
  expect(fetchMock).not.toHaveBeenCalled()
})

test('fetchNewsProducts lancia se manca la chiave LLM', async () => {
  await expect(fetchNewsProducts(source, undefined, {
    sleep: noSleep,
    news: { sources: newsSources('q'), sleep: noSleep, fetchImpl: (async () => rssOk(rss(item('X', 'Thu, 15 Oct 2026 09:00:00 GMT')))) as unknown as typeof fetch },
  })).rejects.toThrow(/GEMINI_API_KEY/)
  expect(fetchMock).not.toHaveBeenCalled()
})
