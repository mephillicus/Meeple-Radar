export function decodeHtml(s: string): string {
  return s
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n)))
    .replace(/&nbsp;/g, ' ')
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
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
    .replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ')
    .replace(BLOCK_TAG, ' ')
    .replace(/<[^>]+>/g, '')
  const decoded = decodeHtml(text).replace(/\s+/g, ' ').trim()
  return decoded === '' ? null : decoded
}
