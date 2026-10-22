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

test('parseRss tiene il corpo dell’articolo da content:encoded', () => {
  const xml = rss(`<item>
    <title><![CDATA[Cinque uscite di novembre - HoG]]></title>
    <link>https://www.houseofgames.it/uscite</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <content:encoded><![CDATA[<p>Il primo gioco arriva il 3 novembre.</p>]]></content:encoded>
  </item>`)
  const items = parseRss(xml)
  expect(items[0]?.body).toBe('<p>Il primo gioco arriva il 3 novembre.</p>')
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

test('alla deduplica vince la copia con il corpo', async () => {
  const conCorpo = `<item>
    <title><![CDATA[Stesso titolo]]></title>
    <link>https://www.houseofgames.it/a</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <content:encoded><![CDATA[<p>Corpo</p>]]></content:encoded>
  </item>`
  const senzaCorpo = `<item>
    <title><![CDATA[Stesso titolo - Testata]]></title>
    <link>https://news.google.com/rss/articles/xyz</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <source url="https://testata.it">Testata</source>
  </item>`
  fetchMock.mockResolvedValueOnce(rssOk(rss(senzaCorpo))).mockResolvedValueOnce(rssOk(rss(conCorpo)))
  const items = await fetchNewsHeadlines({
    sources: [{ label: 'q', url: 'https://news.google.com/rss/search?q=x' }, { label: 'hog', url: 'https://www.houseofgames.it/feed/' }],
    sleep: noSleep,
  })
  expect(items).toHaveLength(1)
  expect(items[0]?.body).toBe('<p>Corpo</p>')
  expect(items[0]?.link).toBe('https://www.houseofgames.it/a')
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

const bodyHog = `<item>
  <title><![CDATA[Cinque uscite di novembre - HoG]]></title>
  <link>https://www.houseofgames.it/uscite</link>
  <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
  <content:encoded><![CDATA[<p>${'Arriva il gioco Cascadia. '.repeat(20)}</p>]]></content:encoded>
</item>`

test('analizza l’articolo ed estrae citazione, data e url dell’articolo', async () => {
  // articleFetch non serve: il corpo arriva dal feed. GEMINI_API_KEY impostata,
  // una sola chiamata LLM (articolo), nessun titolo restante.
  process.env.GEMINI_API_KEY = 'k'
  fetchMock.mockResolvedValueOnce(rssOk(rss(bodyHog)))
  fetchMock.mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [{
    title: 'Cascadia', publisher: 'North Star',
    releaseDate: 'novembre 2026',
    descriptionQuote: 'Arriva il gioco Cascadia.',
  }] })))
  const out = await fetchNewsProducts(source, undefined, {
    sleep: noSleep,
    news: { sources: newsSources('q') },
    articleFetch: async () => null,
  })
  expect(out).toHaveLength(1)
  expect(out[0]).toMatchObject({
    title: 'Cascadia',
    url: 'https://www.houseofgames.it/uscite',
    releaseDateRaw: '2026-11-01',
    articleDescription: 'Arriva il gioco Cascadia.',
  })
})

test('scarta la citazione parafrasata', async () => {
  process.env.GEMINI_API_KEY = 'k'
  fetchMock.mockResolvedValueOnce(rssOk(rss(bodyHog)))
  fetchMock.mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [
    { title: 'Cascadia', descriptionQuote: 'Il gioco di carte arriva a novembre.' },
  ] })))
  const out = await fetchNewsProducts(source, undefined, {
    sleep: noSleep,
    news: { sources: newsSources('q') },
  })
  expect(out[0]).not.toHaveProperty('articleDescription')
})

test('un articolo non fetchabile resta sul percorso titoli', async () => {
  process.env.GEMINI_API_KEY = 'k'
  fetchMock
    .mockResolvedValueOnce(rssOk(rss(item('Arriva Cascadia a novembre', 'Wed, 15 Oct 2026 09:30:00 GMT'))))
    .mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [{ title: 'Cascadia' }] })))
  const articleFetch = vi.fn().mockResolvedValue(null)
  const out = await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q') }, articleFetch })
  // Titolo positivo senza corpo: prova il fetch, fallisce, e l'articolo ricade nei titoli.
  expect(articleFetch).toHaveBeenCalledTimes(1)
  expect(out).toHaveLength(1)
  expect(out[0]?.title).toBe('Cascadia')
})

// L'esito di un fetch di corpo che fallisce (null) è DISTINTO da un corpo recuperato ma
// troppo corto: l'export locale deve riportare le due righe in modo diverso sul foglio Notizie.
test('un fetch di corpo fallito ha un log distinto da un corpo troppo corto', async () => {
  process.env.GEMINI_API_KEY = 'k'
  const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
  const feed = rss(`<item>
  <title><![CDATA[Arriva Cascadia a novembre - HoG]]></title>
  <link>https://www.houseofgames.it/cascadia</link>
  <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
</item>
<item>
  <title><![CDATA[Uscita brevissima - HoG]]></title>
  <link>https://www.houseofgames.it/breve</link>
  <pubDate>Wed, 15 Oct 2026 09:31:00 GMT</pubDate>
  <content:encoded><![CDATA[<p>troppo corto</p>]]></content:encoded>
</item>`)
  fetchMock
    .mockResolvedValueOnce(rssOk(feed))
    .mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [{ title: 'Cascadia' }] })))
  const articleFetch = vi.fn().mockResolvedValue(null)
  await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q') }, articleFetch })
  const righe = spy.mock.calls.map((c) => String(c[0]))
  expect(righe.some((l) => l.includes('"Arriva Cascadia a novembre - HoG": fetch corpo fallito'))).toBe(true)
  expect(righe.some((l) => l.includes('"Uscita brevissima - HoG": corpo mancante o troppo corto'))).toBe(true)
})

test('una recensione non viene analizzata ma resta nei titoli', async () => {
  process.env.GEMINI_API_KEY = 'k'
  fetchMock
    .mockResolvedValueOnce(rssOk(rss(item('Recensione: Cascadia è bellissimo', 'Wed, 15 Oct 2026 09:30:00 GMT'))))
    .mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [] })))
  const articleFetch = vi.fn().mockResolvedValue(null)
  const out = await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q') }, articleFetch })
  expect(articleFetch).not.toHaveBeenCalled()
  expect(out).toEqual([])
})

test('il cap conta i corpi analizzati con successo', async () => {
  vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  process.env.GEMINI_API_KEY = 'k'
  const items = Array.from({ length: 15 }, (_, i) => `<item>
    <title><![CDATA[Novità gioco ${i} - HoG]]></title>
    <link>https://www.houseofgames.it/${i}</link>
    <pubDate>Wed, 15 Oct 2026 09:30:0${i % 10} GMT</pubDate>
    <content:encoded><![CDATA[<p>${'Arriva un gioco nuovo. '.repeat(20)}</p>]]></content:encoded>
  </item>`).join('')
  fetchMock.mockResolvedValueOnce(rssOk(rss(items)))
  const gemini = vi.fn().mockResolvedValue(geminiOk('{"games":[]}'))
  fetchMock.mockImplementation(async (url: string) =>
    url.includes('generativelanguage') ? gemini() : rssOk(rss(items)))
  await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q'), maxItems: 15 }, sleep: noSleep })
  // 15 candidati con corpo: solo 12 chiamate LLM articolo, più 1 chiamata titoli per i 3 rimasti.
  expect(gemini).toHaveBeenCalledTimes(13)
})

// Corpo del feed più lungo di 100k caratteri con un marcatore in coda: il prompt dell'LLM
// non deve mai riceverlo (cap del corpo, step 3 della specifica).
test('il corpo dell’articolo viene troncato a 100k caratteri prima del prompt', async () => {
  process.env.GEMINI_API_KEY = 'k'
  const xml = rss(`<item>
    <title><![CDATA[Novita uscite di novembre - HoG]]></title>
    <link>https://www.houseofgames.it/uscite</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <content:encoded><![CDATA[<p>${'x'.repeat(110_000)} SENTINEL_FINE</p>]]></content:encoded>
  </item>`)
  fetchMock.mockResolvedValueOnce(rssOk(xml)).mockResolvedValueOnce(geminiOk('{"games":[]}'))
  await fetchNewsProducts(source, undefined, {
    sleep: noSleep,
    news: { sources: newsSources('q'), sleep: noSleep },
  })
  expect(fetchMock).toHaveBeenCalledTimes(2)
  const prompt = JSON.parse(fetchMock.mock.calls[1][1].body as string).contents[0].parts[0].text as string
  expect(prompt).not.toContain('SENTINEL_FINE')
  expect(prompt).toContain(`x`.repeat(1000))
})

// Budget di 5 fetch di corpo falliti di fila: dopo il quinto gli altri candidati senza corpo
// saltano direttamente sul percorso solo-titoli senza più essere provati.
test('dopo 5 fetch di corpo falliti di fila smette di provarci e resta sui titoli', async () => {
  process.env.GEMINI_API_KEY = 'k'
  const items = Array.from({ length: 6 }, (_, i) => item(`Arriva il gioco ${i}`, 'Wed, 15 Oct 2026 09:30:00 GMT')).join('')
  fetchMock.mockResolvedValueOnce(geminiOk('[{"title": "Cascadia"}]'))
  const articleFetch = vi.fn().mockResolvedValue(null)
  const out = await fetchNewsProducts(source, undefined, {
    sleep: noSleep,
    news: {
      sources: newsSources('q'),
      sleep: noSleep,
      fetchImpl: (async () => rssOk(rss(items))) as unknown as typeof fetch,
    },
    articleFetch,
  })
  expect(articleFetch).toHaveBeenCalledTimes(5)
  expect(out).toHaveLength(1)
  expect(out[0]?.title).toBe('Cascadia')
  // Tutti e 6 i titoli finiscono nel prompt del percorso solo-titoli.
  const prompt = JSON.parse(fetchMock.mock.calls[0][1].body as string).contents[0].parts[0].text as string
  for (let i = 0; i < 6; i++) expect(prompt).toContain(`Arriva il gioco ${i}`)
})

// Un corpo vuoto nel feed (body: '') è falsy: deve comunque tentare il fetch dell'articolo.
test('un corpo vuoto nel feed prova comunque il fetch dell’articolo', async () => {
  process.env.GEMINI_API_KEY = 'k'
  const xml = rss(`<item>
    <title><![CDATA[Novita uscite di novembre - HoG]]></title>
    <link>https://www.houseofgames.it/uscite</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <content:encoded><![CDATA[]]></content:encoded>
  </item>`)
  fetchMock
    .mockResolvedValueOnce(rssOk(xml))
    .mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [{ title: 'Cascadia' }] })))
  const articleFetch = vi.fn().mockResolvedValue(`<p>${'Arriva il gioco Cascadia. '.repeat(20)}</p>`)
  const out = await fetchNewsProducts(source, undefined, {
    sleep: noSleep,
    news: { sources: newsSources('q'), sleep: noSleep },
    articleFetch,
  })
  expect(articleFetch).toHaveBeenCalledTimes(1)
  expect(out).toHaveLength(1)
  expect(out[0]?.title).toBe('Cascadia')
  expect(out[0]?.url).toBe('https://www.houseofgames.it/uscite')
})

// Un errore di gameFinder su un gioco non scarta gli altri prodotti già mappati dall'articolo.
test('un errore di gameFinder tiene i prodotti già mappati dall’articolo', async () => {
  process.env.GEMINI_API_KEY = 'k'
  fetchMock
    .mockResolvedValueOnce(rssOk(rss(bodyHog)))
    .mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [
      { title: 'Cascadia', publisher: 'North Star', releaseDate: 'novembre 2026', descriptionQuote: 'Arriva il gioco Cascadia.' },
      { title: 'Wingspan', releaseDate: 'novembre 2026' },
    ] })))
  const gameFinder = vi.fn(async (title: string) => {
    if (title === 'cascadia') throw new Error('catalogo inaccessibile')
    return { gameId: 7, price: 39.99, currency: 'EUR', releaseDate: '2027-01-01', imageUrl: 'https://img/x.png' }
  })
  const out = await fetchNewsProducts(source, gameFinder, {
    sleep: noSleep,
    news: { sources: newsSources('q'), sleep: noSleep },
    articleFetch: async () => null,
  })
  expect(out).toHaveLength(2)
  const cascadia = out.find((p) => p.title === 'Cascadia')
  const wingspan = out.find((p) => p.title === 'Wingspan')
  // Il gioco in errore resta con i campi dell'articolo: nessun override di catalogo.
  expect(cascadia).toMatchObject({
    releaseDateRaw: '2026-11-01',
    articleDescription: 'Arriva il gioco Cascadia.',
  })
  expect(cascadia).not.toHaveProperty('priceRaw')
  expect(cascadia).not.toHaveProperty('gameId')
  expect(wingspan).toMatchObject({ priceRaw: '39.99', gameId: 7, releaseDateRaw: '2027-01-01' })
})

// Pacing: una sola pausa tra il primo e il secondo articolo analizzato, mai dopo l'ultimo.
test('dopo il primo articolo analizzato fa una pausa prima del successivo', async () => {
  process.env.GEMINI_API_KEY = 'k'
  const items = ['uscite-novembre', 'uscite-dicembre'].map((slug) => `<item>
    <title><![CDATA[Nuove uscite ${slug} - HoG]]></title>
    <link>https://www.houseofgames.it/${slug}</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <content:encoded><![CDATA[<p>${'Arriva un gioco nuovo. '.repeat(20)}</p>]]></content:encoded>
  </item>`).join('')
  fetchMock.mockImplementation(async (url: string) =>
    url.includes('generativelanguage') ? geminiOk('{"games":[]}') : rssOk(rss(items)))
  const sleep = vi.fn(async () => {})
  await fetchNewsProducts(source, undefined, {
    sleep,
    news: { sources: newsSources('q'), sleep: noSleep },
  })
  expect(sleep).toHaveBeenCalledTimes(1)
  expect(sleep).toHaveBeenCalledWith(1000)
})
