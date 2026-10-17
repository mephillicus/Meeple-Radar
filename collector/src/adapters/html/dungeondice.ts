import type { HtmlSourceSpec } from './crawl.js'
import { collectLinks, toItems } from './links.js'

// dungeondice.it è dietro Cloudflare: il fetch di Node riceve 403 ("Just a moment…"),
// quindi la fonte è `headless` (Task 9 riusa questo spec con il renderer browser).
const SEEDS = [
  'https://www.dungeondice.it/445-ultime-novita',
  'https://www.dungeondice.it/445-ultime-novita?page=2',
  'https://www.dungeondice.it/445-ultime-novita?page=3',
  'https://www.dungeondice.it/445-ultime-novita?page=4',
]

const PRODUCT_URL = /^https:\/\/www\.dungeondice\.it\/\d+-[^/]+\.html$/

export const dungeondiceSpec: HtmlSourceSpec = {
  discover: async (fetchText) => toItems(await collectLinks(fetchText, SEEDS, (url) => PRODUCT_URL.test(url))),
}
