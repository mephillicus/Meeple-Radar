import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { USER_AGENT, type TextFetcher } from '../http.js'

const execFileAsync = promisify(execFile)

const MAX_BUFFER = 64 * 1024 * 1024

// Le fonti dietro Cloudflare (Ravensburger, DungeonDice, Magic Merchant) rispondono
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

let shared: TextFetcher | undefined

export function getSharedFetcher(): TextFetcher {
  if (!shared) shared = createCurlFetcher()
  return shared
}
