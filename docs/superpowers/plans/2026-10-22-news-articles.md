# News Articles Enrichment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Analizzare gli articoli completi delle notizie su uscite/anteprime e usarli solo per riempire i buchi dei prodotti a catalogo (citazione verbatim per description, data write-once per release_date).

**Architecture:** L'adapter news tiene il corpo dell'articolo dal feed (`content:encoded`) o lo scarica via relay Jina; per ogni articolo candidato fa una chiamata LLM dedicata che restituisce giochi, editore, data e citazione verbatim; una verifica per substring garantisce che la citazione sia testo dell'articolo. In `run.ts` le citazioni e le date news finiscono su `product` solo attraverso fill write-once (`fillProductDescription`, nuovo `fillProductReleaseDate`); l'upsert del prodotto in una run news non porta mai prezzo né data.

**Tech Stack:** TypeScript (Node 20+), vitest, Zod, Supabase REST, fetch nativo, Jina AI Reader relay (`relay.ts`).

**Spec:** `docs/superpowers/specs/2026-10-22-news-articles-design.md`

**File structure:**
- Create: `collector/src/adapters/articles.ts` (funzioni pure: classifier, verifyQuote, prompt, parse)
- Create: `collector/test/adapters/articles.test.ts`
- Modify: `collector/src/adapters/google-news.ts` (NewsItem.body, parseRss, dedup, percorso articoli)
- Modify: `collector/src/adapters/gemini.ts` (export di `extractJson`)
- Modify: `collector/src/types.ts` (`RawProductSchema` += `articleDescription`)
- Modify: `collector/src/db.ts` (`ListingStore` += `fillProductReleaseDate`)
- Modify: `collector/test/adapters/google-news.test.ts`, `collector/test/db.test.ts`, `collector/test/run.test.ts`

## Global Constraints

- Tutti i comandi da `collector/`: tsc = `node node_modules\typescript\bin\tsc --noEmit`; test = `node node_modules\vitest\vitest.mjs run` (npx bloccato da ExecutionPolicy). Shell PowerShell 5.1: niente `&&`.
- Commit: messaggi conventional in italiano, **senza accenti**.
- Convenzione test con date relative: pinare con `vi.setSystemTime`; anni relativi nel futuro usano `new Date().getUTCFullYear()`.
- Prezzo **mai** dagli articoli: il prompt di articolo non lo chiede; in una run news la riga corrente non porta `price`/`currency` nell'upsert del prodotto.
- Dati del negozio **mai sovrascritti**: `product.description` e `product.release_date` scritti solo dai fill write-once.
- Cap articoli: `MAX_ARTICLE_BODIES = 12` corpi analizzati con successo per run.
- Commenti in codice in italiano solo dove i file coinvolti li hanno già (tutti i file da toccare li hanno).

## Review Focus

1. Link Google News = stub/interstitial: il corpo inutilizzabile non deve bruciare un posto nel cap e l'articolo deve restare sul percorso solo-titoli → test Task 3 `un articolo non fetchabile resta sul percorso titoli` + `il cap conta i corpi analizzati con successo`.
2. LLM che parafrasa invece di copiare: la citazione non deve entrare → test Task 2 `verifyQuote` (accetta solo substring con spazi normalizzati) + Task 3 `scarta la citazione parafrasata`.
3. Run news che sovrascrive prezzo/data del prodotto in upsert → test Task 5 `una fonte news non porta prezzo e data nell upsert del prodotto`.
4. Set di titoli tutto recensioni: il percorso solo-titoli deve funzionare come prima → test Task 3 `una recensione non viene analizzata ma resta nei titoli`.
5. Filtro cutoff che sfalsa gli indici del pairing raw/riga e fa perdere le citazioni → test Task 5 `la citazione sopravvive al filtro di cutoff`.

---

### Task 1: Corpo dell'articolo nel feed RSS

**Files:**
- Modify: `collector/src/adapters/google-news.ts:11-16` (NewsItem), `:85-103` (parseRss), `:118-169` (fetchNewsHeadlines)
- Test: `collector/test/adapters/google-news.test.ts`

**Interfaces:**
- Produces: `NewsItem` = `{ title: string; source: string; link: string; publishedAt: Date; body?: string }`; `parseRss(xml: string): NewsItem[]` ora riporta in `body` il contenuto del tag `<content:encoded>` (CDATA compreso, tramite la `tag` esistente); `fetchNewsHeadlines` deduplica per normalized title e **sostituisce** la copia già vista quando la nuova ha `body` e quella vecchia no.

- [ ] **Step 1: Write the failing tests**

In `collector/test/adapters/google-news.test.ts`:

```ts
test('parseRss tiene il corpo dell’articolo da content:encoded', () => {
  const xml = rss(`<item>
    <title><![CDATA[Cinque uscite di novembre - HoG]]></title>
    <link>https://www.houseofgames.it/uscite</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <content:encoded><![CDATA[<p>Il primo gioco arriva il 3 novembre.</p>]]></content:encoded>
  </item>`)
  const items = parseRss(xml)
  expect(items[0]?.body).toBe('<p>Il primo gioco arriva il 3 novembre.</p>')
})

test('alla deduplica vince la copia con il corpo', async () => {
  const conCorpo = `<item>
    <title><![CDATA[Stesso titolo]]></title>
    <link>https://www.houseofgames.it/a</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <content:encoded><![CDATA[<p>Corpo</p>]]></content:encoded>
  </item>`
  const senzaCorpo = `<item>
    <title><![CDATA[Stesso titolo - Testata]]></title>
    <link>https://news.google.com/rss/articles/xyz</link>
    <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
    <source url="https://testata.it">Testata</source>
  </item>`
  fetchMock.mockResolvedValueOnce(rssOk(rss(senzaCorpo))).mockResolvedValueOnce(rssOk(rss(conCorpo)))
  const items = await fetchNewsHeadlines({
    sources: [{ label: 'q', url: 'https://news.google.com/rss/search?q=x' }, { label: 'hog', url: 'https://www.houseofgames.it/feed/' }],
    sleep: noSleep,
  })
  expect(items).toHaveLength(1)
  expect(items[0]?.body).toBe('<p>Corpo</p>')
  expect(items[0]?.link).toBe('https://www.houseofgames.it/a')
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/google-news.test.ts`
Expected: FAIL (`body` non definito; dedup tiene la prima copia)

- [ ] **Step 3: Implementare in `google-news.ts`**

Aggiungere `body?: string` a `NewsItem`. In `parseRss`, dopo `pub`: `const body = tag(block, 'content:encoded')` e includerlo nell'oggetto pushato quando presente. In `fetchNewsHeadlines` sostituire il `seen: Set` con `Map<string, number>` (indice in `items`): se la chiave esiste e `items[idx].body` è undefined mentre la nuova copia ha `body`, sostituire `items[idx]`; altrimenti saltare come oggi.

- [ ] **Step 4: Run test to verify it passes**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/google-news.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/adapters/google-news.ts test/adapters/google-news.test.ts
git commit -m "feat(collector): tieni il corpo dell'articolo dal feed RSS"
```

---

### Task 2: Funzioni pure per articoli (classifier, citazioni, prompt, parse)

**Files:**
- Create: `collector/src/adapters/articles.ts`
- Modify: `collector/src/adapters/gemini.ts:43` (rendere `export` la funzione privata `extractJson`)
- Test: `collector/test/adapters/articles.test.ts`

**Interfaces:**
- Consumes: `extractJson(text: string): unknown` da `gemini.js` (stessa gestione fence ```json).
- Produces:
  - `isCandidateHeadline(title: string, hasBody: boolean): boolean`
  - `verifyQuote(quote: string, articleText: string): string | null`
  - `buildArticlePrompt(articleText: string, now: Date): string`
  - `type ArticleGame = { title: string; publisher?: string; releaseDate?: string; descriptionQuote?: string }`
  - `parseArticleGames(text: string): { ok: boolean; games: ArticleGame[] }`

- [ ] **Step 1: Write the failing tests**

In `collector/test/adapters/articles.test.ts`:

```ts
import { expect, test, vi } from 'vitest'
import { afterEach, beforeEach } from 'vitest'
import { isCandidateHeadline, verifyQuote, buildArticlePrompt, parseArticleGames } from '../../src/adapters/articles.js'

// isCandidateHeadline: le negative vincono sempre; senza corpo serve una parola positiva;
// con corpo (feed dei siti) basta l'assenza di negative.
test.each([
  ['Recensione di Ark Nova', false, false],
  ['Provato: Gloomhaven, il verdetto', true, false],
  ['Arriva a novembre Wingspan', false, true],
  ['Nuova uscita: il seguito di Cascadia', true, true],
  ['Titolo qualsiasi', false, false],
  ['Titolo qualsiasi', true, true],
])('isCandidateHeadline(%o, hasBody=%o) => %o', (title, hasBody, expected) => {
  expect(isCandidateHeadline(title, hasBody)).toBe(expected)
})

test('verifyQuote accetta solo una substring con spazi normalizzati', () => {
  const corpo = 'Il gioco arriva\n  il 3 novembre. È un gioco di carte.'
  expect(verifyQuote('Il gioco arriva il 3 novembre.', corpo)).toBe('Il gioco arriva il 3 novembre.')
  expect(verifyQuote('arriva il 3 di novembre', corpo)).toBeNull()
  expect(verifyQuote('', corpo)).toBeNull()
})

test('buildArticlePrompt chiede il JSON senza chiedere il prezzo', () => {
  vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  const prompt = buildArticlePrompt('Testo dell’articolo.', new Date())
  expect(prompt).toContain('15 ottobre 2026')
  expect(prompt).toContain('Testo dell’articolo.')
  expect(prompt).toContain('descriptionQuote')
  expect(prompt).toContain('VERBATIM')
  expect(prompt.toLowerCase()).not.toContain('prezzo')
  vi.useRealTimers()
})

test('parseArticleGames gestisce fence e JSON invalido', () => {
  const ok = parseArticleGames('```json\n{"games":[{"title":"Cascadia","releaseDate":"novembre 2026"}]}\n```')
  expect(ok).toEqual({ ok: true, games: [{ title: 'Cascadia', releaseDate: 'novembre 2026' }] })
  expect(parseArticleGames('frasi di testo')).toEqual({ ok: false, games: [] })
  expect(parseArticleGames('{"games":[]}')).toEqual({ ok: true, games: [] })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/articles.test.ts`
Expected: FAIL (modulo non esistente)

- [ ] **Step 3: Implementare `articles.ts`**

Regole del classifier (testate sopra): negative = `recensione|review|recensito|provato|verdetto|voto|opinion`; positive = `uscita|uscirà|in uscita|novità|annuncio|annunciano|arriva|arrivo|arriverà|anteprima|programmato|edizione italiana|kickstarter|disponibile|appena uscito|listino` (case-insensitive, accenti gestiti con confronto su stringa normalizza lower senza diacritici). `hasBody === true` → true salvo negative; `false` → true solo se positiva.

`verifyQuote`: normalizza entrambe le stringhe con `.replace(/\s+/g, ' ').trim()`; ritorna la citazione normalizzata solo se `corpoNormalizzato.includes(citazioneNormalizzata)` e la citazione normalizzata non è vuota; altrimenti `null`.

`buildArticlePrompt`: specchio di `buildNewsPrompt` (stessa formattazione data in italiano con `timeZone: 'Europe/Rome'`): estrai i giochi da tavolo di cui l'articolo annuncia uscite/anteprime; `descriptionQuote` = frase copiata **VERBATIM** dall'articolo, niente parafrasi; `releaseDate` solo se scritta; `publisher` solo se citato; formato `{"games":[...]}` solo JSON; nessun campo prezzo.

`parseArticleGames`: `extractJson` + stessa logica di `parseUpcomingJson` per distinguere non-interpretabile (`ok:false`) da array valido; ogni entry senza `title` stringa non vuota viene saltata; `publisher`/`releaseDate`/`descriptionQuote` solo se stringhe non vuote.

- [ ] **Step 4: Run test to verify it passes**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/articles.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/adapters/articles.ts src/adapters/gemini.ts test/adapters/articles.test.ts
git commit -m "feat(collector): classifica le notizie e verifica le citazioni verbatim"
```

---

### Task 3: Percorso articoli in google-news

**Files:**
- Modify: `collector/src/types.ts:6-19` (`RawProductSchema` += `articleDescription: z.string().optional()`)
- Modify: `collector/src/adapters/google-news.ts` (import da `articles.ts`, `MAX_ARTICLE_BODIES`, `fetchArticleBody`, flusso in `fetchNewsProducts`)
- Test: `collector/test/adapters/google-news.test.ts`

**Interfaces:**
- Consumes: da Task 1 `NewsItem.body`; da Task 2 `isCandidateHeadline`, `verifyQuote`, `buildArticlePrompt`, `parseArticleGames`; da `normalize.js` `normalizeTitle`, `extractReleaseDate`; da `relay.js` `createRelayFetcher`; da `ai-providers.js` `buildProviders`; da `text.js` `htmlToText`.
- Produces:
  - `RawProduct` += campo opzionale `articleDescription?: string` (citazione verbatim già verificata).
  - `export const MAX_ARTICLE_BODIES = 12`
  - `NewsOptions` += `articleFetch?: TextFetcher` (solo test; default = relay Jina, poi fetch diretto del link).
  - `fetchNewsProducts` ordine: headline → candidati (con corpo per primi, poi per data) → per articolo: corpo (dal feed o scaricato, min 300 caratteri altrimenti skip) → chiamata LLM articolo → per ogni gioco: `releaseDateRaw = extractReleaseDate(dataLLM) ?? omesso`, citazione `verifyQuote` → `articleDescription`, `url = item.link`, `externalId = normalizeTitle(title).replace(/\s+/g,'-')`, poi `gameFinder(titoloNormalizzato, publisher)` che — come in `parseUpcomingJson` — può sovrascrivere price/currency/releaseDate/imageUrl/gameId dai dati di catalogo. Titoli degli articoli analizzati rimossi dal prompt dei titoli. Dedup finale: un gioco estratto da un articolo elimina lo stesso normalized title dal percorso titoli. Un articolo con fetch/LLM fallito non butta giù la fonte e resta nei titoli.

- [ ] **Step 1: Write the failing tests**

In `collector/test/adapters/google-news.test.ts` (stessi helper `rss`/`item`/`geminiOk`/`rssOk`/`fetchMock` già presenti; iniettare `news.articleFetch`):

```ts
const bodyHog = `<item>
  <title><![CDATA[Cinque uscite di novembre - HoG]]></title>
  <link>https://www.houseofgames.it/uscite</link>
  <pubDate>Wed, 15 Oct 2026 09:30:00 GMT</pubDate>
  <content:encoded><![CDATA[<p>${'Arriva il gioco Cascadia. '.repeat(20)}</p>]]></content:encoded>
</item>`

test('analizza l’articolo ed estrae citazione, data e url dell’articolo', async () => {
  // articleFetch non serve: il corpo arriva dal feed. GEMINI_API_KEY impostata,
  // una sola chiamata LLM (articolo), nessun titolo restante.
  process.env.GEMINI_API_KEY = 'k'
  fetchMock.mockResolvedValueOnce(rssOk(rss(bodyHog)))
  fetchMock.mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [{
    title: 'Cascadia', publisher: 'North Star',
    releaseDate: 'novembre 2026',
    descriptionQuote: 'Arriva il gioco Cascadia.',
  }] })))
  const out = await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q') }, articleFetch: async () => null })
  expect(out).toHaveLength(1)
  expect(out[0]).toMatchObject({
    title: 'Cascadia',
    url: 'https://www.houseofgames.it/uscite',
    releaseDateRaw: '2026-11-01',
    articleDescription: 'Arriva il gioco Cascadia.',
  })
})

test('scarta la citazione parafrasata', async () => {
  process.env.GEMINI_API_KEY = 'k'
  fetchMock.mockResolvedValueOnce(rssOk(rss(bodyHog)))
  fetchMock.mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [
    { title: 'Cascadia', descriptionQuote: 'Il gioco di carte arriva a novembre.' },
  ] })))
  const out = await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q') } })
  expect(out[0]).not.toHaveProperty('articleDescription')
})

test('un articolo non fetchabile resta sul percorso titoli', async () => {
  process.env.GEMINI_API_KEY = 'k'
  fetchMock
    .mockResolvedValueOnce(rssOk(rss(item('Arriva Cascadia a novembre', 'Wed, 15 Oct 2026 09:30:00 GMT'))))
    .mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [{ title: 'Cascadia' }] })))
  const articleFetch = vi.fn().mockResolvedValue(null)
  const out = await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q') }, articleFetch })
  // Titolo positivo senza corpo: prova il fetch, fallisce, e l'articolo ricade nei titoli.
  expect(articleFetch).toHaveBeenCalledTimes(1)
  expect(out).toHaveLength(1)
  expect(out[0]?.title).toBe('Cascadia')
})

test('una recensione non viene analizzata ma resta nei titoli', async () => {
  process.env.GEMINI_API_KEY = 'k'
  fetchMock
    .mockResolvedValueOnce(rssOk(rss(item('Recensione: Cascadia è bellissimo', 'Wed, 15 Oct 2026 09:30:00 GMT'))))
    .mockResolvedValueOnce(geminiOk(JSON.stringify({ games: [] })))
  const articleFetch = vi.fn().mockResolvedValue(null)
  const out = await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q') }, articleFetch })
  expect(articleFetch).not.toHaveBeenCalled()
  expect(out).toEqual([])
})

test('il cap conta i corpi analizzati con successo', async () => {
  vi.setSystemTime(new Date('2026-10-15T12:00:00Z'))
  process.env.GEMINI_API_KEY = 'k'
  const items = Array.from({ length: 15 }, (_, i) => `<item>
    <title><![CDATA[Novità gioco ${i} - HoG]]></title>
    <link>https://www.houseofgames.it/${i}</link>
    <pubDate>Wed, 15 Oct 2026 09:30:0${i % 10} GMT</pubDate>
    <content:encoded><![CDATA[<p>${'Arriva un gioco nuovo. '.repeat(20)}</p>]]></content:encoded>
  </item>`).join('')
  fetchMock.mockResolvedValueOnce(rssOk(rss(items)))
  const gemini = vi.fn().mockResolvedValue(geminiOk('{"games":[]}'))
  fetchMock.mockImplementation(async (url: string) =>
    url.includes('generativelanguage') ? gemini() : rssOk(rss(items)))
  await fetchNewsProducts(source, undefined, { news: { sources: newsSources('q'), maxItems: 15 } })
  // 15 candidati con corpo: solo 12 chiamate LLM articolo, più 1 chiamata titoli per i 3 rimasti.
  expect(gemini).toHaveBeenCalledTimes(13)
})
```

Nota sui test con doppia chiamata LLM: il secondo `mockResolvedValueOnce(geminiOk(...))` risponde all'articolo; se restano titoli non analizzati parte una seconda chiamata (che qui non deve avvenire: i test sopra coprono il caso in cui non restano titoli). Adattare gli stub sequenziali alla reale sequenza delle chiamate durante l'implementazione mantenendo le asserzioni.

- [ ] **Step 2: Run test to verify it fails**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/google-news.test.ts`
Expected: FAIL (articoli non analizzati)

- [ ] **Step 3: Implementare**

`types.ts`: aggiungere `articleDescription: z.string().optional()` a `RawProductSchema`. `gemini.ts`: `export function extractJson`. In `google-news.ts`: `import { isCandidateHeadline, verifyQuote, buildArticlePrompt, parseArticleGames } from './articles.js'`, `import { extractReleaseDate } from '../normalize.js'`, `import { htmlToText } from '../text.js'`, `import { createRelayFetcher } from './relay.js'`. `export const MAX_ARTICLE_BODIES = 12` e `const MIN_ARTICLE_CHARS = 300`. `fetchArticleBody(item, articleFetch?)`: se `articleFetch` iniettato usalo; default `createRelayFetcher()` e, se ritorna null, fetch diretto di `item.link`. In `fetchNewsProducts`: dopo `fetchNewsHeadlines`, candidati = `items.filter(i => isCandidateHeadline(i.title, !!i.body))` ordinati (corpo per primo, poi `publishedAt` desc); loop con contatore successi === `MAX_ARTICLE_BODIES`; corpo = `htmlToText(item.body)` o fetch; se `body === null || body.length < MIN_ARTICLE_CHARS` → skip (non conta); per i provider in cascata (stesso schema del percorso titoli): `provider.call(buildArticlePrompt(body, new Date()))` → `parseArticleGames`; `ok:false` → provider successivo; totale fallito → skip articolo; altrimenti mappa giochi in `RawProduct` come da Interfaces e `analyzedTitles.add(normalizeTitle(item.title))`, successi++. Poi percorso titoli su `items.filter(i => !analyzedTitles.has(normalizeTitle(i.title)))` (se vuoto, salta la chiamata titoli); merge finale: prodotti articoli + prodotti titoli filtrati per normalized title già visto negli articoli. Log `console.error('[news] ...')` coerenti con quelli esistenti.

Attenzione: il test esistente `fetchNewsProducts passa i titoli a Gemini SENZA ricerca web` usa il titolo `Carcassonne esce a dicembre`, che non deve diventare candidato (nessuna parola positiva nella lista: **non** aggiungere `esce`) — se un test esistente finisse per essere candidato, iniettare `articleFetch` che ritorna null per mantenerlo sul percorso titoli.

- [ ] **Step 4: Run test to verify it passes**

Run: `node node_modules\vitest\vitest.mjs run test/adapters/google-news.test.ts test/adapters/articles.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/types.ts src/adapters/gemini.ts src/adapters/google-news.ts test/adapters/google-news.test.ts
git commit -m "feat(collector): analizza gli articoli completi per le novità"
```

---

### Task 4: Fill write-once per release_date

**Files:**
- Modify: `collector/src/db.ts:18-29` (interfaccia), `:160-170` (dopo `fillProductDescription`)
- Test: `collector/test/db.test.ts`

**Interfaces:**
- Produces: `ListingStore.fillProductReleaseDate(productId: number, iso: string): Promise<void>` — unico scrittore condizionato di `product.release_date` dai fill: `update({ release_date: iso }).eq('id', productId).is('release_date', null)`.

- [ ] **Step 1: Write the failing test**

In `collector/test/db.test.ts`, specchio di `fillProductDescription aggiorna solo la colonna ancora vuota`:

```ts
test('fillProductReleaseDate aggiorna solo la colonna ancora vuota', async () => {
  const is = vi.fn().mockReturnValue({ error: null })
  const eq = vi.fn(() => ({ is }))
  const update = vi.fn((payload: { release_date: string }) => ({ eq }))
  const from = vi.fn(() => ({ update }))
  const store = createSupabaseStore({ from } as any, () => '2026-10-22T00:00:00Z')
  await store.fillProductReleaseDate(7, '2026-11-01')
  expect(from).toHaveBeenCalledWith('product')
  expect(update.mock.calls[0][0]).toEqual({ release_date: '2026-11-01' })
  expect(eq).toHaveBeenCalledWith('id', 7)
  expect(is).toHaveBeenCalledWith('release_date', null)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node node_modules\vitest\vitest.mjs run test/db.test.ts`
Expected: FAIL (`fillProductReleaseDate` non è una funzione)

- [ ] **Step 3: Implementare in `db.ts`**

Aggiungere il metodo all'interfaccia e a `createSupabaseStore`, con commento in italiano sul perché il filtro `.is('release_date', null)` è la write-once (specchio di `fillProductDescription`).

- [ ] **Step 4: Run test to verify it passes**

Run: `node node_modules\vitest\vitest.mjs run test/db.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/db.ts test/db.test.ts
git commit -m "feat(collector): fill write-once per release_date del prodotto"
```

---

### Task 5: Wiring in run.ts e suite completa

**Files:**
- Modify: `collector/src/run.ts:47-99`
- Test: `collector/test/run.test.ts`

**Interfaces:**
- Consumes: `RawProduct.articleDescription` (Task 3), `store.fillProductReleaseDate` (Task 4).
- Produces: pairing `{ raw: RawProduct; row: Listing }[]` che sopravvive al filtro cutoff; in una run con `source.kind === 'news'` la riga corrente entra in `related` con `releaseDate: null`, `price: null`, `currency: null`; dopo l'upsert: fill citazione (`p.raw.articleDescription`) e fill data (`row.releaseDate`) write-once.

- [ ] **Step 1: Write the failing tests**

In `collector/test/run.test.ts` (aggiungere `fillProductReleaseDate: vi.fn().mockResolvedValue(undefined)` a `storeBase` e ai mock `as any` già presenti):

```ts
test('una fonte news non porta prezzo e data nell upsert del prodotto', async () => {
  const store = storeBase()
  const adapters = {
    news: async () => [{ externalId: '9', title: 'N', url: 'https://e.com/n', releaseDateRaw: '2026-11-01', priceRaw: '29.99' }],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [], html: async () => [], headless: async () => [],
  } as any
  await runCollector({ sources: [src('sN', 'news')], adapters, store })
  const payload = store.upsertProducts.mock.calls[0][0][0]
  expect(payload).not.toHaveProperty('release_date')
  expect(payload).not.toHaveProperty('price')
  expect(store.fillProductReleaseDate).toHaveBeenCalledWith(7, '2026-11-01')
})

test('chiama fillProductDescription con la citazione dell’articolo', async () => {
  const store = storeBase()
  const adapters = {
    news: async () => [{ externalId: '9', title: 'N', url: 'https://e.com/n', articleDescription: 'Frase verbatim dell’articolo' }],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [], html: async () => [], headless: async () => [],
  } as any
  await runCollector({ sources: [src('sN', 'news')], adapters, store })
  expect(store.fillProductDescription).toHaveBeenCalledWith(7, 'Frase verbatim dell’articolo')
})

test('la citazione sopravvive al filtro di cutoff', async () => {
  const store = storeBase()
  const adapters = {
    news: async () => [
      { externalId: '1', title: 'Vecchio', url: 'https://e.com/1', releaseDateRaw: '2025-01-15' },
      { externalId: '2', title: 'Fresco', url: 'https://e.com/2', articleDescription: 'Citazione viva' },
    ],
    shopify: async () => [], woocommerce: async () => [], wordpress: async () => [], html: async () => [], headless: async () => [],
  } as any
  await runCollector({
    sources: [src('sN', 'news')], adapters, store,
    now: () => new Date('2026-10-20T00:00:00Z'),
  })
  expect(store.fillProductDescription).toHaveBeenCalledTimes(1)
  expect(store.fillProductDescription).toHaveBeenCalledWith(7, 'Citazione viva')
  expect(store.fillProductReleaseDate).not.toHaveBeenCalled()
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node node_modules\vitest\vitest.mjs run test/run.test.ts`
Expected: FAIL (payload contiene ancora release_date/price; citazione persa)

- [ ] **Step 3: Implementare in `run.ts`**

Sostituire la costruzione di `rows` con il pairing:

```ts
const paired = raw
  .map((r) => ({ raw: r, row: normalizeListing(source.id, r) }))
  .map((p) => (GENERATED_TEXT_KINDS.has(source.kind) ? { ...p, row: { ...p.row, description: null } } : p))
  .filter((p) => p.row.releaseDate === null || p.row.releaseDate >= cutoff)
```

Ciclo su `paired`: `const isNews = source.kind === 'news'`; in `related` la riga corrente entra come `isNews ? { ...row, releaseDate: null, price: null, currency: null } : row` (le righe match dalla banca dati restano invariate). Dopo l'upsert e il fill canonico: `if (p.raw.articleDescription) await store.fillProductDescription(productId, p.raw.articleDescription)`; `if (isNews && row.releaseDate) await store.fillProductReleaseDate(productId, row.releaseDate)`. `perSource[source.id] = raw.length` invariato.

- [ ] **Step 4: Run test to verify it passes**

Run: `node node_modules\vitest\vitest.mjs run test/run.test.ts`
Expected: PASS

- [ ] **Step 5: Suite completa (tsc + tutti i test)**

Run: `node node_modules\typescript\bin\tsc --noEmit; node node_modules\vitest\vitest.mjs run`
Expected: tsc exit 0; tutti i test PASS (inclusi i pin esistenti `non scrive la descrizione di una fonte news` e `upsertProducts non porta mai description nel payload`).

- [ ] **Step 6: Commit**

```bash
git add src/run.ts test/run.test.ts
git commit -m "feat(collector): citazioni e date degli articoli finiscono write-once sul prodotto"
```
