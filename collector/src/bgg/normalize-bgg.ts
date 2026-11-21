// Normalizzazione dedicata al matching verso BGG (CSV e ricerca online).
// Separata da normalizeTitle() in src/normalize.ts, che è vincolata alla
// migrazione SQL 0011 per l'unicità di product.normalized_title.

const TRAILING_SUFFIX_RE =
  /\s+(?:edizione italiana|ed italiana|versione italiana|lingua italiana|italiano|italiana|ita|(?:\d+[ao]?\s+|prima\s+|seconda\s+|terza\s+|nuova\s+)edizione|ed\s+it|promo|esclusiva)$/

export function normalizeForBggMatch(title: string): string {
  let s = title
    // Normalizza ligature comuni prima della decomposizione NFKD
    .replace(/[æÆ]/g, 'ae')
    .replace(/[œŒ]/g, 'oe')
    .replace(/ß/g, 'ss')
    // NFKD decompone accenti/diacritici e ordinali come 2ª -> 2a
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    // Apostrofi e virgolette tipografiche -> rimossi (come normalizeTitle: "Miller's" -> "millers")
    .replace(/['\u2018\u2019\u201A\u201B\u2032\u0060\u00B4"\u201C\u201D\u201E\u201F]/g, '')
    // Trattini, slash e parentesi -> spazio, così "Dune–Imperium" o "Catan(ITA)" restano separati
    .replace(/[-/\u2010\u2011\u2012\u2013\u2014\u2015\u2212()[\]]/g, ' ')
    // Resto della punteggiatura -> rimosso
    .replace(/[^\w\s]/gu, '')
    .replace(/\s+/g, ' ')
    .trim()

  // Rimuove in cascata i suffissi editoriali/negozio italiani in coda,
  // senza mai svuotare completamente il titolo.
  while (true) {
    const next = s.replace(TRAILING_SUFFIX_RE, '').trim()
    if (next === s || next.length === 0) break
    s = next
  }

  // Rimuove il prefisso brand ridondante "Ravensburger " in testa,
  // senza mai svuotare il titolo se è solo "Ravensburger".
  const withoutBrand = s.replace(/^ravensburger\s+/i, '').trim()
  if (withoutBrand.length > 0) {
    s = withoutBrand
  }

  return s
}
