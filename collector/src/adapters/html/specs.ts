import type { HtmlSourceSpec } from './crawl.js'
import { asmodeeSpec } from './asmodee.js'
import { giochiunitiSpec } from './giochiuniti.js'
import { dvgiochiSpec } from './dvgiochi.js'
import { horribleguildSpec } from './horribleguild.js'
import { gateongamesSpec } from './gateongames.js'

export const HTML_SPECS: Record<string, HtmlSourceSpec> = {
  asmodee: asmodeeSpec,
  giochiuniti: giochiunitiSpec,
  dvgiochi: dvgiochiSpec,
  horribleguild: horribleguildSpec,
  gateongames: gateongamesSpec,
}
