import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import axios, { AxiosError } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'

// 두 Axios 인스턴스가 생성되기 전에 테스트 전용 adapter를 주입한다.
const originalAdapter = axios.defaults.adapter
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
let adapter
axios.defaults.adapter = (config) => adapter(config)
const { apiClient, resetAuthRecovery } = await import('../src/api/client.ts')
const { getCurrentUser, isAuthUser } = await import('../src/api/auth.ts')
const { authQueryKey, authQueryOptions, createAuthActions } = await import('../src/auth/session.ts')
const { safeReturnPath, loginPath, saveLoginReturn, consumeLoginReturn, peekLoginReturn } = await import('../src/auth/returnPath.ts')
axios.defaults.adapter = originalAdapter
afterEach(() => {
  resetAuthRecovery() // 다음 테스트에만 새 인증 세션을 제공한다.
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else delete globalThis.document
  if (originalStorage) Object.defineProperty(globalThis, 'sessionStorage', originalStorage)
  else delete globalThis.sessionStorage
})
const user = { id: 11, nickname: '테스트 사용자', email: null, role: 'USER' }
const response = (config, data, status = 200) => ({ config, data, status, statusText: '', headers: {} })
function http(config, status, code) { throw new AxiosError('private hidden', 'ERR_BAD_RESPONSE', config, {}, response(config, { code, message: 'test only', fieldErrors: {} }, status)) }
function cookie(value = 'XSRF-TOKEN=synthetic-csrf') { Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: value } }) }
function client() { return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } }) }
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done }); return { resolve, promise } }

test('user lookup validates real fields, uses auth recovery normally and distinguishes anonymous 401 from network/server errors', async () => {
  cookie()
  const paths = []
  adapter = async (config) => {
    paths.push(config.url)
    if (config.url === '/users/me') { assert.equal(config.requiresAuth, true); return http(config, 401, 'AUTH_006') }
    assert.equal(config.url, '/auth/reissue'); return http(config, 401, 'AUTH_001')
  }
  assert.equal(await getCurrentUser(), null)
  assert.deepEqual(paths, ['/users/me', '/auth/reissue'])
  for (const failure of ['network', 'server']) {
    adapter = async (config) => failure === 'network' ? Promise.reject(new AxiosError('private', 'ERR_NETWORK', config, {})) : http(config, 503, 'TEST')
    await assert.rejects(getCurrentUser(), (error) => failure === 'network' ? error.kind === 'network' : error.status === 503)
  }
  adapter = async (config) => response(config, user)
  assert.deepEqual(await getCurrentUser(), user)
  assert.equal(isAuthUser({ id: 12, nickname: null, email: null, role: 'ADMIN' }), true)
  for (const bad of [null, { ...user, id: '11' }, { ...user, role: 'SUPERUSER' }, { ...user, nickname: 3 }]) assert.equal(isAuthUser(bad), false)
  adapter = async (config) => response(config, { accessToken: 'test-only-not-a-user' })
  await assert.rejects(getCurrentUser(), (error) => error.kind === 'unknown')
})

test('OAuth probe does not recover or reset on a redirect/failed verification; verified 200 then releases old terminal recovery failure', async () => {
  cookie()
  let rotations = 0, validCookie = false, canReissue = false, reissued = false
  adapter = async (config) => {
    if (config.url === '/auth/reissue') {
      rotations++
      if (!canReissue) return http(config, 401, 'AUTH_002')
      reissued = true; return response(config, '', 200)
    }
    assert.equal(config.url, '/users/me')
    if (validCookie || reissued) return response(config, user)
    return http(config, 401, 'AUTH_007')
  }
  assert.equal(await getCurrentUser(), null)
  assert.equal(rotations, 1)
  const store = client(), actions = createAuthActions(store)
  try {
    await assert.rejects(actions.completeOAuth(), (error) => error.status === 401)
    assert.equal(store.getQueryData(authQueryKey), undefined)
    assert.equal(await getCurrentUser(), null)
    assert.equal(rotations, 1, '실패한 OAuth 확인이 재발급 상태를 초기화하면 안 된다')
    validCookie = true
    await actions.completeOAuth()
    assert.deepEqual(store.getQueryData(authQueryKey), user)
    validCookie = false; canReissue = true
    assert.deepEqual(await getCurrentUser(), user)
    assert.equal(rotations, 2, '유효한 사용자 확인 뒤에는 이전 실패에서 복구한다')
  } finally { store.clear() }
})

test('parallel OAuth completion shares one verification and clears all personalized caches while retaining public options', async () => {
  const started = deferred(), finish = deferred()
  let calls = 0
  adapter = async (config) => { calls++; assert.equal(config.requiresAuth, false); started.resolve(); await finish.promise; return response(config, user) }
  const store = client(), actions = createAuthActions(store)
  for (const key of [['recommendations', 11], ['opportunities', 'page=2'], ['opportunity-detail', '42'], ['home', 'opportunities', 'size=6']]) store.setQueryData(key, ['old account data'])
  store.setQueryData(['interest-options'], { categories: [] })
  try {
    const first = actions.completeOAuth(), second = actions.completeOAuth()
    assert.equal(first, second)
    await started.promise
    assert.equal(store.getQueryData(authQueryKey), undefined)
    finish.resolve(); await Promise.all([first, second])
    assert.equal(calls, 1)
    assert.equal(store.getQueryData(['recommendations', 11]), undefined)
    assert.equal(store.getQueryData(['opportunities', 'page=2']), undefined)
    assert.equal(store.getQueryData(['opportunity-detail', '42']), undefined)
    assert.equal(store.getQueryData(['home', 'opportunities', 'size=6']), undefined)
    assert.deepEqual(store.getQueryData(['interest-options']), { categories: [] })
  } finally { finish.resolve(); store.clear() }
})

test('logout bootstraps CSRF, requires exact 204, deduplicates clicks and clears confirmed user/cache only after success', async () => {
  cookie('')
  const started = deferred(), finish = deferred(), paths = []
  adapter = async (config) => {
    paths.push(config.url)
    if (config.url === '/interests/options') { globalThis.document.cookie = 'XSRF-TOKEN=new-csrf'; return response(config, {}) }
    assert.equal(config.url, '/auth/logout')
    assert.equal(config.requiresAuth, true)
    assert.equal(config.headers.get('X-XSRF-TOKEN'), 'new-csrf')
    started.resolve(); await finish.promise; return response(config, '', 204)
  }
  const store = client(), actions = createAuthActions(store)
  store.setQueryData(authQueryKey, user); store.setQueryData(['recommendations', 11], ['private'])
  try {
    const first = actions.logout(), second = actions.logout()
    assert.equal(first, second)
    await started.promise
    assert.deepEqual(store.getQueryData(authQueryKey), user)
    finish.resolve(); await first
    assert.equal(store.getQueryData(authQueryKey), null)
    assert.equal(store.getQueryData(['recommendations', 11]), undefined)
    assert.deepEqual(paths, ['/interests/options', '/auth/logout'])
  } finally { finish.resolve(); store.clear() }
})

test('logout 401/403/network/incorrect success status do not claim logout or trigger recursive reissue', async () => {
  cookie()
  for (const status of [401, 403, 'network', 200]) {
    const paths = [], store = client()
    store.setQueryData(authQueryKey, user)
    adapter = async (config) => {
      paths.push(config.url)
      if (status === 'network') throw new AxiosError('private', 'ERR_NETWORK', config, {})
      if (status === 200) return response(config, {}, 200)
      return http(config, status, 'AUTH_007')
    }
    try {
      await assert.rejects(createAuthActions(store).logout())
      assert.deepEqual(store.getQueryData(authQueryKey), user)
      assert.deepEqual(paths, status === 'network' || status === 200 ? ['/auth/logout', '/users/me'] : ['/auth/logout'])
    } finally { store.clear() }
  }
})

test('confirmed logout rejects late previous-session 401 instead of rotating tokens into the next session', async () => {
  cookie()
  const started = deferred(), finish = deferred()
  let rotations = 0
  adapter = async (config) => {
    if (config.url === '/auth/logout') return response(config, '', 204)
    if (config.url === '/auth/reissue') { rotations++; return response(config, '') }
    started.resolve(); await finish.promise; return http(config, 401, 'AUTH_007')
  }
  const old = apiClient.get('/users/me/late-test', { requiresAuth: true }).catch((error) => error)
  const store = client()
  try {
    await started.promise; await createAuthActions(store).logout()
    finish.resolve()
    assert.equal((await old).kind, 'cancelled')
    assert.equal(rotations, 0)
  } finally { finish.resolve(); store.clear() }
})

test('identity change and verified anonymous state reset public personalized data; network uncertainty preserves last known session', async () => {
  cookie()
  const store = client(), signal = new AbortController().signal
  try {
    store.setQueryData(authQueryKey, user)
    store.setQueryData(['opportunity-detail', '42'], { saved: true })
    adapter = async (config) => response(config, { ...user, id: 22 })
    assert.equal((await authQueryOptions(store).queryFn({ signal })).id, 22)
    assert.equal(store.getQueryData(['opportunity-detail', '42']), undefined)
    store.setQueryData(['opportunities', ''], { private: 'last known' })
    adapter = async (config) => { throw new AxiosError('private', 'ERR_NETWORK', config, {}) }
    await assert.rejects(authQueryOptions(store).queryFn({ signal }))
    assert.deepEqual(store.getQueryData(authQueryKey), user)
    assert.deepEqual(store.getQueryData(['opportunities', '']), { private: 'last known' })
    adapter = async (config) => http(config, 401, config.url === '/auth/reissue' ? 'AUTH_001' : 'AUTH_006')
    assert.equal(await authQueryOptions(store).queryFn({ signal }), null)
    assert.equal(store.getQueryData(['opportunities', '']), undefined)
  } finally { store.clear() }
})

test('same-user OAuth login resets active public observers and prevents a cancelled late response restoring previous session data', async () => {
  const store = client(), started = deferred(), finish = deferred(), refreshed = deferred()
  const key = ['opportunity-detail', '42']
  let calls = 0, oldSignal
  store.setQueryData(authQueryKey, user)
  store.setQueryData(key, { saved: true, owner: 'old-session' })
  const observer = new QueryObserver(store, { queryKey: key, queryFn: async ({ signal }) => {
    calls++
    if (calls === 1) { oldSignal = signal; started.resolve(); await finish.promise; return { saved: true, owner: 'old-session' } }
    return { saved: false, owner: 'new-session' }
  } })
  const unsubscribe = observer.subscribe((result) => { if (result.data?.owner === 'new-session') refreshed.resolve() })
  adapter = async (config) => response(config, user)
  try {
    await started.promise
    await createAuthActions(store).completeOAuth()
    await refreshed.promise
    assert.equal(oldSignal.aborted, true)
    finish.resolve(); await new Promise((resolve) => setImmediate(resolve))
    assert.deepEqual(observer.getCurrentResult().data, { saved: false, owner: 'new-session' })
  } finally { finish.resolve(); unsubscribe(); store.clear() }
})

test('login return only keeps allowlisted internal routes and canonical search keys without tokens or callback loops', () => {
  for (const path of ['https://evil.example', '//evil.example', '/\\evil.example', '/login', '/login-success.html', '/login-failure.html', '/oauth2/authorization/kakao', '/login/oauth2/code/kakao', '/%2f%2fevil.example', '/bad', '/opportunities\n']) assert.equal(safeReturnPath(path), '/')
  assert.equal(safeReturnPath('/opportunities?keyword=청년&page=2&access_token=not-a-real-token'), '/opportunities?keyword=%EC%B2%AD%EB%85%84&page=2')
  assert.equal(safeReturnPath('/opportunities/42?token=not-a-real-token'), '/opportunities/42')
  assert.equal(safeReturnPath('/saved?refresh_token=test-only'), '/saved')
  assert.equal(new URL(loginPath('/opportunities?page=2'), 'https://example.org').searchParams.get('returnTo'), '/opportunities?page=2')
})

test('session storage holds only a validated return path, consumes it once and tolerates blocked storage', () => {
  const values = new Map()
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: { setItem: (key, value) => values.set(key, value), getItem: (key) => values.get(key), removeItem: (key) => values.delete(key) } })
  saveLoginReturn('/opportunities?page=2')
  assert.equal(peekLoginReturn(), '/opportunities?page=2')
  assert.deepEqual([...values.values()], ['/opportunities?page=2'])
  assert.equal(consumeLoginReturn(), '/opportunities?page=2')
  assert.equal(consumeLoginReturn(), '/')
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, get: () => { throw new Error('blocked') } })
  assert.doesNotThrow(() => saveLoginReturn('/saved'))
  assert.equal(consumeLoginReturn(), '/')
})
