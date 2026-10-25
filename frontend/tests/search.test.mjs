import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { ApiError } from '../src/api/ApiError.ts'
import { getInterestOptions, isSearchResponse, searchOpportunities } from '../src/api/opportunities.ts'
import { availableSorts, changeSearchCategory, changeSearchConditions, changeSearchType, dateRangeError, defaultSearchParams, isValidDate, paginationPages, parseSearchParams, serializeSearchParams } from '../src/pages/opportunities/searchParams.ts'

const originalAdapter = apiClient.defaults.adapter
afterEach(() => { apiClient.defaults.adapter = originalAdapter })
const empty = (page = 0) => ({ content: [], page, size: 20, totalElements: 0, totalPages: 0, hasNext: false })
const response = (config, data) => ({ config, data, status: 200, statusText: 'OK', headers: {} })
function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { resolve, promise }
}

test('default URL and request params omit blank values and defaults consistently', () => {
  assert.deepEqual(parseSearchParams(new URLSearchParams()).params, defaultSearchParams())
  assert.equal(serializeSearchParams(defaultSearchParams()).toString(), '')
  assert.equal(serializeSearchParams({ ...defaultSearchParams(), keyword: '  청년  ', category: ' ' }).toString(), 'keyword=%EC%B2%AD%EB%85%84')
})

test('URL round trip preserves valid filters and 0-based page, including independent category filters', () => {
  const query = new URLSearchParams({ keyword: '지원 & 훈련', type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련',
    subCategory: '정보통신', detailCategory: '정보기술', applicationStatus: 'OPEN', startDateFrom: '2026-10-01', startDateTo: '2026-12-31',
    sort: 'START_DATE_ASC', page: '3', size: '50' })
  const parsed = parseSearchParams(query)
  assert.deepEqual(parsed.notices, [])
  assert.deepEqual(parseSearchParams(serializeSearchParams(parsed.params)).params, parsed.params)
  assert.equal(parsed.params.page, 3)
  assert.equal(parseSearchParams(new URLSearchParams('detailCategory=independent')).params.detailCategory, 'independent')
})

test('malformed enums, non-integer/overflow pages and sizes normalize safely', () => {
  for (const page of ['-1', '1.2', 'NaN', 'Infinity', '2147483648', '1e3', '01']) {
    assert.equal(parseSearchParams(new URLSearchParams({ page })).params.page, 0)
  }
  for (const size of ['0', '-1', '101', '1.2']) assert.equal(parseSearchParams(new URLSearchParams({ size })).params.size, 20)
  for (const size of ['1', '100']) assert.equal(parseSearchParams(new URLSearchParams({ size })).params.size, Number(size))
  const parsed = parseSearchParams(new URLSearchParams('type=OTHER&sort=FAST&applicationStatus=ELIGIBLE'))
  assert.equal(parsed.params.type, '')
  assert.equal(parsed.params.applicationStatus, '')
  assert.equal(parsed.params.sort, 'LATEST')
  assert.ok(parsed.notices.length > 0)
})

test('date validation rejects impossible dates; ranges include equal endpoints', () => {
  for (const date of ['2026-02-29', '2026-04-31', '0000-01-01', '2026-1-01', '2026-01-01T00:00:00']) assert.equal(isValidDate(date), false)
  assert.equal(isValidDate('2024-02-29'), true)
  const equal = { ...defaultSearchParams(), type: 'YOUTH_POLICY', deadlineFrom: '2026-10-25', deadlineTo: '2026-10-25' }
  assert.equal(dateRangeError(equal), undefined)
  assert.equal(parseSearchParams(serializeSearchParams(equal)).params.deadlineTo, '2026-10-25')
  const reversed = { ...equal, deadlineFrom: '2026-11-01' }
  assert.ok(dateRangeError(reversed))
  const normalized = parseSearchParams(serializeSearchParams(reversed))
  assert.equal(normalized.params.deadlineFrom, '')
  assert.equal(normalized.params.deadlineTo, '')
})

test('type changes remove incompatible dates and sorting before sending', () => {
  const policy = { ...defaultSearchParams(), type: 'YOUTH_POLICY', deadlineFrom: '2026-10-25', sort: 'DEADLINE_PRIORITY', page: 4 }
  const training = changeSearchType(policy, 'VOCATIONAL_TRAINING')
  assert.equal(training.deadlineFrom, '')
  assert.equal(training.sort, 'LATEST')
  assert.equal(training.page, 0)
  assert.deepEqual(availableSorts(''), ['LATEST', 'OLDEST'])
  assert.ok(availableSorts('YOUTH_POLICY').includes('DEADLINE_PRIORITY'))
  assert.ok(availableSorts('VOCATIONAL_TRAINING').includes('START_DATE_ASC'))
  const parsed = parseSearchParams(new URLSearchParams('startDateFrom=2026-10-25&deadlineFrom=2026-10-25&sort=START_DATE_ASC'))
  assert.equal(parsed.params.startDateFrom, '')
  assert.equal(parsed.params.deadlineFrom, '')
  assert.equal(parsed.params.sort, 'LATEST')
})

test('conditions reset the page; pagination keeps filters; full reset includes keyword', () => {
  const current = { ...defaultSearchParams(), keyword: '청년', category: '취업', page: 5 }
  const next = changeSearchConditions(current, { applicationStatus: 'OPEN' })
  assert.equal(next.page, 0)
  assert.equal(next.keyword, '청년')
  const paged = parseSearchParams(serializeSearchParams({ ...next, page: 2 })).params
  assert.equal(paged.page, 2)
  assert.equal(paged.category, '취업')
  assert.equal(paged.applicationStatus, 'OPEN')
  assert.equal(defaultSearchParams().keyword, '')
  assert.deepEqual(paginationPages(5, 100), [0, 4, 5, 6, 99])
  assert.deepEqual(paginationPages(0, 0), [])
})

test('unsupported region and authenticated eligibility URL filters are removed with notices', () => {
  const parsed = parseSearchParams(new URLSearchParams('region=1100000000&eligibilityStatuses=ELIGIBLE'))
  assert.equal(serializeSearchParams(parsed.params).toString(), '')
  assert.equal(parsed.notices.length, 2)
})

test('public search uses /api once, a cancellation signal and normalized params without reissue opt-in', async () => {
  let calls = 0
  const controller = new AbortController()
  apiClient.defaults.adapter = async (config) => {
    calls++
    assert.equal(config.url, '/opportunities')
    assert.equal(config.baseURL, '/api')
    assert.equal(config.requiresAuth, undefined)
    assert.equal(config.signal, controller.signal)
    assert.equal(config.headers.get('X-XSRF-TOKEN'), undefined)
    assert.equal(config.params.get('deadlineFrom'), null)
    return response(config, empty())
  }
  assert.deepEqual(await searchOpportunities({ ...defaultSearchParams(), deadlineFrom: '2026-10-25' }, controller.signal), empty())
  assert.equal(calls, 1)
})

test('public search 401 terminates without automatic reissue; errors never become empty results', async () => {
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls++
    throw new AxiosError('test failure', 'ERR_BAD_RESPONSE', config, {}, {
      ...response(config, { code: 'AUTH_006', message: 'test only', fieldErrors: {} }), status: 401,
    })
  }
  await assert.rejects(searchOpportunities(defaultSearchParams()), (error) => error instanceof ApiError && error.status === 401)
  assert.equal(calls, 1)
  apiClient.defaults.adapter = async (config) => response(config, '<html>Unexpected</html>')
  await assert.rejects(searchOpportunities(defaultSearchParams()), (error) => error.kind === 'unknown')
})

test('response checks distinguish null eligibility, actual UNKNOWN and empty pages', () => {
  assert.equal(isSearchResponse(empty()), true)
  const item = { opportunityId: 1, type: 'YOUTH_POLICY', title: '테스트 전용 공고', category: null, subCategory: null,
    detailCategory: null, createdAt: '2026-10-25T12:00:00', eligibilityStatus: null, saved: false }
  const data = { ...empty(), content: [item], totalElements: 1, totalPages: 1 }
  assert.equal(isSearchResponse(data), true)
  assert.equal(isSearchResponse({ ...data, content: [{ ...item, eligibilityStatus: 'UNKNOWN' }] }), true)
  assert.equal(isSearchResponse({ ...data, content: [{ ...item, eligibilityStatus: 'OPEN' }] }), false)
  assert.equal(isSearchResponse({ ...data, content: [{ ...item, saved: 'false' }] }), false)
  assert.equal(isSearchResponse({ ...data, content: [{ ...item, opportunityId: 1.5 }] }), false)
})

test('real interest option hierarchy is accepted and malformed options fail explicitly', async () => {
  const options = { categories: [{ category: '테스트 분류', subCategories: [{ subCategory: '테스트 중분류', detailCategories: ['테스트 세분류'] }] }] }
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.url, '/interests/options')
    assert.equal(config.requiresAuth, undefined)
    return response(config, options)
  }
  assert.deepEqual(await getInterestOptions(), options)
  apiClient.defaults.adapter = async (config) => response(config, { categories: [{ category: 'x' }] })
  await assert.rejects(getInterestOptions(), (error) => error.kind === 'unknown')
})

test('dependent category changes clear only descendants and reset page without losing other search conditions', () => {
  const current = { ...defaultSearchParams(), keyword: '청년', category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술', page: 4 }
  const category = changeSearchCategory(current, 'category', '일자리')
  assert.equal(category.category, '일자리')
  assert.equal(category.subCategory, '')
  assert.equal(category.detailCategory, '')
  assert.equal(category.keyword, '청년')
  assert.equal(category.page, 0)
  const sub = changeSearchCategory(current, 'subCategory', '다른 중분류')
  assert.equal(sub.category, current.category)
  assert.equal(sub.subCategory, '다른 중분류')
  assert.equal(sub.detailCategory, '')
  const detail = changeSearchCategory(current, 'detailCategory', '다른 세부 분류')
  assert.equal(detail.subCategory, current.subCategory)
  assert.equal(detail.detailCategory, '다른 세부 분류')
  assert.equal(changeSearchCategory(current, 'category', '').subCategory, '')
  assert.equal(changeSearchCategory(current, 'subCategory', '').detailCategory, '')
})

test('selected category strings survive URL restore and API serialization without normalizing punctuation or whitespace', async () => {
  const selected = { ...defaultSearchParams(), category: ' 교육･직업훈련 ', subCategory: '정보 통신', detailCategory: '정보·기술' }
  const restored = parseSearchParams(serializeSearchParams(selected)).params
  assert.equal(restored.category, selected.category)
  assert.equal(restored.subCategory, selected.subCategory)
  assert.equal(restored.detailCategory, selected.detailCategory)
  apiClient.defaults.adapter = async (config) => {
    for (const key of ['category', 'subCategory', 'detailCategory']) assert.equal(config.params.get(key), selected[key])
    return response(config, empty())
  }
  await searchOpportunities(restored)
})

test('query key switch cancels stale search; late old response cannot replace latest data', async () => {
  const oldStarted = deferred()
  const oldFinish = deferred()
  const newReady = deferred()
  let oldSignal
  const seen = []
  apiClient.defaults.adapter = async (config) => {
    if (config.params.get('keyword') === 'old') {
      oldSignal = config.signal
      oldStarted.resolve()
      await oldFinish.promise
      return response(config, empty(1))
    }
    return response(config, empty(2))
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } })
  const query = (keyword) => ({ queryKey: ['opportunities', `keyword=${keyword}`],
    queryFn: ({ signal }) => searchOpportunities({ ...defaultSearchParams(), keyword }, signal) })
  const observer = new QueryObserver(client, query('old'))
  const unsubscribe = observer.subscribe((result) => {
    if (result.data) { seen.push(result.data.page); if (result.data.page === 2) newReady.resolve() }
  })
  try {
    await oldStarted.promise
    observer.setOptions(query('new'))
    await newReady.promise
    assert.equal(oldSignal.aborted, true)
    oldFinish.resolve()
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(observer.getCurrentResult().data.page, 2)
    assert.ok(seen.every((page) => page === 2))
  } finally { oldFinish.resolve(); unsubscribe(); client.clear() }
})
