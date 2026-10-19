// Provider LLM a cascata: Gemini (primario) → Gemini di ripiego.
// Ogni provider viene usato solo se GEMINI_API_KEY è presente; il prompt è identico per tutti.
// Gemini NON cerca sul web: i dati gli arrivano già dal chiamante (per esempio dai titoli di
// Google News). Così la fonte resta dentro il tier gratuito, che non ha quota per la ricerca.

export type AiResult =
  | { ok: true; text: string }
  | { ok: false; reason: string }

export type AiProvider = {
  id: string
  model: string
  // Tentativi prima di cedere il turno: il modello primario ne ha uno in più.
  attempts: number
  // Lancia solo per errori di rete/HTTP/timeout non ripristinabili: un contenuto mancante o
  // malformato è `ok: false`, così chi gestisce la cascata distingue "non ha risposto" da
  // "ha risposto ma è inutile".
  call: (prompt: string) => Promise<AiResult>
}

export type Sleep = (ms: number) => Promise<void>

export type AiOptions = {
  sleep?: Sleep
  timeoutMs?: number
  // Solo per i test: quanto insiste il provider primario prima di cedere il turno.
  geminiAttempts?: number
}

type ResolvedOptions = Required<AiOptions>

const defaultSleep: Sleep = (ms) => new Promise<void>((resolve) => setTimeout(resolve, ms))

// Con la ricerca web una risposta richiede da qualche secondo a un minuto. Una richiesta appesa
// viene annullata dopo 3 minuti e conta come tentativo fallito (si riprova).
export const RESPONSE_TIMEOUT_MS = 3 * 60 * 1000

// Attesa tra i tentativi dello stesso provider: 5s, 10s, 20s, 40s, poi al massimo 60s.
const BASE_DELAY_MS = 5000
const MAX_DELAY_MS = 60_000

// Tentativi: il modello primario insiste di più, quello di ripiego un po' meno.
const GEMINI_ATTEMPTS = 5
const FALLBACK_ATTEMPTS = 4

// Budget di output per Gemini: con ricerca e ragionamento il default può essere stretto.
const GEMINI_MAX_OUTPUT_TOKENS = 8192

// Errori Gemini per cui riprovare conviene:
// - 500/502/503/504: problemi o alta domanda lato server;
// - 429: limite al minuto, che si sblocca in pochi secondi. Se però il messaggio parla di un
//   limite giornaliero, riprovare non serve e si cede subito al modello successivo.
//   Gemini scrive quel limite in modi diversi a seconda della quota ("free-model-requests-per-day",
//   "'... requests per day'", "Daily quota"), quindi si cerca il concetto e non una stringa fissa.
function isGeminiRetryable(status: number, body: string): boolean {
  if ([500, 502, 503, 504].includes(status)) return true
  if (status !== 429) return false
  return !/(per[\s_/.-]?day|daily|giornalier)/i.test(body)
}

// Esito di un singolo tentativo, prima che diventi un AiResult.
type Attempt =
  | { status: 'ok'; text: string }
  | { status: 'soft'; reason: string }
  | { status: 'http'; message: string; retryable: boolean }

async function readBody(res: Response): Promise<string> {
  try {
    return (await res.text()).slice(0, 600)
  } catch {
    return ''
  }
}

// Ripete lo stesso provider con attesa crescente finché l'errore è ripristinabile. Un errore
// HTTP non ripristinabile viene subito rilanciato: non ha senso insistere su un 404 o su un
// limite giornaliero.
async function runWithRetries(opts: {
  id: string
  attempts: number
  sleep: Sleep
  attemptOnce: () => Promise<Attempt>
}): Promise<AiResult> {
  let last: Attempt = { status: 'soft', reason: 'nessun tentativo' }

  for (let attempt = 0; attempt < opts.attempts; attempt++) {
    const outcome = await opts.attemptOnce()
    last = outcome

    if (outcome.status === 'ok') return { ok: true, text: outcome.text }
    if (outcome.status === 'http' && !outcome.retryable) throw new Error(outcome.message)
    if (attempt >= opts.attempts - 1) break

    const motivo = outcome.status === 'http' ? outcome.message : outcome.reason
    const delay = Math.min(BASE_DELAY_MS * 2 ** attempt, MAX_DELAY_MS)
    console.error(`[llm] ${opts.id}: ${motivo} (tentativo ${attempt + 1}/${opts.attempts}), riprovo tra ${delay}ms`)
    await opts.sleep(delay)
  }

  if (last.status === 'http') throw new Error(last.message)
  return { ok: false, reason: last.status === 'soft' ? last.reason : 'risposta vuota' }
}

type GeminiResponse = {
  candidates?: {
    content?: { parts?: { text?: unknown; thought?: unknown }[] }
    finishReason?: unknown
  }[] | null
  promptFeedback?: { blockReason?: unknown }
} | null

async function callGemini(
  model: string,
  apiKey: string,
  prompt: string,
  opts: ResolvedOptions,
  attempts: number,
  id = 'gemini',
): Promise<AiResult> {
  return runWithRetries({
    id,
    attempts,
    sleep: opts.sleep,
    attemptOnce: async () => {
      let res: Response
      try {
        res = await fetch(
          `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
          {
            method: 'POST',
            // La chiave va nell'header: nell'URL finirebbe nei log in caso di errore.
            headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
            body: JSON.stringify({
              contents: [{ parts: [{ text: prompt }] }],
              // Nessun tool: il JSON mode è disponibile solo senza grounding, e il prompt
              // arriva già con i dati dentro.
              generationConfig: {
                maxOutputTokens: GEMINI_MAX_OUTPUT_TOKENS,
                responseMimeType: 'application/json',
              },
            }),
            signal: AbortSignal.timeout(opts.timeoutMs),
          },
        )
      } catch (err) {
        // Timeout o errore di rete: sono transitori, si riprova.
        const msg = err instanceof Error ? err.message : String(err)
        return { status: 'http', message: `Gemini rete/timeout: ${msg}`, retryable: true }
      }

      if (!res.ok) {
        const detail = await readBody(res)
        return {
          status: 'http',
          message: `Gemini API: ${res.status}${detail ? ` ${detail}` : ''}`,
          retryable: isGeminiRetryable(res.status, detail),
        }
      }

      let data: GeminiResponse = null
      try {
        data = (await res.json()) as GeminiResponse
      } catch {
        return { status: 'soft', reason: 'risposta non JSON' }
      }

      const candidate = data?.candidates?.[0]
      // I modelli 3.x "pensano" per default: scarta le parti di ragionamento e tieni il testo.
      const parts = candidate?.content?.parts ?? []
      const text = parts
        .filter((p) => p && p.thought !== true && typeof p.text === 'string')
        .map((p) => p.text as string)
        .join('')

      if (text === '') {
        const blocco = data?.promptFeedback?.blockReason
        const fine = candidate?.finishReason
        const motivo = blocco ? `bloccato (${String(blocco)})` : fine ? `risposta vuota (${String(fine)})` : 'risposta vuota'
        return { status: 'soft', reason: motivo }
      }
      return { status: 'ok', text }
    },
  })
}

export function buildProviders(
  env: NodeJS.ProcessEnv = process.env,
  options: AiOptions = {},
): AiProvider[] {
  const opts: ResolvedOptions = {
    sleep: options.sleep ?? defaultSleep,
    timeoutMs: options.timeoutMs ?? RESPONSE_TIMEOUT_MS,
    geminiAttempts: options.geminiAttempts ?? GEMINI_ATTEMPTS,
  }
  const providers: AiProvider[] = []

  const geminiKey = env.GEMINI_API_KEY
  if (geminiKey) {
    const model = env.GEMINI_MODEL ?? 'gemini-3.5-flash-lite'
    providers.push({
      id: 'gemini',
      model,
      attempts: opts.geminiAttempts,
      call: (prompt) => callGemini(model, geminiKey, prompt, opts, opts.geminiAttempts),
    })

    // Secondo modello Gemini (stessa chiave): se il primo fallisce o non trova nulla si prova
    // questo prima di arrendersi.
    const fallbackModel = env.GEMINI_FALLBACK_MODEL ?? 'gemini-3.6-flash'
    if (fallbackModel !== model) {
      providers.push({
        id: 'gemini-fallback',
        model: fallbackModel,
        attempts: FALLBACK_ATTEMPTS,
        call: (prompt) =>
          callGemini(fallbackModel, geminiKey, prompt, opts, FALLBACK_ATTEMPTS, 'gemini-fallback'),
      })
    }
  }

  return providers
}
