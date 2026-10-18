import type { TextFetcher } from './http.js'

// Le fonti dietro Cloudflare sono raggiungibili da IP residenziale (curl) ma sfidate
// dagli IP datacenter dei runner di GitHub Actions. Per l'esecuzione in CI usiamo un
// "reader" pubblico (Jina AI Reader) che scarica la pagina dai propri proxy e la
// restituisce come HTML (`X-Return-Format: html`), così gli estrattori cheerio esistenti
// funzionano senza modifiche. Con `JINA_API_KEY` si alzano i limiti di velocità.

export type FetchLike = (
  url: string,
  init?: { headers?: Record<string, string> },
) => Promise<{ ok: boolean; status: number; text(): Promise<string> }>

export const RELAY_ENDPOINT = 'https://r.jina.ai/'

export function createRelayFetcher(
  fetchImpl: FetchLike = fetch as unknown as FetchLike,
  apiKey = process.env.JINA_API_KEY,
): TextFetcher {
  return async (url) => {
    try {
      const headers: Record<string, string> = { 'X-Return-Format': 'html' }
      if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`
      const res = await fetchImpl(`${RELAY_ENDPOINT}${url}`, { headers })
      if (!res.ok) return null
      const text = await res.text()
      return text || null
    } catch {
      return null
    }
  }
}
