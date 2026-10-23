import { readFileSync } from 'node:fs'
import { expect, test } from 'vitest'
import { parseSitemapEntries } from '../../src/adapters/sitemap.js'
import {
  ROUNDUP_FLOOR_DATE, isRoundupUrl, filterRoundupEntries, extractDatePublished, parseRoundupArticle,
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
