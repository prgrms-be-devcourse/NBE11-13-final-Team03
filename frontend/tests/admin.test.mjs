import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { ApiError } from '../src/api/ApiError.ts'
import { authQueryKey, resetPersonalizedQueries } from '../src/auth/session.ts'
import { safeReturnPath } from '../src/auth/returnPath.ts'
import { job, preview } from './admin-fixtures.mjs'
import { adminErrorKind, adminErrorMessage, adminKeys, assertAdminOwner, executeRetention, getRawRecord, getRetentionPreview, getSync, isRetentionPreview, isSyncJob,
  listSyncs, parseAdminId, retryableStatuses, retrySync, seoulToday, startSync, syncLimitError } from '../src/api/admin.ts'

const originalAdapter = apiClient.defaults.adapter, originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
afterEach(() => { apiClient.defaults.adapter = originalAdapter; if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument); else delete globalThis.document })
const csrf = () => Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=test-admin' } })
const response = (config, data = '', status = 200) => ({ config, data, status, statusText: '', headers: {} })
const fail = (config, status, code) => new AxiosError('fixture', 'ERR_BAD_RESPONSE', config, {}, response(config, { code, message: 'server', fieldErrors: {} }, status))
test('read APIs use exact admin paths with requiresAuth and validate DTOs, IDs and dates before sending', async () => {
  const calls = []
  apiClient.defaults.adapter = async config => {
    calls.push({ url: config.url, method: config.method, params: config.params, requiresAuth: config.requiresAuth })
    if (config.url === '/admin/collection/syncs') return response(config, [job])
    if (config.url === '/admin/collection/syncs/7') return response(config, job)
    if (config.url === '/admin/collection/raw-records/55') return response(config, { rawRecordId: 55, source: 'EMPLOYMENT24', externalId: 'E-1', payloadHash: 'h', collectedAt: null, lastSeenAt: null, payload: { a: 1 } })
    return response(config, preview)
  }
  assert.equal((await listSyncs('2026-10-31'))[0].jobId, 7)
  assert.equal((await getSync(7)).recordFailures[0].rawRecordId, 55)
  assert.deepEqual((await getRawRecord(55)).payload, { a: 1 })
  assert.equal((await getRetentionPreview()).planToken, 'plan-1')
  assert.deepEqual(calls, [
    { url: '/admin/collection/syncs', method: 'get', params: { date: '2026-10-31' }, requiresAuth: true },
    { url: '/admin/collection/syncs/7', method: 'get', params: undefined, requiresAuth: true },
    { url: '/admin/collection/raw-records/55', method: 'get', params: undefined, requiresAuth: true },
    { url: '/admin/behavior-events/retention/preview', method: 'get', params: undefined, requiresAuth: true },
  ])
  // 잘못된 입력은 요청 없이 거부한다.
  for (const run of [() => listSyncs('2026-02-30'), () => getSync(0), () => getRawRecord(-1)]) await assert.rejects(run(), error => error.kind === 'configuration')
  assert.equal(calls.length, 4)
  assert.equal(isSyncJob({ ...job, status: 'DONE' }), false)
  assert.equal(isSyncJob({ ...job, fetchedCount: -1 }), false)
  assert.equal(isRetentionPreview({ ...preview, policy: { ...preview.policy, adminExecuteEnabled: 'true' } }), false)
  apiClient.defaults.adapter = async config => response(config, { ...job, jobId: 8 })
  await assert.rejects(getSync(7), error => error.kind === 'unknown')
})

test('start and retry send POST with CSRF, only given limits, and accept only 202 SyncStart', async () => {
  csrf()
  const calls = []
  apiClient.defaults.adapter = async config => {
    calls.push({ url: config.url, method: config.method, params: config.params, csrf: config.headers['X-XSRF-TOKEN'], data: config.data })
    return response(config, { jobId: 9, source: 'EMPLOYMENT24', status: 'PENDING', message: 'accepted' }, 202)
  }
  assert.equal((await startSync('EMPLOYMENT24')).jobId, 9)
  await startSync('YOUTH_POLICY', { pageSize: 50 })
  await retrySync(7)
  assert.deepEqual(calls.map(({ url, method, params, csrf: token }) => ({ url, method, params, token })), [
    { url: '/admin/collection/syncs/EMPLOYMENT24', method: 'post', params: {}, token: 'test-admin' },
    { url: '/admin/collection/syncs/YOUTH_POLICY', method: 'post', params: { pageSize: 50 }, token: 'test-admin' },
    { url: '/admin/collection/syncs/7/retry', method: 'post', params: undefined, token: 'test-admin' },
  ])
  assert.equal(syncLimitError({ pageSize: 101 }), '페이지 크기는 1~100 사이 정수예요.')
  assert.equal(syncLimitError({ maxPages: 0 }), '최대 페이지 수는 1~1000 사이 정수예요.')
  await assert.rejects(startSync('UNKNOWN'), error => error.kind === 'configuration')
  await assert.rejects(startSync('YOUTH_POLICY', { maxPages: 1001 }), error => error.kind === 'configuration')
  assert.equal(calls.length, 3)
  apiClient.defaults.adapter = async config => response(config, { jobId: 9, source: 'EMPLOYMENT24', status: 'PENDING', message: '' }, 200)
  await assert.rejects(startSync('EMPLOYMENT24'), error => error.kind === 'unknown')
  assert.deepEqual(retryableStatuses, ['FAILED', 'PARTIAL_SUCCESS', 'INTERRUPTED'])
})

test('retention execute sends only the preview planToken and rejects missing tokens without a request', async () => {
  csrf()
  const bodies = []
  apiClient.defaults.adapter = async config => { bodies.push(JSON.parse(config.data)); return response(config, { status: 'COMPLETED', keepFrom: '2026-07-01', created: [], dropped: ['p'], failed: [], ignored: [], executedAt: '2026-10-31T03:00:00' }) }
  assert.equal((await executeRetention('plan-1')).dropped[0], 'p')
  assert.deepEqual(bodies, [{ planToken: 'plan-1' }])
  await assert.rejects(executeRetention(''), error => error.kind === 'configuration')
  assert.equal(bodies.length, 1)
})

test('403 permission codes are denials, RETENTION_002 is a disabled feature, 401 asks for login', async () => {
  for (const [status, code, kind] of [[403, 'COLLECTION_007', 'denied'], [403, 'RETENTION_001', 'denied'], [403, 'RETENTION_002', 'disabled'], [401, 'AUTH_001', 'login'],
    [403, undefined, 'other'], [409, 'COLLECTION_005', 'other'], [409, 'RETENTION_003', 'other']]) {
    apiClient.defaults.adapter = async config => { throw fail(config, status, code) }
    const error = await getRetentionPreview().catch(value => value)
    assert.ok(error instanceof ApiError)
    assert.equal(adminErrorKind(error), kind, `${status} ${code}`)
  }
  assert.match(adminErrorMessage(new ApiError('http', 'x', 403, { code: 'RETENTION_002', message: 'x', fieldErrors: {} })), /실행이 꺼져/)
  assert.match(adminErrorMessage(new ApiError('http', 'x', 403, { code: 'COLLECTION_007', message: 'x', fieldErrors: {} })), /관리자 권한이 확인되지 않았어요/)
  assert.match(adminErrorMessage(new ApiError('http', 'x', 409, { code: 'COLLECTION_005', message: 'x', fieldErrors: {} })), /이미 대기·실행 중/)
})

test('admin queries are cleared on account change and actions require the same confirmed ADMIN session', async () => {
  const client = new QueryClient()
  const admin = { id: 1, role: 'ADMIN' }
  client.setQueryData(authQueryKey, admin)
  client.setQueryData(adminKeys.retention(1), preview)
  client.setQueryData(adminKeys.syncs(1, '2026-10-31'), [job])
  assert.doesNotThrow(() => assertAdminOwner(client, admin))
  assert.throws(() => assertAdminOwner(client, { id: 2, role: 'USER' }), error => error.kind === 'cancelled')
  const user = { id: 1, role: 'USER' }
  client.setQueryData(authQueryKey, user)
  assert.throws(() => assertAdminOwner(client, user), error => error.kind === 'cancelled')
  await resetPersonalizedQueries(client)
  assert.equal(client.getQueryCache().findAll({ queryKey: ['admin'] }).length, 0)
})

test('admin helpers: Seoul date, positive IDs and login return paths', () => {
  assert.equal(seoulToday(new Date('2026-10-31T16:00:00Z')), '2026-11-01')
  assert.equal(parseAdminId('12'), 12)
  for (const value of ['0', '-1', '1.5', 'abc', '', null, '99999999999999999999']) assert.equal(parseAdminId(value), null)
  for (const path of ['/admin', '/admin/collection', '/admin/raw-records', '/admin/retention']) assert.equal(safeReturnPath(path), path)
  assert.equal(safeReturnPath('/admin/unknown'), '/')
})
