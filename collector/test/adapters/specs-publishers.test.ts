import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'

const LISTING = 'https://www.dvgiochi.com/prossime-uscite/'

test('dvgiochi discovers catalog links, reading the release date from the anchor text', async () => {
  const pages: Record<string, string> = {
    [LISTING]: `<html><body>
      <a href="/catalogo/">Catalogo</a>
      <a href="/catalogo/black-flag-ita/">BANG! Game System | Black Flag! (ITA) — 27 OTTOBRE 2026</a>
      <a href="/catalogo/nuovo-gioco/">Nuovo gioco</a>
    </body></html>`,
  }
  const items = await HTML_SPECS.dvgiochi.discover(async (u) => pages[u] ?? null)
  expect(items).toEqual([
    { url: 'https://www.dvgiochi.com/catalogo/black-flag-ita/', releaseDate: '2026-10-27' },
    { url: 'https://www.dvgiochi.com/catalogo/nuovo-gioco/' },
  ])
})

test('dvgiochi extract takes the first h2 text node without the DV Games suffix', () => {
  const html = `<html><body>
    <h2>BANG! Game System | Black Flag! (ITA) | DV Games<br /> <img src="/img/black-flag.png" width="40"></h2>
  </body></html>`
  const product = HTML_SPECS.dvgiochi.extract?.(html, 'https://www.dvgiochi.com/catalogo/black-flag-ita/')
  expect(product?.title).toBe('BANG! Game System | Black Flag! (ITA)')
  expect(product?.externalId).toBe('black-flag-ita')
  expect(product?.priceRaw).toBeUndefined()
})

test('dvgiochi extract returns null when the page has no h2', () => {
  const html = `<html><body><div>nessun titolo</div></body></html>`
  expect(HTML_SPECS.dvgiochi.extract?.(html, 'https://www.dvgiochi.com/catalogo/senza-titolo/')).toBeNull()
})
