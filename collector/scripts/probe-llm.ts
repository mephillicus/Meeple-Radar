// Prova dal vivo la cascata di provider LLM. Uso:
//   $env:GROQ_API_KEY='...'      → solo Groq
//   $env:OPENROUTER_API_KEY='...' → solo OpenRouter
//   entrambe                       → cascata (nessun Gemini)
// Le chiavi restano solo nell'ambiente del processo: non vengono scritte da nessuna parte.
import { fetchGeminiProducts } from '../src/adapters/gemini.js'
import type { Source } from '../src/types.js'

const source: Source = {
  id: 'gemini', name: 'Gemini', kind: 'gemini', baseUrl: 'https://e.com', enabled: true,
}

const chiavi = ['GEMINI', 'GROQ', 'OPENROUTER']
  .filter((k) => process.env[`${k}_API_KEY`])
  .join(' + ')
console.log(`provider configurati: ${chiavi || 'nessuno'}`)

const t0 = Date.now()
const products = await fetchGeminiProducts(source)
console.log(`PRODOTTI: ${products.length}  (${((Date.now() - t0) / 1000).toFixed(1)}s)`)
for (const p of products.slice(0, 6)) {
  console.log(`  - ${p.title} | prezzo=${p.priceRaw ?? '-'} ${p.currencyRaw ?? ''} | uscita=${p.releaseDateRaw ?? '-'}`)
}
