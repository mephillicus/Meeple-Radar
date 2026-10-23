import { readFileSync } from 'node:fs'
import { expect, test, vi } from 'vitest'
import { parseSitemapEntries } from '../../src/adapters/sitemap.js'
import {
  ROUNDUP_FLOOR_DATE, isRoundupUrl, filterRoundupEntries, extractDatePublished, parseRoundupArticle,
  fetchRoundupProducts,
} from '../../src/adapters/roundups.js'

const sitemapXml = readFileSync(new URL('../fixtures/hog-post-sitemap.xml', import.meta.url), 'utf8')
const articleHtml = readFileSync(new URL('../fixtures/hog-roundup-article.html', import.meta.url), 'utf8')

test('isRoundupUrl accetta i roundups mensili e rifiuta tutto il resto', () => {
  expect(isRoundupUrl('https://www.houseofgames.it/giochi-da-tavolo-tutte-le-nuove-uscite-di-ottobre-lucca-comics-e-combo-festival-2026/')).toBe(true)
  expect(isRoundupUrl('https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/')).toBe(true)
  expect(isRoundupUrl('https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-dicembre-perfette-per-natale/')).toBe(true)
  expect(isRoundupUrl('https://www.houseofgames.it/uscite-videogiochi-2025-calendario-completo-per-ps5-xbox-nintendo-switch-e-pc/')).toBe(false)
  expect(isRoundupUrl('https://www.houseofgames.it/recensione-gioco-da-tavolo-catan/')).toBe(false)
  expect(isRoundupUrl('https://www.houseofgames.it/giochi-da-tavolo-catan-recensione/')).toBe(false)
  expect(isRoundupUrl('https://www.houseofgames.it/dungeon-crawler-cose/')).toBe(false)
  expect(isRoundupUrl('non-un-url')).toBe(false)
})

test('filterRoundupEntries tiene gli slug validi con lastmod >= floor e i lastmod mancanti', () => {
  const kept = filterRoundupEntries(parseSitemapEntries(sitemapXml)).map((e) => e.loc)
  expect(kept).toEqual([
    'https://www.houseofgames.it/giochi-da-tavolo-tutte-le-nuove-uscite-di-ottobre-lucca-comics-e-combo-festival-2026/',
    'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/',
    'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-agosto-2026/',
    'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-novembre-2025/', // lastmod assente: tenuto (safe)
  ])
  expect(ROUNDUP_FLOOR_DATE).toBe('2026-08-01')
})

test('extractDatePublished legge il json-ld aioseo della pagina reale', () => {
  expect(extractDatePublished(articleHtml)).toBe('2026-10-02')
  expect(extractDatePublished('<html><body>senza schema</body></html>')).toBeNull()
})

test('parseRoundupArticle estrae i 87 giochi della pagina reale con publisher, data e riassunto', () => {
  const games = parseRoundupArticle(articleHtml)
  expect(games).toHaveLength(87)
  // I li di menu/sidebar (attributi o <a>) non sono giochi.
  expect(games.every((g) => g.title !== 'LIBROGAME')).toBe(true)
  expect(games.every((g) => g.publisher !== undefined)).toBe(true)
  expect(new Set(games.map((g) => g.publisher)).size).toBe(16)

  const toy = games[0]
  expect(toy).toMatchObject({ title: 'TOY BATTLE – LA CROISETTE', dateRaw: '2 Ottobre', publisher: 'ASMODEE' })
  expect(toy.summary).toContain('Porta le tue battaglie di giocattoli')
  // Il riassunto esce prima del link promo dungeondice.
  expect(toy.summary).not.toContain('dungeondice')
  expect(toy.summary).not.toContain('Cliccando')

  // Due giochi senza data fra parentesi restano senza dateRaw.
  const noDate = games.find((g) => g.title === 'FAME & FABLE')
  expect(noDate?.dateRaw).toBeUndefined()
  expect(noDate?.publisher).toBeDefined()

  // Entità decodificate e varianti di parentesi/spazi.
  expect(games.some((g) => g.title === 'LET’S CHAT' && g.dateRaw === 'Fine Ottobre')).toBe(true)
  expect(games.some((g) => g.title.includes('MOZA') && g.dateRaw === '2 Ottobre')).toBe(true)
  expect(games.some((g) => g.title.endsWith('DEL MONDO') && g.dateRaw === '23 Ottobre')).toBe(true)
  expect(games.some((g) => g.title.includes('LORCANA') && g.dateRaw === '1 OTTOBRE')).toBe(true)
  expect(games.some((g) => g.title === 'FOURTH WING' && g.dateRaw === 'Anteprima Lucca')).toBe(true)
})

test('lo summary si ferma ai link, alle immagini e al publisher successivo', () => {
  const html = `
    <p><strong>CASA EDITRICE</strong></p>
    <ul><li><strong>Gioco Uno (3 Gennaio)</strong></li></ul>
    <p><img src="x.webp"/></p>
    <p>Prima frase del riassunto.</p>
    <p>Seconda frase.</p>
    <p><a href="https://www.dungeondice.it/p/1">Cliccando qui</a></p>
    <p><strong>ALTRA CASA</strong></p>
    <ul><li><strong>Gioco Due</strong></li></ul>
    <li class="menu"><a href="/x">MENU</a></li>`
  const games = parseRoundupArticle(html)
  expect(games).toHaveLength(2)
  expect(games[0].summary).toBe('Prima frase del riassunto. Seconda frase.')
  expect(games[1]).toMatchObject({ title: 'Gioco Due', publisher: 'ALTRA CASA', dateRaw: undefined, summary: undefined })
})

test('un li con attributi o un p con testo forte fuori dal contesto non creano publisher', () => {
  const html = `
    <ul><li class="menu-item"><strong>NON UN GIOCO (1 Gennaio)</strong></li></ul>
    <ul><li><strong>Gioco Prima Di Ogni Publisher</strong></li></ul>`
  const games = parseRoundupArticle(html)
  expect(games).toHaveLength(1)
  expect(games[0].title).toBe('Gioco Prima Di Ogni Publisher')
  expect(games[0].publisher).toBeUndefined()
})

const SOURCE = {
  id: 'houseofgames',
  name: 'House of Games',
  kind: 'roundups' as const,
  baseUrl: 'https://www.houseofgames.it',
  enabled: true,
}

test('fetchRoundupProducts mappa la pagina reale in righe RawProduct con gameFinder', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const articoloUrl = 'https://www.houseofgames.it/giochi-da-tavolo-tutte-le-nuove-uscite-di-ottobre-lucca-comics-e-combo-festival-2026/'
  const pages: Record<string, string> = { [sitemapUrl]: sitemapXml, [articoloUrl]: articleHtml }
  const gameFinder = vi.fn().mockResolvedValue({ gameId: 5, price: 29.99, currency: 'EUR', releaseDate: null, imageUrl: 'https://e.com/i.webp' })
  const out = await fetchRoundupProducts(
    { id: 'houseofgames', name: 'House of Games', kind: 'roundups' as const, baseUrl: 'https://www.houseofgames.it', enabled: true },
    gameFinder,
    { fetchText: async (u) => pages[u] ?? null, sleep: async () => {} },
  )
  expect(out).toHaveLength(87)
  const toy = out.find((p) => p.externalId === 'toy-battle-la-croisette')
  expect(toy).toMatchObject({
    url: articoloUrl,
    title: 'TOY BATTLE – LA CROISETTE',
    publisher: 'ASMODEE',
    releaseDateRaw: '2026-10-02',
    priceRaw: '29.99',
    currencyRaw: 'EUR',
    gameId: 5,
  })
  expect(toy?.articleDescription).toContain('Porta le tue battaglie')
  expect(gameFinder).toHaveBeenCalledWith('toy battle la croisette', 'ASMODEE')
})

test('floor pre-fetch: gli articoli con lastmod prima del floor non vengono scaricati', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const vecchio = 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-giugno-2026-speciale-play-bologna/'
  const pages: Record<string, string> = { [sitemapUrl]: sitemapXml, [vecchio]: '<p>non deve servire</p>' }
  const fetched: string[] = []
  const out = await fetchRoundupProducts(SOURCE, undefined, {
    fetchText: async (u) => { fetched.push(u); return pages[u] ?? null },
    sleep: async () => {},
  })
  // La sitemap contiene solo un roundups pre-floor scaricabile: nessun fetch di quel URL.
  expect(fetched.filter((u) => u === vecchio)).toHaveLength(0)
  // Tutti gli altri candidati (ago/set/ott 2026) non sono in pages → fetch null → skip silenzioso.
  expect(out).toEqual([])
})

test('floor autorevole: una pagina con datePublished pre-floor viene scartata', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const url = 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/'
  const vecchiaPagina = articleHtml.replace(
    /"datePublished"\s*:\s*"[^"]+"/,
    '"datePublished": "2026-07-01T09:00:00+02:00"',
  )
  const out = await fetchRoundupProducts(SOURCE, undefined, {
    fetchText: async (u) => (u === sitemapUrl ? sitemapXml : u === url ? vecchiaPagina : null),
    sleep: async () => {},
  })
  expect(out).toEqual([])
})

test('sitemap non raggiungibile lancia; un articolo non fetchabile salta senza far cadere la fonte', async () => {
  await expect(fetchRoundupProducts(SOURCE, undefined, { fetchText: async () => null, sleep: async () => {} }))
    .rejects.toThrow(/non raggiungibile/)
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const out = await fetchRoundupProducts(SOURCE, undefined, {
    fetchText: async (u) => (u === sitemapUrl ? sitemapXml : null),
    sleep: async () => {},
  })
  expect(out).toEqual([])
})

test('due roundups con lo stesso gioco → una riga sola, vince l\'articolo più recente', async () => {
  const sitemapUrl = 'https://www.houseofgames.it/post-sitemap.xml'
  const settembre = 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-settembre-2026/'
  const agosto = 'https://www.houseofgames.it/giochi-da-tavolo-le-nuove-uscite-di-agosto-2026/'
  const mini = (data: string) => `<script type="application/ld+json">{"datePublished":"${data}"}</script>
    <p><strong>CASA</strong></p><ul><li><strong>Gioco Uguale (5 Gennaio)</strong></li></ul><p>Riassunto.</p>`
  const out = await fetchRoundupProducts(SOURCE, undefined, {
    fetchText: async (u) =>
      u === sitemapUrl
        ? `<urlset><url><loc>${settembre}</loc><lastmod>2026-09-03</lastmod></url><url><loc>${agosto}</loc><lastmod>2026-08-04</lastmod></url></urlset>`
        : u === settembre ? mini('2026-09-03') : mini('2026-08-04'),
    sleep: async () => {},
  })
  expect(out).toHaveLength(1)
  expect(out[0].url).toBe(settembre)
})
