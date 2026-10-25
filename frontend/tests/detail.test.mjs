import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { ApiError } from '../src/api/ApiError.ts'
import { getOpportunityDetail, isOpportunityDetail, isOpportunityId, opportunityDetailQuery } from '../src/api/opportunityDetail.ts'
import { applicationWindowText, conditionPresentation, dateRange, regionText, safeExternalUrl, searchReturnPath } from '../src/pages/opportunities/detailPresentation.ts'
import { condition, policy, training } from './detail-fixtures.mjs'

const originalAdapter = apiClient.defaults.adapter
afterEach(() => { apiClient.defaults.adapter = originalAdapter })
const response = (config, data, status = 200) => ({ config, data, status, statusText: '', headers: {} })

test('detail ID validation blocks invalid, unsafe and non-canonical numbers before any request', async () => {
  let calls = 0
  apiClient.defaults.adapter = async (config) => { calls++; return response(config, policy) }
  for (const id of [undefined, '', '0', '-1', '01', '1.5', '1e3', '42?q=x', '9007199254740992', 'Infinity', ' 42']) {
    assert.equal(isOpportunityId(id), false)
    assert.equal(opportunityDetailQuery(id).enabled, false)
    await assert.rejects(getOpportunityDetail(id ?? ''), (error) => error.kind === 'configuration')
  }
  assert.equal(calls, 0)
  assert.equal(isOpportunityId('42'), true)
  assert.equal(isOpportunityId('9007199254740991'), true)
})

test('public detail uses relative path once, a signal and per-ID query keys without forcing auth recovery', async () => {
  const controller = new AbortController()
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.url, '/opportunities/42')
    assert.equal(config.baseURL, '/api')
    assert.equal(config.requiresAuth, undefined)
    assert.equal(config.signal, controller.signal)
    assert.equal(config.headers.get('X-XSRF-TOKEN'), undefined)
    return response(config, policy)
  }
  const query = opportunityDetailQuery('42')
  assert.deepEqual(query.queryKey, ['opportunity-detail', '42'])
  assert.notDeepEqual(query.queryKey, opportunityDetailQuery('43').queryKey)
  assert.deepEqual(await query.queryFn({ signal: controller.signal }), policy)
})

test('policy and training DTOs accept nullable or omitted detail fields; malformed data and wrong IDs fail safely', async () => {
  assert.equal(isOpportunityDetail(policy), true)
  assert.equal(isOpportunityDetail(training), true)
  const partial = { ...policy, detail: null, eligibility: null, regions: [], applicationWindows: [], conditions: [] }
  assert.equal(isOpportunityDetail(partial), true)
  const omitted = { ...partial }
  delete omitted.detail
  delete omitted.eligibility
  delete omitted.category
  assert.equal(isOpportunityDetail(omitted), true)
  for (const bad of [null, '<html>error</html>', { ...policy, type: 'OTHER' }, { ...policy, saved: 'false' },
    { ...policy, conditions: [{ ...condition(), values: [1] }] }, { ...policy, applicationWindows: [{ periodType: 'OTHER' }] },
    { ...policy, detail: training.detail }, { ...training, detail: policy.detail }, { ...training, detail: { capacity: '20' } }]) {
    assert.equal(isOpportunityDetail(bad), false)
  }
  apiClient.defaults.adapter = async (config) => response(config, training)
  await assert.rejects(getOpportunityDetail('42'), (error) => error.kind === 'unknown')
})

test('public 401 never reissues; 404, 5xx, network and timeout remain distinct errors instead of fake content', async () => {
  let calls = 0
  for (const status of [401, 403, 404, 500]) {
    apiClient.defaults.adapter = async (config) => {
      calls++
      assert.equal(config.url, '/opportunities/42')
      throw new AxiosError('synthetic', 'ERR_BAD_RESPONSE', config, {}, response(config, { code: status === 404 ? 'OPPORTUNITY_001' : 'TEST', message: 'test only', fieldErrors: {} }, status))
    }
    await assert.rejects(getOpportunityDetail('42'), (error) => error instanceof ApiError && error.kind === 'http' && error.status === status && (status !== 404 || error.code === 'OPPORTUNITY_001'))
  }
  assert.equal(calls, 4)
  for (const [code, kind] of [['ERR_NETWORK', 'network'], ['ECONNABORTED', 'timeout']]) {
    apiClient.defaults.adapter = async (config) => { throw new AxiosError('synthetic', code, config, {}) }
    await assert.rejects(getOpportunityDetail('42'), (error) => error.kind === kind && error.status === undefined)
  }
})

test('application periods respect validation, all period types and one-sided bounds without using training or business dates', () => {
  assert.equal(applicationWindowText(policy.applicationWindows[0]), '2026-10-01 ~ 2026-10-31')
  assert.equal(applicationWindowText({ periodType: 'ALWAYS', validationStatus: 'VALID' }), '상시 신청')
  assert.equal(applicationWindowText({ periodType: 'CLOSED', validationStatus: 'VALID' }), '신청 마감')
  for (const validationStatus of ['INVALID', 'DATA_CONFLICT', 'UNKNOWN', 'NEEDS_REVIEW', 'UNVERIFIED', null]) {
    assert.match(applicationWindowText({ ...policy.applicationWindows[0], validationStatus }), /확인 필요/)
    assert.doesNotMatch(applicationWindowText({ ...policy.applicationWindows[0], validationStatus }), /2026-10-01/)
  }
  assert.match(applicationWindowText(training.applicationWindows[0]), /확인 필요/)
  assert.equal(dateRange('2026-10-25', null), '2026-10-25부터 (종료일 미제공)')
  assert.equal(dateRange(null, '2026-10-25'), '2026-10-25까지 (시작일 미제공)')
  assert.equal(dateRange('2026-02-30', '2026-03-01'), '일정 확인 필요')
  assert.equal(dateRange('2026-11-01', '2026-10-01'), '일정 확인 필요')
})

test('regions use supplied names and validated national status while preserving residence vs implementation roles', () => {
  assert.equal(regionText(policy.regions[0], false), '거주지 조건: 전국')
  assert.equal(regionText(policy.regions[1], false), '정책 시행 지역: 서울')
  assert.equal(regionText({ ...policy.regions[1], role: 'IMPLEMENTATION' }, true), '교육 시행 지역: 서울')
  for (const region of [{ role: 'RESIDENCE', status: 'UNKNOWN', validationStatus: 'UNKNOWN' },
    { ...policy.regions[0], validationStatus: 'NEEDS_REVIEW' }, { ...policy.regions[1], name: null }, training.regions[0]]) {
    assert.match(regionText(region, false), /지역 확인 필요/)
    assert.doesNotMatch(regionText(region, false), /전국|1100000000/)
  }
})

test('conditions distinguish verified bounds, code labels, and original evidence requiring review', () => {
  assert.deepEqual(conditionPresentation(condition()), { confirmed: true, text: '만 19세 이상 · 만 34세 이하' })
  assert.equal(conditionPresentation(condition({ status: 'MIN_ONLY', min: 0, max: null })).text, '만 0세 이상')
  assert.match(conditionPresentation(condition({ status: 'DATE_BOUND', bornOnOrAfter: '1990-01-01', bornOnOrBefore: null })).text, /1990-01-01 이후 출생 \(포함\)/)
  assert.equal(conditionPresentation(condition({ type: 'EMPLOYMENT', status: 'SPECIFIC', values: ['UNEMPLOYED'] })).text, '미취업자')
  assert.equal(conditionPresentation(condition({ type: 'EDUCATION', status: 'SPECIFIC', values: ['UNIVERSITY_ENROLLED'] })).text, '대학 재학')
  assert.equal(conditionPresentation(condition({ status: 'NO_LIMIT' })).text, '해당 조건 제한 없음')
  for (const patch of [{ validationStatus: 'UNVERIFIED' }, { validationStatus: 'INVALID', status: 'NO_LIMIT' },
    { status: 'RANGE', min: 35, max: 20 }, { type: 'EMPLOYMENT', status: 'SPECIFIC', values: ['UNSUPPORTED'] },
    { type: 'COMPOSITE' }, { type: 'AGE', status: 'UNKNOWN' }]) {
    assert.equal(conditionPresentation(condition(patch)).confirmed, false)
  }
})

test('income only becomes a confirmed amount with known units, period, subject and inclusive flags', () => {
  const income = condition({ type: 'INCOME', status: 'MAX_ONLY', min: null, max: 30000000, incomeType: 'AMOUNT', period: 'YEARLY', subject: 'INDIVIDUAL', maxInclusive: false, unitConfirmed: true })
  assert.equal(conditionPresentation(income).text, '본인 연소득 30,000,000원 미만')
  for (const patch of [{ unitConfirmed: false }, { period: 'UNKNOWN' }, { subject: 'UNKNOWN' }, { maxInclusive: null }, { validationStatus: 'NEEDS_REVIEW' }]) {
    const shown = conditionPresentation({ ...income, ...patch })
    assert.equal(shown.confirmed, false)
    assert.doesNotMatch(shown.text, /30,000,000/)
  }
  assert.equal(conditionPresentation({ ...income, incomeType: 'MEDIAN_PERCENT', subject: 'HOUSEHOLD', period: 'UNKNOWN', max: 72.5, maxInclusive: true }).text, '가구 기준중위소득 72.5% 이하')
})

test('external URLs reject unsafe schemes, relative URLs, credentials and controls', () => {
  for (const value of [null, '', 'javascript:alert(1)', 'data:text/html,test', '//example.org', '/apply', 'https://user:secret@example.org', 'https://example.org/\npath', 'not a url']) assert.equal(safeExternalUrl(value), undefined)
  assert.equal(safeExternalUrl('https://example.org/apply?a=1&b=2'), 'https://example.org/apply?a=1&b=2')
  assert.equal(safeExternalUrl('http://example.org'), 'http://example.org/')
})

test('search return preserves conditions and page; direct entry and untrusted destinations use public search', () => {
  assert.equal(searchReturnPath({ searchReturn: '/opportunities?keyword=test&type=YOUTH_POLICY&page=2' }), '/opportunities?keyword=test&type=YOUTH_POLICY&page=2')
  for (const state of [null, {}, { searchReturn: 'https://evil.example' }, { searchReturn: '//evil.example' }, { searchReturn: '/opportunities/42' }]) assert.equal(searchReturnPath(state), '/opportunities')
})

test('changing detail ID cancels the old request and keeps a late response out of the current view', async () => {
  let finishOld, markStarted, markNew
  const oldFinished = new Promise((resolve) => { finishOld = resolve })
  const started = new Promise((resolve) => { markStarted = resolve })
  const newReady = new Promise((resolve) => { markNew = resolve })
  let oldSignal
  apiClient.defaults.adapter = async (config) => {
    if (config.url.endsWith('/42')) { oldSignal = config.signal; markStarted(); await oldFinished; return response(config, policy) }
    return response(config, training)
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const observer = new QueryObserver(client, opportunityDetailQuery('42'))
  const unsubscribe = observer.subscribe((result) => { if (result.data?.opportunityId === 43) markNew() })
  try {
    await started
    observer.setOptions(opportunityDetailQuery('43'))
    await newReady
    assert.equal(oldSignal.aborted, true)
    finishOld()
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(observer.getCurrentResult().data.opportunityId, 43)
  } finally { finishOld(); unsubscribe(); client.clear() }
})
