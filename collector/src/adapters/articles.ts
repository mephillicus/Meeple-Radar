import { extractJson } from './gemini.js'

// Classifier dei titoli: le negative (recensioni) vincono sempre; senza corpo (solo titolo da
// Google News) serve una parola positiva; con corpo (feed dei siti) basta l'assenza di negative.
// Le regex sono senza diacritici perché il confronto avviene su stringa lower senza accenti.
const NEGATIVE = /recensione|review|recensito|provato|verdetto|voto|opinion/
const POSITIVE = /uscita|uscira|in uscita|novita|annuncio|annunciano|arriva|arrivo|arrivera|anteprima|programmato|edizione italiana|kickstarter|disponibile|appena uscito|listino/

function stripDiacritics(value: string): string {
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
}

export function isCandidateHeadline(title: string, hasBody: boolean): boolean {
  const normalized = stripDiacritics(title)
  if (NEGATIVE.test(normalized)) return false
  if (hasBody) return true
  return POSITIVE.test(normalized)
}

// La citazione entra in descrizione solo se è testo dell'articolo: si normalizzano gli spazi su
// entrambi i lati e si verifica la substring. Altrimenti si ritorna null (parafrasi scartata).
export function verifyQuote(quote: string, articleText: string): string | null {
  const normalized = quote.replace(/\s+/g, ' ').trim()
  if (normalized === '') return null
  const body = articleText.replace(/\s+/g, ' ').trim()
  return body.includes(normalized) ? normalized : null
}

export function buildArticlePrompt(articleText: string, now: Date): string {
  const oggi = now.toLocaleDateString('it-IT', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'Europe/Rome',
  })

  return `Oggi è ${oggi}. Qui sotto trovi il testo completo di un articolo recente sui giochi da tavolo in Italia.

Estrai i giochi da tavolo (da tavolo e di carte; NON videogiochi, film, serie TV, fiere o eventi) di cui l'articolo annuncia un'uscita, una ristampa, un'espansione, un'edizione italiana o un'anteprima.

Regole:
- Usa SOLO giochi il cui nome compare esplicitamente nell'articolo. Non aggiungere giochi che conosci da altre fonti.
- descriptionQuote: copia VERBATIM una frase dell'articolo che descrive il gioco. Niente parafrasi e niente traduzioni: solo testo presente nell'articolo.
- releaseDate: solo se scritta nell'articolo, altrimenti ometti il campo.
- publisher: solo se citato nell'articolo, altrimenti ometti il campo.
- Se l'articolo nomina più giochi, crea un elemento per ciascuno. Se non nomina nessun gioco specifico, restituisci {"games": []}.

Formato della risposta: SOLO un oggetto JSON valido, senza testo prima o dopo e senza markdown. Una sola chiave, "games", con un array di elementi con questi campi:
- title (stringa, obbligatorio): nome del gioco come scritto nell'articolo
- publisher (stringa, opzionale): editore, solo se citato
- releaseDate (stringa, opzionale): data di uscita, solo se scritta nell'articolo
- descriptionQuote (stringa, opzionale): frase copiata VERBATIM dall'articolo

Testo dell'articolo:
${articleText}
`
}

export type ArticleGame = {
  title: string
  publisher?: string
  releaseDate?: string
  descriptionQuote?: string
}

// Stessa semantica di parseUpcomingJson: `ok` distingue "risposta non interpretabile" da
// "elenco valido (anche vuoto)". Le entry senza title non vuoto e i campi opzionali vuoti
// vengono scartati.
export function parseArticleGames(text: string): { ok: boolean; games: ArticleGame[] } {
  let parsed: unknown
  try {
    parsed = extractJson(text)
  } catch {
    return { ok: false, games: [] }
  }

  let entries: unknown[]
  if (Array.isArray(parsed)) {
    entries = parsed
  } else if (parsed !== null && typeof parsed === 'object') {
    // Alcuni modelli avvolgono l'array: {"releases": [...]} → lo scartolo.
    const arrays = Object.values(parsed as Record<string, unknown>).filter(Array.isArray)
    if (arrays.length !== 1) return { ok: false, games: [] }
    entries = arrays[0] as unknown[]
  } else {
    return { ok: false, games: [] }
  }

  const games: ArticleGame[] = []
  for (const entry of entries) {
    if (!entry || typeof entry !== 'object') continue
    const e = entry as {
      title?: unknown; publisher?: unknown; releaseDate?: unknown; descriptionQuote?: unknown
    }
    if (typeof e.title !== 'string' || e.title.trim() === '') continue
    const game: ArticleGame = { title: e.title.trim() }
    if (typeof e.publisher === 'string' && e.publisher.trim() !== '') {
      game.publisher = e.publisher.trim()
    }
    if (typeof e.releaseDate === 'string' && e.releaseDate.trim() !== '') {
      game.releaseDate = e.releaseDate.trim()
    }
    if (typeof e.descriptionQuote === 'string' && e.descriptionQuote.trim() !== '') {
      game.descriptionQuote = e.descriptionQuote.trim()
    }
    games.push(game)
  }
  return { ok: true, games }
}
