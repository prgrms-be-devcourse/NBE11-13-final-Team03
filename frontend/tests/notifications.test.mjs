import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient, InfiniteQueryObserver, QueryObserver } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { authQueryKey, resetPersonalizedQueries } from '../src/auth/session.ts'
import { getNotifications, getUnreadCount, getNotificationSettings, putNotificationSettings, readNotification, readAllNotifications, clickNotification,
  isNotificationPage, isUnreadCount, isNotificationSettings, notificationKeys, notificationsQuery, unreadCountQuery, notificationSettingsQuery, notificationTarget, notificationError, runNotificationChange } from '../src/api/notifications.ts'
import { ApiError } from '../src/api/ApiError.ts'

const originalAdapter = apiClient.defaults.adapter, originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
afterEach(() => { apiClient.defaults.adapter = originalAdapter; if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument); else delete globalThis.document })
const item = { id: 30, opportunityId: 42, type: 'DEADLINE_SOON', title: '테스트 알림', content: '마감 안내', link: '/opportunities/42', createdAt: '2026-10-26T01:00:00Z', readAt: null, clickedAt: null }
const page = { items: [item], nextCursor: null, hasNext: false }
const settings = { inAppEnabled: true, pushEnabled: false, pushConsentAt: null, updatedAt: null }
const response = (config, data = '', status = 200) => ({ config, data, status, statusText: '', headers: {} })
const errorResponse = (config, status) => new AxiosError('fixture', 'ERR_BAD_RESPONSE', config, {}, response(config, { code: status === 503 ? 'NOTIFICATION_UNAVAILABLE' : 'NOTIFICATION_RESOURCE_NOT_FOUND' }, status))
const csrf = () => Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-notifications' } })
const makeClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done }); return { resolve, promise } }
function setOwner(client) { client.setQueryData(authQueryKey, { id: 11, role: 'USER' }); return client.getQueryData(authQueryKey) }

test('notification DTOs validate nullable Instants, enum, descending unique IDs, cursor progress and unavailable counts', () => {
  assert.equal(isNotificationPage(page), true)
  for (const value of [{ ...page, items: [item, item] }, { ...page, items: [{ ...item, type: 'OTHER' }] }, { ...page, items: [{ ...item, readAt: 'invalid' }] }, { ...page, nextCursor: 30 }, { ...page, hasNext: true, nextCursor: 31 }, { items: [], hasNext: true, nextCursor: 30 }]) assert.equal(isNotificationPage(value), false)
  assert.equal(isUnreadCount({ available: false, unreadCount: null }), true)
  assert.equal(isUnreadCount({ available: true, unreadCount: 0 }), true)
  for (const value of [{ available: false, unreadCount: 0 }, { available: true, unreadCount: null }, { available: true, unreadCount: -1 }]) assert.equal(isUnreadCount(value), false)
  assert.equal(isNotificationSettings(settings), true)
  assert.equal(isNotificationSettings({ ...settings, pushEnabled: 'true' }), false)
})

test('cursor GETs and settings use exact relative paths, auth and signals; invalid cursor/size or repeated page fails', async () => {
  const signal = new AbortController().signal, calls = []
  apiClient.defaults.adapter = async config => {
    calls.push(config); assert.equal(config.requiresAuth, true); assert.equal(config.signal, signal)
    return response(config, config.url === '/notifications' ? page : config.url.endsWith('unread-count') ? { available: false, unreadCount: null } : settings)
  }
  await getNotifications(null, 20, signal); await getNotifications(31, 20, signal); await getUnreadCount(signal); await getNotificationSettings(signal)
  assert.deepEqual(calls.map(call => call.url), ['/notifications', '/notifications', '/notifications/unread-count', '/users/me/notification-settings'])
  assert.deepEqual(calls[0].params, { size: 20 }); assert.deepEqual(calls[1].params, { beforeId: 31, size: 20 })
  await assert.rejects(getNotifications(30), error => error.kind === 'unknown')
  for (const args of [[0, 20], [null, 101], [null, 0], [null, 1.2]]) await assert.rejects(getNotifications(...args), error => error.kind === 'configuration')
})

test('infinite observers use nextCursor and share overlapping more requests without duplicate pages', async () => {
  const client = makeClient(), finish = deferred(), started = deferred(), cursors = []
  apiClient.defaults.adapter = async config => {
    cursors.push(config.params.beforeId ?? null)
    if (!config.params.beforeId) return response(config, { items: [item], hasNext: true, nextCursor: 30 })
    started.resolve(); await finish.promise
    return response(config, { items: [{ ...item, id: 29 }], hasNext: false, nextCursor: null })
  }
  const observer = new InfiniteQueryObserver(client, notificationsQuery(11)), off = observer.subscribe(() => {})
  try {
    await client.fetchInfiniteQuery(notificationsQuery(11))
    const first = observer.fetchNextPage({ cancelRefetch: false }); await started.promise
    const second = observer.fetchNextPage({ cancelRefetch: false }); finish.resolve(); await Promise.all([first, second])
    assert.deepEqual(cursors, [null, 30])
    assert.deepEqual(observer.getCurrentResult().data.pages.flatMap(p => p.items.map(i => i.id)), [30, 29])
    assert.equal(observer.getCurrentResult().hasNextPage, false)
  } finally { finish.resolve(); off(); client.clear() }
})

test('read, read-all and click require exact 204, CSRF and auth; settings PUT requires both booleans and a 200 DTO', async () => {
  csrf(); const calls = []
  apiClient.defaults.adapter = async config => {
    assert.equal(config.requiresAuth, true); assert.equal(config.headers.get('X-XSRF-TOKEN'), 'test-notifications')
    calls.push([config.method, config.url, config.data])
    return response(config, config.method === 'put' ? settings : '', config.method === 'put' ? 200 : 204)
  }
  await readNotification(30); await readAllNotifications(); await clickNotification(30); await putNotificationSettings({ inAppEnabled: false, pushEnabled: false })
  assert.deepEqual(calls.map(call => call.slice(0, 2)), [['patch', '/notifications/30/read'], ['patch', '/notifications/read-all'], ['post', '/notifications/30/click'], ['put', '/users/me/notification-settings']])
  assert.deepEqual(JSON.parse(calls[3][2]), { inAppEnabled: false, pushEnabled: false })
  apiClient.defaults.adapter = async config => response(config, {}, 200)
  await assert.rejects(readNotification(30), error => error.kind === 'unknown')
  await assert.rejects(putNotificationSettings(settings), error => error.kind === 'unknown')
  await assert.rejects(clickNotification(-1), error => error.kind === 'configuration')
})

test('401/404/410/503 with facade code-only bodies and network failures remain errors, not empty results', async () => {
  for (const status of [401, 404, 410, 503]) {
    apiClient.defaults.adapter = async config => { throw errorResponse(config, status) }
    await assert.rejects(getNotifications(), error => error.status === status && !notificationError(error).includes('fixture'))
  }
  apiClient.defaults.adapter = async config => { throw new AxiosError('fixture', 'ERR_NETWORK', config, {}) }
  await assert.rejects(getUnreadCount(), error => error.kind === 'network')
})

test('navigation prefers a valid opportunity ID and rejects external, encoded, authentication and unsafe fallback links', () => {
  assert.equal(notificationTarget({ opportunityId: 42, link: 'https://evil.invalid' }), '/opportunities/42')
  assert.equal(notificationTarget({ opportunityId: null, link: '/opportunities/42' }), '/opportunities/42')
  for (const link of ['https://evil.invalid', '//evil.invalid', 'javascript:alert(1)', '/login', '/opportunities/%34%32', '/opportunities/42?next=evil', '/opportunities/9007199254740992']) assert.equal(notificationTarget({ opportunityId: null, link }), null)
})

test('open shares duplicate work and sends read then click; active list/count refresh without unrelated caches or behavior events', async () => {
  csrf(); const client = makeClient(), owner = setOwner(client), calls = [], started = deferred(), finish = deferred()
  let read = false, clicked = false
  apiClient.defaults.adapter = async config => {
    calls.push(config.url)
    if (config.method === 'get') return response(config, config.url.endsWith('unread-count') ? { available: true, unreadCount: read ? 0 : 1 } : { ...page, items: [{ ...item, readAt: read ? item.createdAt : null, clickedAt: clicked ? item.createdAt : null }] })
    if (config.url.endsWith('/read')) { started.resolve(); await finish.promise; read = true } else { assert.equal(read, true); clicked = true }
    return response(config, '', 204)
  }
  client.setQueryData(['recommendations', 11], [])
  const list = new InfiniteQueryObserver(client, notificationsQuery(11)), count = new QueryObserver(client, unreadCountQuery(11))
  const offList = list.subscribe(() => {}), offCount = count.subscribe(() => {})
  try {
    await Promise.all([client.fetchInfiniteQuery(notificationsQuery(11)), client.fetchQuery(unreadCountQuery(11))])
    const first = runNotificationChange(client, owner, { kind: 'open', id: 30 }), second = runNotificationChange(client, owner, { kind: 'open', id: 30 })
    assert.equal(first, second); await started.promise
    await assert.rejects(runNotificationChange(client, owner, { kind: 'read-all' }), error => error.kind === 'configuration')
    finish.resolve(); await first
    assert.equal(list.getCurrentResult().data.pages[0].items[0].clickedAt, item.createdAt)
    assert.deepEqual(count.getCurrentResult().data, { available: true, unreadCount: 0 })
    assert.equal(client.getQueryState(['recommendations', 11]).isInvalidated, false)
    assert.deepEqual(calls.filter(url => url.endsWith('/read') || url.endsWith('/click')), ['/notifications/30/read', '/notifications/30/click'])
    assert.equal(calls.some(url => url.includes('behavior')), false)
  } finally { finish.resolve(); offList(); offCount(); client.clear() }
})

test('read failure blocks click; click failure preserves read refresh and rejects completion, releasing the lock', async () => {
  csrf(); const client = makeClient(), owner = setOwner(client), calls = []
  client.setQueryData(notificationKeys.list(11), { pages: [page], pageParams: [null] })
  for (const failing of ['read', 'click']) {
    calls.length = 0
    apiClient.defaults.adapter = async config => { calls.push(config.url); if (config.url.endsWith('/' + failing)) throw errorResponse(config, 404); return response(config, '', 204) }
    await assert.rejects(runNotificationChange(client, owner, { kind: 'open', id: 30 }), error => error.status === 404)
    assert.equal(calls.some(url => url.endsWith('/click')), failing === 'click')
    assert.equal(client.getQueryState(notificationKeys.list(11)).isInvalidated, true)
  }
  apiClient.defaults.adapter = async config => response(config, '', 204)
  await runNotificationChange(client, owner, { kind: 'read-all' })
  client.clear()
})

test('settings changes invalidate only own settings and retain the server push setting', async () => {
  csrf(); const client = makeClient(), owner = setOwner(client)
  for (const key of [notificationKeys.settings(11), notificationKeys.list(11), notificationKeys.unread(11), notificationKeys.settings(22)]) client.setQueryData(key, {})
  apiClient.defaults.adapter = async config => { assert.deepEqual(JSON.parse(config.data), { inAppEnabled: false, pushEnabled: true }); return response(config, { ...settings, inAppEnabled: false, pushEnabled: true }) }
  await runNotificationChange(client, owner, { kind: 'settings', values: { inAppEnabled: false, pushEnabled: true } })
  for (const key of [notificationKeys.settings(11), notificationKeys.list(11), notificationKeys.unread(11), notificationKeys.settings(22)]) assert.equal(client.getQueryState(key).isInvalidated, key[1] === 11 && key[2] === 'settings')
  assert.equal(notificationSettingsQuery(11).gcTime, 0)
  client.clear()
})

test('read-all refetches active list and count, including unread notifications beyond the loaded cursor', async () => {
  csrf(); const client = makeClient(), owner = setOwner(client)
  let unread = 12, writes = 0
  apiClient.defaults.adapter = async config => {
    if (config.method === 'get') return response(config, config.url.endsWith('unread-count') ? { available: true, unreadCount: unread } : { ...page, items: [{ ...item, readAt: item.createdAt }] })
    assert.equal(config.url, '/notifications/read-all'); assert.equal(config.method, 'patch')
    writes++; unread = 0; return response(config, '', 204)
  }
  const list = new InfiniteQueryObserver(client, notificationsQuery(11)), count = new QueryObserver(client, unreadCountQuery(11))
  const offList = list.subscribe(() => {}), offCount = count.subscribe(() => {})
  try {
    await Promise.all([client.fetchInfiniteQuery(notificationsQuery(11)), client.fetchQuery(unreadCountQuery(11))])
    assert.equal(count.getCurrentResult().data.unreadCount, 12)
    await runNotificationChange(client, owner, { kind: 'read-all' })
    assert.equal(writes, 1); assert.equal(count.getCurrentResult().data.unreadCount, 0)
    assert.equal(list.getCurrentResult().data.pages[0].items[0].readAt, item.createdAt)
  } finally { offList(); offCount(); client.clear() }
})

test('click failure refreshes an already committed read and unread count while preserving the primary 404 error', async () => {
  csrf(); const client = makeClient(), owner = setOwner(client)
  let read = false
  apiClient.defaults.adapter = async config => {
    if (config.method === 'get') return response(config, config.url.endsWith('unread-count') ? { available: true, unreadCount: read ? 0 : 1 } : { ...page, items: [{ ...item, readAt: read ? item.createdAt : null }] })
    if (config.url.endsWith('/read')) { read = true; return response(config, '', 204) }
    throw errorResponse(config, 404)
  }
  const list = new InfiniteQueryObserver(client, notificationsQuery(11)), count = new QueryObserver(client, unreadCountQuery(11))
  const offList = list.subscribe(() => {}), offCount = count.subscribe(() => {})
  try {
    await Promise.all([client.fetchInfiniteQuery(notificationsQuery(11)), client.fetchQuery(unreadCountQuery(11))])
    await assert.rejects(runNotificationChange(client, owner, { kind: 'open', id: 30 }), error => error.status === 404)
    assert.equal(list.getCurrentResult().data.pages[0].items[0].readAt, item.createdAt)
    assert.equal(count.getCurrentResult().data.unreadCount, 0)
  } finally { offList(); offCount(); client.clear() }
})

test('account transition cancels list GETs, clears all notification caches and blocks a late read from clicking or touching the next account', async () => {
  csrf(); const client = makeClient(), owner = setOwner(client), started = deferred(), finish = deferred(), calls = []
  apiClient.defaults.adapter = async config => { calls.push(config.url); started.resolve(); await finish.promise; return response(config, '', 204) }
  for (const key of [notificationKeys.list(11), notificationKeys.unread(11), notificationKeys.settings(11)]) client.setQueryData(key, {})
  const promise = runNotificationChange(client, owner, { kind: 'open', id: 30 }); await started.promise
  await resetPersonalizedQueries(client)
  client.setQueryData(authQueryKey, { id: 22, role: 'USER' }); client.setQueryData(notificationKeys.unread(22), { available: true, unreadCount: 7 })
  finish.resolve(); await assert.rejects(promise, error => error.kind === 'cancelled')
  assert.deepEqual(calls, ['/notifications/30/read'])
  assert.equal(client.getQueryData(notificationKeys.settings(11)), undefined)
  assert.equal(client.getQueryState(notificationKeys.unread(22)).isInvalidated, false)
  client.clear()
})

test('switching infinite notification observers cancels old transport and never carries previous pages into the next account', async () => {
  const client = makeClient(), started = deferred(), finish = deferred(); let signal
  apiClient.defaults.adapter = async config => { signal = config.signal; started.resolve(); await finish.promise; return response(config, page) }
  const observer = new InfiniteQueryObserver(client, notificationsQuery(11)), off = observer.subscribe(() => {})
  try {
    await started.promise
    observer.setOptions({ ...notificationsQuery(22), enabled: false })
    assert.equal(signal.aborted, true); assert.equal(observer.getCurrentResult().data, undefined)
    finish.resolve(); await new Promise(resolve => setImmediate(resolve))
    assert.equal(client.getQueryData(notificationKeys.list(22)), undefined)
  } finally { finish.resolve(); off(); client.clear() }
})

test('error messages do not expose remote details or treat service unavailability as an empty inbox', () => {
  assert.match(notificationError(new ApiError('http', 'private', 503)), /일시적으로/)
  assert.match(notificationError(new ApiError('http', 'private', 410)), /탈퇴/)
  assert.doesNotMatch(notificationError(new ApiError('http', 'private', 503)), /private|알림이 없/)
})
