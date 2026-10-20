import type { Source } from './types.js'

export const SOURCES: Source[] = [
  { id: 'asmodee',       name: 'Asmodee Italia',       kind: 'html',        baseUrl: 'https://www.asmodee.it',           enabled: true },
  { id: 'cranio',        name: 'Cranio Creations',     kind: 'shopify',     baseUrl: 'https://craniocreations.it',       enabled: true },
  { id: 'ravensburger',  name: 'Ravensburger',         kind: 'headless',    baseUrl: 'https://www.ravensburger.it',      enabled: false }, // Cloudflare + reader Jina anonimo inaffidabile in CI (quota ~20/min): riabilitare con JINA_API_KEY; spec in html/ravensburger.ts
  { id: 'giochiuniti',   name: 'Giochi Uniti',         kind: 'html',        baseUrl: 'https://www.giochiuniti.it',       enabled: true },
  { id: 'ghenos',        name: 'Ghenos Games',         kind: 'wordpress',   baseUrl: 'https://www.ghenosgames.com',      enabled: true },
  { id: 'pendragon',     name: 'Pendragon Game Studio', kind: 'woocommerce', baseUrl: 'https://www.pendragongamestudio.com', enabled: true },
  { id: 'dungeondice',   name: 'DungeonDice',          kind: 'headless',    baseUrl: 'https://www.dungeondice.it',       enabled: true },  // Cloudflare: risponde 403 a fetch/headless, ma accetta curl; spec in html/dungeondice.ts
  { id: 'magicmerchant', name: 'Magic Merchant',       kind: 'headless',    baseUrl: 'https://magicmerchant.it',         enabled: false }, // Cloudflare managed challenge: blocca fetch/curl e il reader Jina AI → resta disabilitata
  { id: 'uplay',         name: 'uplay.it',             kind: 'headless',    baseUrl: 'https://www.uplay.it',             enabled: false }, // Cloudflare + reader Jina anonimo inaffidabile in CI (quota ~20/min): riabilitare con JINA_API_KEY; spec in html/uplay.ts
  { id: 'boardhouse',    name: 'BoardHouse Store',      kind: 'shopify',    baseUrl: 'https://boardhousestore.com',      collection: 'preordini', enabled: true }, // collection preordini: l'intero catalogo sono ~2800 prodotti
  { id: 'philibert',     name: 'Philibert',            kind: 'html',        baseUrl: 'https://www.philibertnet.com',     enabled: true },
  { id: 'supernova',     name: 'Studio Supernova',      kind: 'shopify',     baseUrl: 'https://www.studiosupernova.it',   enabled: true },   // il dominio senza www ha TLS rotto
  { id: 'littlerocket',  name: 'Little Rocket Games',   kind: 'woocommerce', baseUrl: 'https://www.littlerocketgames.com', enabled: true },
  { id: 'msedizioni',    name: 'MS Edizioni',           kind: 'woocommerce', baseUrl: 'https://www.msedizioni.it',        enabled: true },
  { id: 'dvgiochi',      name: 'dV Games',             kind: 'html',        baseUrl: 'https://www.dvgiochi.com',         enabled: true },
  { id: 'horribleguild', name: 'Horrible Guild',       kind: 'html',        baseUrl: 'https://horribleguild.com',        enabled: true },
  { id: 'gateongames',   name: 'GateOnGames',          kind: 'html',        baseUrl: 'https://www.gateongames.com',     enabled: true },
  { id: 'gemini',        name: 'Gemini',               kind: 'gemini',      baseUrl: 'https://generativelanguage.googleapis.com', enabled: false }, // Senza grounding web Gemini risponde solo di memoria (lista vuota o titoli inventati): il passaggio news→Gemini è in google-news; riabilitare solo se serve un estrattore standalone
  { id: 'google-news',   name: 'Google News',          kind: 'news',        baseUrl: 'https://news.google.com/rss/search', enabled: true }, // RSS delle ricerche in NEWS_QUERIES, poi Gemini estrae i giochi dai titoli
]
