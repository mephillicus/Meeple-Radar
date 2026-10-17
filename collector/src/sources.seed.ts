import type { Source } from './types.js'

export const SOURCES: Source[] = [
  { id: 'asmodee',       name: 'Asmodee Italia',       kind: 'html',        baseUrl: 'https://www.asmodee.it',           enabled: true },
  { id: 'cranio',        name: 'Cranio Creations',     kind: 'shopify',     baseUrl: 'https://craniocreations.it',       enabled: true },
  { id: 'ravensburger',  name: 'Ravensburger',         kind: 'headless',    baseUrl: 'https://www.ravensburger.it',      enabled: true },  // Cloudflare: fetch/browser bloccati, raccolto via curl (spec in html/ravensburger.ts)
  { id: 'giochiuniti',   name: 'Giochi Uniti',         kind: 'html',        baseUrl: 'https://www.giochiuniti.it',       enabled: true },
  { id: 'ghenos',        name: 'Ghenos Games',         kind: 'wordpress',   baseUrl: 'https://www.ghenosgames.com',      enabled: true },
  { id: 'pendragon',     name: 'Pendragon Game Studio', kind: 'woocommerce', baseUrl: 'https://www.pendragongamestudio.com', enabled: true },
  { id: 'stonemaier',    name: 'Stonemaier Games',     kind: 'wordpress',   baseUrl: 'https://stonemaiergames.com',      enabled: true },  // WordPress custom post type 'smg_game' (route wp/v2/smg_game)
  { id: 'kosmos',        name: 'Kosmos',               kind: 'shopify',     baseUrl: 'https://www.kosmos.de',            enabled: true },
  { id: 'dungeondice',   name: 'DungeonDice',          kind: 'headless',    baseUrl: 'https://www.dungeondice.it',       enabled: true },  // Cloudflare: fetch/browser bloccati, raccolto via curl (spec in html/dungeondice.ts)
  { id: 'magicmerchant', name: 'Magic Merchant',       kind: 'headless',    baseUrl: 'https://magicmerchant.it',         enabled: false }, // Cloudflare managed challenge: blocca anche curl → resta disabilitata
  { id: 'uplay',         name: 'uplay.it',             kind: 'html',        baseUrl: 'https://www.uplay.it',             enabled: true },
  { id: 'boardhouse',    name: 'BoardHouse Store',     kind: 'shopify',     baseUrl: 'https://boardhousestore.com',      enabled: true },
  { id: 'philibert',     name: 'Philibert',            kind: 'html',        baseUrl: 'https://www.philibertnet.com',     enabled: true },
  { id: 'gamesacademy',  name: 'Games Academy / Funside', kind: 'shopify', baseUrl: 'https://shop.gamesacademy.it',     enabled: true },  // lo shop è un dominio Shopify separato
]
