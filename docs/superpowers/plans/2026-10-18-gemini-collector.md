# Gemini Collector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `gemini` source to the collector that asks an LLM (once per collect run) which board games release in the next two months, and enriches each entry with the real price/date/photo already in the database.

**Architecture:** A new `gemini` adapter in the collector calls the Gemini API (POST, `gemini-2.0-flash`) with a fixed prompt, parses the strict-JSON response into `RawProduct`s, and enriches each one through an injected `GameFinder` that looks the game up by normalized title in the `listing` table. Matches copy the verified price/date/photo onto the Gemini listing and set `game_id` so the app merges it into the existing game card. The source runs with the existing `collect` workflow; it only needs the `GEMINI_API_KEY` secret.

**Tech Stack:** Node 22, TypeScript ESM (`.js` imports), vitest, `tsc --noEmit`, Supabase JS, Gemini REST API, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-18-gemini-collector-design.md`

## Global Constraints

- Node 22 / TypeScript ESM; relative imports carry the `.js` extension.
- `SOURCE_KINDS` is a closed set — this plan extends it with exactly one new kind: `'gemini'`. No other new kinds.
- The Gemini API is called with `fetch` directly (it is a POST with a JSON body; the existing `HttpClient` is GET-only). Tests mock `global.fetch`.
- `GEMINI_API_KEY` is read from `process.env.GEMINI_API_KEY` (throw if missing); it is a GitHub Actions secret, never logged.
- Prices are never invented: an unparseable or missing price ⇒ `priceRaw: undefined`.
- The Gemini job never deletes listings — upsert only — so the app's `listing:<id>` ignore keys stay stable.
- `RawProduct.url` is required and must be a valid URL: Gemini entries get a DuckDuckGo search URL built from title + publisher.
- Code comments only in Italian, and only where neighbouring lines already have them.
- Tests: `npm.cmd test` (vitest) and `npm.cmd run typecheck` must be green. Commit per task. No push.

## Review Focus

1. **Fenced or non-JSON API response** — the parser must extract the JSON array from ```json fences and tolerate surrounding text; a response with no parseable array yields `[]` (not a crash).
2. **Entries with a missing/empty title** — skipped individually; they must not fail the whole run or produce an empty-title product.
3. **Non-numeric or comma-decimal price** — `toPrice` returns `null` ⇒ `priceRaw: undefined`; the price is never mangled or invented.
4. **Release date in an unexpected format** — lost gracefully (`releaseDate: null`), never a crash and never a wrong date.
5. **Normalized-title collision** — two different games that normalize alike must not be merged; the publisher hint disambiguates, and an ambiguous match yields no enrichment.

---

## File Structure

- `collector/src/adapters/gemini.ts` — the adapter: prompt building, API call, JSON parsing, `RawProduct` mapping, enrichment via `GameFinder`. Also exports `createGameFinder` (Task 2).
- `collector/prompts/upcoming-releases.txt` — the fixed prompt with `{MESE_CORRENTE}`, `{MESE_SUCCESSIVO}`, `{ANNO}` placeholders.
- `collector/src/normalize.ts` — gains `normalizeTitle` (exported).
- `collector/src/types.ts` — `SOURCE_KINDS` + `'gemini'`; `RawProductSchema.gameId?`; `Listing.gameId`.
- `collector/src/db.ts` — `upsertListings` persists `game_id`; gains `findListingsByTitle`.
- `collector/src/adapters/registry.ts` — `buildRegistry(http?, gameFinder?)`.
- `collector/src/run.ts` — wires `gameFinder` into the registry.
- `collector/src/sources.seed.ts` — the `gemini` source row.
- `collector/.env.example` — `GEMINI_API_KEY`.
- `.github/workflows/collect.yml` — the secret.
- `docs/superpowers/specs/2026-10-17-meeple-radar-design.md` — the new kind + source.

---

### Task 1: Adapter Gemini (API, parser, normalization, enrichment)

**Files:**
- Create: `collector/src/adapters/gemini.ts`
- Create: `collector/prompts/upcoming-releases.txt`
- Modify: `collector/src/types.ts`, `collector/src/normalize.ts`, `collector/src/db.ts`
- Test: `collector/test/adapters/gemini.test.ts`, `collector/test/db.test.ts`

**Interfaces:**
- Consumes: `RawProduct`, `Listing`, `Source`, `normalizeTitle` (from `normalize.ts`), `toPrice`/`isoOrNull` (from `normalize.js`).
- Produces:
  - `export type GameMatch = { gameId: number | null; price: number | null; currency: string | null; releaseDate: string | null; imageUrl: string | null }`
  - `export type GameFinder = (normalizedTitle: string, publisherHint?: string) => Promise<GameMatch | null>`
  - `export function fetchGeminiProducts(source: Source, gameFinder?: GameFinder): Promise<RawProduct[]>`
  - `normalizeTitle(title: string): string` (exported from `normalize.ts`; lowercase, strip punctuation, collapse whitespace)

- [ ] **Step 1: Write the failing tests** — in `collector/test/adapters/gemini.test.ts`:
  - (a) a fenced JSON array response (```` ```json ... ``` ````) with two valid entries and one entry missing `title` ⇒ `fetchGeminiProducts` returns 2 `RawProduct`s, the third skipped.
  - (b) an entry whose `price` is `"19,99 €"` ⇒ `priceRaw === "19,99"` (the raw string; `toPrice` normalization is `normalizeListing`'s job) and `currencyRaw === 'EUR'`.
  - (c) an entry with an `articleCode` ⇒ `externalId === articleCode`; an entry without ⇒ `externalId` is the normalized title with spaces replaced by `-`.
  - (d) `url` is `https://duckduckgo.com/?q=<encoded title + publisher>`.
  - (e) enrichment: a fake `gameFinder` returning a match ⇒ the product gets the match's `price`/`releaseDate`/`imageUrl` (model values only fill gaps the match lacks) and `gameId`; a fake returning `null` ⇒ model data kept, `gameId` undefined.
  - (f) a response with no parseable array ⇒ `[]` (no throw).
  - In `collector/test/db.test.ts`: `upsertListings` persists `game_id` in the payload.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm.cmd test test/adapters/gemini.test.ts test/db.test.ts`
Expected: FAIL (module `gemini.js` missing; `game_id` not in the upsert payload).

- [ ] **Step 3: Implement**
  - `normalizeTitle` in `normalize.ts` (exported).
  - `SOURCE_KINDS` + `'gemini'` in `types.ts`; `RawProductSchema.gameId: z.number().optional()`; `Listing.gameId: number | null`.
  - `upsertListings` in `db.ts` includes `game_id: r.gameId ?? null` in the payload.
  - `collector/prompts/upcoming-releases.txt` with the exact prompt (Italian, fields `title`/`publisher`/`articleCode`/`price`/`releaseDate`/`description`, demands a JSON array only, `{MESE_CORRENTE}` `{MESE_SUCCESSIVO}` `{ANNO}` placeholders).
  - `fetchGeminiProducts(source, gameFinder?)` in `gemini.ts`: read `process.env.GEMINI_API_KEY` (throw if missing); compute the current and next Italian month names + year; read the prompt file (via `import.meta.url`); POST to `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=...` with `{ contents: [{ parts: [{ text }] }] }`; extract `candidates[0].content.parts[0].text`; strip ```` ```json ```` fences; `JSON.parse`; keep entries with a non-empty `title`; map each to a `RawProduct` (`externalId` = articleCode ?? normalized-title slug, `url` = DuckDuckGo search, `priceRaw`/`currencyRaw` only when a price is present, `releaseDateRaw` only when present, `descriptionRaw` = description); when a `gameFinder` is given, call it with `normalizeTitle(title)` + publisher and copy the match's non-null `price`/`releaseDate`/`imageUrl` (model fills gaps) and set `gameId`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm.cmd test; npm.cmd run typecheck`
Expected: PASS, 60+ tests green, typecheck clean.

- [ ] **Step 5: Commit**

`git add collector/src collector/test collector/prompts; git commit -m "feat(collector): add the Gemini adapter that lists upcoming releases"`

---

### Task 2: Join with the database (GameFinder + store lookup + wiring)

**Files:**
- Modify: `collector/src/db.ts`, `collector/src/adapters/registry.ts`, `collector/src/run.ts`, `collector/src/adapters/gemini.ts`
- Test: `collector/test/adapters/gemini-join.test.ts` (new), `collector/test/db.test.ts`

**Interfaces:**
- Consumes: `fetchGeminiProducts(source, gameFinder?)`, `GameFinder`, `GameMatch` (Task 1); `ListingStore`.
- Produces:
  - `export function createGameFinder(store: ListingStore): GameFinder` (in `gemini.ts`)
  - `ListingStore.findListingsByTitle(normalizedTitle: string): Promise<ListingRow[]>` where `ListingRow = { id: number; title: string; url: string; price: number | null; currency: string | null; releaseDate: string | null; imageUrl: string | null; gameId: number | null }`
  - `buildRegistry(http?: HttpClient, gameFinder?: GameFinder): Record<SourceKind, Adapter>`

- [ ] **Step 1: Write the failing tests** — in `collector/test/adapters/gemini-join.test.ts`:
  - (a) `createGameFinder` with a fake store whose `findListingsByTitle` returns one row ⇒ resolves to a `GameMatch` built from that row (`gameId`, `price`, `currency`, `releaseDate`, `imageUrl`).
  - (b) two rows normalizing to the same title, one whose `sourceId` matches the publisher hint ⇒ that one wins.
  - (c) no rows ⇒ `null`.
  - (d) `buildRegistry(http, gameFinder).gemini` is a function that passes `gameFinder` to `fetchGeminiProducts` (assert via a fake source + a `fetch` mock that returns `[]`, and check the finder was invoked through the parsed products).
  In `collector/test/db.test.ts`: `findListingsByTitle` fetches the `listing` columns and returns only rows whose `normalizeTitle(title)` equals the argument.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `npm.cmd test test/adapters/gemini-join.test.ts test/db.test.ts`
Expected: FAIL (`createGameFinder` / `findListingsByTitle` missing).

- [ ] **Step 3: Implement**
  - `findListingsByTitle(normalizedTitle)` in `db.ts`: `from('listing').select('id, title, url, price, currency, release_date, image_url, game_id')`, then filter in JS by `normalizeTitle(row.title) === normalizedTitle` (SQL cannot replicate the normalization). Return `ListingRow[]`.
  - `createGameFinder(store)` in `gemini.ts`: call `store.findListingsByTitle(title)`; if empty ⇒ `null`; if one ⇒ project to `GameMatch`; if several ⇒ prefer the row whose source name matches the publisher hint (via a `sources` lookup passed in or a second store method), else `null` (ambiguous ⇒ no match).
  - `buildRegistry(http?, gameFinder?)`: the `gemini` entry becomes `(source) => fetchGeminiProducts(source, gameFinder)`.
  - `run.ts`: build the registry with a `gameFinder` created from the store, and pass it through.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm.cmd test; npm.cmd run typecheck`
Expected: PASS, 60+ tests green, typecheck clean.

- [ ] **Step 5: Commit**

`git add collector/src collector/test; git commit -m "feat(collector): join Gemini releases with the database listings"`

---

### Task 3: Seed, config, workflow, spec

**Files:**
- Modify: `collector/src/sources.seed.ts`, `collector/.env.example`, `.github/workflows/collect.yml`, `docs/superpowers/specs/2026-10-17-meeple-radar-design.md`
- Test: `collector/test/sources.test.ts`

**Interfaces:**
- Consumes: `Source`, `SOURCE_KINDS` (Task 1), `fetchGeminiProducts` (Task 1).
- Produces: the `gemini` source row; the `GEMINI_API_KEY` secret in the workflow; the spec's new kind + source.

- [ ] **Step 1: Write the failing test** — in `collector/test/sources.test.ts`: the seed now has **18 sources, 14 enabled**; the enabled list includes `gemini`; `gemini` maps to `kind: 'gemini'` and `baseUrl: 'https://generativelanguage.googleapis.com'`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `npm.cmd test test/sources.test.ts`
Expected: FAIL (counts are 17/13).

- [ ] **Step 3: Implement**
  - Seed row: `{ id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://generativelanguage.googleapis.com', enabled: true }` (inserted in the sorted position).
  - `collector/.env.example`: add `GEMINI_API_KEY=`.
  - `.github/workflows/collect.yml`: add `GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}` to the `npm run collect` env block.
  - Spec: add `'gemini'` to the source-kind list and a row for the Gemini source (LLM, daily, indicative data enriched by the DB join); update any source counters.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `npm.cmd test; npm.cmd run typecheck`
Expected: PASS, 60+ tests green, typecheck clean.

- [ ] **Step 5: Commit**

`git add collector/src/sources.seed.ts collector/.env.example .github/workflows/collect.yml docs/superpowers/specs/2026-10-17-meeple-radar-design.md; git commit -m "feat(collector): enable the Gemini source in seed, workflow and spec"`

---

## Self-Review

**Spec coverage:**
- §2 decisions (daily run, DB enrichment, new games kept, photos from publishers, ignore button, article code) → Tasks 1–3. ✓
- §3 my calls (model, strict JSON, no new workflow, new kind, join by title, DuckDuckGo url, never delete, game_id, provenance) → Tasks 1–3. ✓
- Goals (daily list, fields, enrichment, app unchanged) → Tasks 1–2. ✓
- Non-goals (no per-game search, no BGG, no verification, keep scrapers) → respected. ✓
- Anti-goals (wrong data as provenance, hallucinations, lost ignores) → Task 1 (provenance via source name, never-delete), Task 2 (game_id stable). ✓
- Constraints (Node 22 ESM, kind set, secrets, no push) → Global Constraints. ✓

**Step scan:** each step names one action (test / run / implement with exact signature / commit). No "handle edge cases" or "write tests for the above" without a name. ✓

**Type consistency:** `GameMatch`, `GameFinder`, `fetchGeminiProducts`, `normalizeTitle`, `ListingRow`, `findListingsByTitle`, `createGameFinder`, `buildRegistry` are named identically across tasks. ✓

**Review Focus:** (1) fenced JSON → Task 1a/f; (2) missing title → Task 1a; (3) price format → Task 1b + `toPrice`; (4) date format → `isoOrNull` in `normalizeListing`; (5) title collision → Task 2b. ✓

**Proportion:** the plan is ~1 task of code per 2–3 steps of specification; no function bodies beyond the parser mapping. ✓
