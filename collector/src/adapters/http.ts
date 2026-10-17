export type HttpResponse = { status: number; json(): Promise<unknown> }
export type HttpClient = (url: string, headers?: Record<string, string>) => Promise<HttpResponse>

export const USER_AGENT = 'MeepleRadar/0.1 (+https://github.com/mephillicus/Meeple-Radar)'

export const defaultFetch: HttpClient = (url, headers) => fetch(url, { headers })
