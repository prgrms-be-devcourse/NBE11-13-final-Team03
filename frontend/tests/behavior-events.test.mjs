import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import axios, { AxiosError } from 'axios'
import { MutationObserver, QueryClient } from '@tanstack/react-query'

// 재발급용 내부 클라이언트도 같은 모의 adapter를 쓰도록 공통 클라이언트를 불러오기 전에 지정한다.
let handler = () => { throw new Error('adapter not set') }
axios.defaults.adapter = (config) => handler(config)
Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-behavior' } })
const { recordBehaviorEvent, behaviorSourceFromState } = await import('../src/api/behaviorEvents.ts')
const { authQueryKey, authTransitionKey } = await import('../src/auth/session.ts')
const { observeImpression, impressionDelayMs } = await import('../src/pages/home/recommendationImpression.ts')

const user = { id: 11, nickname: '행동 테스트 회원', email: null, role: 'USER' }
const response = (config, data = '', status = 204) => ({ config, data, status, statusText: '', headers: {} })
const reject = (config, status, code = 'TEST_001') => {
  throw new AxiosError('test only', 'ERR_BAD_RESPONSE', config, {}, response(config, { code, message: 'test only', fieldErrors: {} }, status))
}
const signedIn = () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  client.setQueryData(authQueryKey, user)
  return client
}
const impression = { eventType: 'IMPRESSION', source: 'RECOMMENDATION' }
afterEach(() => { handler = () => { throw new Error('adapter not set') } })

test('events use the existing /api client with auth, CSRF and the exact backend DTO fields', async () => {
  const calls = []
  handler = (config) => { calls.push(config); return response(config) }
  const client = signedIn()
  await recordBehaviorEvent(client, 11, 42, impression)
  await recordBehaviorEvent(client, 11, 42, { eventType: 'DETAIL_VIEW', source: 'SEARCH' })
  await recordBehaviorEvent(client, 11, 42, { eventType: 'EXTERNAL_LINK_CLICK', source: 'SAVED', linkType: 'APPLICATION' })
  assert.equal(calls.length, 3)
  for (const config of calls) {
    assert.equal(config.url, '/opportunities/42/events')
    assert.equal(config.baseURL, '/api')
    assert.equal(config.method, 'post')
    assert.equal(config.requiresAuth, true)
    assert.equal(config.headers.get('X-XSRF-TOKEN'), 'test-behavior')
  }
  assert.deepEqual(calls.map(config => JSON.parse(config.data)), [
    { eventType: 'IMPRESSION', source: 'RECOMMENDATION' },
    { eventType: 'DETAIL_VIEW', source: 'SEARCH' },
    { eventType: 'EXTERNAL_LINK_CLICK', source: 'SAVED', linkType: 'APPLICATION' },
  ])
})

test('anonymous, unconfirmed, different or transitioning users and invalid IDs send nothing', async () => {
  const calls = []
  handler = (config) => { calls.push(config.url); return response(config) }
  const anonymous = signedIn(); anonymous.setQueryData(authQueryKey, null)
  await recordBehaviorEvent(anonymous, 11, 42, impression)
  await recordBehaviorEvent(new QueryClient(), 11, 42, impression)
  await recordBehaviorEvent(signedIn(), 12, 42, impression)
  for (const id of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1]) await recordBehaviorEvent(signedIn(), 11, id, impression)

  const transitioning = signedIn()
  let finish
  const transition = new MutationObserver(transitioning, { mutationKey: authTransitionKey, mutationFn: () => new Promise((resolve) => { finish = resolve }) })
  const pending = transition.mutate()
  await recordBehaviorEvent(transitioning, 11, 42, impression)
  finish(); await pending
  assert.deepEqual(calls, [])
  await recordBehaviorEvent(transitioning, 11, 42, impression)
  assert.deepEqual(calls, ['/opportunities/42/events'])
})

test('400/401/403/404/500, network and timeout failures resolve quietly after one request without retry', async () => {
  const failures = [
    (config) => reject(config, 400), (config) => reject(config, 401, 'AUTH_001'), (config) => reject(config, 403),
    (config) => reject(config, 404, 'OPPORTUNITY_001'), (config) => reject(config, 500),
    (config) => { throw new AxiosError('network', 'ERR_NETWORK', config) },
    (config) => { throw new AxiosError('timeout', 'ECONNABORTED', config) },
  ]
  for (const fail of failures) {
    let count = 0
    handler = (config) => { count += 1; return fail(config) }
    assert.equal(await recordBehaviorEvent(signedIn(), 11, 42, impression), undefined)
    assert.equal(count, 1)
  }
})

test('an expired access token follows the shared single reissue and stops when reissue fails', async () => {
  const calls = []
  handler = (config) => { calls.push(config.url); return config.url === '/auth/reissue' ? reject(config, 401, 'AUTH_003') : reject(config, 401, 'AUTH_006') }
  await recordBehaviorEvent(signedIn(), 11, 42, impression)
  assert.deepEqual(calls, ['/opportunities/42/events', '/auth/reissue'])
})

test('only sources written by in-app links are accepted; other state values fall back to OTHER', () => {
  for (const source of ['RECOMMENDATION', 'SEARCH', 'SAVED', 'OTHER']) assert.equal(behaviorSourceFromState({ behaviorSource: source }), source)
  for (const state of [undefined, null, 'SEARCH', 1, {}, { behaviorSource: 'search' }, { behaviorSource: 'SAVE' }, { behaviorSource: ['SEARCH'] },
    { behaviorSource: { toString: () => 'SEARCH' } }, { searchReturn: '/opportunities?type=YOUTH_POLICY' }, JSON.parse('{"__proto__":{"behaviorSource":"SEARCH"}}')]) {
    assert.equal(behaviorSourceFromState(state), 'OTHER')
  }
})

function impressionEnvironment() {
  const observers = [], listeners = new Set()
  globalThis.IntersectionObserver = class {
    constructor(callback, options) { this.callback = callback; this.options = options; this.disconnected = false; observers.push(this) }
    observe(element) { this.element = element }
    disconnect() { this.disconnected = true }
    emit(ratio) { this.callback([{ isIntersecting: ratio > 0, intersectionRatio: ratio }]) }
  }
  const document = {
    cookie: 'XSRF-TOKEN=test-behavior', visibilityState: 'visible',
    addEventListener: (type, listener) => { if (type === 'visibilitychange') listeners.add(listener) },
    removeEventListener: (type, listener) => { if (type === 'visibilitychange') listeners.delete(listener) },
  }
  Object.defineProperty(globalThis, 'document', { configurable: true, value: document })
  const setVisibility = (state) => { document.visibilityState = state; for (const listener of [...listeners]) listener() }
  return { observers, listeners, setVisibility }
}

test('impression needs 50% visibility for one continuous second and fires once', (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const { observers, listeners } = impressionEnvironment()
  let count = 0
  const element = {}
  observeImpression(element, () => { count += 1 })
  const [observer] = observers
  assert.equal(observer.element, element)
  assert.deepEqual(observer.options, { threshold: 0.5 })

  observer.emit(0); context.mock.timers.tick(impressionDelayMs * 3)
  assert.equal(count, 0, 'off-screen card')
  observer.emit(0.49); context.mock.timers.tick(impressionDelayMs * 3)
  assert.equal(count, 0, 'less than half visible')
  observer.emit(0.5); context.mock.timers.tick(impressionDelayMs - 1); observer.emit(0.2); context.mock.timers.tick(impressionDelayMs * 3)
  assert.equal(count, 0, 'visible for less than one second')
  observer.emit(0.8); context.mock.timers.tick(impressionDelayMs - 1); observer.emit(1); context.mock.timers.tick(1)
  assert.equal(count, 1, 'continuous visibility across ratio changes')
  assert.equal(observer.disconnected, true)
  assert.equal(listeners.size, 0)
  observer.emit(1); context.mock.timers.tick(impressionDelayMs * 3)
  assert.equal(count, 1)
})

test('hidden tab interrupts the visible second and unmount cleanup cancels a pending impression', (context) => {
  context.mock.timers.enable({ apis: ['setTimeout'] })
  const { observers, listeners, setVisibility } = impressionEnvironment()
  let count = 0
  const stop = observeImpression({}, () => { count += 1 })
  const [observer] = observers
  observer.emit(1); context.mock.timers.tick(impressionDelayMs - 1)
  setVisibility('hidden'); context.mock.timers.tick(impressionDelayMs * 3)
  assert.equal(count, 0)
  setVisibility('visible'); context.mock.timers.tick(impressionDelayMs - 1)
  assert.equal(count, 0, 'the second restarts after the tab is visible again')
  stop(); context.mock.timers.tick(impressionDelayMs * 3)
  assert.equal(count, 0)
  assert.equal(observer.disconnected, true)
  assert.equal(listeners.size, 0)
})

test('impression observation is a no-op without IntersectionObserver', () => {
  impressionEnvironment(); delete globalThis.IntersectionObserver
  let count = 0
  const stop = observeImpression({}, () => { count += 1 })
  stop()
  assert.equal(count, 0)
})
