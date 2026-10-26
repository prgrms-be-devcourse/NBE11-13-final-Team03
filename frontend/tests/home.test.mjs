import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { getRecommendations, isRecommendations, recommendationsQuery } from '../src/api/recommendations.ts'
import { homeLatestQuery } from '../src/pages/home/homeQueries.ts'
import { latestResponse, policyRecommendation, trainingRecommendation } from './home-fixtures.mjs'
import { authQueryKey } from '../src/auth/session.ts'
import { runPreferenceChange } from '../src/api/preferences.ts'

const originalAdapter = apiClient.defaults.adapter
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else delete globalThis.document
})
const response = (config, data, status = 200) => ({ config, data, status, statusText: '', headers: {} })
function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { resolve, promise }
}

test('home latest query reuses public search, requests six latest items, forwards signal and avoids detail/auth calls', async () => {
  let calls = 0
  const controller = new AbortController()
  apiClient.defaults.adapter = async (config) => {
    calls++
    assert.equal(config.url, '/opportunities')
    assert.equal(config.baseURL, '/api')
    assert.equal(config.params.get('size'), '6')
    assert.equal(config.params.get('sort'), null) // 기존 직렬화는 서버 기본값 LATEST를 생략한다.
    assert.equal(config.params.get('page'), null) // 기본 page=0
    assert.equal(config.requiresAuth, undefined)
    assert.equal(config.signal, controller.signal)
    return response(config, latestResponse)
  }
  const options = homeLatestQuery()
  assert.deepEqual(options.queryKey, ['home', 'opportunities', 'size=6'])
  assert.deepEqual(await options.queryFn({ signal: controller.signal }), latestResponse)
  assert.equal(calls, 1)
})

test('recommendation request requires auth, forwards cancellation and preserves backend order and reasons', async () => {
  const data = [trainingRecommendation, policyRecommendation]
  const controller = new AbortController()
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.url, '/users/me/recommendations')
    assert.equal(config.baseURL, '/api')
    assert.equal(config.requiresAuth, true)
    assert.equal(config.signal, controller.signal)
    assert.equal(config.params, undefined)
    return response(config, data)
  }
  assert.deepEqual(await getRecommendations(controller.signal), data)
  assert.equal(isRecommendations([]), true)
  assert.equal(isRecommendations(data), true)
})

test('recommendation DTO validates enum, hierarchy, typed fields, max counts and reason structure', () => {
  for (const value of [null, { content: [] }, '<html>Error</html>', [null],
    [{ ...policyRecommendation, opportunityId: '102' }], [{ ...policyRecommendation, saved: 'true' }],
    [{ ...policyRecommendation, eligibilityStatus: null }], [{ ...policyRecommendation, applicationStatus: 'OPEN_SOON' }],
    [{ ...policyRecommendation, regions: [{ role: 'UNKNOWN', status: 'UNKNOWN', validationStatus: 'INVALID_VALUE' }] }],
    [{ ...policyRecommendation, recommendationReasons: { badges: [1], description: 'test' } }],
    [{ ...policyRecommendation, recommendationReasons: { badges: ['1', '2', '3', '4'], description: 'test' } }],
    Array(6).fill(policyRecommendation)]) assert.equal(isRecommendations(value), false)
})

test('nullable recommendation fields and interest-free reasons preserve the supplied five-item order', async () => {
  const data = Array.from({ length: 5 }, (_, index) => ({ ...policyRecommendation, opportunityId: 110 - index,
    category: null, subCategory: '', detailCategory: null, createdAt: null, applicationEndDate: null, trainingStartDate: null,
    regions: [], recommendationReasons: { badges: ['신청 조건 충족'], description: '저장된 최신 판정에서 신청 조건을 충족합니다.' } }))
  apiClient.defaults.adapter = async (config) => response(config, data)
  assert.equal(isRecommendations(data), true)
  assert.deepEqual(await getRecommendations(), data)
})

test('active recommendation observers share reads and refresh server saved state or hidden exclusions after exact 204', async () => {
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-home' } })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
  client.setQueryData(authQueryKey, { id: 11, nickname: '테스트 회원', email: null, role: 'USER' })
  const owner = client.getQueryData(authQueryKey), calls = [], started = deferred(), finish = deferred()
  let saved = false, hidden = false
  apiClient.defaults.adapter = async (config) => {
    calls.push({ url: config.url, method: config.method, source: config.params?.source })
    if (config.url === '/users/me/recommendations') return response(config, hidden ? [] : [{ ...policyRecommendation, saved }])
    assert.equal(config.requiresAuth, true)
    assert.equal(config.headers.get('X-XSRF-TOKEN'), 'test-home')
    if (config.url === '/opportunities/102/save') {
      if (config.method === 'post') { started.resolve(); await finish.promise }
      saved = config.method === 'post'
    } else {
      assert.equal(config.url, '/opportunities/102/hide')
      hidden = true
    }
    return response(config, '', 204)
  }
  const first = new QueryObserver(client, recommendationsQuery(11)), second = new QueryObserver(client, recommendationsQuery(11))
  const offFirst = first.subscribe(() => {}), offSecond = second.subscribe(() => {})
  try {
    await client.fetchQuery(recommendationsQuery(11))
    const a = runPreferenceChange(client, owner, 102, 'save', 'RECOMMENDATION')
    const b = runPreferenceChange(client, owner, 102, 'save', 'RECOMMENDATION')
    assert.equal(a, b)
    await started.promise
    assert.equal(first.getCurrentResult().data[0].saved, false)
    finish.resolve(); await a
    assert.equal(first.getCurrentResult().data[0].saved, true)
    assert.equal(second.getCurrentResult().data[0].saved, true)
    await runPreferenceChange(client, owner, 102, 'unsave', 'RECOMMENDATION')
    assert.equal(first.getCurrentResult().data[0].saved, false)
    await runPreferenceChange(client, owner, 102, 'save', 'RECOMMENDATION')
    client.setQueryData(['opportunities', 'untouched-by-hide'], { saved: true })
    client.setQueryData(['opportunity-detail', '102'], { saved: true })
    await runPreferenceChange(client, owner, 102, 'hide', 'RECOMMENDATION')
    assert.deepEqual(first.getCurrentResult().data, [])
    assert.deepEqual(second.getCurrentResult().data, [])
    assert.equal(saved, true)
    for (const key of [['opportunities', 'untouched-by-hide'], ['opportunity-detail', '102']]) {
      assert.deepEqual(client.getQueryData(key), { saved: true })
      assert.equal(client.getQueryState(key).isInvalidated, false)
    }
    assert.equal(calls.filter(call => call.method === 'get').length, 5)
    assert.deepEqual(calls.filter(call => call.method !== 'get').map(call => [call.method, call.source]),
      [['post', 'RECOMMENDATION'], ['delete', undefined], ['post', 'RECOMMENDATION'], ['post', 'RECOMMENDATION']])
    assert.equal(calls.some(call => call.url.includes('behavior')), false)
  } finally { finish.resolve(); offFirst(); offSecond(); client.clear() }
})

test('failed recommendation hide leaves observed results intact and a later successful retry refetches the server', async () => {
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-home' } })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
  client.setQueryData(authQueryKey, { id: 11, nickname: '테스트 회원', email: null, role: 'USER' })
  const owner = client.getQueryData(authQueryKey)
  let fail = true, hidden = false, reads = 0
  apiClient.defaults.adapter = async (config) => {
    if (config.url === '/users/me/recommendations') { reads++; return response(config, hidden ? [] : [policyRecommendation]) }
    assert.equal(config.url, '/opportunities/102/hide')
    if (fail) throw new AxiosError('fixture', 'ERR_NETWORK', config, {})
    hidden = true
    return response(config, '', 204)
  }
  const observer = new QueryObserver(client, recommendationsQuery(11)), off = observer.subscribe(() => {})
  try {
    await client.fetchQuery(recommendationsQuery(11))
    await assert.rejects(runPreferenceChange(client, owner, 102, 'hide', 'RECOMMENDATION'), error => error.kind === 'network')
    assert.deepEqual(observer.getCurrentResult().data, [policyRecommendation])
    assert.equal(reads, 1)
    fail = false
    await runPreferenceChange(client, owner, 102, 'hide', 'RECOMMENDATION')
    assert.deepEqual(observer.getCurrentResult().data, [])
    assert.equal(reads, 2)
  } finally { off(); client.clear() }
})

test('recommendation profile absence and HTTP/network failures never become an empty result', async () => {
  for (const [status, code] of [[404, 'ELIGIBILITY_001'], [403, 'TEST'], [503, 'TEST']]) {
    apiClient.defaults.adapter = async (config) => { throw new AxiosError('synthetic', 'ERR_BAD_RESPONSE', config, {}, response(config, { code, message: 'test only', fieldErrors: {} }, status)) }
    await assert.rejects(getRecommendations(), (error) => error.status === status && error.code === code)
  }
  apiClient.defaults.adapter = async (config) => { throw new AxiosError('synthetic', 'ERR_NETWORK', config, {}) }
  await assert.rejects(getRecommendations(), (error) => error.kind === 'network')
  apiClient.defaults.adapter = async (config) => response(config, { invalid: true })
  await assert.rejects(getRecommendations(), (error) => error.kind === 'unknown')
})

test('without confirmed authentication a mounted recommendation observer and even manual refetch make no API call', async () => {
  let calls = 0
  apiClient.defaults.adapter = async (config) => { calls++; return response(config, []) }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const observer = new QueryObserver(client, recommendationsQuery())
  const unsubscribe = observer.subscribe(() => {})
  try {
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(observer.getCurrentResult().fetchStatus, 'idle')
    assert.equal(recommendationsQuery().enabled, false)
    const result = await observer.refetch()
    assert.equal(result.error.kind, 'configuration')
    assert.equal(calls, 0)
    for (const id of [0, -1, NaN, Infinity, 1.2]) assert.equal(recommendationsQuery(id).enabled, false)
  } finally { unsubscribe(); client.clear() }
})

test('account-specific recommendation keys discard previous account data and cancel stale requests on transition', async () => {
  const firstStarted = deferred(), firstFinished = deferred(), secondStarted = deferred(), secondFinished = deferred(), secondReady = deferred()
  let calls = 0, oldSignal
  const secondAccountData = [{ ...policyRecommendation, title: '테스트 두 번째 계정의 추천' }]
  apiClient.defaults.adapter = async (config) => {
    calls++
    if (calls === 1) { oldSignal = config.signal; firstStarted.resolve(); await firstFinished.promise; return response(config, [policyRecommendation]) }
    secondStarted.resolve(); await secondFinished.promise; return response(config, secondAccountData)
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(recommendationsQuery(11).queryKey, [policyRecommendation])
  const observer = new QueryObserver(client, recommendationsQuery(11))
  const unsubscribe = observer.subscribe((result) => { if (result.data?.[0]?.title === secondAccountData[0].title) secondReady.resolve() })
  try {
    await firstStarted.promise
    observer.setOptions(recommendationsQuery(22))
    await secondStarted.promise
    assert.equal(oldSignal.aborted, true)
    assert.equal(observer.getCurrentResult().data, undefined)
    secondFinished.resolve(); await secondReady.promise
    firstFinished.resolve(); await new Promise((resolve) => setImmediate(resolve))
    assert.deepEqual(observer.getCurrentResult().data, secondAccountData)
    observer.setOptions(recommendationsQuery())
    assert.equal(observer.getCurrentResult().fetchStatus, 'idle')
    assert.equal(observer.getCurrentResult().data, undefined)
  } finally { firstFinished.resolve(); secondFinished.resolve(); unsubscribe(); client.clear() }
})
