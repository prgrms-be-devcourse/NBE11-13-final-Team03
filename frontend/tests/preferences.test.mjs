import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { ApiError } from '../src/api/ApiError.ts'
import { authQueryKey, resetPersonalizedQueries } from '../src/auth/session.ts'
import { correctedSavedPage, getSavedOpportunities, isSavedOpportunities, savedOpportunitiesQuery, saveOpportunity, unsaveOpportunity, hideOpportunity, unhideOpportunity, runPreferenceChange } from '../src/api/preferences.ts'

const adapter = apiClient.defaults.adapter, originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
afterEach(() => {
  apiClient.defaults.adapter = adapter
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else delete globalThis.document
})
const csrf = () => Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-preference' } })
const response = (config, data = '', status = 204) => ({ config, data, status, statusText: '', headers: {} })
const client = () => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } } })
const user = { id: 11, nickname: '테스트 회원', email: null, role: 'USER' }
const item = { opportunityId: 42, type: 'YOUTH_POLICY', title: '테스트 저장 공고', category: '일자리', subCategory: null, savedAt: '2026-10-26T09:00:00.123456' }
const page = { content: [item], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false }
const deferred = () => { let resolve; const promise = new Promise((done) => { resolve = done }); return { resolve, promise } }

test('save/unsave/hide/unhide independently use exact paths, 204, auth and CSRF; only POST sends source and no behavior request is added', async () => {
  csrf(); const calls = [], controller = new AbortController()
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.requiresAuth, true); assert.equal(config.baseURL, '/api')
    assert.equal(config.headers.get('X-XSRF-TOKEN'), 'test-preference'); assert.equal(config.signal, controller.signal)
    calls.push([config.method, config.url, config.params]); return response(config)
  }
  await saveOpportunity(42, 'SEARCH', controller.signal); await unsaveOpportunity(42, controller.signal)
  await hideOpportunity(42, 'RECOMMENDATION', controller.signal); await unhideOpportunity(42, controller.signal)
  assert.deepEqual(calls, [['post', '/opportunities/42/save', { source: 'SEARCH' }], ['delete', '/opportunities/42/save', undefined], ['post', '/opportunities/42/hide', { source: 'RECOMMENDATION' }], ['delete', '/opportunities/42/hide', undefined]])
})
test('invalid IDs never send a request and any non-204 response is not accepted as mutation success', async () => {
  csrf(); let calls = 0
  apiClient.defaults.adapter = async (config) => { calls++; return response(config, {}, 200) }
  for (const id of [0, -1, 1.5, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) await assert.rejects(saveOpportunity(id), (error) => error.kind === 'configuration')
  assert.equal(calls, 0)
  await assert.rejects(saveOpportunity(42), (error) => error.kind === 'unknown'); assert.equal(calls, 1)
})
test('401/404/403 and network failures propagate without fake success or an extra preference call', async () => {
  csrf(); let calls = 0
  for (const [status, code] of [[401, 'AUTH001'], [404, 'PREFERENCE_001'], [404, 'USER_001'], [403, 'TEST']]) {
    apiClient.defaults.adapter = async (config) => { calls++; throw new AxiosError('private', 'ERR_BAD_RESPONSE', config, {}, response(config, { code, message: 'fixture', fieldErrors: {} }, status)) }
    await assert.rejects(unsaveOpportunity(42), (error) => error.status === status && error.code === code)
  }
  assert.equal(calls, 4)
  apiClient.defaults.adapter = async (config) => { throw new AxiosError('private', 'ERR_NETWORK', config, {}) }
  await assert.rejects(hideOpportunity(42), (error) => error.kind === 'network')
})
test('saved DTO and 0-based page/size are checked and missing fields, wrong item types and mismatched pages fail safely', async () => {
  const controller = new AbortController()
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.url, '/users/me/saved-opportunities'); assert.equal(config.requiresAuth, true)
    assert.deepEqual(config.params, { page: 0, size: 20 }); assert.equal(config.signal, controller.signal)
    return response(config, page, 200)
  }
  assert.deepEqual(await getSavedOpportunities(0, 20, controller.signal), page)
  for (const bad of [null, [], {}, { ...page, content: [null] }, { ...page, size: 101 }, { ...page, content: [{ ...item, savedAt: null }] }, { ...page, content: [{ ...item, opportunityId: '42' }] }, { ...page, content: [{ ...item, type: 'OTHER' }] }]) assert.equal(isSavedOpportunities(bad), false)
  for (const [pageNumber, size] of [[-1, 20], [0, 0], [0, 101], [0.5, 20]]) await assert.rejects(getSavedOpportunities(pageNumber, size), (error) => error.kind === 'configuration')
  apiClient.defaults.adapter = async (config) => response(config, { ...page, page: 1 }, 200)
  await assert.rejects(getSavedOpportunities(), (error) => error.kind === 'unknown')
})
test('last item removal corrects to the last valid 0-based page, including all items removed and concurrent page shrink', () => {
  assert.equal(correctedSavedPage(2, { ...page, page: 2, content: [], totalPages: 2 }), 1)
  assert.equal(correctedSavedPage(5, { ...page, page: 5, content: [], totalPages: 1 }), 0)
  assert.equal(correctedSavedPage(1, { ...page, page: 1, content: [], totalPages: 0, totalElements: 0 }), 0)
  assert.equal(correctedSavedPage(0, { ...page, content: [], totalPages: 0 }), 0)
  assert.equal(correctedSavedPage(2, page), 2)
})
test('duplicate actions on one user/item share one in-flight request; an opposite action is rejected until refresh finishes', async () => {
  csrf(); const queryClient = client(), started = deferred(), finish = deferred()
  queryClient.setQueryData(authQueryKey, user); const owner = queryClient.getQueryData(authQueryKey)
  let calls = 0
  apiClient.defaults.adapter = async (config) => { calls++; started.resolve(); await finish.promise; return response(config) }
  const a = runPreferenceChange(queryClient, owner, 42, 'save', 'SEARCH', new AbortController().signal)
  const b = runPreferenceChange(queryClient, owner, 42, 'save', 'SEARCH', new AbortController().signal)
  assert.equal(a, b); await started.promise
  await assert.rejects(runPreferenceChange(queryClient, owner, 42, 'unsave', 'OTHER', new AbortController().signal), (error) => error.kind === 'configuration')
  assert.equal(calls, 1); finish.resolve(); await Promise.all([a, b])
  await runPreferenceChange(queryClient, owner, 42, 'unsave', 'OTHER', new AbortController().signal)
  assert.equal(calls, 2); queryClient.clear()
})
test('failed shared requests terminate all waiters, release the lock, and leave successful cached state untouched', async () => {
  csrf(); const queryClient = client(); queryClient.setQueryData(authQueryKey, user); queryClient.setQueryData(['saved-opportunities', 11, 0, 20], page)
  const owner = queryClient.getQueryData(authQueryKey)
  apiClient.defaults.adapter = async (config) => { throw new AxiosError('fixture', 'ERR_NETWORK', config, {}) }
  const a = runPreferenceChange(queryClient, owner, 42, 'unsave', 'SAVED', new AbortController().signal)
  const b = runPreferenceChange(queryClient, owner, 42, 'unsave', 'SAVED', new AbortController().signal)
  const results = await Promise.allSettled([a, b]); assert.ok(results.every((result) => result.status === 'rejected'))
  assert.deepEqual(queryClient.getQueryData(['saved-opportunities', 11, 0, 20]), page)
  apiClient.defaults.adapter = async (config) => response(config)
  await runPreferenceChange(queryClient, owner, 42, 'unsave', 'SAVED', new AbortController().signal)
  queryClient.clear()
})
test('save success invalidates only affected saved/search/detail/home/recommendation keys; hide touches only recommendations', async () => {
  csrf()
  for (const change of ['save', 'hide']) {
    const queryClient = client(); queryClient.setQueryData(authQueryKey, user)
    const keys = [['saved-opportunities', 11, 0, 20], ['opportunities', 'filter'], ['opportunity-detail', '42'], ['home', 'opportunities', 'size=6'], ['recommendations', 11], ['recommendations', 22], ['opportunity-detail', '99'], ['regions'], ['profile', 11]]
    for (const key of keys) queryClient.setQueryData(key, [])
    apiClient.defaults.adapter = async (config) => response(config)
    await runPreferenceChange(queryClient, queryClient.getQueryData(authQueryKey), 42, change, 'OTHER', new AbortController().signal)
    for (const key of keys) assert.equal(queryClient.getQueryState(key).isInvalidated, change === 'save' ? keys.indexOf(key) < 5 : key[0] === 'recommendations' && key[1] === 11, JSON.stringify(key))
    queryClient.clear()
  }
})
test('a late pre-save GET is cancelled; the active observer refetches saved state after the confirmed 204', async () => {
  csrf(); const queryClient = client(); queryClient.setQueryData(authQueryKey, user)
  let reads = 0, saved = false, oldSignal
  const started = deferred(), finish = deferred()
  const observer = new QueryObserver(queryClient, { queryKey: ['opportunities', 'test'], queryFn: async ({ signal }) => {
    reads++
    if (reads === 1) { oldSignal = signal; started.resolve(); await finish.promise; return { saved: false } }
    return { saved }
  } })
  const off = observer.subscribe(() => {}); await started.promise
  apiClient.defaults.adapter = async (config) => { saved = true; return response(config) }
  await runPreferenceChange(queryClient, queryClient.getQueryData(authQueryKey), 42, 'save', 'SEARCH', new AbortController().signal)
  assert.equal(oldSignal.aborted, true); assert.equal(observer.getCurrentResult().data.saved, true)
  finish.resolve(); await new Promise((resolve) => setImmediate(resolve))
  assert.equal(observer.getCurrentResult().data.saved, true); assert.equal(reads, 2)
  off(); queryClient.clear()
})
test('confirmed user change removes saved caches and rejects old write completion without invalidating the next account', async () => {
  csrf(); const queryClient = client(), started = deferred(), finish = deferred()
  queryClient.setQueryData(authQueryKey, user); queryClient.setQueryData(['saved-opportunities', 11, 0, 20], page)
  apiClient.defaults.adapter = async (config) => { started.resolve(); await finish.promise; return response(config) }
  const old = runPreferenceChange(queryClient, queryClient.getQueryData(authQueryKey), 42, 'save', 'SEARCH', new AbortController().signal)
  await started.promise; await resetPersonalizedQueries(queryClient)
  queryClient.setQueryData(authQueryKey, { ...user, id: 22 }); queryClient.setQueryData(['saved-opportunities', 22, 0, 20], { ...page, content: [] })
  finish.resolve(); await assert.rejects(old, (error) => error instanceof ApiError && error.kind === 'cancelled')
  assert.equal(queryClient.getQueryData(['saved-opportunities', 11, 0, 20]), undefined)
  assert.equal(queryClient.getQueryState(['saved-opportunities', 22, 0, 20]).isInvalidated, false)
  queryClient.clear()
})
test('saved-list observer uses account-specific keys and cancellation signals without carrying data between accounts', async () => {
  const queryClient = client(); const started = deferred(), finish = deferred(); let signal
  apiClient.defaults.adapter = async (config) => { signal = config.signal; started.resolve(); await finish.promise; return response(config, page, 200) }
  const observer = new QueryObserver(queryClient, savedOpportunitiesQuery(11)); const off = observer.subscribe(() => {})
  await started.promise; await resetPersonalizedQueries(queryClient)
  observer.setOptions({ ...savedOpportunitiesQuery(22), enabled: false })
  assert.equal(signal.aborted, true); assert.equal(observer.getCurrentResult().data, undefined)
  finish.resolve(); await new Promise((resolve) => setImmediate(resolve))
  assert.equal(queryClient.getQueryData(savedOpportunitiesQuery(22).queryKey), undefined)
  off(); queryClient.clear()
})
