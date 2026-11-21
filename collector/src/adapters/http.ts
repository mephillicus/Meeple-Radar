export type HttpResponse = {
  status: number
  headers?: { get(name: string): string | null }
  json(): Promise<unknown>
  text(): Promise<string>
}
export type HttpClient = (url: string, headers?: Record<string, string>) => Promise<HttpResponse>

export const USER_AGENT = 'MeepleRadar/0.1 (+https://github.com/mephillicus/Meeple-Radar)'

export const FETCH_TIMEOUT_MS = 20_000

const RETRYABLE_STATUSES = new Set([429, 502, 503, 504])
const DEFAULT_MAX_RETRIES = 3
const DEFAULT_BASE_DELAY_MS = 1_000
const MAX_RETRY_AFTER_MS = 60_000

const defaultSleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

export const defaultFetch: HttpClient = (url, headers) =>
  fetch(url, { headers, signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) })

export type TextFetcher = (url: string) => Promise<string | null>

export type RetryOptions = {
  sleep?: (ms: number) => Promise<void>
  maxRetries?: number
  baseDelayMs?: number
  now?: () => number
}

export type TrackedTextFetcherOptions = {
  fetch?: typeof fetch
  sleep?: (ms: number) => Promise<void>
  maxRetries?: number
  baseDelayMs?: number
  timeoutMs?: number
  now?: () => number
}

export type TrackedTextFetcher = {
  fetcher: TextFetcher
  getErrorsByStatus: () => Record<string, number>
}

export function parseRetryAfterMs(headerValue: string | null | undefined, nowMs = Date.now()): number | null {
  if (!headerValue) return null
  const trimmed = headerValue.trim()
  if (!trimmed) return null
  if (/^\d+$/.test(trimmed)) {
    return Math.min(Number(trimmed) * 1_000, MAX_RETRY_AFTER_MS)
  }
  const dateMs = Date.parse(trimmed)
  if (!Number.isNaN(dateMs)) {
    return Math.min(Math.max(0, dateMs - nowMs), MAX_RETRY_AFTER_MS)
  }
  return null
}

function isTimeoutError(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false
  const name = (err as { name?: unknown }).name
  return name === 'TimeoutError' || name === 'AbortError'
}

function createRetryingTextFetcher(
  http: HttpClient,
  opts?: RetryOptions,
  onError?: (statusKey: string) => void,
): TextFetcher {
  const sleep = opts?.sleep ?? defaultSleep
  const maxRetries = opts?.maxRetries ?? DEFAULT_MAX_RETRIES
  const baseDelayMs = opts?.baseDelayMs ?? DEFAULT_BASE_DELAY_MS
  const now = opts?.now ?? Date.now

  return async (url) => {
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        const res = await http(url, { 'User-Agent': USER_AGENT })
        if (res.status === 200) return await res.text()
        onError?.(String(res.status))
        if (!RETRYABLE_STATUSES.has(res.status) || attempt >= maxRetries) return null
        const retryAfterMs = parseRetryAfterMs(res.headers?.get('retry-after'), now())
        const delayMs = retryAfterMs ?? baseDelayMs * 2 ** attempt
        await sleep(delayMs)
      } catch (err) {
        onError?.(isTimeoutError(err) ? 'timeout' : 'network')
        if (attempt >= maxRetries) return null
        await sleep(baseDelayMs * 2 ** attempt)
      }
    }
    return null
  }
}

export function textFetcher(http: HttpClient = defaultFetch, opts?: RetryOptions): TextFetcher {
  return createRetryingTextFetcher(http, opts)
}

export function createTrackedTextFetcher(opts?: TrackedTextFetcherOptions): TrackedTextFetcher {
  const fetchImpl = opts?.fetch ?? fetch
  const timeoutMs = opts?.timeoutMs ?? FETCH_TIMEOUT_MS
  const http: HttpClient = (url, headers) =>
    fetchImpl(url, { headers, signal: AbortSignal.timeout(timeoutMs) })
  const errorsByStatus: Record<string, number> = {}
  const recordError = (key: string) => {
    errorsByStatus[key] = (errorsByStatus[key] ?? 0) + 1
  }
  const fetcher = createRetryingTextFetcher(http, opts, recordError)
  return {
    fetcher,
    getErrorsByStatus: () => ({ ...errorsByStatus }),
  }
}
