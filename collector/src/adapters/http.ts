export type HttpResponse = { status: number; json(): Promise<unknown>; text(): Promise<string> }
export type HttpClient = (url: string, headers?: Record<string, string>) => Promise<HttpResponse>

export const USER_AGENT = 'MeepleRadar/0.1 (+https://github.com/mephillicus/Meeple-Radar)'

export const defaultFetch: HttpClient = (url, headers) => fetch(url, { headers })

export type TextFetcher = (url: string) => Promise<string | null>

export function textFetcher(http: HttpClient = defaultFetch): TextFetcher {
  return async (url) => {
    const res = await http(url, { 'User-Agent': USER_AGENT })
    return res.status === 200 ? await res.text() : null
  }
}
