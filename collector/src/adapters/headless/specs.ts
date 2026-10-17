import type { HtmlSourceSpec } from '../html/crawl.js'
import { magicmerchantSpec } from './magicmerchant.js'
import { ravensburgerSpec } from '../html/ravensburger.js'
import { dungeondiceSpec } from '../html/dungeondice.js'

// Spec per le fonti dietro Cloudflare, servite tramite il renderer browser (render.ts).
// Lo spec è lo stesso tipo usato dall'HTML crawl: cambia solo il `fetchText`.
export const HEADLESS_SPECS: Record<string, HtmlSourceSpec> = {
  magicmerchant: magicmerchantSpec,
  ravensburger: ravensburgerSpec,
  dungeondice: dungeondiceSpec,
}
