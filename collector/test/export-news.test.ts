import { describe, expect, test } from 'vitest'
import * as exportNews from '../scripts/export-news.js'
import { parseEnvFile, parseArticleCount, parseArticleEsiti, mapProductRow, mapNewsRow } from '../scripts/export-news.js'

describe('applyEnvFile', () => {
  test('salta i valori vuoti e non sovrascrive chiavi già presenti', () => {
    const env: NodeJS.ProcessEnv = { GEMINI_API_KEY: 'già-qui' }
    exportNews.applyEnvFile({ GEMINI_API_KEY: 'nuovo', GEMINI_MODEL: '', JINA_API_KEY: 'jina-1' }, env)
    expect(env).toEqual({ GEMINI_API_KEY: 'già-qui', JINA_API_KEY: 'jina-1' })
  })
})

describe('buildPayload', () => {
  const item = {
    title: 'Cascadia arriva',
    source: 'Testata',
    link: 'https://news.google.com/xyz',
    publishedAt: new Date('2026-10-20T10:00:00Z'),
  }

  test('con un errore LLM registra il fallimento senza perdere notizie e log', () => {
    const payload = exportNews.buildPayload({
      items: [item],
      products: [],
      log: ['[news] 12 titoli da analizzare'],
      errore: 'nessun provider LLM ha funzionato',
    })
    expect(payload.riepilogo.errore).toBe('nessun provider LLM ha funzionato')
    expect(payload.notizie).toHaveLength(1)
    expect(payload.estrazioni).toEqual([])
    expect(payload.log.some((l) => l.includes('nessun provider LLM ha funzionato'))).toBe(true)
  })

  test('senza errore il riepilogo non ha la voce errore', () => {
    const payload = exportNews.buildPayload({ items: [], products: [], log: [] })
    expect(payload.riepilogo).not.toHaveProperty('errore')
  })
})

describe('parseEnvFile', () => {
  test('ignora commenti e righe vuote e separa alla prima ugualità', () => {
    const text = [
      '# chiave LLM',
      'GEMINI_API_KEY=abc=123',
      '',
      'JINA_API_KEY=jina-1',
      '  VUOTA=  ',
    ].join('\r\n')
    expect(parseEnvFile(text)).toEqual({
      GEMINI_API_KEY: 'abc=123',
      JINA_API_KEY: 'jina-1',
      VUOTA: '',
    })
  })

  test('non chiede valori alle righe senza ugualità', () => {
    expect(parseEnvFile('solo testo\nA=B')).toEqual({ A: 'B' })
  })
})

describe('parseArticleCount', () => {
  test('legge il numero di prodotti dagli articoli', () => {
    expect(parseArticleCount(['[news] 5 prodotti dagli articoli'])).toBe(5)
  })

  test('senza la riga di log non ci sono prodotti da articoli', () => {
    expect(parseArticleCount(['[news] 12 titoli da analizzare', '[llm] 3 prodotti da gemini (notizie)'])).toBe(0)
  })

  test('prende l’ultima occorrenza se il log viene concatenato', () => {
    expect(parseArticleCount(['[news] 2 prodotti dagli articoli', 'altro', '[news] 1 prodotti dagli articoli'])).toBe(1)
  })
})

describe('parseArticleEsiti', () => {
  test('mappa ogni articolo sul suo esito', () => {
    const lines = [
      '[news] "Cascadia arriva": 2 giochi dall\'articolo',
      '[news] "Prova vecchia": corpo mancante o troppo corto, resta tra i titoli',
      '[news] "Rotto": fetch corpo: timeout',
      '[news] "Risposta strana": articolo non analizzato, resta tra i titoli',
      '[news] "Altro errore": boom, resta tra i titoli',
      '[news] 3 prodotti dagli articoli',
      '[llm] 4 prodotti da gemini (notizie)',
    ]
    expect(parseArticleEsiti(lines)).toEqual({
      'Cascadia arriva': '2 giochi dall\'articolo',
      'Prova vecchia': 'corpo mancante o troppo corto',
      'Rotto': 'fetch corpo: timeout',
      'Risposta strana': 'articolo non analizzato',
      'Altro errore': 'boom',
    })
  })

  test('le righe di log senza titolo non entrano nella mappa', () => {
    expect(parseArticleEsiti(['[news] 12 titoli da analizzare'])).toEqual({})
  })
})

describe('mapProductRow', () => {
  const base = {
    externalId: 'cascadia',
    title: 'Cascadia',
    url: 'https://example.com/articolo',
    releaseDateRaw: '2026-11-01',
    descriptionRaw: 'desc dal titolo',
    articleDescription: 'citazione verbatim',
    gameId: 42,
  }

  test('i primi N prodotti sono del percorso articolo', () => {
    expect(mapProductRow(base, 0, 3).percorso).toBe('articolo')
    expect(mapProductRow(base, 3, 3).percorso).toBe('titoli')
    expect(mapProductRow(base, 0, 0).percorso).toBe('titoli')
  })

  test('la descrizione preferisce la citazione dell\'articolo', () => {
    expect(mapProductRow(base, 0, 1).descrizione).toBe('citazione verbatim')
    const { articleDescription: _a, ...senzaCitazione } = base
    expect(mapProductRow(senzaCitazione, 0, 1).descrizione).toBe('desc dal titolo')
  })

  test('i campi mancanti restano vuoti invece di undefined', () => {
    const row = mapProductRow({ externalId: 'x', title: 'X', url: 'https://e.com/x' }, 0, 0)
    expect(row.prezzo).toBe('')
    expect(row.dataUscita).toBe('')
    expect(row.gameId).toBe('')
  })

  test('la colonna pubblicato non sta nelle estrazioni: il percorso news non la popola', () => {
    const row = mapProductRow({ ...base, publishedAtRaw: '2026-10-01' }, 0, 1)
    expect(row).not.toHaveProperty('pubblicato')
  })
})

describe('mapNewsRow', () => {
  const item = {
    title: 'Cascadia arriva in Italia',
    source: 'Testata',
    link: 'https://news.google.com/xyz',
    publishedAt: new Date('2026-10-20T10:00:00Z'),
  }

  test('l\'esito del log vince su tutto', () => {
    const row = mapNewsRow({ ...item, body: '<p>corpo lungo</p>' }, { 'Cascadia arriva in Italia': '2 giochi dall\'articolo' })
    expect(row.esito).toBe('2 giochi dall\'articolo')
    expect(row.percorso).toBe('articolo')
  })

  test('un articolo analizzato male è tornato tra i titoli', () => {
    const row = mapNewsRow({ ...item, body: '<p>corpo lungo</p>' }, { 'Cascadia arriva in Italia': 'corpo mancante o troppo corto' })
    expect(row.esito).toBe('corpo mancante o troppo corto')
    expect(row.percorso).toBe('titoli')
  })

  test('un candidato senza esito è andato per titoli', () => {
    expect(mapNewsRow(item, {}).esito).toBe('in coda ai titoli')
    expect(mapNewsRow(item, {}).percorso).toBe('titoli')
  })

  test('un titolo non candidato resta fuori dagli articoli', () => {
    const recensione = { ...item, title: 'Recensione di Ark Nova' }
    const row = mapNewsRow(recensione, {})
    expect(row.percorso).toBe('titoli')
    expect(row.esito).toBe('solo titoli')
  })

  test('riporta se il corpo c\'era nel feed e la lunghezza', () => {
    const conCorpo = mapNewsRow({ ...item, body: '<p>abc</p>' }, {})
    expect(conCorpo.corpoNelFeed).toBe('sì (3 caratteri)')
    expect(mapNewsRow(item, {}).corpoNelFeed).toBe('no')
  })
})
