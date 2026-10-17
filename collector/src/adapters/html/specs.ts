import type { HtmlSourceSpec } from './crawl.js'
import { uplaySpec } from './uplay.js'
import { asmodeeSpec } from './asmodee.js'
import { giochiunitiSpec } from './giochiuniti.js'
import { philibertSpec } from './philibert.js'
import { dvgiochiSpec } from './dvgiochi.js'
import { horribleguildSpec } from './horribleguild.js'

export const HTML_SPECS: Record<string, HtmlSourceSpec> = {
  uplay: uplaySpec,
  asmodee: asmodeeSpec,
  giochiuniti: giochiunitiSpec,
  philibert: philibertSpec,
  dvgiochi: dvgiochiSpec,
  horribleguild: horribleguildSpec,
}
