import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { AxiosError } from 'axios'
import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { apiClient } from '../src/api/client.ts'
import { ApiError } from '../src/api/ApiError.ts'
import { getInterestOptions, isCategoryRows, isSearchResponse, searchOpportunities, searchQueryOptions } from '../src/api/opportunities.ts'
import { availableSorts, categoryChoiceLabel, categoryEntries, categoryEntryKey, categoryLabel, changeSearchCategory, changeSearchConditions, changeSearchType, classificationLabel, dateRangeError, defaultSearchParams, defaultSort, hasSearchTokens, isValidDate, paginationPages, parseCategoryEntryKey, parseSearchParams, policyClassificationLabel, selectedCategoryEntry, serializeSearchParams } from '../src/pages/opportunities/searchParams.ts'
import { sharedCategoryOptions } from './profile-fixtures.mjs'
import { buildRegionTree, chooseRegionLevel, regionPath } from '../src/pages/profile/regionSelection.ts'

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
  // 검색어가 있을 때의 기본 정렬은 관련도순이다
  assert.equal(serializeSearchParams({ ...defaultSearchParams(), keyword: '  청년  ', category: ' ', sort: 'RELEVANCE' }).toString(), 'keyword=%EC%B2%AD%EB%85%84')
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
  for (const size of ['0', '-1', '101', '1.2']) assert.equal(parseSearchParams(new URLSearchParams({ size })).params.size, 10)
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

test('default sort follows the keyword like the backend and only non-default sorts are serialized', () => {
  const keyword = parseSearchParams(new URLSearchParams('keyword=청년 월세'))
  assert.equal(keyword.params.sort, 'RELEVANCE')
  assert.equal(serializeSearchParams(keyword.params).get('sort'), null)
  assert.equal(parseSearchParams(new URLSearchParams()).params.sort, 'LATEST')
  // 검색어가 있을 때 최신순은 명시해야 관련도순과 구분된다
  const latest = parseSearchParams(new URLSearchParams('keyword=청년&sort=LATEST'))
  assert.deepEqual(latest.notices, [])
  assert.equal(latest.params.sort, 'LATEST')
  assert.equal(serializeSearchParams(latest.params).get('sort'), 'LATEST')
  assert.deepEqual(parseSearchParams(serializeSearchParams(latest.params)).params, latest.params)
  // 검색어 없는 관련도순은 백엔드처럼 최신순이며 오류로 보지 않는다
  const noKeyword = parseSearchParams(new URLSearchParams('sort=RELEVANCE'))
  assert.equal(noKeyword.params.sort, 'LATEST')
  assert.deepEqual(noKeyword.notices, [])
  assert.equal(serializeSearchParams({ ...defaultSearchParams(), sort: 'RELEVANCE' }).get('sort'), null)
  assert.deepEqual(availableSorts('', '청년'), ['RELEVANCE', 'LATEST', 'OLDEST'])
  assert.deepEqual(availableSorts('YOUTH_POLICY', '청년'), ['RELEVANCE', 'LATEST', 'OLDEST', 'DEADLINE_PRIORITY'])
})

test('separator-only keywords have no search tokens and use LATEST like the backend', () => {
  // 백엔드 SearchKeyword.hasTokens() 기준: 구분자·단독 '+'·하이픈만 있으면 토큰이 없다
  for (const keyword of ['!!!', '[', '()', '+', '-', ' · ', 'ㆍ', '「」', '/,|']) {
    assert.equal(hasSearchTokens(keyword), false, keyword)
    assert.equal(defaultSort(keyword), 'LATEST', keyword)
    assert.deepEqual(availableSorts('', keyword), ['LATEST', 'OLDEST'], keyword)
    const parsed = parseSearchParams(new URLSearchParams({ keyword }))
    assert.equal(parsed.params.sort, 'LATEST', keyword)
    assert.equal(serializeSearchParams(parsed.params).get('sort'), null, keyword)
    // 관련도순을 지정한 URL도 백엔드처럼 최신순으로 처리하고 오류로 보지 않는다
    const relevance = parseSearchParams(new URLSearchParams({ keyword, sort: 'RELEVANCE' }))
    assert.equal(relevance.params.sort, 'LATEST', keyword)
    assert.deepEqual(relevance.notices, [], keyword)
  }
  for (const keyword of ['@#$', 'c++', '++', '１００％', '!!! 청년', 'ai']) {
    assert.equal(hasSearchTokens(keyword), true, keyword)
    assert.equal(defaultSort(keyword), 'RELEVANCE', keyword)
  }
  // 구분자만 있는 검색어로 바꾸면 기본 정렬이 최신순으로 따라가고, 직접 고른 정렬은 유지된다
  const searched = changeSearchConditions(defaultSearchParams(), { keyword: '청년' })
  assert.equal(changeSearchConditions(searched, { keyword: '!!!' }).sort, 'LATEST')
  const oldest = changeSearchConditions(searched, { sort: 'OLDEST' })
  assert.equal(changeSearchConditions(oldest, { keyword: '()' }).sort, 'OLDEST')
  assert.equal(serializeSearchParams(changeSearchConditions(defaultSearchParams(), { keyword: '[', sort: 'LATEST' })).get('sort'), null)
})

test('keyword changes move only the default sort; explicitly chosen sorts are kept', () => {
  const none = defaultSearchParams()
  const searched = changeSearchConditions(none, { keyword: '파이썬' })
  assert.equal(searched.sort, 'RELEVANCE')
  assert.equal(changeSearchConditions(searched, { keyword: '' }).sort, 'LATEST')
  const chosenLatest = changeSearchConditions(searched, { sort: 'LATEST' })
  assert.equal(serializeSearchParams(chosenLatest).get('sort'), 'LATEST')
  assert.equal(changeSearchConditions(chosenLatest, { keyword: '엑셀' }).sort, 'LATEST')
  assert.equal(changeSearchConditions(chosenLatest, { keyword: '' }).sort, 'LATEST')
  const oldest = changeSearchConditions(searched, { sort: 'OLDEST' })
  assert.equal(changeSearchConditions(oldest, { keyword: '' }).sort, 'OLDEST')
  // 유형 변경으로 전용 정렬을 쓸 수 없게 되면 검색어 기준 기본 정렬로 돌아간다
  const deadline = changeSearchConditions({ ...searched, type: 'YOUTH_POLICY' }, { sort: 'DEADLINE_PRIORITY' })
  assert.equal(changeSearchType(deadline, 'VOCATIONAL_TRAINING').sort, 'RELEVANCE')
  // 필터 변경·페이지 이동은 정렬을 바꾸지 않는다
  assert.equal(changeSearchConditions(chosenLatest, { region: '11' }).sort, 'LATEST')
  assert.equal(parseSearchParams(serializeSearchParams({ ...chosenLatest, page: 2 })).params.sort, 'LATEST')
})

test('search requests send sort only when it differs from the backend default', async () => {
  const sent = []
  apiClient.defaults.adapter = async config => {
    sent.push(config.params.get('sort'))
    return response(config, empty())
  }
  const keyword = { ...defaultSearchParams(), keyword: '내일배움카드' }
  for (const sort of ['RELEVANCE', 'LATEST', 'OLDEST']) await searchOpportunities({ ...keyword, sort })
  await searchOpportunities({ ...keyword, type: 'YOUTH_POLICY', sort: 'DEADLINE_PRIORITY' })
  await searchOpportunities({ ...keyword, type: 'VOCATIONAL_TRAINING', sort: 'START_DATE_ASC' })
  await searchOpportunities(defaultSearchParams())
  await searchOpportunities({ ...defaultSearchParams(), sort: 'OLDEST' })
  assert.deepEqual(sent, [null, 'LATEST', 'OLDEST', 'DEADLINE_PRIORITY', 'START_DATE_ASC', null, 'OLDEST'])
})

test('type changes clear incompatible classification descendants and keep original education request values', async () => {
  const policy = { ...defaultSearchParams(), type: 'YOUTH_POLICY', category: '교육', subCategory: '정책 하위 분류', detailCategory: '정책 세부 분류' }
  const changed = changeSearchType(policy, 'VOCATIONAL_TRAINING')
  assert.equal(changed.category, '')
  assert.equal(changed.subCategory, '')
  assert.equal(changed.detailCategory, '')
  const training = { ...changed, category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술' }
  assert.equal(changeSearchType(training, 'YOUTH_POLICY').category, '')
  // 직업훈련 분류는 유형을 고를 때 자동으로 정해지므로 전체 유형으로 돌아가도 남기지 않는다
  assert.deepEqual([changeSearchType(training, '').category, changeSearchType(training, '').categoryType], ['', ''])
  apiClient.defaults.adapter = async config => {
    assert.equal(config.params.get('category'), '교육')
    return response(config, empty())
  }
  await searchOpportunities(policy)
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

test('region codes and multiple eligibility statuses survive canonical URL round trip without shortening codes', () => {
  const parsed = parseSearchParams(new URLSearchParams('region=1100000000&eligibilityStatuses=UNKNOWN,ELIGIBLE&eligibilityStatuses=ELIGIBLE'))
  assert.equal(parsed.params.region, '1100000000')
  assert.deepEqual(parsed.params.eligibilityStatuses, ['ELIGIBLE', 'UNKNOWN'])
  assert.deepEqual(parseSearchParams(serializeSearchParams(parsed.params)).params, parsed.params)
  assert.equal(parsed.notices.length, 0)
  for (const region of ['1100000000x', ' 1100000000', '1', '111']) assert.equal(parseSearchParams(new URLSearchParams({ region })).params.region, '')
  assert.deepEqual(parseSearchParams(new URLSearchParams('eligibilityStatuses=OTHER,ELIGIBLE')).params.eligibilityStatuses, ['ELIGIBLE'])
})

test('API-provided two-digit province codes survive URL and request serialization unchanged', async () => {
  for (const region of ['11', '26', '41', '1100000000']) {
    const params = parseSearchParams(new URLSearchParams({ region })).params
    assert.equal(params.region, region)
    assert.equal(serializeSearchParams(params).get('region'), region)
    apiClient.defaults.adapter = async config => {
      assert.equal(config.params.get('region'), region)
      return response(config, empty())
    }
    await searchOpportunities(params)
  }
})

test('region hierarchy permits provinces and intermediate cities, clears descendants and sends the exact selected code', async () => {
  const tree = buildRegionTree([
    { value: '4100000000', label: '테스트 도', parentValue: null, isLeaf: false },
    { value: '4111000000', label: '테스트 시', parentValue: '4100000000', isLeaf: false },
    { value: '4111100000', label: '테스트 구', parentValue: '4111000000', isLeaf: true },
    { value: '3611000000', label: '세종특별자치시', parentValue: null, isLeaf: true },
  ])
  const province = chooseRegionLevel(tree, [], 0, '4100000000', 'school')
  assert.equal(province.value, '4100000000')
  const city = chooseRegionLevel(tree, province.path, 1, '4111000000', 'school')
  assert.equal(city.value, '4111000000')
  const district = chooseRegionLevel(tree, city.path, 2, '4111100000', 'school')
  assert.deepEqual(regionPath(tree, district.value), district.path)
  assert.equal(chooseRegionLevel(tree, district.path, 2, '', 'school').value, city.value)
  assert.deepEqual(chooseRegionLevel(tree, district.path, 0, '3611000000', 'school'), { path: ['3611000000'], value: '3611000000' })
  apiClient.defaults.adapter = async config => {
    assert.equal(config.params.get('region'), city.value)
    assert.equal(config.requiresAuth, undefined)
    return response(config, empty())
  }
  await searchOpportunities({ ...defaultSearchParams(), region: city.value })
  const changed = changeSearchConditions({ ...defaultSearchParams(), region: city.value, page: 4 }, { region: province.value })
  assert.equal(changed.page, 0)
  assert.equal(changed.region, province.value)
})

test('eligibility filter sends comma-separated real enums with auth recovery; an empty result triggers no judgment API', async () => {
  let calls = 0
  apiClient.defaults.adapter = async config => {
    calls++
    assert.equal(config.url, '/opportunities')
    assert.equal(config.requiresAuth, true)
    assert.equal(config.params.get('eligibilityStatuses'), 'ELIGIBLE,POSSIBLY_ELIGIBLE,NOT_ELIGIBLE,UNKNOWN')
    return response(config, empty())
  }
  const params = { ...defaultSearchParams(), eligibilityStatuses: ['UNKNOWN', 'NOT_ELIGIBLE', 'ELIGIBLE', 'POSSIBLY_ELIGIBLE'] }
  assert.deepEqual(await searchOpportunities(params), empty())
  assert.equal(calls, 1)
})

test('real search observer isolates accounts, cancels late responses and does not carry authenticated placeholders into public results', async () => {
  const params = { ...defaultSearchParams(), eligibilityStatuses: ['ELIGIBLE'] }
  const firstStarted = deferred(), firstFinish = deferred(), secondStarted = deferred(), secondFinish = deferred(), publicStarted = deferred(), publicFinish = deferred()
  let firstSignal, call = 0
  apiClient.defaults.adapter = async config => {
    call++
    if (call === 1) { firstSignal = config.signal; firstStarted.resolve(); await firstFinish.promise; return response(config, empty(11)) }
    if (call === 2) { secondStarted.resolve(); await secondFinish.promise; return response(config, empty(22)) }
    assert.equal(config.params.get('eligibilityStatuses'), null)
    assert.equal(config.requiresAuth, undefined)
    assert.equal(config.recoverAuth, true)
    publicStarted.resolve(); await publicFinish.promise; return response(config, empty(0))
  }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  const observer = new QueryObserver(client, searchQueryOptions(params, 11))
  const unsubscribe = observer.subscribe(() => {})
  try {
    await firstStarted.promise
    observer.setOptions(searchQueryOptions(params, 22))
    await secondStarted.promise
    assert.equal(firstSignal.aborted, true)
    assert.equal(observer.getCurrentResult().data, undefined)
    firstFinish.resolve(); secondFinish.resolve()
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(observer.getCurrentResult().data.page, 22)
    observer.setOptions(searchQueryOptions(defaultSearchParams(), null))
    await publicStarted.promise
    assert.equal(observer.getCurrentResult().data, undefined)
    publicFinish.resolve()
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(observer.getCurrentResult().data.page, 0)
    assert.notDeepEqual(searchQueryOptions(params, 11).queryKey, searchQueryOptions(params, 22).queryKey)
    const unresolved = new QueryObserver(client, searchQueryOptions(params, null, false))
    const stop = unresolved.subscribe(() => {})
    assert.equal(unresolved.getCurrentResult().fetchStatus, 'idle')
    stop()
    assert.equal(call, 3)
  } finally { firstFinish.resolve(); secondFinish.resolve(); publicFinish.resolve(); unsubscribe(); client.clear() }
})

test('public search uses /api once, a cancellation signal and normalized params with public recovery instead of requiresAuth', async () => {
  let calls = 0
  const controller = new AbortController()
  apiClient.defaults.adapter = async (config) => {
    calls++
    assert.equal(config.url, '/opportunities')
    assert.equal(config.baseURL, '/api')
    assert.equal(config.requiresAuth, undefined)
    assert.equal(config.recoverAuth, true)
    assert.equal(config.signal, controller.signal)
    assert.equal(config.headers.get('X-XSRF-TOKEN'), undefined)
    assert.equal(config.params.get('deadlineFrom'), null)
    assert.equal(config.params.get('size'), '10')
    return response(config, empty())
  }
  assert.deepEqual(await searchOpportunities({ ...defaultSearchParams(), deadlineFrom: '2026-10-25' }, controller.signal), empty())
  assert.equal(calls, 1)
})

test('public search 401 that is not a token failure terminates without reissue; errors never become empty results', async () => {
  let calls = 0
  apiClient.defaults.adapter = async (config) => {
    calls++
    throw new AxiosError('test failure', 'ERR_BAD_RESPONSE', config, {}, {
      ...response(config, { code: 'TEST', message: 'test only', fieldErrors: {} }), status: 401,
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
  const options = { categories: [{ category: '테스트 분류', opportunityTypes: ['VOCATIONAL_TRAINING'],
    subCategories: [{ subCategory: '테스트 중분류', detailCategories: ['테스트 세분류'], opportunityTypes: ['VOCATIONAL_TRAINING'] }] }] }
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.url, '/interests/options')
    assert.equal(config.requiresAuth, undefined)
    return response(config, options)
  }
  assert.deepEqual(await getInterestOptions(), options)
  // 공고 유형이 없거나 비어 있거나 알 수 없으면 이름으로 추측하지 않고 형식 오류로 본다
  const category = options.categories[0], sub = category.subCategories[0]
  for (const invalid of [{ categories: [{ category: 'x' }] },
    { categories: [{ ...category, opportunityTypes: undefined }] }, { categories: [{ ...category, opportunityTypes: [] }] },
    { categories: [{ ...category, opportunityTypes: ['POLICY'] }] },
    { categories: [{ ...category, subCategories: [{ ...sub, opportunityTypes: undefined }] }] }]) {
    apiClient.defaults.adapter = async (config) => response(config, invalid)
    await assert.rejects(getInterestOptions(), (error) => error.kind === 'unknown')
  }
})

test('same raw large category is split into youth policy and training choices by API opportunity types', () => {
  // 실제 충돌: 청년정책 원본 대분류 '교육･직업훈련'과 직업훈련 대분류 '교육･직업훈련'이 한 노드로 온다
  const all = categoryEntries(sharedCategoryOptions)
  assert.deepEqual(all.map(entry => [entry.type, entry.category, entry.subCategories.map(sub => sub.subCategory)]), [
    ['YOUTH_POLICY', '교육', ['미래역량강화']],
    ['YOUTH_POLICY', '교육･직업훈련', ['미래역량강화', '온·오프라인교육']],
    ['VOCATIONAL_TRAINING', '교육･직업훈련', ['정보통신']],
  ])
  assert.deepEqual(categoryEntries(sharedCategoryOptions, 'VOCATIONAL_TRAINING').map(entry => entry.subCategories),
    [[{ subCategory: '정보통신', detailCategories: ['정보기술'] }]])
  // 표시: 청년정책은 원본 이름, 직업훈련 대분류만 '직업훈련'
  assert.equal(categoryLabel('YOUTH_POLICY', '교육･직업훈련'), '교육･직업훈련')
  assert.equal(categoryLabel('VOCATIONAL_TRAINING', '교육･직업훈련'), '직업훈련')
  assert.equal(categoryChoiceLabel('YOUTH_POLICY', '교육･직업훈련'), '교육･직업훈련 · 청년정책')
  assert.equal(categoryChoiceLabel('VOCATIONAL_TRAINING', '교육･직업훈련'), '직업훈련')
  // 청년정책 카드는 원문의 옛 이름이 아니라 정규화된 분류 행의 현재 이름을 보여 준다
  assert.equal(classificationLabel({ type: 'YOUTH_POLICY', category: '교육･직업훈련', subCategory: '미래역량강화', detailCategory: null,
    categories: [{ largeCategory: '교육', mediumCategory: '미래역량강화' }] }), '교육 · 미래역량강화')
  // 선택 조건 해석: 유형(type·categoryType)이 없으면 같은 이름 대분류를 하나로 정하지 않는다
  const params = { ...defaultSearchParams(), category: '교육･직업훈련' }
  assert.deepEqual(selectedCategoryEntry(all, params), { entry: undefined, ambiguous: true })
  assert.equal(selectedCategoryEntry(all, { ...params, categoryType: 'YOUTH_POLICY' }).entry.type, 'YOUTH_POLICY')
  assert.equal(selectedCategoryEntry(categoryEntries(sharedCategoryOptions, 'VOCATIONAL_TRAINING'), { ...params, type: 'VOCATIONAL_TRAINING' }).entry.type, 'VOCATIONAL_TRAINING')
  assert.deepEqual(parseCategoryEntryKey(categoryEntryKey(all[1])), { type: 'YOUTH_POLICY', category: '교육･직업훈련' })
  assert.equal(parseCategoryEntryKey('교육'), undefined)
})

test('categoryType is sent only for all-type searches with a classification and stays compatible with old URLs', async () => {
  // 유형 전체에서 대분류를 고르면 그 대분류의 유형을 함께 보낸다
  const picked = changeSearchCategory({ ...defaultSearchParams(), page: 3 }, 'category', '교육･직업훈련', 'YOUTH_POLICY')
  assert.equal(picked.categoryType, 'YOUTH_POLICY')
  assert.equal(serializeSearchParams(picked).toString(), new URLSearchParams({ category: '교육･직업훈련', categoryType: 'YOUTH_POLICY' }).toString())
  assert.deepEqual(parseSearchParams(serializeSearchParams(picked)).params, { ...picked, page: 0 })
  assert.equal(changeSearchCategory(picked, 'category', '').categoryType, '')
  // 유형을 고르면 type이 분류 유형을 정하므로 categoryType은 보내지 않는다
  assert.equal(changeSearchCategory({ ...defaultSearchParams(), type: 'YOUTH_POLICY' }, 'category', '교육', 'YOUTH_POLICY').categoryType, '')
  const typed = changeSearchType(picked, 'YOUTH_POLICY')
  assert.equal(typed.category, '교육･직업훈련')
  assert.equal(typed.categoryType, '')
  // 다른 유형으로 바꾸면 그 유형의 분류가 아니므로 분류를 지운다. 전체로 돌아가면 분류의 유형을 유지한다
  assert.equal(changeSearchType(picked, 'VOCATIONAL_TRAINING').category, '')
  assert.equal(changeSearchType(typed, '').categoryType, 'YOUTH_POLICY')
  assert.equal(changeSearchType({ ...defaultSearchParams(), type: 'YOUTH_POLICY' }, '').categoryType, '')
  // 기존 URL(categoryType 없음)은 그대로 복원하고 그대로 보낸다
  const old = parseSearchParams(new URLSearchParams({ category: '교육･직업훈련' }))
  assert.deepEqual(old.notices, [])
  assert.equal(old.params.categoryType, '')
  assert.equal(serializeSearchParams(old.params).toString(), new URLSearchParams({ category: '교육･직업훈련' }).toString())
  // 백엔드가 400을 돌려주는 조합은 요청 전에 정리한다
  const mismatch = parseSearchParams(new URLSearchParams({ type: 'VOCATIONAL_TRAINING', categoryType: 'YOUTH_POLICY', category: '교육･직업훈련', subCategory: '미래역량강화' }))
  assert.deepEqual([mismatch.params.category, mismatch.params.subCategory, mismatch.params.categoryType], ['', '', ''])
  assert.ok(mismatch.notices.length > 0)
  assert.equal(parseSearchParams(new URLSearchParams({ categoryType: 'YOUTH_POLICY' })).params.categoryType, '')
  assert.ok(parseSearchParams(new URLSearchParams({ categoryType: 'POLICY', category: '교육' })).notices.length > 0)
  const same = parseSearchParams(new URLSearchParams({ type: 'YOUTH_POLICY', categoryType: 'YOUTH_POLICY', category: '교육' }))
  assert.deepEqual([same.params.category, same.params.categoryType, same.notices], ['교육', '', []])
  apiClient.defaults.adapter = async (config) => {
    assert.equal(config.params.get('categoryType'), 'YOUTH_POLICY')
    assert.equal(config.params.get('type'), null)
    return response(config, empty())
  }
  await searchOpportunities(picked)
})

test('search requests carry the raw category value and the picked opportunity type for each type scope', async () => {
  const base = defaultSearchParams()
  const cases = [
    // 전체 유형: 선택지의 유형을 categoryType으로 보낸다
    [changeSearchCategory(base, 'category', '교육･직업훈련', 'YOUTH_POLICY'), { category: '교육･직업훈련', categoryType: 'YOUTH_POLICY', type: null }],
    [{ ...changeSearchCategory(base, 'category', '교육･직업훈련', 'VOCATIONAL_TRAINING'), subCategory: '정보통신', detailCategory: '정보기술' },
      { category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술', categoryType: 'VOCATIONAL_TRAINING', type: null }],
    // 유형 지정: type이 분류 유형을 정하므로 categoryType은 없다. 직업훈련의 표시 이름이 아니라 원본 값을 보낸다
    [{ ...base, type: 'YOUTH_POLICY', category: '교육･직업훈련', subCategory: '미래역량강화' }, { type: 'YOUTH_POLICY', category: '교육･직업훈련', subCategory: '미래역량강화', categoryType: null }],
    [{ ...base, type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술' },
      { type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술', categoryType: null }],
  ]
  for (const [params, expected] of cases) {
    apiClient.defaults.adapter = async (config) => {
      for (const [key, value] of Object.entries(expected)) assert.equal(config.params.get(key), value, key)
      assert.equal(config.params.get('category') === '직업훈련', false)
      return response(config, empty())
    }
    await searchOpportunities(params)
  }
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

test('youth policy classification comes only from normalized category rows without inventing pairs', () => {
  const row = (largeCategory, mediumCategory) => ({ largeCategory, mediumCategory })
  // 같은 대분류는 한 번만, 그 대분류 행의 중분류만 묶는다
  assert.equal(policyClassificationLabel([row('일자리', '취업'), row('일자리', '창업'), row('교육', '미래역량강화')]),
    '일자리 · 취업, 창업 / 교육 · 미래역량강화')
  // (일자리, 취업)·(교육, 미래역량강화)를 교육 · 취업처럼 엇갈려 묶지 않는다
  assert.equal(policyClassificationLabel([row('일자리', '취업'), row('교육', '미래역량강화')]), '일자리 · 취업 / 교육 · 미래역량강화')
  // 개수가 맞지 않아 짝이 없는 행: 대분류만, 중분류만 따로 보여 주고 대분류에 붙이지 않는다
  assert.equal(policyClassificationLabel([row('일자리', null), row('교육', null), row(null, '취업'), row(null, '창업')]),
    '일자리 / 교육 / 취업, 창업')
  assert.equal(policyClassificationLabel([row(null, '취업')]), '취업')
  // 공백·중복 행 정리, 빈 목록과 필드 없음은 빈 문자열
  assert.equal(policyClassificationLabel([row(' 일자리 ', ' 취업 '), row('일자리', '취업'), row(' ', ''), { largeCategory: '주거' }]), '일자리 · 취업 / 주거')
  assert.equal(policyClassificationLabel([]), '')
  assert.equal(policyClassificationLabel(undefined), '')
  // 청년정책은 원문 쉼표 문자열로 대체하지 않는다
  const raw = { type: 'YOUTH_POLICY', category: '일자리,교육･직업훈련', subCategory: '취업,미래역량강화', detailCategory: null }
  assert.equal(classificationLabel({ ...raw, categories: [] }), '')
  assert.equal(classificationLabel(raw), '')
  assert.equal(classificationLabel({ ...raw, categories: [row('일자리', '취업'), row('교육', '미래역량강화')] }), '일자리 · 취업 / 교육 · 미래역량강화')
  // 직업훈련은 분류 행과 무관하게 기존 NCS 표시를 유지한다
  const training = { type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술', categories: [] }
  assert.equal(classificationLabel(training), '정보통신 · 정보기술')
  assert.equal(classificationLabel({ ...training, subCategory: '직업훈련', detailCategory: null }), '직업훈련')
})

test('category rows are optional but must be well formed when present', () => {
  assert.ok(isCategoryRows(undefined))
  assert.ok(isCategoryRows([]))
  assert.ok(isCategoryRows([{ largeCategory: '일자리', mediumCategory: null }, { mediumCategory: '취업' }]))
  assert.ok(!isCategoryRows('일자리,교육'))
  assert.ok(!isCategoryRows([{ largeCategory: 1 }]))
  assert.ok(!isCategoryRows([null]))
  const item = { opportunityId: 1, type: 'YOUTH_POLICY', title: '테스트', category: null, subCategory: null, detailCategory: null,
    createdAt: '2026-10-25T09:00:00', eligibilityStatus: null, saved: false }
  const page = (content) => ({ content, page: 0, size: 10, totalElements: content.length, totalPages: 1, hasNext: false })
  assert.ok(isSearchResponse(page([item])))
  assert.ok(isSearchResponse(page([{ ...item, categories: [{ largeCategory: '일자리', mediumCategory: '취업' }] }])))
  assert.ok(!isSearchResponse(page([{ ...item, categories: [{ largeCategory: ['일자리'] }] }])))
})
