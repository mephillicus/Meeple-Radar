// Prova dal vivo la cascata Gemini. Uso:
//   $env:GEMINI_API_KEY='...'
// Opzionali:
//   $env:GEMINI_MODEL='...'          → modello primario (default gemini-3.5-flash-lite)
//   $env:GEMINI_FALLBACK_MODEL='...' → modello di ripiego (default gemini-3.6-flash)
// La chiave resta solo nell'ambiente del processo: non viene scritta da nessuna parte.
import { fetchGeminiProducts } from '../src/adapters/gemini.js'
import { buildProviders } from '../src/adapters/ai-providers.js'
import type { Source } from '../src/types.js'

const source: Source = {
  id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://e.com', enabled: true,
}

const providers = buildProviders(process.env)
console.log(
  `cascata: ${providers.map((p) => `${p.id}=${p.model} (${p.attempts} tentativi)`).join(' → ') || 'nessuna'}`,
)

const t0 = Date.now()
const products = await fetchGeminiProducts(source)
console.log(`PRODOTTI: ${products.length}  (${((Date.now() - t0) / 1000).toFixed(1)}s)`)
for (const p of products.slice(0, 6)) {
  console.log(`  - ${p.title} | prezzo=${p.priceRaw ?? '-'} ${p.currencyRaw ?? ''} | uscita=${p.releaseDateRaw ?? '-'}`)
}
