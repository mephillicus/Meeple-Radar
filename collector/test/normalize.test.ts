import { expect, test } from 'vitest'
import {
  areTitlesNearDuplicate,
  isNonBoardgameBrand,
  isNonBoardgameTitle,
  normalizeAvailability,
  toPrice,
  extractReleaseDate,
  normalizeListing,
  normalizeTitle,
} from '../src/normalize.js'
import { decodeHtml, htmlToText } from '../src/text.js'
import { isNonBoardgameProduct } from '../src/bgg/enrich.js'

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

test('normalizeTitle uniforma la congiunzione & circondata da spazi a "e" preservando sigle compatte', () => {
  expect(normalizeTitle('Ahoy: Rivali & Rinnegati')).toBe('ahoy rivali e rinnegati')
  expect(normalizeTitle('Ahoy - Rivali e Rinnegati')).toBe('ahoy rivali e rinnegati')
  expect(normalizeTitle('Ahoy: Fauci & Fortuna')).toBe('ahoy fauci e fortuna')
  expect(normalizeTitle('Ahoy - Fauci E Fortuna')).toBe('ahoy fauci e fortuna')
  expect(normalizeTitle('D&D')).toBe('dd')
  expect(normalizeTitle('Cat&Mouse')).toBe('catmouse')
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

  const reprintListing = normalizeListing('cranio', {
    externalId: '3',
    title: 'Bookshelf',
    url: 'https://e.com/bookshelf',
    availabilityRaw: 'https://schema.org/OutOfStock',
    descriptionRaw: '<p>Prodotto esaurito, in rientro nella primavera 2027.</p>',
  })
  expect(reprintListing.availability).toBe('reprint')
  expect(reprintListing.reprintDate).toBe('2027-04-15')
  expect(reprintListing.releaseDate).toBeNull()

  const dvgiochiNoReprint = normalizeListing('dvgiochi', {
    externalId: '4',
    title: 'Bookshelf',
    url: 'https://www.dvgiochi.com/catalogo/bookshelf/',
    availabilityRaw: 'false',
    descriptionRaw: '<p>In rientro nella primavera 2027.</p>',
  })
  expect(dvgiochiNoReprint.reprintDate).toBeNull()
  expect(dvgiochiNoReprint.availability).toBe('out_of_stock')
})

test('normalizeAvailability gestisce reprint, ristampa, riassortimento e promozione con reprintDate futura', () => {
  const now = new Date('2026-06-15T12:00:00Z')
  expect(normalizeAvailability('reprint', null, now)).toBe('reprint')
  expect(normalizeAvailability('ristampa', null, now)).toBe('reprint')
  expect(normalizeAvailability('riassortimento', null, now)).toBe('reprint')

  // reprintDate strettamente futura promuove out_of_stock, backorder e null -> reprint
  expect(normalizeAvailability('OutOfStock', null, now, '2027-04-15')).toBe('reprint')
  expect(normalizeAvailability('BackOrder', null, now, '2027-04-15')).toBe('reprint')
  expect(normalizeAvailability(null, null, now, '2027-04-15')).toBe('reprint')
  // Non sovrascrive available, preorder o discontinued
  expect(normalizeAvailability('InStock', null, now, '2027-04-15')).toBe('available')
  expect(normalizeAvailability('PreOrder', null, now, '2027-04-15')).toBe('preorder')
  expect(normalizeAvailability('Discontinued', null, now, '2027-04-15')).toBe('discontinued')
  // reprintDate passata o odierna non promuove
  expect(normalizeAvailability('OutOfStock', null, now, '2026-06-15')).toBe('out_of_stock')
  expect(normalizeAvailability('OutOfStock', null, now, '2026-06-10')).toBe('out_of_stock')
})

test('isNonBoardgameTitle filtra merch, sleeves, puzzle, TCG, RPG/GDR e accessori dadi', () => {
  const nonBoardgames = [
    // Merch & Gadget: abbigliamento e accessori
    'T-Shirt Meeple Nera L',
    'Tshirt Catan Limited',
    'Maglietta Dungeons & Dragons',
    'Magliette Ufficiali Lucca',
    'Felpa con Cappuccio Meeple',
    'Cappellino Pokémon Pikachu',
    'Tazza Ceramica D20',
    'Mug Star Wars Grogu',
    'Shopper Cotone Boardgame',
    'Portachiavi Metallo D20',
    'Spilla Smaltata Meeple',
    'Poster Mappa della Terra di Mezzo',
    'Peluche Cthulhu 25cm',
    'Plush Eevee Official',
    'Funko Pop! Demogorgon',
    'Lampada LED D20',
    "Calendario dell'Avvento EXIT",
    'Calendario dell’Avvento Pokémon',
    // Merch & Gadget: sticker, biglietti, voucher e cancelleria
    'Set Sticker Catan',
    'Sticker Pack Meeple',
    'Adesivo Vinile D20',
    'Biglietto Auguri Compleanno Gamer',
    'Gift Card 50€',
    'Giftcard Digitale 25€',
    'Buono Regalo Digitale',
    'Set Cancelleria Harry Potter',
    // Sleeves, Bustine, Binder, Playmat, Deck Box
    'Dragon Shield Bustine Protettive Matte',
    'Bustina Standard 63x88',
    'Buste Protettive Standard 100pz',
    'Box 24 Buste Magic',
    'Blister 3 Buste Lorcana',
    'Katana Sleeves Transparent',
    'Sleeve Perfect Fit',
    'Foderine Trasparenti Mini Euro',
    'Ultimate Guard Zipfolio Binder 9-Pocket',
    'Album Porta Carte Pokémon',
    'Portamazzo Sidewinder',
    'Portamazzi Doppio Nero',
    'Ultimate Guard Deck Box 100+',
    'Deckbox Boulder 80+',
    'Gamegenic Playmat Nero',
    'Play Mat Neoprene 60x35',
    'Tappetino da Gioco Lorcana',
    'Tappetini Neoprene Set',
    // Puzzle & Giocattoli
    'Ravensburger Puzzle 1000 Pezzi - Venezia',
    'Puzzle 3D Torre Eiffel',
    'Puzzle 2D Mappa Mondo',
    'Puzzle da Viaggio 500',
    'Boardgame Puzzle Catan',
    'GraviTrax Starter Set',
    'BRIO Treno Merci a Batteria',
    'Treno ad Alta Velocità',
    'Treno della Giungla',
    'Set Binari Curvi',
    'Pista di Biglie Deluxe',
    'Action Figure Geralt of Rivia',
    'CreArt Cavalli',
    'tiptoi Penna Interattiva',
    // TCG Packs / Displays / Starter Decks
    'Star Wars Unlimited Booster Pack',
    'One Piece Booster Box OP-09',
    'Lorcana Booster Display',
    'Pokémon Booster Set',
    'One Piece Extra Booster EB-01',
    'Magic Booster (36)',
    'Magic Prerelease Box',
    'Star Wars Unlimited Spotlight Deck',
    'Lorcana Starter Deck',
    'Yu-Gi-Oh Structure Deck',
    'Pokémon Theme Deck',
    'Display 24 Buste Pokémon',
    'Pokémon Elite Trainer Box',
    'Tin Box Collezionabile Pikachu',
    // Painting Minis & RPG / GDR
    "D&D Nolzur's Marvelous Minis: Beholder",
    'Nolzurs Marvelous Minis Elf Wizard',
    'WizKids Deep Cuts Minis: Treasure Chest',
    'Fabula Ultima GDR',
    'Brancalonia Gioco di Ruolo',
    'Schermo del Master D&D 5e',
    'Schermo del GM Cyberpunk',
    'Schermo del Narratore Vampiri',
    'Kit del Game Master Pathfinder',
    'Manuale Base Fabula Ultima',
    'Manuale del Giocatore 5e',
    'Manuale GDR Blade Runner',
    'Manuale RPG Alien',
    'Modulo Avventura La Miniera Perduta',
    'Avventura GDR Il Risveglio',
    'Cthulhu RPG Corebook',
    // Dice Towers, Dice Trays, Dice Bags & Dice Sets
    'Torre Lancia Dadi in Legno',
    'Torre Dadi Pieghevole',
    'Dice Tower Castello Medievale',
    'Gamegenic Dice Tray Esagonale',
    'Vassoio Lancia Dadi Neoprene',
    'Vassoio Porta Dadi Pelle',
    'Vassoi Dadi Set',
    'Dice Bag Velluto Nero',
    'Dice Pouch Pelle',
    'Sacchetto Portadadi Runico',
    'Sacchetto Dadi Cotone',
    'Chessex Dice Set 7 dadi',
    'Chessex Dice Block 36 dadi',
    'Set 12 Dadi 16mm',
    'Dadi Custom Fazione',
  ]

  for (const title of nonBoardgames) {
    expect(isNonBoardgameTitle(title), `expected non-boardgame: ${title}`).toBe(true)
    expect(isNonBoardgameProduct(title), `expected delegate match: ${title}`).toBe(true)
  }
})

test('isNonBoardgameTitle preserva organizer, upgrade/monete e tutti i giochi da tavolo protetti', () => {
  const keptTitles = [
    // Organizer / Inserti (esplicitamente mantenuti)
    'CASCADIA – Organizer',
    'Ants - Bundle Organizer',
    'SETI - Super Bundle Organizer',
    // Upgrade Kit / Monete in Metallo / Risorse Deluxe (esplicitamente mantenuti)
    'Kutnà Hora - Monete in Metallo',
    'SETI - Upgrade Kit',
    'Scythe – Metal Coins $2',
    'Scythe – Risorse Realistiche',
    'Tiny Turbo Cars – Wooden Tokens Upgrade Pack',
    // Giochi da tavolo protetti contro falsi positivi
    'Ticket to Ride - Europa',
    'Carcassonne - Cacciatori e Raccoglitori',
    'Calzini Spaiati',
    'Astoria – La Ferrovia degli Animali',
    'Horrified - Dungeons & Dragons - Ravenloft',
    'Flock Together',
    'Ra: The Dice Game',
    '7 Wonders Dice',
    'Florenza – Dice Game',
    'Ratti di Wistar - The Dice Tower Invention Card',
    // Ulteriori giochi reali & promo Dice Tower
    'Racket Dice',
    'Dune: Imperium – Rivolta',
    'Heat: Pedal to the Metal',
    'Wingspan - The Dice Tower Promo',
    'Cascadia - Dice Tower Card',
  ]

  for (const title of keptTitles) {
    expect(isNonBoardgameTitle(title), `expected kept boardgame/accessory: ${title}`).toBe(false)
    expect(isNonBoardgameProduct(title), `expected delegate false: ${title}`).toBe(false)
  }
})

test('isNonBoardgameBrand filtra brand non-boardgame e gestisce eccezioni LEGO', () => {
  const blockedBrands = [
    'PANTASY',
    '  pantasy  ',
    'Lemax',
    'Funko',
    'Jakks Pacific',
    'Ultimate Guard',
    'Gamegenic',
    'Dragon Shield',
    'Ultra Pro',
    'Chessex',
    'Q-Workshop',
    'WizKids',
    'Vallejo',
    'The Army Painter',
    'Army Painter',
    'Citadel',
    'Paladone',
    'ABYstyle',
    'Banpresto',
    'Noble Collection',
    'Nemesis Now',
    'NECA',
    'Keeppley',
    'Bandai',
    'Funism',
    'Monogram',
    'Good Smile',
    'Good Smile Company',
    'MegaHouse',
    'Sega',
    'FuRyu',
    'Taito',
    'Two Dots',
    'ToyLab',
    'Zuru',
    'Waboba',
    'Playmobil',
    'MGA Entertainment',
    'Giochi Preziosi',
    'Bburago',
    'Jada',
    'Stopuzzle',
    'SD Toys',
    'Ubisoft',
    'Semic',
    'Cinereplicas',
    'Cosmic Group',
    'Giunti',
  ]
  for (const brand of blockedBrands) {
    expect(isNonBoardgameBrand(brand, 'Prodotto Qualsiasi'), `expected blocked brand: ${brand}`).toBe(true)
  }

  // LEGO: bloccato di default, consentito per giochi da tavolo LEGO
  expect(isNonBoardgameBrand('LEGO', 'LEGO Star Wars Millennium Falcon 75375')).toBe(true)
  expect(isNonBoardgameBrand('LEGO®', undefined)).toBe(true)
  expect(isNonBoardgameBrand('LEGO', 'Monkey Palace - Un Gioco da Tavolo LEGO®')).toBe(false)
  expect(isNonBoardgameBrand('LEGO', "LEGO NINJAGO: Destiny's Bounty Adventures")).toBe(false)
  expect(isNonBoardgameBrand('LEGO', 'LEGO Creationary Board Game')).toBe(false)

  // Editori reali e valori vuoti/assenti
  for (const publisher of ['Salt & Pepper Games', 'Asmodee', 'Cranio Creations', 'dV Giochi', 'Ravensburger', '', '   ', null, undefined]) {
    expect(isNonBoardgameBrand(publisher, 'Un Gioco Da Tavolo'), `expected allowed brand: ${String(publisher)}`).toBe(false)
  }
})

test('isNonBoardgameTitle filtra Pantasy, Lemax, LEGO, nuovi pattern puzzle/dadi preservando giochi protetti', () => {
  const blockedTitles = [
    // Pantasy / modular bricks
    'PAN85001 Retro TV',
    'PAN86228A Bakery',
    'Pantasy Steampunk Clocktower',
    'Edifici Modulari Piazza Centrale',
    // Lemax
    "Lemax Santa's Wonderland",
    'Adattatore 4.5V 3 Uscite',
    'Alimentatore 4.5V Nero',
    // LEGO construction sets
    'LEGO Star Wars Millennium Falcon 75375',
    'LEGO Technic Porsche 911',
    // Puzzles
    '3D Puzzle Torre Eiffel',
    'Puzzle Ball Mondo 540',
    'Puzzle Ravensburger 1000',
    'Maxi Puzzle Frozen',
    '1000 Puzzle Mappa Antica',
    'Puzzle 500p Venezia',
    'GeoPuzzle Europa',
    // Dice accessories
    'Set 7D20 Trasparente',
    '36d6 Chessex Blu',
    'Dice Case Pelle',
    'Dice Pack 10 Dadi',
    'Dice Tube Nero',
    'Dice Tin Metallo',
    'Cofanetto With 12 Dice',
    'Adventure Dice Pack',
    'Set di Dadi Metallici',
    'Set Dadi Blu',
  ]
  for (const title of blockedTitles) {
    expect(isNonBoardgameTitle(title), `expected non-boardgame: ${title}`).toBe(true)
  }

  const protectedTitles = [
    // LEGO board games
    'Monkey Palace - Un Gioco da Tavolo LEGO®',
    "LEGO NINJAGO: Destiny's Bounty Adventures",
    'LEGO Creationary Board Game',
    // Puzzle-named board games
    'The Voynich Puzzle',
    'My Puzzle Adventure',
    // Dice-named board games
    'Dadi & Affetta',
    'Dice Forge',
    'Dice Town',
    'Dice Throne',
    'Dicequest',
    'Sushi Dice',
    '7 Wonders Dice',
    'Dice Scrapers',
  ]
  for (const title of protectedTitles) {
    expect(isNonBoardgameTitle(title), `expected kept boardgame: ${title}`).toBe(false)
  }
})

test('isNonBoardgameTitle filtra Funko varianti, statuette/blind box/bag clip, brick SKU, TCG bundle e puzzle pezzi', () => {
  const blockedTitles = [
    '31214 - ART - LOVE',
    '75403 - STAR WARS - GROGU E CARROZZINA',
    'KP28050 - CITY CORNER - BOUTIQUE DEI PROFUMI MAGICI',
    'FUNKO Bitty POP: Star Wars',
    'Pop Dlx: Lord Of The Rings',
    'Pop Keychain - Grogu 4cm',
    'Carrot Pocket Pop Pdq - Marvel #1',
    'Figurina My Hero Academia (13 cm)',
    "Aza Chobe (Figura) - Hell's Paradise",
    'Uta (Figura) - Q Posket',
    'Kingdom Hearts Bag Clip Blind Box Display (24 pezzi)',
    'Pokemon - Bundle 6 Buste',
    'Ravensburger Minions 3 da 35 pezzi',
    'Puzzle 1000pz World Tour Santorini',
  ]
  for (const title of blockedTitles) {
    expect(isNonBoardgameTitle(title), `expected non-boardgame: ${title}`).toBe(true)
  }

  const protectedTitles = [
    'Monkey Palace',
    "LEGO NINJAGO: Destiny's Bounty Adventures",
    'The Voynich Puzzle',
    'My Puzzle Adventure',
    '7 Wonders Dice',
    'Dadi & Affetta',
    'Ticket to Ride',
    'CASCADIA – Organizer',
    'SETI - Upgrade Kit',
  ]
  for (const title of protectedTitles) {
    expect(isNonBoardgameTitle(title), `expected kept boardgame: ${title}`).toBe(false)
  }
})
