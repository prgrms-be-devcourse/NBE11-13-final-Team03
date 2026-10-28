import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import axios, { AxiosError } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'

// 공개 조회(recoverAuth)의 만료 토큰 복구를 실제 client/session 규칙으로 검증한다.
// 두 Axios 인스턴스가 생성되기 전에 테스트 전용 adapter를 주입한다.
const originalAdapter = axios.defaults.adapter
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
let adapter
axios.defaults.adapter = (config) => adapter(config)
const { endAuthSession, onAuthLost, resetAuthRecovery } = await import('../src/api/client.ts')
const { searchOpportunities } = await import('../src/api/opportunities.ts')
const { getCurrentUser } = await import('../src/api/auth.ts')
const { getOpportunityDetail } = await import('../src/api/opportunityDetail.ts')
const { getRegions } = await import('../src/api/regions.ts')
const { authQueryKey, authQueryOptions, observeAuthLoss } = await import('../src/auth/session.ts')
const { defaultSearchParams } = await import('../src/pages/opportunities/searchParams.ts')
const { policy } = await import('./detail-fixtures.mjs')
const { regionRows } = await import('./profile-fixtures.mjs')
axios.defaults.adapter = originalAdapter
afterEach(() => {
  resetAuthRecovery() // 다음 테스트에만 새 인증 세션을 제공한다.
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else delete globalThis.document
})

const user = { id: 11, nickname: null, email: null, role: 'USER' }
const item = (saved) => ({ opportunityId: 42, type: 'YOUTH_POLICY', title: '테스트 전용 공고', category: null, subCategory: null,
  detailCategory: null, createdAt: '2026-10-25T12:00:00', eligibilityStatus: null, saved })
const page = (saved) => ({ content: [item(saved)], page: 0, size: 10, totalElements: 1, totalPages: 1, hasNext: false })
const response = (config, data, status = 200) => ({ config, data, status, statusText: '', headers: {} })
function http(config, status, code) { throw new AxiosError('test only', 'ERR_BAD_RESPONSE', config, {}, response(config, { code, message: 'test only', fieldErrors: {} }, status)) }
function cookie() { Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=synthetic-csrf' } }) }
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done }); return { resolve, promise } }
const tick = () => new Promise((resolve) => setImmediate(resolve))

// AUTH_007: 만료된 Access Token 쿠키가 아직 전달되는 짧은 창. AUTH_006: 오래 열어 둔 앱에서 Access Token 쿠키는
// 사라지고 Refresh Token 쿠키만 남은 상태(서버가 공개 조회를 익명으로 답하지 않고 401을 낸다).
for (const code of ['AUTH_007', 'AUTH_006']) {
  test(`public search 401 ${code} while the cached user is still authenticated: reissue 200 → same request resent with the saved flag intact`, async () => {
    cookie()
    const paths = []
    let reissued = false
    adapter = async (config) => {
      paths.push(config.url)
      if (config.url === '/auth/reissue') { reissued = true; return response(config, '') }
      assert.equal(config.url, '/opportunities')
      assert.equal(config.recoverAuth, true)
      assert.equal(config.requiresAuth, undefined)
      return reissued ? response(config, page(true)) : http(config, 401, code)
    }
    let lost = 0
    const off = onAuthLost(() => { lost++ })
    try {
      const result = await searchOpportunities(defaultSearchParams())
      assert.equal(result.content[0].saved, true)
      assert.deepEqual(paths, ['/opportunities', '/auth/reissue', '/opportunities'])
      assert.equal(lost, 0)
    } finally { off() }
  })
}

// 재발급 401 응답은 서버가 토큰 쿠키를 지우므로(불일치 AUTH_004 제외) 익명 재전송에는 토큰 쿠키가 붙지 않는다.
test('reissue 401 on public search: the same request is resent anonymously once and auth loss is reported exactly once', async () => {
  cookie()
  const paths = []
  adapter = async (config) => {
    paths.push(config.url)
    if (config.url === '/auth/reissue') return http(config, 401, 'AUTH_002')
    return paths.filter((path) => path === '/opportunities').length % 2 === 1 ? http(config, 401, 'AUTH_007') : response(config, page(false))
  }
  let lost = 0
  const off = onAuthLost(() => { lost++ })
  try {
    const result = await searchOpportunities(defaultSearchParams())
    assert.equal(result.content[0].saved, false)
    assert.deepEqual(paths, ['/opportunities', '/auth/reissue', '/opportunities'])
    assert.equal(lost, 1)
    // 재발급 401은 재로그인 확인 전까지 고정된다. 이후 공개 조회의 401은 새 재발급 없이 익명으로 재전송하고 다시 알리지 않는다.
    paths.length = 0
    assert.equal((await searchOpportunities(defaultSearchParams())).content[0].saved, false)
    assert.deepEqual(paths, ['/opportunities', '/opportunities'])
    assert.equal(lost, 1)
  } finally { off() }
})

test('auth loss re-verifies the cached user through the auth query and clears personalized caches without a second reissue', async () => {
  cookie()
  const paths = []
  let searches = 0
  adapter = async (config) => {
    paths.push(config.url)
    if (config.url === '/auth/reissue') return http(config, 401, 'AUTH_002')
    if (config.url === '/users/me') return http(config, 401, 'AUTH_007')
    assert.equal(config.url, '/opportunities')
    return ++searches === 1 ? http(config, 401, 'AUTH_007') : response(config, page(false))
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  client.setQueryData(authQueryKey, user)
  client.setQueryData(['recommendations', 11], [])
  client.setQueryData(['profile', 11], { id: 11 })
  const observer = new QueryObserver(client, authQueryOptions(client))
  const unsubscribe = observer.subscribe(() => {})
  const stop = observeAuthLoss(client)
  try {
    const result = await searchOpportunities(defaultSearchParams())
    assert.equal(result.content[0].saved, false)
    for (let i = 0; i < 5 && client.isFetching({ queryKey: authQueryKey }); i++) await tick()
    await tick()
    assert.equal(client.getQueryData(authQueryKey), null)
    assert.equal(client.getQueryData(['recommendations', 11]), undefined)
    assert.equal(client.getQueryData(['profile', 11]), undefined)
    assert.equal(paths.filter((path) => path === '/auth/reissue').length, 1, '인증 재확인은 고정된 재발급 실패를 공유한다')
    assert.deepEqual(paths.filter((path) => path === '/users/me'), ['/users/me'])
  } finally { stop(); unsubscribe(); client.clear() }
})

test('reissue lost to another tab (AUTH_004): resend with that tab\'s cookies, no auth loss, and the next expiry can reissue again', async () => {
  cookie()
  const paths = []
  let expired = true, conflicts = 0, otherTabRotates = true, otherTabCookie = false
  adapter = async (config) => {
    paths.push(config.url)
    if (config.url === '/auth/reissue') {
      // 다른 탭이 먼저 회전시켜 이 탭의 재발급은 불일치로 실패하지만 브라우저에는 그 탭의 새 쿠키가 있다.
      if (otherTabRotates) { otherTabRotates = false; conflicts++; otherTabCookie = true; return http(config, 401, 'AUTH_004') }
      expired = false; return response(config, '')
    }
    const authenticated = !expired || otherTabCookie
    if (otherTabCookie) { otherTabCookie = false; expired = false }
    if (!authenticated) return http(config, 401, 'AUTH_007')
    return config.url === '/users/me' ? response(config, user) : response(config, page(true))
  }
  let lost = 0
  const off = onAuthLost(() => { lost++ })
  try {
    assert.equal((await searchOpportunities(defaultSearchParams())).content[0].saved, true)
    assert.deepEqual(paths, ['/opportunities', '/auth/reissue', '/opportunities'])
    assert.equal(lost, 0)
    // 다른 탭의 Access Token 쿠키가 나중에 만료되면 이전 충돌 실패를 재사용하지 않고 다시 재발급한다.
    expired = true; paths.length = 0
    assert.equal((await searchOpportunities(defaultSearchParams())).content[0].saved, true)
    assert.deepEqual(paths, ['/opportunities', '/auth/reissue', '/opportunities'])
    assert.equal(conflicts, 1)
    // 인증 API(requiresAuth)도 충돌 뒤 그 탭의 새 쿠키로 재전송해 사용자를 비회원으로 오판하지 않는다.
    expired = true; otherTabRotates = true; paths.length = 0
    assert.deepEqual(await getCurrentUser(), user)
    assert.deepEqual(paths, ['/users/me', '/auth/reissue', '/users/me'])
    assert.equal(conflicts, 2)
    assert.equal(lost, 0)
  } finally { off() }
})

for (const [label, failure] of [['network', (config) => { throw new AxiosError('offline', 'ERR_NETWORK', config, {}) }],
  ['timeout', (config) => { throw new AxiosError('slow', 'ECONNABORTED', config, {}) }],
  ['server', (config) => http(config, 503, 'TEST')]]) {
  test(`uncertain reissue failure (${label}) keeps the error instead of falling back to an anonymous result`, async () => {
    cookie()
    const paths = []
    adapter = async (config) => {
      paths.push(config.url)
      if (config.url === '/auth/reissue') return failure(config)
      return http(config, 401, 'AUTH_007')
    }
    let lost = 0
    const off = onAuthLost(() => { lost++ })
    try {
      await assert.rejects(searchOpportunities(defaultSearchParams()), (error) =>
        label === 'server' ? error.status === 503 : error.kind === label && error.status === undefined)
      assert.deepEqual(paths, ['/opportunities', '/auth/reissue'])
      assert.equal(lost, 0)
    } finally { off() }
  })
}

test('three concurrent public 401s share a single reissue and each request is resent once', async () => {
  cookie()
  const paths = []
  const reissueStarted = deferred(), finishReissue = deferred()
  let reissued = false
  adapter = async (config) => {
    paths.push(config.url)
    if (config.url === '/auth/reissue') { reissueStarted.resolve(); await finishReissue.promise; reissued = true; return response(config, '') }
    assert.equal(config.recoverAuth, true)
    if (!reissued) return http(config, 401, 'AUTH_007')
    if (config.url === '/opportunities') return response(config, page(true))
    if (config.url === '/opportunities/42') return response(config, { ...policy, saved: true })
    assert.equal(config.url, '/regions')
    return response(config, { regions: regionRows })
  }
  const pending = Promise.all([searchOpportunities(defaultSearchParams()), getOpportunityDetail('42'), getRegions()])
  await reissueStarted.promise
  await tick()
  assert.equal(paths.filter((path) => path === '/auth/reissue').length, 1)
  finishReissue.resolve()
  const [search, detail, regions] = await pending
  assert.equal(search.content[0].saved, true)
  assert.equal(detail.saved, true)
  assert.equal(regions.length, regionRows.length)
  assert.equal(paths.filter((path) => path === '/auth/reissue').length, 1)
  assert.equal(paths.filter((path) => path === '/opportunities').length, 2)
  assert.equal(paths.filter((path) => path === '/opportunities/42').length, 2)
  assert.equal(paths.filter((path) => path === '/regions').length, 2)
})

for (const outcome of ['success', 'unauthorized']) {
  test(`logout during reissue (${outcome}) discards the late public response instead of resending or reporting auth loss`, async () => {
    cookie()
    const paths = []
    const reissueStarted = deferred(), finishReissue = deferred()
    adapter = async (config) => {
      paths.push(config.url)
      if (config.url === '/auth/reissue') {
        reissueStarted.resolve(); await finishReissue.promise
        return outcome === 'success' ? response(config, '') : http(config, 401, 'AUTH_002')
      }
      return http(config, 401, 'AUTH_007')
    }
    let lost = 0
    const off = onAuthLost(() => { lost++ })
    try {
      const search = searchOpportunities(defaultSearchParams())
      await reissueStarted.promise
      endAuthSession() // 서버 로그아웃 204 확인
      finishReissue.resolve()
      await assert.rejects(search, (error) => error.kind === 'cancelled')
      assert.deepEqual(paths, ['/opportunities', '/auth/reissue'])
      assert.equal(lost, 0)
    } finally { off() }
  })
}
