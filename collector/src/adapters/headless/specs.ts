import type { HtmlSourceSpec } from '../html/crawl.js'
import { magicmerchantSpec } from './magicmerchant.js'
import { ravensburgerSpec } from '../html/ravensburger.js'
import { dungeondiceSpec } from '../html/dungeondice.js'
import { uplaySpec } from '../html/uplay.js'

// Spec per le fonti dietro Cloudflare: stesso tipo dell'HTML crawl, ma servite dal
// fetcher dedicato (`curl` da IP residenziale → reader Jina AI da IP datacenter).
export const HEADLESS_SPECS: Record<string, HtmlSourceSpec> = {
  magicmerchant: magicmerchantSpec,
  ravensburger: ravensburgerSpec,
  dungeondice: dungeondiceSpec,
  uplay: uplaySpec,
}
