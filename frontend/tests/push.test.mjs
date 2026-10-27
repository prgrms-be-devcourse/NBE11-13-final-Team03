import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import fs from 'node:fs'
import { AxiosError } from 'axios'
import { QueryClient } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { ApiError } from '../src/api/ApiError.ts'
import { registerPushEndpoint, deactivatePushEndpoint } from '../src/api/push.ts'
import { readPushConfig, supportsPush, pushEnvironmentKeys } from '../src/push/config.ts'
import { createPushController } from '../src/push/model.ts'
import { emptyPushStore, parsePushStore, pushStorageKey } from '../src/push/storage.ts'
import { parsePushClick, pushTarget, pushClickPath } from '../src/push/protocol.ts'
import { safeReturnPath, loginPath } from '../src/auth/returnPath.ts'
import { changePushIdentity, releasePushSession, pushLock } from '../src/push/lifecycle.ts'
import { createAuthActions, authQueryKey, authQueryOptions } from '../src/auth/session.ts'
import { getNotificationSettings, putNotificationSettings, runNotificationChange } from '../src/api/notifications.ts'

const adapter = apiClient.defaults.adapter, descriptors = Object.fromEntries(['document', 'localStorage', 'navigator'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
afterEach(() => {
  apiClient.defaults.adapter = adapter
  for (const [key, value] of Object.entries(descriptors)) { if (value) Object.defineProperty(globalThis, key, value); else delete globalThis[key] }
})
const response = (config, data = '', status = 200) => ({ config, data, status, statusText: '', headers: {} })
const rejectStatus = (config, status) => { throw new AxiosError('fixture', 'ERR_BAD_RESPONSE', config, {}, response(config, { code: 'COMMON_500', message: 'fixture', fieldErrors: {} }, status)) }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { resolve, promise } }
function fixture(overrides = {}, initial = emptyPushStore()) {
  let store = structuredClone(initial)
  const calls = [], settings = { inAppEnabled: false, pushEnabled: false, pushConsentAt: null, updatedAt: null }
  const deps = {
    ready: true, supported: true, permission: () => 'default', requestPermission: async () => { calls.push('permission'); return 'granted' },
    lock: async work => work(), assertOwner: () => {}, load: () => structuredClone(store), save: next => { store = structuredClone(next) },
    token: async () => { calls.push('token'); return 'test-only-token' }, removeToken: async () => { calls.push('remove-token') },
    settings: async () => { calls.push('settings'); return { ...settings } }, update: async value => { calls.push(['update', value]); Object.assign(settings, value); return { ...settings } },
    register: async token => { assert.equal(token, 'test-only-token'); calls.push('register'); return { id: 7, active: true } },
    deactivate: async id => { calls.push(['deactivate', id]) }, ...overrides,
  }
  return { controller: createPushController(11, deps), calls, settings, store: () => store }
}
test('missing Firebase fields and unsupported/secure-context capabilities never become a configured browser', () => {
  assert.equal(readPushConfig({}), null)
  const env = Object.fromEntries(pushEnvironmentKeys.map(key => [key, 'fixture-public-value']))
  assert.ok(readPushConfig(env))
  for (const key of pushEnvironmentKeys) assert.equal(readPushConfig({ ...env, [key]: '' }), null)
  const envSupport = { secure: true, notification: true, serviceWorker: true, pushManager: true, locks: true }
  assert.equal(supportsPush(envSupport), true)
  for (const key of Object.keys(envSupport)) assert.equal(supportsPush({ ...envSupport, [key]: false }), false)
})

for (const [name, overrides, expected] of [
  ['configuration missing', { ready: false }, /지금은 푸시 알림을 사용할 수 없어요/], ['unsupported browser', { supported: false }, /이 브라우저에서는 푸시 알림을 사용할 수 없어요/],
  ['permission denied', { requestPermission: async () => 'denied' }, /권한/], ['permission dismissed', { requestPermission: async () => 'default' }, /권한/],
  ['worker registration failed', { token: async () => { throw Error('worker fixture') } }, /worker fixture/],
  ['FCM token failed', { token: async () => { throw Error('token fixture') } }, /token fixture/],
  ['endpoint registration failed', { register: async () => { throw Error('endpoint fixture') } }, /endpoint fixture/],
]) test(`enable failure: ${name} never claims active subscription`, async () => {
  const f = fixture(overrides)
  await assert.rejects(f.controller.enable(), expected)
  assert.notEqual(f.store().users[11]?.phase, 'active')
  assert.equal(f.calls.some(call => Array.isArray(call) && call[0] === 'update'), false)
  if (!f.store().users[11]) assert.equal(f.calls.includes('register'), false)
})

test('permission is requested synchronously only from explicit enable; complete registration preserves latest in-app setting', async () => {
  const f = fixture()
  const promise = f.controller.enable()
  assert.deepEqual(f.calls, ['permission'])
  await promise
  assert.deepEqual(f.calls, ['permission', 'settings', 'token', 'register', ['update', { inAppEnabled: false, pushEnabled: true }]])
  assert.deepEqual(f.store(), { binding: 11, users: { 11: { endpointIds: [7], phase: 'active' } } })
  const automatic = fixture()
  await assert.rejects(automatic.controller.enable(false), /권한/)
  assert.deepEqual(automatic.calls, [])
})

test('registered endpoint survives settings failure as partial state; retry reuses the endpoint rather than faking success', async () => {
  let fail = true
  const f = fixture({ update: async value => { if (fail) throw Error('settings fixture'); return { ...value, pushConsentAt: null, updatedAt: null } } })
  await assert.rejects(f.controller.enable(), /settings fixture/)
  assert.deepEqual(f.store().users[11], { endpointIds: [7], phase: 'pending' })
  assert.equal(f.store().binding, 11)
  fail = false; await f.controller.enable()
  assert.deepEqual(f.store().users[11], { endpointIds: [7], phase: 'active' })
})

test('rapid duplicate enable shares one permission/token/register flow and competing action is rejected', async () => {
  const waiting = deferred(), f = fixture({ requestPermission: () => waiting.promise })
  const a = f.controller.enable(), b = f.controller.enable()
  assert.equal(a, b)
  await assert.rejects(f.controller.disable(), /다른 푸시 알림 작업을 처리하고 있어요/)
  waiting.resolve('granted'); await a
  assert.equal(f.calls.filter(call => call === 'register').length, 1)
})

test('token rotation registers a new endpoint and retires the exact known old endpoint', async () => {
  const f = fixture({ permission: () => 'granted', settings: async () => ({ inAppEnabled: false, pushEnabled: true }) }, { binding: 11, users: { 11: { endpointIds: [3], phase: 'active' } } })
  await f.controller.enable(false)
  assert.ok(f.calls.some(call => Array.isArray(call) && call[0] === 'deactivate' && call[1] === 3))
  assert.deepEqual(f.store().users[11].endpointIds, [7])
  assert.equal(f.calls.includes('permission'), false)
})

test('rotation cleanup failure retains both IDs and can recover on retry', async () => {
  let fail = true
  const f = fixture({ permission: () => 'granted', deactivate: async () => { if (fail) throw Error('cleanup') } }, { binding: 11, users: { 11: { endpointIds: [3], phase: 'active' } } })
  await assert.rejects(f.controller.enable(), /cleanup/)
  assert.deepEqual(f.store().users[11], { endpointIds: [3, 7], phase: 'pending' })
  fail = false; await f.controller.enable()
  assert.deepEqual(f.store().users[11], { endpointIds: [7], phase: 'active' })
})

test('browser detach removes only its known endpoints and local token without turning other devices off', async () => {
  const f = fixture({}, { binding: 11, users: { 11: { endpointIds: [7], phase: 'active' } } })
  await f.controller.disable()
  assert.deepEqual(f.calls, [['deactivate', 7], 'remove-token'])
  assert.deepEqual(f.store(), emptyPushStore())
})

test('global off preserves in-app setting and does not invent an endpoint ID when storage is missing', async () => {
  const f = fixture()
  await f.controller.disable(true)
  assert.deepEqual(f.calls, ['settings', ['update', { inAppEnabled: false, pushEnabled: false }], 'remove-token'])
})

test('failed endpoint deletion still revokes local reception and retains exact cleanup IDs', async () => {
  const f = fixture({ deactivate: async () => { throw Error('server fixture') } }, { binding: 11, users: { 11: { endpointIds: [7], phase: 'active' } } })
  await assert.rejects(f.controller.disable(), /해제가 모두 끝났는지 확인하지 못했어요/)
  assert.ok(f.calls.includes('remove-token'))
  assert.deepEqual(f.store(), { binding: null, users: { 11: { endpointIds: [7], phase: 'cleanup' } } })
})

test('failed global settings update or local unsubscribe never reports successful disable', async () => {
  const f = fixture({ update: async () => { throw Error('settings fixture') } }, { binding: 11, users: { 11: { endpointIds: [7], phase: 'active' } } })
  await assert.rejects(f.controller.disable(true), /settings fixture/)
  assert.equal(f.calls.includes('remove-token'), false)
  const local = fixture({ removeToken: async () => { throw Error('unsubscribe fixture') } })
  await assert.rejects(local.controller.disable(), /unsubscribe fixture/)
})

test('different account revokes the previous token before issuing a new token and never sends foreign endpoint IDs', async () => {
  const f = fixture({}, { binding: 22, users: { 22: { endpointIds: [90], phase: 'active' } } })
  await f.controller.enable()
  assert.ok(f.calls.indexOf('remove-token') < f.calls.indexOf('token'))
  assert.equal(f.calls.some(call => Array.isArray(call) && call[0] === 'deactivate' && call[1] === 90), false)
  assert.deepEqual(f.store().users[22], { endpointIds: [90], phase: 'cleanup' })
})

test('lost local ownership metadata with endpoint conflict rotates the token once rather than binding another account token', async () => {
  let calls = 0
  const f = fixture({ register: async () => { calls++; if (calls === 1) throw new ApiError('http', 'fixture', 409); return { id: 8, active: true } } })
  await f.controller.enable()
  assert.equal(calls, 2)
  assert.equal(f.calls.filter(call => call === 'token').length, 2)
  assert.equal(f.calls.filter(call => call === 'remove-token').length, 1)
  assert.deepEqual(f.store().users[11], { endpointIds: [8], phase: 'active' })
  const failed = fixture({ register: async () => { throw new ApiError('http', 'fixture', 409) } })
  await assert.rejects(failed.controller.enable(), error => error.status === 409)
  assert.equal(failed.calls.filter(call => call === 'token').length, 2)
})

test('account transition during endpoint registration preserves original owner metadata and blocks settings commit', async () => {
  let current = true
  const f = fixture({ assertOwner: () => { if (!current) throw Error('account changed') }, register: async () => { current = false; return { id: 7, active: true } } })
  await assert.rejects(f.controller.enable(), /account changed/)
  assert.deepEqual(f.store().users[11], { endpointIds: [7], phase: 'pending' })
  assert.equal(f.calls.some(call => Array.isArray(call) && call[0] === 'update'), false)
})

test('automatic reconnect never re-enables a server-disabled account or prompts for permission', async () => {
  const f = fixture({ permission: () => 'granted' }, { binding: 11, users: { 11: { endpointIds: [7], phase: 'active' } } })
  await f.controller.enable(false)
  assert.deepEqual(f.calls, ['settings', 'remove-token'])
  assert.equal(f.store().binding, null)
})

test('backend endpoint API validates real FCM token format, 200 active response and exact 204 delete without leaking a raw request', async () => {
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-push' } })
  const calls = []
  apiClient.defaults.adapter = async config => {
    assert.equal(config.requiresAuth, true); assert.equal(config.headers.get('X-XSRF-TOKEN'), 'test-push')
    calls.push(config)
    return response(config, config.method === 'post' ? { id: 7, active: true } : '', config.method === 'post' ? 200 : 204)
  }
  assert.deepEqual(await registerPushEndpoint('fixture-token'), { id: 7, active: true }); await deactivatePushEndpoint(7)
  assert.equal(calls[0].url, '/users/me/notification-endpoints'); assert.deepEqual(JSON.parse(calls[0].data), { fcmToken: 'fixture-token' })
  assert.equal(calls[1].url, '/users/me/notification-endpoints/7')
  for (const value of ['', 'a b', 'x'.repeat(2049)]) await assert.rejects(registerPushEndpoint(value), error => error.kind === 'configuration')
  apiClient.defaults.adapter = async config => response(config, { id: 7, active: false }, 200)
  await assert.rejects(registerPushEndpoint('fixture-token'), error => error.kind === 'unknown')
  await assert.rejects(deactivatePushEndpoint(7), error => error.kind === 'unknown')
  apiClient.defaults.adapter = async config => { throw new AxiosError('fixture-token', 'ERR_BAD_RESPONSE', config, {}, response(config, { code: 'NOTIFICATION_ENDPOINT_CONFLICT' }, 409)) }
  await assert.rejects(registerPushEndpoint('fixture-token'), error => error.status === 409 && !error.message.includes('fixture-token') && !('config' in error))
})

test('safe push targets preserve notificationId through login without trusting external links, schemes or malformed payloads', () => {
  const payload = { data: { notificationId: '7' }, fcmOptions: { link: 'https://yon.test/opportunities/42' } }
  assert.deepEqual(pushTarget(payload, 'https://yon.test'), { notificationId: 7, opportunityId: 42 })
  assert.equal(pushClickPath(pushTarget(payload, 'https://yon.test')), '/notifications?pushId=7&opportunityId=42')
  assert.equal(safeReturnPath('/notifications?pushId=7&opportunityId=42&secret=drop'), '/notifications?pushId=7&opportunityId=42')
  assert.ok(loginPath('/notifications?pushId=7&opportunityId=42').includes('pushId%3D7'))
  for (const link of ['https://evil.test/opportunities/42', 'javascript:alert(1)', '//evil.test/opportunities/42', '/login', '/opportunities/%34%32', '/opportunities/42?x=y']) assert.equal(pushTarget({ ...payload, fcmOptions: { link } }, 'https://yon.test'), null)
  for (const value of [null, {}, { data: null }, { data: { notificationId: '7' }, fcmOptions: { link: 3 } }]) assert.equal(pushTarget(value, 'https://yon.test'), null)
  assert.equal(parsePushClick('?pushId=0&opportunityId=42'), null)
})

test('push storage contains only bound account/endpoint IDs and partial phase, and rejects malformed records', () => {
  const record = { binding: 11, users: { 11: { endpointIds: [7], phase: 'pending' } } }
  assert.deepEqual(parsePushStore(JSON.stringify(record)), record)
  assert.deepEqual(parsePushStore(null), emptyPushStore())
  for (const value of ['{}', '{"binding":null,"users":[]}', '{"binding":null,"users":{"11":{"endpointIds":[-1],"phase":"active"}}}']) assert.throws(() => parsePushStore(value))
  assert.doesNotMatch(JSON.stringify(record), /token|cookie|secret/i)
})

function sessionFixture() {
  let value = JSON.stringify({ binding: 11, users: { 11: { endpointIds: [7], phase: 'active' } } }), unsubscribed = 0
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: key => key === pushStorageKey ? value : null, setItem: (_key, next) => { value = next } } })
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: { request: async (_name, _options, callback) => callback({}) }, serviceWorker: { getRegistrations: async () => [{ scope: 'https://yon.test/assets/yon-fcm-push/', pushManager: { getSubscription: async () => ({ unsubscribe: async () => { unsubscribed++; return true } }) }, getNotifications: async () => [] }] } } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-push' } })
  return { store: () => parsePushStore(value), unsubscribed: () => unsubscribed }
}

function orphanFixture({ raw = null, subscribed = true, worker = true, locks = true } = {}) {
  let active = subscribed, unsubscribes = 0, foreignReads = 0, inspections = 0
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => raw, setItem: (_key, next) => { raw = next } } })
  const own = { scope: 'https://yon.test/assets/yon-fcm-push/', pushManager: { getSubscription: async () => active ? { unsubscribe: async () => { unsubscribes++; active = false; return true } } : null }, getNotifications: async () => [] }
  const foreign = { scope: 'https://yon.test/unrelated/', pushManager: { getSubscription: async () => { foreignReads++; throw Error('unrelated subscription must stay untouched') } } }
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
    ...(locks ? { locks: { request: async (_name, _options, work) => work({}) } } : {}),
    ...(worker ? { serviceWorker: { getRegistrations: async () => { inspections++; return [own, foreign] } } } : {}),
  } })
  return { active: () => active, unsubscribes: () => unsubscribes, foreignReads: () => foreignReads, inspections: () => inspections }
}

for (const action of ['account-change', 'logout']) test(`missing push ownership metadata: ${action} revokes only the dedicated browser subscription without inventing endpoint IDs`, async () => {
  const f = orphanFixture()
  apiClient.defaults.adapter = async () => { throw Error('unknown server endpoint must not be deleted') }
  await (action === 'account-change' ? changePushIdentity(22) : releasePushSession(11))
  assert.equal(f.active(), false)
  assert.equal(f.unsubscribes(), 1)
  assert.equal(f.foreignReads(), 0)
})

test('missing worker, absent subscription, invalid metadata and cleanup without Web Locks remain safe', async () => {
  for (const options of [{ worker: false }, { subscribed: false }, { raw: '{broken-json' }, { locks: false }]) {
    const f = orphanFixture(options)
    await changePushIdentity(22)
    await releasePushSession(22)
    assert.equal(f.foreignReads(), 0)
    if (options.worker === false) assert.equal(f.inspections(), 0)
    else assert.equal(f.active(), false)
  }
})

for (const action of ['account-change', 'logout']) test(`failed dedicated unsubscribe: ${action} rejects and retains the old binding for retry`, async () => {
  const f = sessionFixture()
  let fail = true
  navigator.serviceWorker.getRegistrations = async () => [{ scope: 'https://yon.test/assets/yon-fcm-push/', pushManager: { getSubscription: async () => ({ unsubscribe: async () => !fail }) }, getNotifications: async () => [] }]
  apiClient.defaults.adapter = async config => response(config, '', 204)
  const run = () => action === 'account-change' ? changePushIdentity(22) : releasePushSession(11)
  await assert.rejects(run(), /해제를 확인하지 못했어요/)
  assert.equal(f.store().binding, 11)
  fail = false
  await run()
  assert.equal(f.store().binding, null)
})

test('logout with missing ownership metadata cleans local reception before confirmed server logout', async () => {
  const f = orphanFixture(), calls = [], client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-only' } })
  client.setQueryData(authQueryKey, { id: 11, role: 'USER' })
  apiClient.defaults.adapter = async config => {
    calls.push(config.url)
    assert.equal(f.active(), false)
    return response(config, '', 204)
  }
  try {
    await createAuthActions(client).logout()
    assert.deepEqual(calls, ['/auth/logout'])
    assert.equal(client.getQueryData(authQueryKey), null)
  } finally { client.clear() }
})

test('subscription inspection failure with lost metadata still sends cookie logout and clears the confirmed user', async () => {
  orphanFixture()
  navigator.serviceWorker.getRegistrations = async () => { throw new Error('fixture subscription inspection failed') }
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-only' } })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(authQueryKey, { id: 11, role: 'USER' })
  const calls = []
  apiClient.defaults.adapter = async config => { calls.push(config.url); return response(config, '', 204) }
  try {
    assert.equal(await createAuthActions(client).logout(), null)
    assert.deepEqual(calls, ['/auth/logout'])
    assert.equal(client.getQueryData(authQueryKey), null)
  } finally { client.clear() }
})

function busyLockOnce() {
  let busy = true, requests = 0
  navigator.locks = { request: async (_name, _options, callback) => { requests++; const held = busy; busy = false; return callback(held ? null : {}) } }
  return { requests: () => requests }
}
for (const kind of ['logout', 'withdraw']) test(`push lock held by another tab still sends ${kind}, keeps cleanup unconfirmed, then retries local revoke after 204`, async () => {
  const f = sessionFixture(), lock = busyLockOnce(), calls = [], client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(authQueryKey, { id: 11, role: 'USER' })
  apiClient.defaults.adapter = async config => {
    calls.push(`${config.method.toUpperCase()} ${config.url}`)
    // 정리 실패를 성공으로 기록하지 않은 상태에서 서버 요청을 보낸다.
    assert.equal(f.store().binding, 11)
    assert.equal(f.unsubscribed(), 0)
    return response(config, '', 204)
  }
  try {
    assert.equal(await createAuthActions(client)[kind](), null)
    assert.deepEqual(calls, [kind === 'withdraw' ? 'DELETE /users/me' : 'POST /auth/logout'])
    assert.equal(client.getQueryData(authQueryKey), null)
    assert.equal(lock.requests(), 2)
    // 쿠키가 없으므로 서버 DELETE 없이 로컬 수신만 끊고, 재시도할 endpoint ID는 원래 소유자에게 남긴다.
    assert.equal(f.unsubscribed(), 1)
    assert.deepEqual(f.store(), { binding: null, users: { 11: { endpointIds: [7], phase: 'cleanup' } } })
  } finally { client.clear() }
})
test('push cleanup failure never converts a failed server logout into success', async () => {
  const f = sessionFixture(), client = new QueryClient({ defaultOptions: { queries: { retry: false } } }), owner = { id: 11, role: 'USER' }
  navigator.locks = { request: async (_name, _options, callback) => callback(null) }
  client.setQueryData(authQueryKey, owner)
  apiClient.defaults.adapter = async config => rejectStatus(config, 500)
  try {
    await assert.rejects(createAuthActions(client).logout(), error => error instanceof ApiError && error.status === 500)
    assert.equal(client.getQueryData(authQueryKey), owner)
    assert.equal(f.store().binding, 11)
  } finally { client.clear() }
})
test('auth check succeeds while another tab holds the push lock, keeps the old binding and retries cleanup on the next check', async () => {
  const f = sessionFixture(), lock = busyLockOnce(), client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  apiClient.defaults.adapter = async config => { assert.equal(config.url, '/users/me'); return response(config, { id: 22, nickname: null, email: null, role: 'USER' }) }
  try {
    assert.equal((await client.fetchQuery(authQueryOptions(client))).id, 22)
    assert.equal(f.store().binding, 11)
    assert.equal(f.unsubscribed(), 0)
    assert.equal((await client.fetchQuery({ ...authQueryOptions(client), staleTime: 0 })).id, 22)
    assert.equal(lock.requests(), 2)
    assert.equal(f.unsubscribed(), 1)
    assert.deepEqual(f.store(), { binding: null, users: { 11: { endpointIds: [7], phase: 'cleanup' } } })
  } finally { client.clear() }
})
test('auth check for a browser without push binding or dedicated subscription takes no lock and revokes nothing', async () => {
  const f = orphanFixture({ subscribed: false })
  let locks = 0
  navigator.locks = { request: async () => { locks++; throw Error('lock must not be requested') } }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-only' } })
  apiClient.defaults.adapter = async config => response(config, { id: 22, nickname: null, email: null, role: 'USER' })
  try {
    for (let index = 0; index < 3; index++) assert.equal((await client.fetchQuery({ ...authQueryOptions(client), staleTime: 0 })).id, 22)
    assert.equal(locks, 0)
    assert.equal(f.unsubscribes(), 0)
  } finally { client.clear() }
})

test('automatic token reconnect and a concurrent real notification settings mutation preserve the last user save without a reconnect PUT', async () => {
  const tokenStarted = deferred(), finishToken = deferred()
  let settings = { inAppEnabled: true, pushEnabled: true, pushConsentAt: null, updatedAt: null }
  const puts = [], client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  const owner = { id: 11, role: 'USER' }
  client.setQueryData(authQueryKey, owner)
  Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-only' } })
  apiClient.defaults.adapter = async config => {
    assert.equal(config.url, '/users/me/notification-settings')
    if (config.method === 'put') {
      const value = typeof config.data === 'string' ? JSON.parse(config.data) : config.data
      puts.push(value); settings = { ...settings, ...value }
    }
    return response(config, { ...settings })
  }
  const f = fixture({ permission: () => 'granted', settings: getNotificationSettings, update: putNotificationSettings,
    token: async () => { tokenStarted.resolve(); await finishToken.promise; return 'test-only-token' },
  }, { binding: 11, users: { 11: { endpointIds: [7], phase: 'active' } } })
  try {
    const reconnect = f.controller.enable(false)
    await tokenStarted.promise
    await runNotificationChange(client, owner, { kind: 'settings', values: { inAppEnabled: false, pushEnabled: true } })
    finishToken.resolve(); await reconnect
    assert.deepEqual(puts, [{ inAppEnabled: false, pushEnabled: true }])
    assert.equal(settings.inAppEnabled, false)
    assert.equal(f.store().users[11].phase, 'active')
    assert.ok(f.calls.includes('register'))
    await runNotificationChange(client, owner, { kind: 'settings', values: { inAppEnabled: true, pushEnabled: false } })
    await f.controller.enable(false)
    assert.deepEqual(puts.at(-1), { inAppEnabled: true, pushEnabled: false })
    assert.equal(puts.length, 2)
    assert.ok(f.calls.includes('remove-token'))
  } finally { finishToken.resolve(); client.clear() }
})
test('verified account change revokes local subscription, retains old cleanup IDs and sends no foreign authenticated DELETE', async () => {
  const f = sessionFixture()
  apiClient.defaults.adapter = async () => { throw Error('must not call old account API') }
  await changePushIdentity(22)
  assert.equal(f.unsubscribed(), 1)
  assert.deepEqual(f.store(), { binding: null, users: { 11: { endpointIds: [7], phase: 'cleanup' } } })
})
test('logout deactivates known owned endpoint before cookie logout; transport failure still stops local push and retains cleanup IDs', async () => {
  const f = sessionFixture()
  apiClient.defaults.adapter = async config => { assert.equal(config.url, '/users/me/notification-endpoints/7'); throw new AxiosError('fixture', 'ERR_NETWORK', config, {}) }
  await releasePushSession(11)
  assert.equal(f.unsubscribed(), 1)
  assert.deepEqual(f.store().users[11], { endpointIds: [7], phase: 'cleanup' })
  assert.equal(f.store().binding, null)
})

test('another tab holding the browser lock blocks state changes rather than issuing competing registrations', async () => {
  let ran = false
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: { request: async (_name, options, callback) => { assert.equal(options.ifAvailable, true); return callback(null) } } } })
  await assert.rejects(pushLock(async () => { ran = true }), /다른 탭/)
  assert.equal(ran, false)
})
test('permission revocation cleanup never requests permission again or changes other-device preferences', async () => {
  const f = fixture({ permission: () => 'denied' }, { binding: 11, users: { 11: { endpointIds: [7], phase: 'active' } } })
  await f.controller.disable()
  assert.deepEqual(f.calls, [['deactivate', 7], 'remove-token'])
})
test('actual auth logout pipeline detaches the browser before server cookie logout and clears confirmed auth only after 204', async () => {
  const f = sessionFixture(), calls = [], client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(authQueryKey, { id: 11, role: 'USER' })
  apiClient.defaults.adapter = async config => { calls.push(config.url); if (config.url === '/auth/logout') assert.equal(f.unsubscribed(), 1); return response(config, '', 204) }
  try {
    await createAuthActions(client).logout()
    assert.deepEqual(calls, ['/users/me/notification-endpoints/7', '/auth/logout'])
    assert.equal(client.getQueryData(authQueryKey), null)
    assert.deepEqual(f.store(), emptyPushStore())
  } finally { client.clear() }
})

test('FCM worker leaves background display to SDK, intercepts safe clicks and never performs authenticated read/click calls', () => {
  const sw = fs.readFileSync(new URL('../src/push/firebase-messaging-sw.ts', import.meta.url), 'utf8')
  assert.ok(sw.indexOf("addEventListener('notificationclick'") < sw.indexOf('getMessaging(initializeApp'))
  assert.match(sw, /onBackgroundMessage\(messaging, \(\) => \{\}\)/)
  assert.doesNotMatch(sw, /\.showNotification\(|apiClient|document\.cookie|fetch\(/)
  // 전용 scope 워커는 앱 탭을 제어하지 않으므로 navigate() 대신 앱 라우터에 이동을 요청한다.
  assert.doesNotMatch(sw, /\.navigate\(/); assert.match(sw, /client\.postMessage\(/); assert.match(sw, /clients\.openWindow\(/)
  const runtime = fs.readFileSync(new URL('../src/push/browser.ts', import.meta.url), 'utf8')
  assert.match(runtime, /onMessage\(messaging/); assert.match(runtime, /seen\.has\(messageId\)/)
  assert.doesNotMatch(runtime, /new Notification\(|\.showNotification\(|console\./)
})
