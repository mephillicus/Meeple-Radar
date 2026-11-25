import type { Listing, RawProduct } from './types.js'
import { decodeHtml, htmlToText } from './text.js'

const TRAILING_SUFFIX_RE =
  /\s+(?:edizione italiana|ed italiana|italiano|ita|il gioco di carte|gioco di carte|il gioco da tavolo|gioco da tavolo|card game|board game)$/

export function normalizeTitle(title: string): string {
  // Normalizzazione deterministica anti-duplicato (allineata 1:1 con SQL public.normalize_collector_title):
  // 1. Lowercase
  let s = title.toLowerCase()
  // 2. De-offuscamento parole censurate (es. "Adult S#!ts" -> "adult shits")
  s = s.replace(/\bs#!t(s?)\b/g, 'shit$1').replace(/\bsh!t(s?)\b/g, 'shit$1')
  // 3. Punteggiatura separatrice (-, –, —, /, :) -> spazio prima di rimuovere i caratteri non-word
  s = s.replace(/[-–—/:]+/g, ' ')
  // 4. Rimozione caratteri non-word/non-space residui e collasso spazi
  s = s.replace(/[^\w\s]/gu, '').replace(/\s+/g, ' ').trim()
  // 5. Uniforma "<N> minutes" in "<N> minute"
  s = s.replace(/\b(\d+)\s+minutes\b/g, '$1 minute')
  // 6. Rimuove suffissi generici di edizione/formato in coda (fino a 3 passate),
  //    solo se preceduti da almeno una parola (\s+).
  for (let i = 0; i < 3; i++) {
    const next = s.replace(TRAILING_SUFFIX_RE, '')
    if (next === s) break
    s = next
  }
  return s
}

function levenshteinDistance(a: string, b: string): number {
  const m = a.length
  const n = b.length
  const dp: number[] = Array.from({ length: n + 1 }, (_, j) => j)
  for (let i = 1; i <= m; i++) {
    let prev = dp[0]
    dp[0] = i
    for (let j = 1; j <= n; j++) {
      const temp = dp[j]
      const cost = a[i - 1] === b[j - 1] ? 0 : 1
      dp[j] = Math.min(dp[j] + 1, dp[j - 1] + 1, prev + cost)
      prev = temp
    }
  }
  return dp[n]
}

export function areTitlesNearDuplicate(normA: string, normB: string): boolean {
  if (normA === normB) return true
  if (normA.length < 8 || normB.length < 8) return false
  const firstWordA = normA.split(/\s+/)[0] ?? ''
  const firstWordB = normB.split(/\s+/)[0] ?? ''
  if (firstWordA.length < 3 || firstWordA !== firstWordB) return false
  if (Math.abs(normA.length - normB.length) > 2) return false
  if (normA.replace(/\D+/g, '') !== normB.replace(/\D+/g, '')) return false
  return levenshteinDistance(normA, normB) <= 2
}

export function toPrice(priceRaw?: string, minorUnit?: number): number | null {
  if (priceRaw === undefined) return null
  let cleaned = priceRaw
    .replace(/(?:€|EUR|\$|USD|£|GBP)/gi, '')
    .replace(/[\s\u00a0]+/g, '')
    .trim()
  if (cleaned === '') return null

  const hasComma = cleaned.includes(',')
  const hasDot = cleaned.includes('.')
  if (hasComma && hasDot) {
    if (cleaned.lastIndexOf(',') > cleaned.lastIndexOf('.')) {
      // Formato europeo con separatore migliaia: "1.200,50" -> "1200.50"
      cleaned = cleaned.replace(/\./g, '').replace(',', '.')
    } else {
      // Formato anglosassone: "1,200.50" -> "1200.50"
      cleaned = cleaned.replace(/,/g, '')
    }
  } else if (hasComma) {
    if (/^\d{1,3}(?:,\d{3})+$/.test(cleaned)) {
      cleaned = cleaned.replace(/,/g, '')
    } else {
      cleaned = cleaned.replace(',', '.')
    }
  }

  const n = Number(cleaned)
  if (!Number.isFinite(n)) return null
  if (minorUnit === undefined) return n
  const value = n / 10 ** minorUnit
  return Number.isFinite(value) ? value : null
}

function isValidIso(iso: string): boolean {
  const [y, m, d] = iso.split('-').map(Number)
  if (!y || !m || !d || m < 1 || m > 12 || d < 1 || d > 31) return false
  const dt = new Date(Date.UTC(y, m - 1, d))
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d
}

export const RELEASE_YEAR_MIN_OFFSET = -1
export const RELEASE_YEAR_MAX_OFFSET = 3

function isPlausibleYear(year: number, nowYear: number): boolean {
  return year >= nowYear + RELEASE_YEAR_MIN_OFFSET && year <= nowYear + RELEASE_YEAR_MAX_OFFSET
}

function isPlausibleIso(iso: string, nowYear: number): boolean {
  return isValidIso(iso) && isPlausibleYear(Number(iso.slice(0, 4)), nowYear)
}

const DM_RELEASE_CUE_RE = /\b(?:uscita|arrivo|dal\b|il\b|previst|disponibile|day\s*one\b|spedizion)/i
const SEASON_RELEASE_CUE_RE = /\b(?:uscita|arrivo|disponibile|previst|preordine|spedizion)/i
const REPRINT_CUE_RE =
  /\b(?:rientro|riassortimento|ristampa|reprint|restock|nuova\s+stampa|torna\s+disponibile)\b/i
const REPRINT_CUE_GLOBAL_RE = new RegExp(REPRINT_CUE_RE.source, 'gi')

const FULL_MONTHS: Record<string, number> = {
  gennaio: 1, january: 1,
  febbraio: 2, february: 2,
  marzo: 3, march: 3,
  aprile: 4, april: 4,
  maggio: 5,
  giugno: 6, june: 6,
  luglio: 7, july: 7,
  agosto: 8, august: 8,
  settembre: 9, september: 9,
  ottobre: 10, october: 10,
  novembre: 11, november: 11,
  dicembre: 12, december: 12,
}

const ABBREV_MONTHS: Record<string, number> = {
  gen: 1, jan: 1,
  febr: 2, feb: 2,
  mar: 3,
  apr: 4,
  mag: 5, may: 5,
  giu: 6, jun: 6,
  lug: 7, jul: 7,
  ago: 8, aug: 8,
  sept: 9, sep: 9, set: 9,
  ott: 10, oct: 10,
  nov: 11,
  dic: 12, dec: 12,
}

const ALL_MONTHS: Record<string, number> = { ...FULL_MONTHS, ...ABBREV_MONTHS }
const MONTH_NAME_RE = new RegExp(
  '\\b(?:(\\d{1,2})\\s+)?(' + Object.keys(ALL_MONTHS).join('|') + ')\\b',
  'gi',
)

const STAGIONI: Record<string, { m: number; d: number }> = {
  primavera: { m: 4, d: 15 },
  estate: { m: 7, d: 15 },
  autunno: { m: 10, d: 15 },
  inverno: { m: 1, d: 15 },
}

function isPrecededByReprintCue(text: string, idx: number, window = 45): boolean {
  const before = text.slice(Math.max(0, idx - window), idx)
  return REPRINT_CUE_RE.test(before)
}

export function extractReleaseDate(text?: string): string | null {
  if (!text) return null
  const cleaned = text.includes('<') ? htmlToText(text) : text
  if (!cleaned) return null

  const nowYear = new Date().getUTCFullYear()
  const accept = (iso: string): boolean => isPlausibleIso(iso, nowYear)

  for (const dmy of cleaned.matchAll(/(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{4})/g)) {
    const idx = dmy.index ?? 0
    if (isPrecededByReprintCue(cleaned, idx)) continue
    const iso = `${dmy[3]}-${dmy[2].padStart(2, '0')}-${dmy[1].padStart(2, '0')}`
    if (accept(iso)) return iso
  }

  // anno-mese-giorno: la data intera, non va degradata al primo del mese
  for (const ymd of cleaned.matchAll(/(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)/g)) {
    const idx = ymd.index ?? 0
    if (isPrecededByReprintCue(cleaned, idx)) continue
    const iso = `${ymd[1]}-${ymd[2].padStart(2, '0')}-${ymd[3].padStart(2, '0')}`
    if (accept(iso)) return iso
  }

  for (const ym of cleaned.matchAll(/(\d{4})-(\d{1,2})(?!\d)/g)) {
    const idx = ym.index ?? 0
    if (isPrecededByReprintCue(cleaned, idx)) continue
    const iso = `${ym[1]}-${ym[2].padStart(2, '0')}-01`
    if (accept(iso)) return iso
  }

  // Solo lo slash resta separatore giorno/mese, e solo se preceduto entro 30 caratteri
  // da un indizio di uscita, così rapporti/giocatori ("2/4", "1/2", "9/10") non matchano.
  for (const dm of cleaned.matchAll(/(\d{1,2})\/(\d{1,2})(?![\/\-.]\d)/g)) {
    const idx = dm.index ?? 0
    if (isPrecededByReprintCue(cleaned, idx)) continue
    const before = cleaned.slice(Math.max(0, idx - 30), idx)
    if (!DM_RELEASE_CUE_RE.test(before)) continue
    const iso = `${nowYear}-${dm[2].padStart(2, '0')}-${dm[1].padStart(2, '0')}`
    if (accept(iso)) return iso
  }

  for (const mn of cleaned.matchAll(MONTH_NAME_RE)) {
    const idx = mn.index ?? 0
    if (isPrecededByReprintCue(cleaned, idx)) continue
    const monthKey = mn[2].toLowerCase()
    const isFull = monthKey in FULL_MONTHS
    const after = cleaned.slice(idx + mn[0].length)

    // Le abbreviazioni a 3 lettere ("set", "may", "ago", ...) matchano solo se
    // precedute da un giorno ("15 set") o seguite subito da un anno 20xx ("set 2026").
    if (!isFull && !mn[1] && !/^\s*20\d\d\b/.test(after)) continue

    const monthPart = String(ALL_MONTHS[monthKey]).padStart(2, '0')
    // Il giorno può stare prima del nome del mese ("10 marzo") o dopo ("March 10").
    const dayPart = (mn[1] ?? after.match(/^\s*(\d{1,2})\b/)?.[1] ?? '1').padStart(2, '0')
    // L'anno deve stare entro 20 caratteri dal mese per non catturare copyright lontani.
    const yearMatch = after.slice(0, 20).match(/^\s*(?:\d{1,2}[,\s]+)?(\d{4})\b/)
    const year = Number(yearMatch?.[1] ?? nowYear)
    const iso = `${year}-${monthPart}-${dayPart}`
    if (accept(iso)) return iso
  }

  const trimmedLower = cleaned.trim().toLowerCase()
  for (const sn of cleaned.matchAll(/\b(primavera|estate|autunno|inverno)\b/gi)) {
    const seasonKey = sn[1].toLowerCase()
    const idx = sn.index ?? 0
    if (isPrecededByReprintCue(cleaned, idx)) continue
    const after = cleaned.slice(idx + sn[0].length)
    const yearMatch = after.slice(0, 20).match(/^\s*(\d{4})\b/)
    const before = cleaned.slice(Math.max(0, idx - 30), idx)
    const isBareSeason = trimmedLower === seasonKey
    if (!yearMatch && !SEASON_RELEASE_CUE_RE.test(before) && !isBareSeason) continue

    const mg = STAGIONI[seasonKey]
    const year = Number(yearMatch?.[1] ?? nowYear)
    const iso = `${year}-${String(mg.m).padStart(2, '0')}-${String(mg.d).padStart(2, '0')}`
    if (accept(iso)) return iso
  }

  return null
}

export function extractReprintDate(text?: string): string | null {
  if (!text) return null
  const cleaned = text.includes('<') ? htmlToText(text) : text
  if (!cleaned) return null

  const nowYear = new Date().getUTCFullYear()
  const accept = (iso: string): boolean => isPlausibleIso(iso, nowYear)

  for (const cue of cleaned.matchAll(REPRINT_CUE_GLOBAL_RE)) {
    const cueEnd = (cue.index ?? 0) + cue[0].length
    const snippet = cleaned.slice(cueEnd, cueEnd + 80)

    for (const dmy of snippet.matchAll(/(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{4})/g)) {
      if ((dmy.index ?? 0) > 60) break
      const iso = `${dmy[3]}-${dmy[2].padStart(2, '0')}-${dmy[1].padStart(2, '0')}`
      if (accept(iso)) return iso
    }

    for (const ymd of snippet.matchAll(/(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)/g)) {
      if ((ymd.index ?? 0) > 60) break
      const iso = `${ymd[1]}-${ymd[2].padStart(2, '0')}-${ymd[3].padStart(2, '0')}`
      if (accept(iso)) return iso
    }

    for (const ym of snippet.matchAll(/(\d{4})-(\d{1,2})(?!\d)/g)) {
      if ((ym.index ?? 0) > 60) break
      const iso = `${ym[1]}-${ym[2].padStart(2, '0')}-01`
      if (accept(iso)) return iso
    }

    for (const mn of snippet.matchAll(MONTH_NAME_RE)) {
      const idx = mn.index ?? 0
      if (idx > 60) break
      const monthKey = mn[2].toLowerCase()
      const isFull = monthKey in FULL_MONTHS
      const after = snippet.slice(idx + mn[0].length)
      if (!isFull && !mn[1] && !/^\s*20\d\d\b/.test(after)) continue

      const monthPart = String(ALL_MONTHS[monthKey]).padStart(2, '0')
      const dayPart = (mn[1] ?? after.match(/^\s*(\d{1,2})\b/)?.[1] ?? '1').padStart(2, '0')
      const yearMatch = after.slice(0, 20).match(/^\s*(?:\d{1,2}[,\s]+)?(\d{4})\b/)
      const year = Number(yearMatch?.[1] ?? nowYear)
      const iso = `${year}-${monthPart}-${dayPart}`
      if (accept(iso)) return iso
    }

    for (const sn of snippet.matchAll(/\b(primavera|estate|autunno|inverno)\b/gi)) {
      const idx = sn.index ?? 0
      if (idx > 60) break
      const seasonKey = sn[1].toLowerCase()
      const after = snippet.slice(idx + sn[0].length)
      const yearMatch = after.slice(0, 20).match(/^\s*(\d{4})\b/)
      const mg = STAGIONI[seasonKey]
      const year = Number(yearMatch?.[1] ?? nowYear)
      const iso = `${year}-${String(mg.m).padStart(2, '0')}-${String(mg.d).padStart(2, '0')}`
      if (accept(iso)) return iso
    }
  }

  return null
}

export type CanonicalAvailability =
  | 'available'
  | 'out_of_stock'
  | 'preorder'
  | 'backorder'
  | 'reprint'
  | 'discontinued'

const AVAILABILITY_MAP: Record<string, CanonicalAvailability> = {
  true: 'available',
  '1': 'available',
  yes: 'available',
  available: 'available',
  instock: 'available',
  limitedavailability: 'available',
  instoreonly: 'available',
  false: 'out_of_stock',
  '0': 'out_of_stock',
  no: 'out_of_stock',
  outofstock: 'out_of_stock',
  soldout: 'out_of_stock',
  unavailable: 'out_of_stock',
  oos: 'out_of_stock',
  preorder: 'preorder',
  presale: 'preorder',
  preordine: 'preorder',
  preordina: 'preorder',
  backorder: 'backorder',
  backordered: 'backorder',
  ordinabile: 'backorder',
  suordinazione: 'backorder',
  reprint: 'reprint',
  ristampa: 'reprint',
  riassortimento: 'reprint',
  discontinued: 'discontinued',
  fuoriproduzione: 'discontinued',
}

export function normalizeAvailability(
  raw?: string | null,
  releaseDate?: string | null,
  now?: Date,
  reprintDate?: string | null,
): CanonicalAvailability | null {
  let mapped: CanonicalAvailability | null = null
  if (raw !== undefined && raw !== null) {
    const stripped = raw
      .trim()
      .replace(/^https?:\/\/schema\.org\//i, '')
      .toLowerCase()
      .replace(/[\s_-]+/g, '')
    if (stripped !== '') {
      mapped = AVAILABILITY_MAP[stripped] ?? null
    }
  }

  const todayIso = (now ?? new Date()).toISOString().slice(0, 10)

  if (mapped === 'available' && releaseDate && isValidIso(releaseDate)) {
    if (releaseDate > todayIso) return 'preorder'
  }

  if (
    reprintDate &&
    isValidIso(reprintDate) &&
    reprintDate > todayIso &&
    (mapped === 'out_of_stock' || mapped === 'backorder' || mapped === null)
  ) {
    return 'reprint'
  }

  return mapped
}

const LEGO_BOARDGAME_ALLOW_RE =
  /gioco\s+da\s+tavolo|board\s+game|monkey\s+palace|destiny['’]?s\s+bounty/i

const NON_BOARDGAME_BRAND_PATTERNS: readonly RegExp[] = [
  /\bpantasy\b/i,
  /\blemax\b/i,
  /\bfunko\b/i,
  /\bjakks\s+pacific\b/i,
  /\bultimate\s+guard\b/i,
  /\bgamegenic\b/i,
  /\bdragon\s+shield\b/i,
  /\bultra\s+pro\b/i,
  /\bchessex\b/i,
  /\bq-workshop\b/i,
  /\bwizkids\b/i,
  /\bvallejo\b/i,
  /\b(?:the\s+)?army\s+painter\b/i,
  /\bcitadel\b/i,
  /\bpaladone\b/i,
  /\babystyle\b/i,
  /\bbanpresto\b/i,
  /\bnoble\s+collection\b/i,
  /\bnemesis\s+now\b/i,
  /\bneca\b/i,
  /\bkeeppley\b/i,
  /\bbandai\b/i,
  /\bfunism\b/i,
  /\bmonogram\b/i,
  /\bgood\s+smile\b/i,
  /\bmegahouse\b/i,
  /\bsega\b/i,
  /\bfuryu\b/i,
  /\btaito\b/i,
  /\btwo\s+dots\b/i,
  /\btoylab\b/i,
  /\bzuru\b/i,
  /\bwaboba\b/i,
  /\bplaymobil\b/i,
  /\bmga\s+entertainment\b/i,
  /\bgiochi\s+preziosi\b/i,
  /\bbburago\b/i,
  /\bjada\b/i,
  /\bstopuzzle\b/i,
  /\bsd\s+toys\b/i,
  /\bubisoft\b/i,
  /\bsemic\b/i,
  /\bcinereplicas\b/i,
  /\bcosmic\s+group\b/i,
  /\bgiunti\b/i,
]

export function isNonBoardgameBrand(brand?: string | null, title?: string | null): boolean {
  if (!brand) return false
  const cleaned = brand.trim()
  if (!cleaned) return false
  if (/\blego\b/i.test(cleaned)) {
    return !(title ? LEGO_BOARDGAME_ALLOW_RE.test(title) : false)
  }
  return NON_BOARDGAME_BRAND_PATTERNS.some((re) => re.test(cleaned))
}

const NON_BOARDGAME_PATTERNS: readonly RegExp[] = [
  // Merch & Gadget: abbigliamento e accessori
  /\bt-?shirt\b/i,
  /\bmagliett[ae]\b/i,
  /\bfelp[ae]\b/i,
  /\bcappellin[oi]\b/i,
  /\btazz[ae]\b/i,
  /\bmug\b/i,
  /\bshopper\b/i,
  /\bportachiavi\b/i,
  /\bspill[ae]\b/i,
  /\bposter\b/i,
  /\bpeluche\b/i,
  /\bplush\b/i,
  /\bfunko\s+pop\b/i,
  /\bfunko\b/i,
  /\bbitty\s+pop\b/i,
  /\bpocket\s+pop\b/i,
  /\bpop\s+dlx\b/i,
  /\bpop\s+keychain\b/i,
  /\bblind\s+box\b/i,
  /\bbag\s+clip\b/i,
  /\bfigurina\b/i,
  /\(\s*figura\s*\)/i,
  /\bq\s+posket\b/i,
  /\blampada\b/i,
  /\bcalendario?\s+dell['’]?avvento\b/i,
  // Merch & Gadget: sticker, biglietti, voucher e cancelleria
  /\bset\s+sticker\b/i,
  /\bsticker\b/i,
  /\badesiv[oi]\b/i,
  /\bbigliett[oi]\s+auguri\b/i,
  /\bgift\s*card\b/i,
  /\bbuono\s+regalo\b/i,
  /\bset\s+cancelleria\b/i,
  // Sleeves, Bustine, Binder, Playmat, Deck Box
  /\bbustin[ae]\b/i,
  /\bbuste\s+protettive\b/i,
  /\bbox\s+\d+\s*buste\b/i,
  /\bblister\s+\d+\s*buste\b/i,
  /\bsleeves?\b/i,
  /\bfoderin[ae]\b/i,
  /\bbinder\b/i,
  /\balbum\s+porta\s*carte\b/i,
  /\bportamazz[oi]\b/i,
  /\bdeck\s*box\b/i,
  /\bplay\s*mat\b/i,
  /\bplaymat\b/i,
  /\btappetin[oi]\b/i,
  // Puzzle & Giocattoli (BRIO / GraviTrax / CreArt / tiptoi / Pantasy / Lemax / Keeppley / LEGO SKU)
  /\bpan\d{4,}[a-z]*\b/i,
  /\bkp\d{4,}[a-z]*\b/i,
  /^\s*\d{5}\s*-\s+/i,
  /\bpantasy\b/i,
  /\bedifici\s+modulari\b/i,
  /\blemax\b/i,
  /\b(?:adattatore|alimentatore)\s+4\.5v\b/i,
  /\bpuzzle\s+\d+\s*pezzi\b/i,
  /\bpuzzle\s+\d+\s*pz\b/i,
  /\b\d+\s+pezzi\b/i,
  /\bpuzzle\s+[23]d\b/i,
  /\b3d\s+puzzle\b/i,
  /\bpuzzle\s+ball\b/i,
  /\bpuzzle\s+ravensburger\b/i,
  /\bmaxi\s+puzzle\b/i,
  /\b\d+\s+puzzle\b/i,
  /\bpuzzle\s+\d+p\b/i,
  /\bgeopuzzle\b/i,
  /\bpuzzle\s+da\s+viaggio\b/i,
  /\bboardgame\s+puzzle\b/i,
  /\bravensburger\s+puzzle\b/i,
  /\bgravitrax\b/i,
  /\btreno\s+(?:della|ad\s+alta|merci)\b/i,
  /\bbinari\b/i,
  /\bpista\s+di\s+biglie\b/i,
  /\baction\s+figure\b/i,
  /\bcreart\b/i,
  /\btiptoi\b/i,
  // TCG Packs / Displays / Starter Decks
  /\bbooster\s+(?:pack|box|display|set)\b/i,
  /\bextra\s+booster\b/i,
  /\bbooster\s*\(\d+\)/i,
  /\bprerelease\s+box\b/i,
  /\bspotlight\s+deck\b/i,
  /\bstarter\s+deck\b/i,
  /\bstructure\s+deck\b/i,
  /\btheme\s+deck\b/i,
  /\bdisplay\s+\d+\s*buste\b/i,
  /\bbundle\s+\d+\s*buste\b/i,
  /\belite\s+trainer\b/i,
  /\btin\s+box\b/i,
  // Miniature da pittura & RPG / GDR
  /\bnolzur['’]?s?\s+marvelous\s+minis\b/i,
  /\bdeep\s+cuts\s+minis\b/i,
  /\bgdr\b/i,
  /\bgioco\s+di\s+ruolo\b/i,
  /\bschermo\s+del\s+(?:master|gm|narratore)\b/i,
  /\bkit\s+del\s+game\s+master\b/i,
  /\bmanuale\s+(?:base|del|gdr|rpg)\b/i,
  /\bmodulo\s+avventura\b/i,
  /\bavventura\s+gdr\b/i,
  /\brpg\b/i,
  // Accessori dadi (non giochi "Dice Game" / "7 Wonders Dice" né promo "The Dice Tower Invention/Promo/Card")
  /\btorre\s+(?:lancia\s+)?dadi\b/i,
  /\bdice\s+tower\b(?!\s+(?:invention|promo|card)\b)/i,
  /\bdice\s+tray\b/i,
  /\bvassoio?\s+(?:lancia|porta)?\s*dadi\b/i,
  /\bdice\s+bag\b/i,
  /\bdice\s+pouch\b/i,
  /\bsacchetto\s+(?:porta)?dadi\b/i,
  /\bdice\s+set\b/i,
  /\bdice\s+block\b/i,
  /\b\d+d\d+\b/i,
  /\bdice\s+(?:case|pack|tube|tin)\b/i,
  /\bwith\s+\d+\s+dice\b/i,
  /\badventure\s+dice\b/i,
  /\bset\s+(?:di\s+)?dadi\b/i,
  /\bset\s+\d+\s+dadi\b/i,
  /\bdadi\s+custom\b/i,
]

export function isNonBoardgameTitle(title: string): boolean {
  if (/\blego\b/i.test(title) && !LEGO_BOARDGAME_ALLOW_RE.test(title)) return true
  return NON_BOARDGAME_PATTERNS.some((re) => re.test(title))
}

function isoOrNull(preferred?: string, text?: string): string | null {
  if (preferred !== undefined && isPlausibleIso(preferred, new Date().getUTCFullYear())) return preferred
  return extractReleaseDate(text)
}

// Fonti che dichiarano la data solo nell'elenco strutturato (per dvgiochi è
// l'ancora di /prossime-uscite/: "27 OTTOBRE 2026" oppure "DA DEFINIRE").
// Lì l'assenza di data è un dato; la descrizione non va mai minata per
// inventarsene una (es. "per 2-4 giocatori" diventava il 2 aprile).
const DATE_FROM_LISTING_ONLY: ReadonlySet<string> = new Set(['dvgiochi'])

export function normalizeListing(sourceId: string, raw: RawProduct): Listing {
  const dateFromListing = DATE_FROM_LISTING_ONLY.has(sourceId)
  const releaseDate = dateFromListing
    ? isoOrNull(raw.releaseDateRaw, undefined)
    : isoOrNull(raw.releaseDateRaw, raw.descriptionRaw)
  const reprintDate = dateFromListing ? null : extractReprintDate(raw.descriptionRaw)
  return {
    sourceId,
    externalId: raw.externalId,
    // Le fonti WordPress-family (woocommerce) e i JSON-LD (html) consegnano titoli
    // con entità HTML (&#8217; &#039; &#8211;): decodificate qui, choke point unico,
    // perché normalized_title e' la chiave di upsert e match.
    title: decodeHtml(raw.title),
    url: raw.url,
    price: toPrice(raw.priceRaw, raw.minorUnitRaw),
    currency: raw.currencyRaw ? raw.currencyRaw.toUpperCase() : null,
    availability: normalizeAvailability(raw.availabilityRaw, releaseDate, undefined, reprintDate),
    releaseDate,
    reprintDate,
    // L'assenza di data da una fonte autorevole è un dato ("DA DEFINIRE"):
    // segna la riga perché lo store azzeri una data scritta in precedenza.
    clearReleaseDate: (dateFromListing && releaseDate === null) || undefined,
    description: htmlToText(raw.descriptionRaw),
    imageUrl: raw.imageUrl ?? null,
    gameId: raw.gameId ?? null,
  }
}