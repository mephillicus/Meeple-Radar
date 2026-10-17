import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'

const LISTING = 'https://www.dvgiochi.com/prossime-uscite/'

test('dvgiochi discovers catalog links, reading the release date from the start of the anchor text', async () => {
  const pages: Record<string, string> = {
    [LISTING]: `<html><body>
      <a href="/catalogo/">Catalogo</a>
      <a href="/catalogo/black-flag-ita/">27 OTTOBRE 2026 – in arrivo BANG! Il gioco di carte definitivo!</a>
      <a href="/catalogo/nuovo-gioco/">DA DEFINIRE – in arrivo Nuovo gioco: scoprilo presto!</a>
    </body></html>`,
  }
  const items = await HTML_SPECS.dvgiochi.discover(async (u) => pages[u] ?? null)
  expect(items).toEqual([
    { url: 'https://www.dvgiochi.com/catalogo/black-flag-ita/', releaseDate: '2026-10-27' },
    { url: 'https://www.dvgiochi.com/catalogo/nuovo-gioco/' },
  ])
})

test('dvgiochi extract takes the clean product name from the h2 image title', () => {
  const html = `<html><body>
    <h2>BANG! Game System | Black Flag! (ITA) | DV Games<br /> <img src="/images/infobox.gif" alt="Black Flag! (ITA)" title="Black Flag! (ITA)"></h2>
  </body></html>`
  const product = HTML_SPECS.dvgiochi.extract?.(html, 'https://www.dvgiochi.com/catalogo/black-flag-ita/')
  expect(product?.title).toBe('Black Flag! (ITA)')
  expect(product?.externalId).toBe('black-flag-ita')
  expect(product?.priceRaw).toBeUndefined()
})

test('dvgiochi extract falls back to the h2 text node when there is no image', () => {
  const html = `<html><body><h2>Combo Creatures: Piccoli e scattanti</h2></body></html>`
  const product = HTML_SPECS.dvgiochi.extract?.(html, 'https://www.dvgiochi.com/catalogo/combo-creatures/')
  expect(product?.title).toBe('Combo Creatures: Piccoli e scattanti')
})

test('dvgiochi extract returns null when the page has no h2', () => {
  const html = `<html><body><div>nessun titolo</div></body></html>`
  expect(HTML_SPECS.dvgiochi.extract?.(html, 'https://www.dvgiochi.com/catalogo/senza-titolo/')).toBeNull()
})
