import assert from 'node:assert/strict'
import { createServer as createHttpServer } from 'node:http'
import { once } from 'node:events'
import { test } from 'node:test'
import { createServer, loadConfigFromFile } from 'vite'

test('Vite development proxy keeps /api exactly once and forwards cookie headers', async () => {
  const previousBackend = process.env.BACKEND_URL
  let vite
  const upstream = createHttpServer((request, response) => {
    response.setHeader('Content-Type', 'application/json')
    response.setHeader('Set-Cookie', 'XSRF-TOKEN=synthetic-csrf; Path=/')
    response.end(JSON.stringify({ path: request.url, cookie: request.headers.cookie }))
  })

  try {
    process.env.BACKEND_URL = ''
    const loaded = await loadConfigFromFile({ command: 'serve', mode: 'test' })
    assert.equal(loaded.config.server.proxy['/api'].target, 'http://localhost:8080')
    upstream.listen(0, '127.0.0.1')
    await once(upstream, 'listening')
    process.env.BACKEND_URL = `http://127.0.0.1:${upstream.address().port}`
    vite = await createServer({
      mode: 'test',
      server: { host: '127.0.0.1', port: 0, open: false },
      optimizeDeps: { noDiscovery: true, include: [] },
    })
    await vite.listen()
    const port = vite.httpServer.address().port
    const response = await fetch(`http://127.0.0.1:${port}/api/opportunities?keyword=sample`, {
      headers: { Cookie: 'synthetic-session=test-only' },
    })
    assert.equal(response.status, 200)
    assert.deepEqual(await response.json(), {
      path: '/api/opportunities?keyword=sample', cookie: 'synthetic-session=test-only',
    })
    assert.equal(response.headers.get('set-cookie'), 'XSRF-TOKEN=synthetic-csrf; Path=/')
  } finally {
    await vite?.close()
    if (upstream.listening) {
      await new Promise((resolve, reject) => upstream.close((error) => error ? reject(error) : resolve()))
    }
    if (previousBackend === undefined) delete process.env.BACKEND_URL
    else process.env.BACKEND_URL = previousBackend
  }
})
