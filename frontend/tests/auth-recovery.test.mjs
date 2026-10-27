import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import axios, { AxiosError } from 'axios'
import { QueryClient, QueryObserver, MutationObserver, focusManager } from '@tanstack/react-query'

// 실제 session/API/Query 규칙을 결합한다. UI 경계의 이동·오류 콜백만 관찰한다.
const originalAdapter = axios.defaults.adapter
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage')
let adapter
axios.defaults.adapter = (config) => adapter(config)
const { resetAuthRecovery } = await import('../src/api/client.ts')
const { getCurrentUser } = await import('../src/api/auth.ts')
const { authQueryKey, authObservationOptions, authStatus, createAuthActions } = await import('../src/auth/session.ts')
const { recommendationsQuery } = await import('../src/api/recommendations.ts')
const { createOAuthCompletionRunner } = await import('../src/auth/oauthCompletion.ts')
const { saveLoginReturn, peekLoginReturn, consumeLoginReturn } = await import('../src/auth/returnPath.ts')
axios.defaults.adapter = originalAdapter
afterEach(() => {
  resetAuthRecovery()
  for (const [key, descriptor] of [['document', originalDocument], ['sessionStorage', originalStorage]]) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor)
    else delete globalThis[key]
  }
})
const user = { id: 11, nickname: null, email: null, role: 'USER' }
const response = (config, data, status = 200) => ({ config, data, status, statusText: '', headers: {} })
function fail(config, status, code) { throw new AxiosError('test only', 'ERR_BAD_RESPONSE', config, {}, response(config, { code, message: 'test only', fieldErrors: {} }, status)) }
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done }); return { resolve, promise } }
const tick = () => new Promise((resolve) => setImmediate(resolve))
function browserState() {
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=synthetic' } })
  const values = new Map()
  Object.defineProperty(globalThis, 'sessionStorage', { configurable: true, value: { getItem: (key) => values.get(key), setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) } })
}

// Provider가 실제로 사용하는 options/status를 MutationObserver와 QueryObserver에 연결한다.
function harness({ oauthPage = false, cachedUser = user } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } } })
  if (cachedUser !== undefined) client.setQueryData(authQueryKey, cachedUser)
  const actions = createAuthActions(client)
  const mutation = new MutationObserver(client, { mutationFn: (kind) => kind === 'oauth' ? actions.completeOAuth() : actions.logout() })
  const query = new QueryObserver(client, authObservationOptions(client, mutation.getCurrentResult(), oauthPage))
  const status = () => authStatus(query.getCurrentResult(), mutation.getCurrentResult(), oauthPage)
  const recOptions = () => recommendationsQuery(status() === 'authenticated' ? query.getCurrentResult().data?.id : undefined)
  const recommendations = new QueryObserver(client, recOptions())
  const offRecommendations = recommendations.subscribe(() => {})
  const offQuery = query.subscribe(() => { recommendations.setOptions(recOptions()) })
  const sync = () => {
    query.setOptions(authObservationOptions(client, mutation.getCurrentResult(), oauthPage))
    recommendations.setOptions(recOptions())
  }
  const offMutation = mutation.subscribe(sync)
  return {
    client, mutation, query, status,
    recommendationEnabled: () => recommendations.getCurrentResult().isEnabled,
    leaveOAuth() { oauthPage = false; sync() },
    close() { offMutation(); offQuery(); offRecommendations(); client.clear() },
  }
}

test('tab-return background auth refresh preserves the confirmed user branch; a subsequent real 401 blocks protected views', async () => {
  browserState()
  const started = deferred(), finish = deferred()
  let expired = false
  adapter = async config => {
    if (config.url === '/users/me/recommendations') return response(config, [])
    assert.equal(config.url, '/users/me')
    if (expired) return fail(config, 401, 'AUTH_004')
    started.resolve(); await finish.promise; return response(config, { ...user })
  }
  const h = harness(), statuses = []
  const unsubscribe = h.query.subscribe(() => statuses.push(h.status()))
  h.client.mount()
  try {
    focusManager.setFocused(false)
    h.client.getQueryCache().find({ queryKey: authQueryKey }).setState({ dataUpdatedAt: Date.now() - 31_000 })
    focusManager.setFocused(true)
    await started.promise
    assert.equal(h.query.getCurrentResult().isFetching, true)
    assert.equal(h.status(), 'authenticated')
    assert.equal(h.query.getCurrentResult().data, user)
    finish.resolve(); await tick()
    assert.equal(h.status(), 'authenticated')
    assert.equal(h.query.getCurrentResult().data, user)
    assert.ok(statuses.every(status => status === 'authenticated'))
    expired = true
    await h.query.refetch()
    assert.equal(h.status(), 'anonymous')
    assert.equal(h.recommendationEnabled(), false)
  } finally { finish.resolve(); unsubscribe(); h.client.unmount(); h.close(); focusManager.setFocused(undefined) }
})

test('cached authentication is not trusted after background verification fails; initial authentication still waits', async () => {
  browserState()
  adapter = async config => {
    if (config.url === '/users/me/recommendations') return response(config, [])
    throw new AxiosError('fixture offline', 'ERR_NETWORK', config, {})
  }
  const h = harness()
  try {
    await h.query.refetch()
    assert.equal(h.status(), 'error')
    assert.equal(h.recommendationEnabled(), false)
    assert.equal(authStatus({ isPending: true, isFetching: true, isError: false, data: undefined }, { isPending: false, isError: false, error: null }, false), 'checking')
    assert.equal(authStatus({ isPending: false, isFetching: true, isError: false, data: user }, { isPending: true, isError: false, variables: 'logout', error: null }, false), 'checking')
  } finally { h.close() }
})

test('OAuth failure and retry share the full mutation pipeline, preserve old recovery failure until verified, and navigate only after completion', async () => {
  browserState(); saveLoginReturn('/opportunities?page=2')
  let phase = 'expired', rotations = 0, probes = 0, reissued = false
  const probeStarted = deferred(), finishProbe = deferred()
  adapter = async (config) => {
    if (config.url === '/users/me/recommendations') return response(config, [])
    if (config.url === '/auth/reissue') {
      rotations++
      if (phase !== 'after-login') return fail(config, 401, 'AUTH_002')
      reissued = true; return response(config, '')
    }
    if (config.requiresAuth === false) {
      probes++
      if (phase === 'expired') return fail(config, 401, 'AUTH_007')
      probeStarted.resolve(); await finishProbe.promise; return response(config, user)
    }
    if (phase === 'after-login' && reissued) return response(config, user)
    return fail(config, 401, 'AUTH_007')
  }
  assert.equal(await getCurrentUser(), null)
  assert.equal(rotations, 1)
  const h = harness({ oauthPage: true, cachedUser: null }), navigations = [], errors = []
  const runner = createOAuthCompletionRunner(() => h.mutation.mutate('oauth').then(() => {}), () => { navigations.push(consumeLoginReturn()); h.leaveOAuth() }, (error) => errors.push(error))
  try {
    runner.activate(); await runner.run()
    assert.equal(h.status(), 'error')
    assert.equal(h.query.getCurrentResult().isEnabled, false, '완료 페이지에서 일반 Query 재조회는 활성화되지 않는다')
    assert.equal(navigations.length, 0)
    assert.equal(await getCurrentUser(), null)
    assert.equal(rotations, 1)
    phase = 'verified'
    const first = runner.run(), duplicate = runner.run()
    assert.equal(first, duplicate)
    await probeStarted.promise
    assert.equal(h.status(), 'checking')
    assert.equal(h.recommendationEnabled(), false)
    assert.equal(navigations.length, 0)
    finishProbe.resolve(); await first
    assert.equal(h.mutation.getCurrentResult().isSuccess, true)
    assert.equal(h.status(), 'authenticated')
    assert.deepEqual(navigations, ['/opportunities?page=2'])
    assert.equal(probes, 2)
    assert.equal(errors.length, 1)
    phase = 'after-login'
    assert.deepEqual(await getCurrentUser(), user)
    assert.equal(rotations, 2)
  } finally { runner.deactivate(); finishProbe.resolve(); h.close() }
})

for (const outcome of ['anonymous', 'other-user', 'network-failure', 'timeout']) {
  test(`lost logout response with fresh auth cache forces server verification: ${outcome}`, async () => {
    browserState()
    const verifyStarted = deferred(), finishVerify = deferred()
    let meCalls = 0, recCalls = 0, verified = false, manualRecovery = false
    adapter = async (config) => {
      if (config.url === '/users/me/recommendations') { recCalls++; return response(config, []) }
      if (config.url === '/auth/logout') throw new AxiosError('lost response', outcome === 'timeout' ? 'ECONNABORTED' : 'ERR_NETWORK', config, {})
      if (config.url === '/auth/reissue') return fail(config, 401, 'AUTH_001')
      assert.equal(config.url, '/users/me'); meCalls++; verifyStarted.resolve(); await finishVerify.promise
      if (!manualRecovery && outcome === 'network-failure') throw new AxiosError('verification failed', 'ERR_NETWORK', config, {})
      verified = true
      if (!manualRecovery && outcome === 'anonymous') return fail(config, 401, 'AUTH_006')
      return response(config, { ...user, id: 22 })
    }
    const h = harness()
    h.client.setQueryData(['opportunity-detail', '42'], { saved: true, previousAccount: true })
    await tick()
    const baselineRecCalls = recCalls
    const logout = h.mutation.mutate('logout').catch((error) => error)
    try {
      await verifyStarted.promise
      assert.equal(meCalls, 1, '30초 fresh 캐시여도 실제 GET이 필요하다')
      assert.equal(h.status(), 'checking')
      assert.equal(h.recommendationEnabled(), false)
      assert.equal(recCalls, baselineRecCalls)
      assert.equal(verified, false)
      assert.equal(h.client.getQueryData(['opportunity-detail', '42']), undefined)
      finishVerify.resolve()
      const originalError = await logout
      assert.equal(originalError.kind, outcome === 'timeout' ? 'timeout' : 'network', '재확인은 로그아웃 204 성공으로 변환하지 않는다')
      await tick()
      assert.equal(meCalls, 1, '재확인 실패 후에도 자동 재활성화로 중복 GET을 만들지 않는다')
      if (outcome === 'anonymous') {
        assert.equal(h.status(), 'anonymous'); assert.equal(h.recommendationEnabled(), false)
        assert.equal(h.client.getQueryData(authQueryKey), null)
      } else if (outcome === 'network-failure') {
        assert.equal(h.status(), 'error'); assert.equal(h.recommendationEnabled(), false)
        assert.equal(recCalls, baselineRecCalls)
        assert.equal(h.query.getCurrentResult().isEnabled, false)
        manualRecovery = true
        await h.query.refetch()
        assert.equal(h.status(), 'authenticated')
        assert.equal(h.client.getQueryData(authQueryKey).id, 22)
      } else {
        assert.equal(h.status(), 'authenticated')
        assert.equal(h.client.getQueryData(authQueryKey).id, 22)
        assert.equal(h.client.getQueryData(['recommendations', 11]), undefined)
        assert.equal(h.recommendationEnabled(), true)
      }
    } finally { finishVerify.resolve(); await logout; h.close() }
  })
}

test('manual OAuth retry leaving the page still confirms server auth but does not consume return path or navigate', async () => {
  browserState(); saveLoginReturn('/saved')
  const retryStarted = deferred(), finishRetry = deferred()
  let calls = 0
  adapter = async (config) => {
    if (config.url === '/users/me/recommendations') return response(config, [])
    calls++
    if (calls === 1) throw new AxiosError('first check failed', 'ERR_NETWORK', config, {})
    retryStarted.resolve(); await finishRetry.promise; return response(config, user)
  }
  const h = harness({ oauthPage: true, cachedUser: null }), navigations = [], errors = []
  const runner = createOAuthCompletionRunner(() => h.mutation.mutate('oauth').then(() => {}), () => navigations.push(consumeLoginReturn()), (error) => errors.push(error))
  try {
    runner.activate(); await runner.run()
    const retry = runner.run(); await retryStarted.promise
    runner.deactivate(); h.leaveOAuth()
    finishRetry.resolve(); await retry
    assert.deepEqual(h.client.getQueryData(authQueryKey), user)
    assert.deepEqual(navigations, [])
    assert.equal(peekLoginReturn(), '/saved')
    assert.equal(errors.length, 1)
  } finally { finishRetry.resolve(); runner.deactivate(); h.close() }
})

test('completion lifecycle invalidates old successes/errors and old finally cannot release a newer in-flight request', async () => {
  const first = deferred(), second = deferred(), outputs = [], errors = []
  let calls = 0
  const runner = createOAuthCompletionRunner(() => (++calls === 1 ? first.promise : second.promise), () => outputs.push('current'), (error) => errors.push(error))
  runner.activate(); const old = runner.run(); await tick()
  runner.deactivate(); runner.activate()
  const current = runner.run(); await tick()
  first.resolve(); await old
  assert.deepEqual(outputs, [])
  assert.equal(runner.run(), current, '이전 finally가 새 pending을 비우면 안 된다')
  second.resolve(); await current
  assert.deepEqual(outputs, ['current'])
  assert.deepEqual(errors, [])
  runner.deactivate()
})

test('late failure from an invalidated completion cannot overwrite the successful retry UI', async () => {
  let rejectOld
  const oldFailure = new Promise((_resolve, reject) => { rejectOld = reject })
  const outputs = [], errors = []
  let calls = 0
  const runner = createOAuthCompletionRunner(() => (++calls === 1 ? oldFailure : Promise.resolve()), () => outputs.push('success'), (error) => errors.push(error))
  runner.activate(); const old = runner.run(); await tick()
  runner.deactivate(); runner.activate(); await runner.run()
  rejectOld(new Error('late old error')); await old
  assert.deepEqual(outputs, ['success'])
  assert.deepEqual(errors, [])
  runner.deactivate()
})

test('completion passes its result (such as the profile setup decision) to success only while the page is still active', async () => {
  const outputs = []
  const runner = createOAuthCompletionRunner(async () => true, (setup) => outputs.push(setup ? '/onboarding' : '/saved'), () => outputs.push('error'))
  runner.activate(); await runner.run()
  assert.deepEqual(outputs, ['/onboarding'])
  const late = deferred()
  const leaving = createOAuthCompletionRunner(() => late.promise, (setup) => outputs.push(setup), () => outputs.push('error'))
  leaving.activate(); const pending = leaving.run(); leaving.deactivate(); late.resolve(true); await pending
  assert.deepEqual(outputs, ['/onboarding'], '화면을 떠난 뒤 늦게 끝난 판단으로 이동하지 않는다')
  runner.deactivate()
})
