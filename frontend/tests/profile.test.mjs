import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient, QueryObserver, MutationObserver } from '@tanstack/react-query'
import { apiClient, endAuthSession } from '../src/api/client.ts'
import { ApiError } from '../src/api/ApiError.ts'
import { authQueryKey, authTransitionKey, resetPersonalizedQueries } from '../src/auth/session.ts'
import { getProfile, getInterests, parseProfile, parseInterests, profileQuery, interestsQuery, saveProfile, saveInterests, interestKey } from '../src/api/profile.ts'
import { emptyProfile, choices, validateProfile, profilePatch, validateProfileRegions } from '../src/pages/profile/profileModel.ts'
import { interestOptions, toggleInterest } from '../src/pages/profile/interestModel.ts'
import { safeReturnPath } from '../src/auth/returnPath.ts'
import { profile, user, options } from './profile-fixtures.mjs'

const adapter = apiClient.defaults.adapter
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
afterEach(() => {
  apiClient.defaults.adapter = adapter; endAuthSession()
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else delete globalThis.document
})
const response = (config, data, status = 200) => ({ config, data, status, statusText: '', headers: {} })
const fail = (config, status, code = 'TEST') => { throw new AxiosError('private fixture', 'ERR_BAD_RESPONSE', config, {}, response(config, { code, message: 'test only', fieldErrors: {} }, status)) }
const csrf = () => { Object.defineProperty(globalThis, 'document', { configurable: true, value: { cookie: 'XSRF-TOKEN=fixture-csrf' } }) }
const makeClient = () => new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } } })
const deferred = () => { let resolve; const promise = new Promise((done) => { resolve = done }); return { resolve, promise } }

test('only profile 404 ELIGIBILITY_001 means unregistered; user 401 and other HTTP errors propagate', async () => {
  apiClient.defaults.adapter = async (config) => { assert.equal(config.requiresAuth, true); return fail(config, 404, 'ELIGIBILITY_001') }
  assert.equal(await getProfile(), null)
  for (const status of [400, 401, 403, 404, 500]) {
    apiClient.defaults.adapter = async (config) => fail(config, status)
    await assert.rejects(getProfile(), (error) => error.status === status)
    await assert.rejects(getInterests(), (error) => error.status === status)
  }
  apiClient.defaults.adapter = async (config) => { throw new AxiosError('synthetic', 'ERR_NETWORK', config, {}) }
  await assert.rejects(getProfile(), (error) => error.kind === 'network')
})
test('profile response and enum contracts fail safely without accepting wrong typed or conditional values', () => {
  assert.deepEqual(parseProfile(profile), profile)
  for (const invalid of [null, {}, { ...profile, employmentStatus: 'UNKNOWN' }, { ...profile, householdSize: '2' }, { ...profile, personalIncome: { status: 'PROVIDED', amount: -1, period: 'MONTHLY' } }, { ...profile, specialTargets: { status: 'PROVIDED', targets: ['NOT_REAL'] } }]) assert.throws(() => parseProfile(invalid), ApiError)
  assert.equal(choices.employmentStatus.UNEMPLOYED, '미취업')
  assert.equal(choices.educationLevel.UNIVERSITY, '대학교')
})
test('required values and real dates validate against Korean today without inventing a region', () => {
  const missing = validateProfile(emptyProfile, '2026-10-26')
  for (const key of ['birthDate', 'residenceRegionCode', 'employmentStatus', 'educationLevel']) assert.ok(missing[key])
  for (const birthDate of ['2026-10-27', '1899-12-31', '2026-02-30', 'invalid']) assert.ok(validateProfile({ ...profile, birthDate }, '2026-10-26').birthDate)
  assert.deepEqual(validateProfile({ ...profile, birthDate: '2026-10-26' }, '2026-10-26'), {})
})
test('income distinguishes missing, unknown and zero; pairs, units and numeric bounds match backend validation', () => {
  for (const personalIncome of [null, { status: 'UNKNOWN', amount: null, period: null }, { status: 'PROVIDED', amount: 0, period: 'MONTHLY' }]) assert.deepEqual(validateProfile({ ...profile, personalIncome }), {})
  for (const personalIncome of [{ status: 'PROVIDED', amount: null, period: 'MONTHLY' }, { status: 'PROVIDED', amount: 1, period: null }, { status: 'PROVIDED', amount: 1.5, period: 'YEARLY' }, { status: 'PROVIDED', amount: 1e15, period: 'YEARLY' }, { status: 'UNKNOWN', amount: 0, period: null }]) assert.ok(validateProfile({ ...profile, personalIncome }).personalIncome)
  for (const [key, value] of [['medianIncome', { status: 'PROVIDED', percent: 1000 }], ['medianIncome', { status: 'UNKNOWN', percent: 20 }], ['medianIncome', { status: 'PROVIDED', percent: 1.001 }], ['studentLoanBracket', { status: 'PROVIDED', bracket: 0 }], ['householdSize', 100], ['specialTargets', { status: 'PROVIDED', targets: [] }], ['militaryService', { status: 'EXEMPTED', months: 10 }]]) assert.ok(validateProfile({ ...profile, [key]: value })[key])
})
test('PATCH emits only changed fields and explicit null without dropping untouched sensitive fields', () => {
  const initial = { ...profile, personalIncome: { status: 'PROVIDED', amount: 0, period: 'MONTHLY' }, schoolRegionCode: '11' }
  assert.deepEqual(profilePatch({ ...initial, personalIncome: null }, initial), { personalIncome: null })
  assert.deepEqual(profilePatch({ ...initial, employmentStatus: 'EMPLOYED' }, initial), { employmentStatus: 'EMPLOYED' })
  assert.deepEqual(profilePatch(initial, initial), {})
})
test('interest options map exact strings to backend types with hierarchy and never fabricate NCS codes', () => {
  const available = interestOptions(options)
  assert.deepEqual(available.map(({ type, code }) => ({ type, code })), [
    { type: 'CATEGORY', code: '일자리' }, { type: 'SUB_CATEGORY', code: '취업지원' },
    { type: 'CATEGORY', code: '교육･직업훈련' }, { type: 'SUB_CATEGORY', code: '정보기술' }, { type: 'DETAIL_CATEGORY', code: '정보기술개발' },
  ])
  assert.match(available[4].label, /교육･직업훈련 › 정보기술 › 정보기술개발/)
})
test('editing interests preserves unknown existing NCS, prevents duplicates and enforces 30 including existing values', () => {
  const old = { type: 'NCS', code: '20010101' }, added = { type: 'CATEGORY', code: '교육･직업훈련' }
  assert.deepEqual(toggleInterest([old], added), [old, added])
  assert.deepEqual(toggleInterest([old, added], added), [old])
  const full = Array.from({ length: 30 }, (_, i) => ({ type: 'NCS', code: String(i) }))
  assert.equal(toggleInterest(full, added), full)
  assert.equal(toggleInterest(full, full[0]).length, 29)
  assert.deepEqual(parseInterests({ interests: [] }), [])
  assert.throws(() => parseInterests({ interests: [old, old] }))
  assert.throws(() => parseInterests({ interests: [...full, added] }))
})
test('actual mutation performs PUT creation and PATCH edit with CSRF, auth, exact fields and targeted Query invalidation', async () => {
  csrf()
  const client = makeClient(), signal = new AbortController().signal
  client.setQueryData(authQueryKey, user)
  const owner = client.getQueryData(authQueryKey)
  for (const key of [['recommendations', 11], ['opportunities', 'test'], ['opportunity-detail', '42'], ['home', 'opportunities', 'size=6'], ['interest-options']]) client.setQueryData(key, [])
  const calls = []
  apiClient.defaults.adapter = async (config) => {
    calls.push(config)
    assert.equal(config.requiresAuth, true); assert.equal(config.headers.get('X-XSRF-TOKEN'), 'fixture-csrf')
    return response(config, { ...profile, ...JSON.parse(config.data) })
  }
  const create = new MutationObserver(client, { mutationFn: (body) => saveProfile(client, owner, body, true, signal) })
  const { updatedAt: _updatedAt, ...request } = profile
  assert.ok(_updatedAt)
  await create.mutate(request)
  assert.equal(calls[0].method, 'put')
  const edit = new MutationObserver(client, { mutationFn: (body) => saveProfile(client, owner, body, false, signal) })
  await edit.mutate({ householdIncome: null })
  assert.equal(calls[1].method, 'patch'); assert.deepEqual(JSON.parse(calls[1].data), { householdIncome: null })
  assert.equal(client.getQueryData(profileQuery(11).queryKey).residenceRegionCode, '11680')
  for (const key of [['recommendations', 11], ['opportunities', 'test'], ['opportunity-detail', '42'], ['home', 'opportunities', 'size=6']]) assert.equal(client.getQueryState(key).isInvalidated, true)
  assert.equal(client.getQueryState(['interest-options']).isInvalidated, false)
  client.clear()
})
test('interest PUT sends the entire edited list including old unknown values and permits explicit removal of all', async () => {
  csrf()
  const client = makeClient(); client.setQueryData(authQueryKey, user)
  const list = [{ type: 'NCS', code: '20010101' }, { type: 'CATEGORY', code: '교육･직업훈련' }]
  apiClient.defaults.adapter = async (config) => { assert.equal(config.url, '/users/me/interests'); const data = JSON.parse(config.data); return response(config, data) }
  assert.deepEqual(await saveInterests(client, client.getQueryData(authQueryKey), list, new AbortController().signal), list)
  assert.deepEqual(client.getQueryData(interestsQuery(11).queryKey), list)
  assert.deepEqual(await saveInterests(client, client.getQueryData(authQueryKey), [], new AbortController().signal), [])
  client.clear()
})
test('failed save never reports success or overwrites the previous server snapshot; field errors remain available', async () => {
  csrf()
  const client = makeClient(); client.setQueryData(authQueryKey, user); client.setQueryData(profileQuery(11).queryKey, profile)
  for (const status of [400, 401, 403, 500]) {
    apiClient.defaults.adapter = async (config) => { throw new AxiosError('private input', 'ERR_BAD_RESPONSE', config, {}, response(config, { code: 'COMMON_001', message: 'internal', fieldErrors: { birthDate: 'test error' } }, status)) }
    await assert.rejects(saveProfile(client, client.getQueryData(authQueryKey), { birthDate: '' }, false, new AbortController().signal), (error) => error.status === status && error.fieldErrors.birthDate === 'test error')
    assert.deepEqual(client.getQueryData(profileQuery(11).queryKey), profile)
  }
  client.clear()
})
test('account switch cancels actual profile observer, drops private caches and rejects a late mutation response', async () => {
  csrf()
  const client = makeClient(); client.setQueryData(authQueryKey, user)
  const owner = client.getQueryData(authQueryKey), getStarted = deferred(), getFinish = deferred(), putStarted = deferred(), putFinish = deferred()
  let getSignal
  apiClient.defaults.adapter = async (config) => {
    if (config.method === 'get') { getSignal = config.signal; getStarted.resolve(); await getFinish.promise; return response(config, profile) }
    putStarted.resolve(); await putFinish.promise; return response(config, { interests: [{ type: 'CATEGORY', code: '일자리' }] })
  }
  const observer = new QueryObserver(client, profileQuery(11)); const off = observer.subscribe(() => {})
  await getStarted.promise
  const mutation = new MutationObserver(client, { mutationFn: () => saveInterests(client, owner, [], new AbortController().signal) })
  const save = mutation.mutate(); await putStarted.promise
  await resetPersonalizedQueries(client); client.setQueryData(authQueryKey, { ...user, id: 22 })
  assert.equal(getSignal.aborted, true)
  putFinish.resolve(); await assert.rejects(save, (error) => error.kind === 'cancelled')
  getFinish.resolve(); await new Promise((resolve) => setImmediate(resolve))
  assert.equal(client.getQueryData(profileQuery(11).queryKey), undefined)
  assert.equal(client.getQueryData(interestsQuery(11).queryKey), undefined)
  assert.equal(client.getQueryData(interestsQuery(22).queryKey), undefined)
  off(); client.clear()
})
test('onboarding leaving a completed step keeps the saved profile but an aborted in-flight save cannot commit UI cache', async () => {
  csrf()
  const client = makeClient(); client.setQueryData(authQueryKey, user); client.setQueryData(profileQuery(11).queryKey, profile)
  const started = deferred(), finish = deferred(), controller = new AbortController()
  apiClient.defaults.adapter = async (config) => { started.resolve(); await finish.promise; return response(config, profile) }
  const save = saveProfile(client, client.getQueryData(authQueryKey), { householdSize: 2 }, false, controller.signal)
  await started.promise; controller.abort(); finish.resolve()
  await assert.rejects(save)
  assert.deepEqual(client.getQueryData(profileQuery(11).queryKey), profile)
  assert.equal(interestKey({ type: 'CATEGORY', code: '일자리' }), '["CATEGORY","일자리"]')
  client.clear()
})
test('an auth transition invalidates an in-flight save even before the old user object has been replaced', async () => {
  csrf()
  const client = makeClient(); client.setQueryData(authQueryKey, user)
  const owner = client.getQueryData(authQueryKey), started = deferred(), finish = deferred(), authFinish = deferred(), authStarted = deferred()
  apiClient.defaults.adapter = async (config) => { started.resolve(); await finish.promise; return response(config, profile) }
  const save = saveProfile(client, owner, { householdSize: 2 }, false, new AbortController().signal)
  await started.promise
  const transition = new MutationObserver(client, { mutationKey: authTransitionKey, mutationFn: async () => { authStarted.resolve(); await authFinish.promise } })
  const auth = transition.mutate('oauth'); await authStarted.promise
  finish.resolve(); await assert.rejects(save, (error) => error.kind === 'cancelled')
  assert.equal(client.getQueryData(profileQuery(11).queryKey), undefined)
  authFinish.resolve(); await auth; client.clear()
})
test('login return allowlist accepts only the two implemented settings routes and strips arbitrary query values', () => {
  assert.equal(safeReturnPath('/mypage/profile?token=secret'), '/mypage/profile')
  assert.equal(safeReturnPath('/mypage/interests'), '/mypage/interests')
  assert.equal(safeReturnPath('/mypage/unknown'), '/')
})
test('unavailable regional options still allow a real non-region PATCH while retaining both server region codes', async () => {
  csrf()
  const client = makeClient(); client.setQueryData(authQueryKey, user)
  const initial = { ...profile, schoolRegionCode: '1100000000' }
  const next = { ...initial, employmentStatus: 'EMPLOYED' }
  assert.deepEqual(validateProfileRegions(next, initial, { status: 'unavailable' }), {})
  const body = profilePatch(next, initial)
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls++
    assert.equal(config.url, '/users/me/eligibility-profile')
    assert.equal(config.method, 'patch')
    assert.deepEqual(JSON.parse(config.data), { employmentStatus: 'EMPLOYED' })
    return response(config, next)
  }
  const saved = await saveProfile(client, client.getQueryData(authQueryKey), body, false, new AbortController().signal)
  assert.equal(saved.residenceRegionCode, initial.residenceRegionCode)
  assert.equal(saved.schoolRegionCode, initial.schoolRegionCode)
  assert.equal(calls, 1)
  client.clear()
})
