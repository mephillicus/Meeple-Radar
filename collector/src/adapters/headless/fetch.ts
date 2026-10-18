import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { USER_AGENT, type TextFetcher } from '../http.js'
import { createRelayFetcher } from '../relay.js'

const execFileAsync = promisify(execFile)

const MAX_BUFFER = 64 * 1024 * 1024

// Le fonti dietro Cloudflare (uplay, Ravensburger, DungeonDice, Magic Merchant) rispondono
// 403 alla fetch di Node *e* al browser headless, ma accettano `curl` (fingerprint TLS
// diverso). Questo fetcher lancia `curl` in un sottoprocesso. `-f` fa fallire la richiesta
// su HTTP >= 400 (stdout vuoto) -> null.
export function createCurlFetcher(binary = 'curl'): TextFetcher {
  return async (url) => {
    try {
      const { stdout } = await execFileAsync(
        binary,
        ['-fsSL', '--compressed', '-A', USER_AGENT, '--max-time', '45', url],
        { encoding: 'utf8', maxBuffer: MAX_BUFFER },
      )
      return stdout || null
    } catch {
      return null
    }
  }
}

// Pagina di challenge Cloudflare servita con HTTP 200: il contenuto non è quello reale.
const CHALLENGE_RE = /Just a moment|cf-mitigated|Attention Required|Enable JavaScript and cookies|Checking your browser/i

// Prima `curl` (IP residenziale: veloce e senza terze parti). Se fallisce o riceve una
// challenge, ripiega sul reader Jina AI, che passa anche dagli IP datacenter della CI.
export function createCloudflareFetcher(
  direct: TextFetcher = createCurlFetcher(),
  relay: TextFetcher = createRelayFetcher(),
): TextFetcher {
  return async (url) => {
    const html = await direct(url)
    if (html && !CHALLENGE_RE.test(html)) return html
    return await relay(url)
  }
}

let shared: TextFetcher | undefined

export function getSharedFetcher(): TextFetcher {
  if (!shared) shared = createCloudflareFetcher()
  return shared
}
