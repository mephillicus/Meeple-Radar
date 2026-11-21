const NAMED_ENTITIES: Record<string, string> = {
  nbsp: ' ',
  quot: '"',
  apos: "'",
  lt: '<',
  gt: '>',
  rsquo: '\u2019',
  lsquo: '\u2018',
  ldquo: '\u201C',
  rdquo: '\u201D',
  ndash: '\u2013',
  mdash: '\u2014',
  hellip: '\u2026',
  egrave: 'è',
  eacute: 'é',
  agrave: 'à',
  igrave: 'ì',
  ograve: 'ò',
  ugrave: 'ù',
  Egrave: 'È',
  Agrave: 'À',
  euro: '€',
  trade: '™',
  reg: '®',
  copy: '©',
}

export function decodeHtml(s: string): string {
  return s
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&([a-zA-Z]+);/g, (m, name: string) => NAMED_ENTITIES[name] ?? m)
    .replace(/&amp;/g, '&')
}

// I tag di blocco diventano spazio, così due paragrafi non si saldano in uno
// solo; quelli in linea spariscono senza lasciar buchi. Senza questa
// distinzione `<strong>esplorazione</strong>.` diventerebbe "esplorazione .".
const BLOCK_TAG =
  /<\/?(?:p|div|br|hr|li|ul|ol|h[1-6]|tr|td|th|table|thead|tbody|section|article|header|footer|blockquote|pre)\b[^>]*>/gi

export function htmlToText(html?: string): string | null {
  if (html === undefined) return null
  const text = html
    .replace(/<(script|style|noscript)\b[\s\S]*?<\/\1>/gi, ' ')
    .replace(BLOCK_TAG, ' ')
    .replace(/<[^>]+>/g, '')
  const decoded = decodeHtml(text).replace(/\s+/g, ' ').trim()
  return decoded === '' ? null : decoded
}
