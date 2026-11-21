import { expect, test } from 'vitest'
import { HTML_SPECS } from '../../src/adapters/html/specs.js'
import { dungeondiceSpec } from '../../src/adapters/html/dungeondice.js'
import { collectLinks } from '../../src/adapters/html/links.js'

test('dungeondice collects product links (with or without category prefix) from its new-arrivals pages', async () => {
  const pages: Record<string, string> = {
    'https://www.dungeondice.it/445-ultime-novita': `<html><body>
      <a href="https://www.dungeondice.it/44937-barchette-papere-e-saponette.html">X</a>
      <a href="https://www.dungeondice.it/giochi-da-tavolo/44938-catan.html">Catan</a>
      <a href="https://www.dungeondice.it/445-ultime-novita?page=2">2</a>
      <a href="https://www.dungeondice.it/253-giochi-da-tavolo">Cat</a></body></html>`,
    'https://www.dungeondice.it/445-ultime-novita?page=2': `<html><body></body></html>`,
    'https://www.dungeondice.it/445-ultime-novita?page=3': `<html><body></body></html>`,
    'https://www.dungeondice.it/445-ultime-novita?page=4': `<html><body></body></html>`,
  }
  const urls = await dungeondiceSpec.discover(async (u) => pages[u] ?? null)
  expect(urls).toEqual([
    { url: 'https://www.dungeondice.it/44937-barchette-papere-e-saponette.html' },
    { url: 'https://www.dungeondice.it/giochi-da-tavolo/44938-catan.html' },
  ])
})

test('collectLinks retries a failed seed page once before skipping', async () => {
  const attempts: Record<string, number> = {}
  const fetchText = async (url: string) => {
    attempts[url] = (attempts[url] ?? 0) + 1
    if (url.endsWith('page=2') && attempts[url] === 1) return null
    return '<a href="https://www.dungeondice.it/123-gioco.html">G</a>'
  }
  const urls = await collectLinks(
    fetchText,
    ['https://www.dungeondice.it/445-ultime-novita?page=2'],
    (u) => u.endsWith('.html'),
    { retryDelayMs: 0 },
  )
  expect(attempts['https://www.dungeondice.it/445-ultime-novita?page=2']).toBe(2)
  expect(urls).toEqual(['https://www.dungeondice.it/123-gioco.html'])
})
