import type { HtmlSourceSpec } from './crawl.js'
import { uplaySpec } from './uplay.js'
import { asmodeeSpec } from './asmodee.js'

export const HTML_SPECS: Record<string, HtmlSourceSpec> = {
  uplay: uplaySpec,
  asmodee: asmodeeSpec,
}
