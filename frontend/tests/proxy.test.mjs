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

test('development proxy reports connection refusal distinctly while preserving actual backend HTTP errors', async () => {
  const previousBackend = process.env.BACKEND_URL
  let vite
  const upstream = createHttpServer((_request, response) => {
    response.writeHead(503, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ code: 'ACTUAL_BACKEND_ERROR', message: 'synthetic failure', fieldErrors: {} }))
  })
  try {
    upstream.listen(0, '127.0.0.1')
    await once(upstream, 'listening')
    process.env.BACKEND_URL = `http://127.0.0.1:${upstream.address().port}`
    vite = await createServer({
      mode: 'test', logLevel: 'silent',
      server: { host: '127.0.0.1', port: 0, open: false },
      optimizeDeps: { noDiscovery: true, include: [] },
    })
    await vite.listen()
    const url = `http://127.0.0.1:${vite.httpServer.address().port}/api/interests/options`
    const actual = await fetch(url)
    assert.equal(actual.status, 503)
    assert.equal((await actual.json()).code, 'ACTUAL_BACKEND_ERROR')
    await new Promise((resolve, reject) => upstream.close((error) => error ? reject(error) : resolve()))
    const unavailable = await fetch(url)
    assert.equal(unavailable.status, 502)
    assert.deepEqual(await unavailable.json(), {
      code: 'DEV_BACKEND_UNAVAILABLE', message: '개발 백엔드 연결이 거부되었습니다.', fieldErrors: {},
    })
  } finally {
    await vite?.close()
    if (upstream.listening) await new Promise((resolve) => upstream.close(resolve))
    if (previousBackend === undefined) delete process.env.BACKEND_URL
    else process.env.BACKEND_URL = previousBackend
  }
})

test('OAuth proxy preserves frontend Host, callback cookies and redirects; explicit result HTML and login route stay frontend-owned', async () => {
  const previousBackend = process.env.BACKEND_URL
  let vite
  const paths = []
  const upstream = createHttpServer((request, response) => {
    paths.push(request.url)
    if (request.url.startsWith('/oauth2/authorization/kakao')) {
      response.setHeader('Set-Cookie', 'JSESSIONID=synthetic-only; Path=/; HttpOnly; SameSite=Lax')
      response.writeHead(302, { Location: `https://kauth.kakao.com/oauth/authorize?${new URLSearchParams({ redirect_uri: `http://${request.headers.host}/login/oauth2/code/kakao` })}` })
    } else {
      assert.equal(request.headers.cookie, 'JSESSIONID=synthetic-only')
      response.setHeader('Set-Cookie', ['access_token=synthetic-only; Path=/; HttpOnly; SameSite=Lax', 'refresh_token=synthetic-only; Path=/; HttpOnly; SameSite=Lax'])
      response.writeHead(302, { Location: '/login-success.html' })
    }
    response.end()
  })
  try {
    upstream.listen(0, '127.0.0.1'); await once(upstream, 'listening')
    process.env.BACKEND_URL = `http://127.0.0.1:${upstream.address().port}`
    vite = await createServer({ mode: 'test', server: { host: '127.0.0.1', port: 0, open: false }, optimizeDeps: { noDiscovery: true, include: [] } })
    await vite.listen()
    const origin = `http://127.0.0.1:${vite.httpServer.address().port}`
    const start = await fetch(`${origin}/oauth2/authorization/kakao`, { redirect: 'manual' })
    assert.equal(start.status, 302)
    assert.equal(new URL(start.headers.get('location')).searchParams.get('redirect_uri'), `${origin}/login/oauth2/code/kakao`)
    assert.match(start.headers.get('set-cookie'), /JSESSIONID=synthetic-only/)
    const callback = await fetch(`${origin}/login/oauth2/code/kakao?code=synthetic&state=synthetic`, { redirect: 'manual', headers: { Cookie: 'JSESSIONID=synthetic-only' } })
    assert.equal(callback.status, 302)
    assert.equal(callback.headers.get('location'), '/login-success.html')
    assert.match(callback.headers.get('set-cookie'), /access_token=synthetic-only/)
    assert.match(callback.headers.get('set-cookie'), /refresh_token=synthetic-only/)
    for (const [path, title] of [['/login-success.html', 'Y:ON 로그인 확인'], ['/login-failure.html', 'Y:ON 로그인 실패']]) {
      const html = await fetch(origin + path, { headers: { Accept: 'text/html' } })
      assert.equal(html.status, 200)
      assert.ok((await html.text()).includes(`<title>${title}</title>`))
    }
    assert.equal((await fetch(`${origin}/login`, { headers: { Accept: 'text/html' } })).status, 200)
    assert.equal(paths.length, 2)
    const loaded = await loadConfigFromFile({ command: 'build', mode: 'test' })
    assert.match(loaded.config.build.rolldownOptions.input.loginSuccess, /login-success\.html$/)
    assert.match(loaded.config.build.rolldownOptions.input.loginFailure, /login-failure\.html$/)
  } finally {
    await vite?.close()
    if (upstream.listening) await new Promise((resolve) => upstream.close(resolve))
    if (previousBackend === undefined) delete process.env.BACKEND_URL
    else process.env.BACKEND_URL = previousBackend
  }
})
