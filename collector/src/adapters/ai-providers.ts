// Provider LLM gratuiti a cascata: Gemini (primario) → Groq → OpenRouter.
// Ogni provider viene usato solo se la sua chiave è presente; il prompt è identico per tutti.

export type AiResult =
  | { ok: true; text: string }
  | { ok: false; reason: string }

export type AiProvider = {
  id: string
  model: string
  // Tentativi prima di cedere il turno: Gemini ha la priorità e ne ha uno in più.
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

// Una richiesta appesa non deve bloccare il collector per sempre: dopo 5 minuti si annulla e si
// passa al provider successivo.
export const RESPONSE_TIMEOUT_MS = 5 * 60 * 1000

// Attesa tra i tentativi dello stesso provider: 2s, 4s, ...
const BASE_DELAY_MS = 2000

// Tentativi: il provider primario insiste di più, i fallback devono solo fare da scorta.
const GEMINI_ATTEMPTS = 3
const FALLBACK_ATTEMPTS = 2

// Modelli gratuiti come gpt-oss ragionano prima di rispondere: se il budget è stretto il
// ragionamento lo consuma tutto e il contenuto visibile arriva vuoto. Anche il TPM di Groq
// (8k/min) va rispettato, quindi un compromesso generoso ma non illimitato.
const MAX_TOKENS = 6000

// 503 è "alta domanda": riprovare conviene. Un 429 con `retry-after` è un limite al minuto e si
// sblocca in secondi; senza `retry-after` è quasi sempre un limite giornaliero, dove riprovare
// non solo non aiuta ma brucia quota (su OpenRouter le richieste fallite contano comunque).
function isRetryable(res: Response): boolean {
  if (res.status === 503) return true
  if (res.status !== 429) return false
  const headers = res.headers as Headers | undefined
  return typeof headers?.get === 'function' && headers.get('retry-after') !== null
}

// Esito di un singolo tentativo, prima che diventi un AiResult.
type Attempt =
  | { status: 'ok'; text: string }
  | { status: 'soft'; reason: string }
  | { status: 'http'; message: string; retryable: boolean }

async function errorDetail(res: Response): Promise<string> {
  try {
    const body = (await res.text()).slice(0, 300)
    return body ? ` ${body}` : ''
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
    const delay = BASE_DELAY_MS * 2 ** attempt
    console.error(`[llm] ${opts.id}: ${motivo} (tentativo ${attempt + 1}/${opts.attempts}), riprovo tra ${delay}ms`)
    await opts.sleep(delay)
  }

  if (last.status === 'http') throw new Error(last.message)
  return { ok: false, reason: last.status === 'soft' ? last.reason : 'risposta vuota' }
}

async function callGemini(
  model: string,
  apiKey: string,
  prompt: string,
  opts: ResolvedOptions,
  attempts: number,
): Promise<AiResult> {
  return runWithRetries({
    id: 'gemini',
    attempts,
    sleep: opts.sleep,
    attemptOnce: async () => {
      const res = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            contents: [{ parts: [{ text: prompt }] }],
            generationConfig: { responseMimeType: 'application/json' },
          }),
          signal: AbortSignal.timeout(opts.timeoutMs),
        },
      )
      if (!res.ok) {
        return { status: 'http', message: `Gemini API: ${res.status}${await errorDetail(res)}`, retryable: isRetryable(res) }
      }

      let data: unknown = null
      try {
        data = await res.json()
      } catch {
        return { status: 'soft', reason: 'risposta non JSON' }
      }
      // I modelli 3.x "pensano" per default: scarta le parti di ragionamento e tieni il testo.
      const parts =
        (
          data as {
            candidates?: { content?: { parts?: { text?: unknown; thought?: unknown }[] } }[] | null
          } | null
        )?.candidates?.[0]?.content?.parts ?? []
      const text = parts
        .filter((p) => p && p.thought !== true && typeof p.text === 'string')
        .map((p) => p.text as string)
        .join('')
      if (text === '') return { status: 'soft', reason: 'risposta vuota' }
      return { status: 'ok', text }
    },
  })
}

async function callOpenAiCompat(opts: {
  label: string
  baseUrl: string
  model: string
  apiKey: string
  prompt: string
  attempts: number
  sleep: Sleep
  timeoutMs: number
  headers?: Record<string, string>
}): Promise<AiResult> {
  const { label, baseUrl, model, apiKey, prompt, attempts, sleep, timeoutMs, headers } = opts
  return runWithRetries({
    id: label.toLowerCase(),
    attempts,
    sleep,
    attemptOnce: async () => {
      const res = await fetch(`${baseUrl}/chat/completions`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${apiKey}`,
          ...headers,
        },
        body: JSON.stringify({
          model,
          messages: [{ role: 'user', content: prompt }],
          response_format: { type: 'json_object' },
          max_tokens: MAX_TOKENS,
        }),
        signal: AbortSignal.timeout(timeoutMs),
      })
      if (!res.ok) {
        return { status: 'http', message: `${label} API: ${res.status}${await errorDetail(res)}`, retryable: isRetryable(res) }
      }

      let data: {
        choices?: { message?: { content?: unknown }; finish_reason?: unknown }[]
      } | null = null
      try {
        data = await res.json()
      } catch {
        return { status: 'soft', reason: 'risposta non JSON' }
      }
      const choice = data?.choices?.[0]
      const text = choice?.message?.content
      if (typeof text !== 'string' || text === '') {
        // `length` significa che il modello ha finito il budget mentre "pensava": utile saperlo.
        const motivo = choice?.finish_reason === 'length' ? 'budget token esaurito' : 'risposta vuota'
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
  const opts = {
    sleep: options.sleep ?? defaultSleep,
    timeoutMs: options.timeoutMs ?? RESPONSE_TIMEOUT_MS,
    geminiAttempts: options.geminiAttempts ?? GEMINI_ATTEMPTS,
  }
  const providers: AiProvider[] = []

  const geminiKey = env.GEMINI_API_KEY
  if (geminiKey) {
    const model = env.GEMINI_MODEL ?? 'gemini-3.6-flash'
    providers.push({
      id: 'gemini',
      model,
      attempts: opts.geminiAttempts,
      call: (prompt) => callGemini(model, geminiKey, prompt, opts, opts.geminiAttempts),
    })
  }

  const groqKey = env.GROQ_API_KEY
  if (groqKey) {
    const model = env.GROQ_MODEL ?? 'openai/gpt-oss-120b'
    providers.push({
      id: 'groq',
      model,
      attempts: FALLBACK_ATTEMPTS,
      call: (prompt) =>
        callOpenAiCompat({
          label: 'Groq',
          baseUrl: 'https://api.groq.com/openai/v1',
          model,
          apiKey: groqKey,
          prompt,
          attempts: FALLBACK_ATTEMPTS,
          sleep: opts.sleep,
          timeoutMs: opts.timeoutMs,
        }),
    })
  }

  const openrouterKey = env.OPENROUTER_API_KEY
  if (openrouterKey) {
    const model = env.OPENROUTER_MODEL ?? 'openrouter/free'
    // Su OpenRouter solo i modelli `:free` (o il router openrouter/free) sono gratuiti: un modello
    // a pagamento a cascata costerebbe soldi senza che nessuno lo abbia chiesto.
    if (model !== 'openrouter/free' && !model.endsWith(':free')) {
      console.error(`[llm] openrouter: modello "${model}" non free, provider saltato`)
    } else {
      providers.push({
        id: 'openrouter',
        model,
        attempts: FALLBACK_ATTEMPTS,
        call: (prompt) =>
          callOpenAiCompat({
            label: 'OpenRouter',
            baseUrl: 'https://openrouter.ai/api/v1',
            model,
            apiKey: openrouterKey,
            prompt,
            attempts: FALLBACK_ATTEMPTS,
            sleep: opts.sleep,
            timeoutMs: opts.timeoutMs,
            // Identificativi richiesti (e cortesi) da OpenRouter.
            headers: {
              'HTTP-Referer': 'https://github.com/mephillicus/Meeple-Radar',
              'X-Title': 'Meeple Radar',
            },
          }),
      })
    }
  }

  return providers
}
