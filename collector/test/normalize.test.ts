import { expect, test } from 'vitest'
import {
  areTitlesNearDuplicate,
  isNonBoardgameTitle,
  normalizeAvailability,
  toPrice,
  extractReleaseDate,
  normalizeListing,
  normalizeTitle,
} from '../src/normalize.js'
import { decodeHtml, htmlToText } from '../src/text.js'

test('normalizeTitle assimila il suffisso edizione ITA ma mantiene separata l\'edizione inglese', () => {
  expect(normalizeTitle('Black Flag! (ITA)')).toBe('black flag')
  expect(normalizeTitle('BLACK FLAG')).toBe('black flag')
  expect(normalizeTitle('Bunny 100 (ITA)')).toBe('bunny 100')
  expect(normalizeTitle('Clue: One Piece (EDIZIONE INGLESE)')).toBe('clue one piece edizione inglese')
  expect(normalizeTitle('One Piece: Nakama - ENG')).toBe('one piece nakama eng')
  // Titoli che contengono ita in altro modo restano se stessi.
  expect(normalizeTitle('Akita')).toBe('akita')
  expect(normalizeTitle('Dita')).toBe('dita')
  expect(normalizeTitle('Ita')).toBe('ita')
})

test('normalizeTitle deduplica varianti di punteggiatura, minuti, suffissi descrittivi e parole censurate', () => {
  expect(normalizeTitle('5 Minutes: One Piece')).toBe('5 minute one piece')
  expect(normalizeTitle('5 Minute: One Piece')).toBe('5 minute one piece')
  expect(normalizeTitle('5-Minute One Piece')).toBe('5 minute one piece')
  expect(normalizeTitle('7 LIVES')).toBe('7 lives')
  expect(normalizeTitle('7 Lives - Il Gioco di Carte')).toBe('7 lives')
  expect(normalizeTitle('Adult S#!ts')).toBe('adult shits')
  expect(normalizeTitle('Adult Shits')).toBe('adult shits')
  expect(normalizeTitle('AVVENTURE E MISTERI/CATTIVI')).toBe('avventure e misteri cattivi')
  // Un titolo che consiste solo nel suffisso non viene svuotato
  expect(normalizeTitle('Gioco di Carte')).toBe('gioco di carte')
})

test('areTitlesNearDuplicate cattura varianti tipografiche/censurate vicine senza fondere numeri diversi', () => {
  expect(areTitlesNearDuplicate('adult sts', 'adult shits')).toBe(true)
  expect(areTitlesNearDuplicate('adult shits', 'adult shits')).toBe(true)
  expect(areTitlesNearDuplicate('dune 1', 'dune 2')).toBe(false)
  expect(areTitlesNearDuplicate('dune imperium 1', 'dune imperium 2')).toBe(false)
  expect(areTitlesNearDuplicate('catan', 'catan')).toBe(true)
  expect(areTitlesNearDuplicate('catan', 'caton')).toBe(false)
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

test('decodeHtml decodifica entità nominate comuni e htmlToText rimuove noscript', () => {
  expect(
    decodeHtml(
      '&lsquo;Citt&agrave;&rsquo; &ndash; &ldquo;Et&agrave;&rdquo; &mdash; &hellip; &egrave; &eacute; &igrave; &ograve; &ugrave; &Egrave; &Agrave; 29&euro; &trade;&reg;&copy;',
    ),
  ).toBe('‘Città’ – “Età” — … è é ì ò ù È À 29€ ™®©')
  expect(
    htmlToText('<p>Testo</p><noscript><img src="https://e.com/2026-03/pixel.gif">NoJS</noscript><p>Fine</p>'),
  ).toBe('Testo Fine')
})

test('toPrice gestisce decimali europei con virgola, migliaia e simboli valuta', () => {
  expect(toPrice('29,90')).toBe(29.9)
  expect(toPrice('1.200,50')).toBe(1200.5)
  expect(toPrice('1,200.50')).toBe(1200.5)
  expect(toPrice('1,200')).toBe(1200)
  expect(toPrice('€ 29,90')).toBe(29.9)
  expect(toPrice('29,90 €')).toBe(29.9)
  expect(toPrice('EUR 49,50')).toBe(49.5)
  expect(toPrice('49.50 EUR')).toBe(49.5)
  expect(toPrice('  ')).toBeNull()
  expect(toPrice('€')).toBeNull()
  expect(toPrice('1299', 2)).toBe(12.99)
})

test('normalizeAvailability mappa valori canonici, legacy, schema.org e promuove date future a preorder', () => {
  const now = new Date('2026-06-15T12:00:00Z')
  expect(normalizeAvailability('https://schema.org/InStock', null, now)).toBe('available')
  expect(normalizeAvailability('http://schema.org/LimitedAvailability', null, now)).toBe('available')
  expect(normalizeAvailability('InStoreOnly', null, now)).toBe('available')
  expect(normalizeAvailability('true', null, now)).toBe('available')
  expect(normalizeAvailability('1', null, now)).toBe('available')
  expect(normalizeAvailability('yes', null, now)).toBe('available')

  expect(normalizeAvailability('https://schema.org/OutOfStock', null, now)).toBe('out_of_stock')
  expect(normalizeAvailability('false', null, now)).toBe('out_of_stock')
  expect(normalizeAvailability('0', null, now)).toBe('out_of_stock')
  expect(normalizeAvailability('no', null, now)).toBe('out_of_stock')
  expect(normalizeAvailability('sold_out', null, now)).toBe('out_of_stock')
  expect(normalizeAvailability('oos', null, now)).toBe('out_of_stock')

  expect(normalizeAvailability('https://schema.org/PreOrder', null, now)).toBe('preorder')
  expect(normalizeAvailability('preordine', null, now)).toBe('preorder')
  expect(normalizeAvailability('preordina', null, now)).toBe('preorder')
  expect(normalizeAvailability('pre-sale', null, now)).toBe('preorder')

  expect(normalizeAvailability('https://schema.org/BackOrder', null, now)).toBe('backorder')
  expect(normalizeAvailability('ordinabile', null, now)).toBe('backorder')
  expect(normalizeAvailability('su ordinazione', null, now)).toBe('backorder')

  expect(normalizeAvailability('https://schema.org/Discontinued', null, now)).toBe('discontinued')
  expect(normalizeAvailability('fuori produzione', null, now)).toBe('discontinued')

  expect(normalizeAvailability(null, null, now)).toBeNull()
  expect(normalizeAvailability('', null, now)).toBeNull()
  expect(normalizeAvailability('unknown_value', null, now)).toBeNull()

  // Data di uscita strettamente futura promuove 'available' -> 'preorder'
  expect(normalizeAvailability('true', '2026-06-16', now)).toBe('preorder')
  expect(normalizeAvailability('https://schema.org/InStock', '2026-06-15', now)).toBe('available')
  expect(normalizeAvailability('true', '2026-06-10', now)).toBe('available')
  // Non promuove out_of_stock o null
  expect(normalizeAvailability('false', '2026-06-20', now)).toBe('out_of_stock')
  expect(normalizeAvailability(null, '2026-06-20', now)).toBeNull()
})

test('normalizeListing normalizza availability usando normalizeAvailability', () => {
  const inStock = normalizeListing('dungeondice', {
    externalId: '1',
    title: 'Dune: Imperium',
    url: 'https://e.com/dune',
    availabilityRaw: 'https://schema.org/InStock',
  })
  expect(inStock.availability).toBe('available')

  const futurePreorder = normalizeListing('cranio', {
    externalId: '2',
    title: 'Nuovo Gioco',
    url: 'https://e.com/new',
    availabilityRaw: 'true',
    releaseDateRaw: `${new Date().getUTCFullYear() + 1}-10-01`,
  })
  expect(futurePreorder.availability).toBe('preorder')
})

test('isNonBoardgameTitle filtra accessori/puzzle/BRIO/TCG ma preserva i veri giochi da tavolo', () => {
  // Non-boardgames -> true
  expect(isNonBoardgameTitle('Ravensburger Puzzle 1000 Pezzi - Venezia')).toBe(true)
  expect(isNonBoardgameTitle('Puzzle 3D Torre Eiffel')).toBe(true)
  expect(isNonBoardgameTitle('GraviTrax Starter Set')).toBe(true)
  expect(isNonBoardgameTitle('BRIO Treno Merci a Batteria')).toBe(true)
  expect(isNonBoardgameTitle('Set Binari Curvi')).toBe(true)
  expect(isNonBoardgameTitle('Pista di Biglie Deluxe')).toBe(true)
  expect(isNonBoardgameTitle('CreArt Cavalli')).toBe(true)
  expect(isNonBoardgameTitle('tiptoi Penna Interattiva')).toBe(true)
  expect(isNonBoardgameTitle('Dragon Shield Bustine Protettive Matte')).toBe(true)
  expect(isNonBoardgameTitle('Buste Protettive Standard 100pz')).toBe(true)
  expect(isNonBoardgameTitle('Katana Sleeves Transparent')).toBe(true)
  expect(isNonBoardgameTitle('Gamegenic Playmat Nero')).toBe(true)
  expect(isNonBoardgameTitle('Tappetino da Gioco Lorcana')).toBe(true)
  expect(isNonBoardgameTitle('Portamazzo Sidewinder')).toBe(true)
  expect(isNonBoardgameTitle('Ultimate Guard Deck Box 100+')).toBe(true)
  expect(isNonBoardgameTitle('Gift Card 50€')).toBe(true)
  expect(isNonBoardgameTitle('Buono Regalo Digitale')).toBe(true)
  expect(isNonBoardgameTitle('Star Wars Unlimited Booster Pack')).toBe(true)
  expect(isNonBoardgameTitle('Magic Prerelease Box')).toBe(true)
  expect(isNonBoardgameTitle('Display 24 Buste Pokémon')).toBe(true)
  expect(isNonBoardgameTitle('Chessex Dice Set 7 dadi')).toBe(true)
  expect(isNonBoardgameTitle('Set 12 Dadi 16mm')).toBe(true)
  expect(isNonBoardgameTitle('Dadi Custom Fazione')).toBe(true)

  // Real board games -> false
  expect(isNonBoardgameTitle('Ra: The Dice Game')).toBe(false)
  expect(isNonBoardgameTitle('7 Wonders Dice')).toBe(false)
  expect(isNonBoardgameTitle('Florenza – Dice Game')).toBe(false)
  expect(isNonBoardgameTitle('Racket Dice')).toBe(false)
  expect(isNonBoardgameTitle('Dune: Imperium – Rivolta')).toBe(false)
  expect(isNonBoardgameTitle('Ticket to Ride: Europa')).toBe(false)
  expect(isNonBoardgameTitle('Heat: Pedal to the Metal')).toBe(false)
})
