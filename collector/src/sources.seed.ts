import type { Source } from './types.js'

export const SOURCES: Source[] = [
  { id: 'asmodee',       name: 'Asmodee Italia',       kind: 'headless',    baseUrl: 'https://www.asmodee.it',           enabled: false },
  { id: 'cranio',        name: 'Cranio Creations',     kind: 'shopify',     baseUrl: 'https://craniocreations.it',       enabled: true },
  { id: 'ravensburger',  name: 'Ravensburger',         kind: 'html',        baseUrl: 'https://www.ravensburger.org',     enabled: false },
  { id: 'giochiuniti',   name: 'Giochi Uniti',         kind: 'html',        baseUrl: 'https://www.giochiuniti.it',       enabled: false },
  { id: 'ghenos',        name: 'Ghenos Games',         kind: 'wordpress',   baseUrl: 'https://www.ghenosgames.com',      enabled: true },
  { id: 'pendragon',     name: 'Pendragon Game Studio', kind: 'woocommerce', baseUrl: 'https://www.pendragongamestudio.com', enabled: true },
  { id: 'stonemaier',    name: 'Stonemaier Games',     kind: 'wordpress',   baseUrl: 'https://stonemaiergames.com',      enabled: true },
  { id: 'kosmos',        name: 'Kosmos',               kind: 'shopify',     baseUrl: 'https://www.kosmos.de',            enabled: true },
  { id: 'dungeondice',   name: 'DungeonDice',          kind: 'headless',    baseUrl: 'https://www.dungeondice.it',       enabled: false },
  { id: 'magicmerchant', name: 'Magic Merchant',       kind: 'headless',    baseUrl: 'https://magicmerchant.it',         enabled: false },
  { id: 'uplay',         name: 'uplay.it',             kind: 'html',        baseUrl: 'https://www.uplay.it',             enabled: false },
  { id: 'boardhouse',    name: 'BoardHouse Store',     kind: 'shopify',     baseUrl: 'https://boardhousestore.com',      enabled: true },
  { id: 'philibert',     name: 'Philibert',            kind: 'html',        baseUrl: 'https://www.philibertnet.com',     enabled: false },
  { id: 'gamesacademy',  name: 'Games Academy / Funside', kind: 'wordpress', baseUrl: 'https://www.gamesacademy.it',      enabled: true },
]
