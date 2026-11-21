import type { TextFetcher } from './http.js'

// Le fonti dietro Cloudflare sono raggiungibili da IP residenziale (curl) ma sfidate
// dagli IP datacenter dei runner di GitHub Actions. Per l'esecuzione in CI usiamo un
// "reader" pubblico (Jina AI Reader) che scarica la pagina dai propri proxy e la
// restituisce come HTML (`X-Return-Format: html`), così gli estrattori cheerio esistenti
// funzionano senza modifiche. Con `JINA_API_KEY` si alzano i limiti di velocità (200
// richieste/min contro ~20 senza chiave: senza chiave le fonti grandi vengono limitate).

export type FetchLike = (
  url: string,
  init?: { headers?: Record<string, string> },
) => Promise<{ ok: boolean; status: number; text(): Promise<string> }>

export type RelayRateLimiterOptions = {
  minIntervalMs?: number
  sleep?: (ms: number) => Promise<void>
  now?: () => number
}

export type RelayOptions = {
  minIntervalMs?: number
  now?: () => number
}

export const RELAY_ENDPOINT = 'https://r.jina.ai/'

// Limiti di sicurezza rispetto alle quote Jina AI Reader:
// - con JINA_API_KEY: 200 req/min -> 350 ms (~171 req/min)
// - senza chiave: ~20 req/min -> 3100 ms (~19 req/min)
export const RELAY_MIN_INTERVAL_WITH_KEY_MS = 350
export const RELAY_MIN_INTERVAL_ANON_MS = 3_100

const RETRYABLE_STATUSES = new Set([429, 502, 503, 504])
const MAX_ATTEMPTS = 3

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

// Serializza gli slot di richiesta così che chiamate parallele da più fonti
// rispettino sempre `minIntervalMs` senza superare la quota per minuto.
export function createMinIntervalLimiter(opts: RelayRateLimiterOptions = {}): () => Promise<void> {
  const minIntervalMs = opts.minIntervalMs ?? RELAY_MIN_INTERVAL_WITH_KEY_MS
  const sleep = opts.sleep ?? defaultSleep
  const now = opts.now ?? Date.now
  let nextSlotAt = 0
  let queue: Promise<void> = Promise.resolve()

  return () => {
    if (minIntervalMs <= 0) return Promise.resolve()
    const run = queue.then(async () => {
      const current = now()
      const waitMs = nextSlotAt - current
      if (waitMs > 0) {
        await sleep(waitMs)
      }
      const afterWait = now()
      nextSlotAt = Math.max(afterWait, nextSlotAt) + minIntervalMs
    })
    queue = run.catch(() => {})
    return run
  }
}

export function createRelayFetcher(
  fetchImpl: FetchLike = fetch as unknown as FetchLike,
  apiKey = process.env.JINA_API_KEY,
  sleep: (ms: number) => Promise<void> = defaultSleep,
  options?: RelayOptions,
): TextFetcher {
  const minIntervalMs =
    options?.minIntervalMs ?? (apiKey ? RELAY_MIN_INTERVAL_WITH_KEY_MS : RELAY_MIN_INTERVAL_ANON_MS)
  const acquireSlot = createMinIntervalLimiter({
    minIntervalMs,
    sleep,
    now: options?.now,
  })

  return async (url) => {
    const headers: Record<string, string> = { 'X-Return-Format': 'html' }
    if (apiKey) headers['Authorization'] = `Bearer ${apiKey}`
    for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      if (attempt > 0) await sleep(1000 * 2 ** (attempt - 1))
      await acquireSlot()
      try {
        const res = await fetchImpl(`${RELAY_ENDPOINT}${url}`, { headers })
        if (res.ok) {
          const text = await res.text()
          return text || null
        }
        if (!RETRYABLE_STATUSES.has(res.status)) return null
      } catch {
        // errore di rete: ritenta
      }
    }
    return null
  }
}
