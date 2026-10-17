import { expect, test } from 'vitest'
import { buildRegistry } from '../../src/adapters/registry.js'

const src = (id: string, kind: any) => ({ id, name: id, kind, baseUrl: 'https://e.com', enabled: true })

test('html route throws a clear error for a source without a spec', async () => {
  const r = buildRegistry(async () => ({ status: 200, json: async () => ({}), text: async () => '' }))
  await expect(r.html(src('nope', 'html'))).rejects.toThrow(/spec HTML/i)
})
