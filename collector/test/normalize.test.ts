import { expect, test } from 'vitest'
import { toPrice, extractReleaseDate, normalizeListing, normalizeTitle } from '../src/normalize.js'
import { htmlToText } from '../src/text.js'

test('normalizeTitle assimila il suffisso edizione ITA: stesso gioco, non due', () => {
  expect(normalizeTitle('Black Flag! (ITA)')).toBe('black flag')
  expect(normalizeTitle('BLACK FLAG')).toBe('black flag')
  expect(normalizeTitle('Bunny 100 (ITA)')).toBe('bunny 100')
  // Titoli che contengono ita in altro modo restano se stessi.
  expect(normalizeTitle('Akita')).toBe('akita')
  expect(normalizeTitle('Dita')).toBe('dita')
  expect(normalizeTitle('Ita')).toBe('ita')
})

test('converts minor units to a decimal price', () => {
  expect(toPrice('1299', 2)).toBe(12.99)
  expect(toPrice('89.90')).toBe(89.9)
  expect(toPrice(undefined)).toBeNull()
})

test('extracts an Italian release date', () => {
  expect(extractReleaseDate('<p>In uscita il 15/03/2026</p>')).toBe('2026-03-15')
  expect(extractReleaseDate('<p>In uscita: 2026-04</p>')).toBe('2026-04-01')
  expect(extractReleaseDate('<p>Nessuna data</p>')).toBeNull()
})

test('giorno/mese senza anno: gli intervalli di giocatori non sono date', () => {
  expect(extractReleaseDate('Villa è un gioco astratto e riflessivo per 2-4 giocatori')).toBeNull()
  expect(extractReleaseDate('gioco cooperativo per 1-6 giocatori')).toBeNull()
  expect(extractReleaseDate('durata della partita 2.5 ore')).toBeNull()
  expect(extractReleaseDate('in uscita il 28/11')).toBe('2026-11-28')
})

test('dvgiochi: la data arriva solo dall\'elenco prossime uscite, mai dalla descrizione', () => {
  // Villa è "DA DEFINIRE" sul sito: la descrizione ("per 2-4 giocatori,
  // in arrivo a novembre") non deve produrre nessuna data.
  const villa = normalizeListing('dvgiochi', {
    externalId: 'villa', title: 'Villa', url: 'https://www.dvgiochi.com/catalogo/villa/',
    descriptionRaw: '<p>Un gioco per 2-4 giocatori, in arrivo a novembre</p>',
  })
  expect(villa.releaseDate).toBeNull()
  // La data dichiarata dall'elenco resta autorevole.
  const blablada = normalizeListing('dvgiochi', {
    externalId: 'blablada-ita', title: 'Blabladà', url: 'https://www.dvgiochi.com/catalogo/blablada-ita/',
    releaseDateRaw: '2026-10-27', descriptionRaw: '<p>in arrivo a novembre</p>',
  })
  expect(blablada.releaseDate).toBe('2026-10-27')
})

test('dvgiochi senza data marca la riga per azzerare una data precedente', () => {
  const villa = normalizeListing('dvgiochi', {
    externalId: 'villa', title: 'Villa', url: 'https://www.dvgiochi.com/catalogo/villa/',
    descriptionRaw: '<p>Un gioco per 2-4 giocatori</p>',
  })
  expect(villa.clearReleaseDate).toBe(true)
  const blablada = normalizeListing('dvgiochi', {
    externalId: 'blablada-ita', title: 'Blabladà', url: 'https://www.dvgiochi.com/catalogo/blablada-ita/',
    releaseDateRaw: '2026-10-27',
  })
  expect(blablada.clearReleaseDate).toBeFalsy()
  // Per le altre fonti l'assenza resta ambigua: nessuna cancellazione.
  const cranio = normalizeListing('cranio', {
    externalId: '1', title: 'X', url: 'https://e.com/x', descriptionRaw: '<p>Nessuna data</p>',
  })
  expect(cranio.clearReleaseDate).toBeFalsy()
})

test('normalizes a raw product, never inventing a date', () => {
  const l = normalizeListing('cranio', {
    externalId: '1', title: 'X', url: 'https://e.com/x', descriptionRaw: '<p>Nessuna data</p>',
  })
  expect(l).toMatchObject({ sourceId: 'cranio', price: null, currency: null, releaseDate: null })
})

test('prefers a structured release date over the parsed description', () => {
  const structured = normalizeListing('x', {
    externalId: '1', title: 'X', url: 'https://e.com/x', releaseDateRaw: '2026-10-27',
  })
  expect(structured.releaseDate).toBe('2026-10-27')
  const fallback = normalizeListing('x', {
    externalId: '1', title: 'X', url: 'https://e.com/x', releaseDateRaw: 'non-una-data',
    descriptionRaw: '<p>In uscita: 2026-04</p>',
  })
  expect(fallback.releaseDate).toBe('2026-04-01')
})

test('htmlToText rimuove i tag e decodifica le entità', () => {
  expect(htmlToText('<p>Salpa per i sette mari!</p><p>Un gioco di <strong>esplorazione</strong>.</p>'))
    .toBe('Salpa per i sette mari! Un gioco di esplorazione.')
  expect(htmlToText('Ghenos&#039; gioco &amp; carta')).toBe("Ghenos' gioco & carta")
})

test('htmlToText restituisce null per testo vuoto o solo spazi', () => {
  expect(htmlToText(undefined)).toBeNull()
  expect(htmlToText('')).toBeNull()
  expect(htmlToText('<p></p>   \n')).toBeNull()
})

test('normalizeListing decodifica le entità HTML nel titolo', () => {
  const row = normalizeListing('littlerocket', {
    externalId: '1', title: 'BLACK DRAGON&#8217;S GUILD', url: 'https://e.com/b',
  })
  expect(row.title).toBe('BLACK DRAGON’S GUILD')
  const dash = normalizeListing('giochiuniti', {
    externalId: '2', title: 'Hidden Games &#8211; Un Piano Perfetto', url: 'https://e.com/h',
  })
  expect(dash.title).toBe('Hidden Games – Un Piano Perfetto')
  const apos = normalizeListing('littlerocket', {
    externalId: '3', title: "GOT LCG - RITUALS OF R&#039;HLLOR", url: 'https://e.com/r',
  })
  expect(apos.title).toBe("GOT LCG - RITUALS OF R'HLLOR")
  const amp = normalizeListing('littlerocket', {
    externalId: '4', title: 'SIMPLE & MADAMA', url: 'https://e.com/s',
  })
  expect(amp.title).toBe('SIMPLE & MADAMA')
})

test('normalizeListing porta descriptionRaw nella descrizione come testo pulito', () => {
  const row = normalizeListing('cranio', {
    externalId: '1', title: 'Azul', url: 'https://e.com/a',
    descriptionRaw: '<div>Un gioco di <em>astratto</em></div>',
  })
  expect(row.description).toBe('Un gioco di astratto')
})

test('normalizeListing lascia la descrizione a null quando il sorgente non la ha', () => {
  const row = normalizeListing('cranio', { externalId: '1', title: 'Azul', url: 'https://e.com/a' })
  expect(row.description).toBeNull()
})
