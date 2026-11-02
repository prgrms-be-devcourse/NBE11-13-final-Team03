import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'

// DOM 라이브러리를 추가하지 않고 실제 TSX의 서버 렌더링 결과를 확인한다.
// 클릭·포커스·미디어쿼리 검증을 대신하지 않는다.
const require = createRequire(import.meta.url)
require.extensions['.css'] = (module) => {
  module.exports = { __esModule: true, default: new Proxy({}, { get: (_, key) => String(key) }) }
}
for (const extension of ['.ts', '.tsx']) {
  require.extensions[extension] = (module, filename) => {
    const { outputText } = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true },
    })
    module._compile(outputText, filename)
  }
}
const React = require('react')
const { renderToStaticMarkup } = require('react-dom/server')
const { MemoryRouter } = require('react-router-dom')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const { AuthContext } = require('../src/auth/AuthContext.ts')
const Page = require('../src/pages/opportunities/OpportunitiesPage.tsx').default
const Filters = require('../src/pages/opportunities/SearchFilters.tsx').default
const RegionFilter = require('../src/pages/opportunities/SearchRegionFilter.tsx').default
const { changeSearchCategory, changeSearchType, defaultSearchParams, parseCategoryEntryKey, parseSearchParams, resetSearchFilters, selectSingleTrainingCategory, serializeSearchParams } = require('../src/pages/opportunities/searchParams.ts')
const { ApiError } = require('../src/api/ApiError.ts')
const { searchErrorMessage } = require('../src/pages/opportunities/searchError.ts')

function renderPage(data, url = '/opportunities', error, auth = { status: 'anonymous' }) {
  let params = parseSearchParams(new URL(url, 'http://localhost').searchParams).params
  if (auth.status === 'anonymous' && params.eligibilityStatuses.length) params = { ...params, eligibilityStatuses: [], page: 0 }
  const canonical = serializeSearchParams(params).toString()
  const userId = auth.status === 'authenticated' ? auth.user.id : null
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, gcTime: Infinity, staleTime: Infinity } } })
  if (data) client.setQueryData(['opportunities', canonical, userId], data)
  if (error) client.getQueryCache().build(client, { queryKey: ['opportunities', canonical, userId] })
    .setState({ status: 'error', error, fetchStatus: 'idle' })
  client.setQueryData(['interest-options'], { categories: [] })
  client.setQueryData(['regions'], [])
  try {
    return renderToStaticMarkup(React.createElement(MemoryRouter, { initialEntries: [url] },
      React.createElement(QueryClientProvider, { client }, React.createElement(AuthContext.Provider, { value: auth }, React.createElement(Page)))))
  } finally { client.clear() }
}

const item = { opportunityId: 42, type: 'YOUTH_POLICY', title: '테스트 전용 검색 결과', category: '테스트 분류',
  subCategory: null, detailCategory: null, createdAt: '2026-10-25T09:00:00', eligibilityStatus: null, saved: false }
const data = { content: [item], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false }

test('sort select shows relevance only with a keyword and selects the applied sort', () => {
  const selected = (html) => html.match(/<option value="([A-Z_]+)" selected="">/)?.[1]
  const keyword = renderPage(data, '/opportunities?keyword=%EC%B2%AD%EB%85%84')
  assert.match(keyword, /<option value="RELEVANCE"[^>]*>관련도순<\/option>/)
  assert.equal(selected(keyword), 'RELEVANCE')
  assert.equal(selected(renderPage(data, '/opportunities?keyword=%EC%B2%AD%EB%85%84&sort=LATEST')), 'LATEST')
  const none = renderPage(data, '/opportunities')
  assert.doesNotMatch(none, /관련도순/)
  assert.equal(selected(none), 'LATEST')
  assert.equal(selected(renderPage(data, '/opportunities?sort=RELEVANCE')), 'LATEST')
})

test('header-style keyword search URL relies on the default relevance sort', () => {
  // Header.tsx와 같은 방식으로 검색 URL을 만든다
  const { changeSearchConditions } = require('../src/pages/opportunities/searchParams.ts')
  assert.equal(serializeSearchParams(changeSearchConditions(defaultSearchParams(), { keyword: '청년' })).toString(),
    'keyword=%EC%B2%AD%EB%85%84')
})

test('result markup renders only supplied fields, accessible bookmark state and existing detail link', () => {
  const html = renderPage(data)
  assert.match(html, /테스트 전용 검색 결과/)
  assert.match(html, /href="\/opportunities\/42"/)
  assert.match(html, /최초 등록일/)
  assert.match(html, /저장 안 함/)
  assert.doesNotMatch(html, /판정 불가/)
  assert.match(renderPage({ ...data, content: [{ ...item, eligibilityStatus: 'UNKNOWN', saved: true }] }), /판정 불가/)
  assert.match(renderPage({ ...data, content: [{ ...item, saved: true }] }), /저장됨/)
})

test('search cards follow home type colors and classification rules without visible save labels', () => {
  const training = renderPage({ ...data, content: [{ ...item, type: 'VOCATIONAL_TRAINING', category: ' 교육･직업훈련 ', subCategory: '직업훈련', detailCategory: '정보기술' }] })
  assert.match(training, /class="typeBadge" data-opportunity-type="VOCATIONAL_TRAINING">직업훈련/)
  assert.match(training, /<div data-accent="VOCATIONAL_TRAINING" class="card default resultCard">/)
  assert.match(training, /class="category">정보기술<\/p>/)
  assert.doesNotMatch(training, /교육･직업훈련|직업훈련 · |class="category">직업훈련/)
  const ncs = renderPage({ ...data, content: [{ ...item, type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: ' 정보통신 ', detailCategory: '정보기술' }] })
  assert.match(ncs, /class="category">정보통신 · 정보기술<\/p>/)
  const policy = renderPage({ ...data, content: [{ ...item, category: ' 일자리 ', subCategory: '일자리', detailCategory: ' 취업지원 ' }] })
  assert.match(policy, /class="category">일자리 · 취업지원<\/p>/)
  const missing = renderPage({ ...data, content: [{ ...item, category: ' ', subCategory: null, detailCategory: '' }] })
  assert.doesNotMatch(missing, /class="category"|분류 미확인/)
  assert.match(policy, /<a(?=[^>]*class="iconButton")(?=[^>]*href="\/login\?returnTo=%2Fopportunities")(?=[^>]*aria-label="테스트 전용 검색 결과 저장 안 함, 저장하려면 로그인")[^>]*>/)
  const authenticated = renderPage({ ...data, content: [{ ...item, saved: true }] }, '/opportunities', undefined, { status: 'authenticated', user: { id: 11 } })
  assert.match(authenticated, /aria-label="테스트 전용 검색 결과 저장 해제" aria-pressed="true"/)
  assert.match(authenticated, /fill="currentColor"/)
  assert.doesNotMatch(authenticated, />저장됨<|>저장 해제<|>저장 안 함<|>저장하려면 로그인</)
  const css = fs.readFileSync(new URL('../src/components/opportunities/OpportunityTypeBadge.module.css', import.meta.url), 'utf8')
  assert.match(css, /data-opportunity-type='VOCATIONAL_TRAINING'[\s\S]*?var\(--color-accent-blue\)/)
})

const regionOptions = [
  { value: '4100000000', label: '테스트 도', parentValue: null, isLeaf: false },
  { value: '4111000000', label: '테스트 시', parentValue: '4100000000', isLeaf: false },
  { value: '4111100000', label: '테스트 구', parentValue: '4111000000', isLeaf: true },
]
test('search region selects restore every hierarchy level and distinguish loading, error, empty and missing selections', () => {
  const render = (source, value = '') => renderToStaticMarkup(React.createElement(RegionFilter, { source, value, onChange: () => {} }))
  const ready = render({ status: 'ready', options: regionOptions }, '4111000000')
  assert.match(ready, /value="4100000000" selected=""/)
  assert.match(ready, /value="4111000000" selected=""/)
  assert.match(ready, /value="4111100000"/)
  assert.match(ready, /선택한 상위 지역 전체/)
  assert.doesNotMatch(ready, /말단 지역만|준비 중|학교 소재지/)
  assert.match(render({ status: 'loading' }), /지역 목록을 불러오는 중/)
  assert.match(render({ status: 'error', onRetry: () => {} }), /role="alert"[\s\S]*지역 목록 다시 불러오기/)
  assert.match(render({ status: 'ready', options: [] }), /선택할 수 있는 지역이 없어요/)
  const shortCodes = render({ status: 'ready', options: [{ value: '11', label: '서울특별시', parentValue: null, isLeaf: true }] })
  assert.match(shortCodes, /value="11">서울특별시<\/option>/)
  assert.doesNotMatch(shortCodes, /지역 코드 형식을 확인할 수 없어요/)
  assert.match(render({ status: 'ready', options: regionOptions }, '9999999999'), /현재 목록에 없어요/)
})

test('only verified users see eligibility checkboxes and auth-unresolved URLs do not show cached search data', () => {
  const auth = { status: 'authenticated', user: { id: 11 } }
  const html = renderPage(data, '/opportunities?eligibilityStatuses=ELIGIBLE,UNKNOWN', undefined, auth)
  assert.match(html, /<legend>신청 가능성<\/legend>/)
  assert.equal((html.match(/type="checkbox"/g) ?? []).length, 4)
  assert.equal((html.match(/type="checkbox" checked=""/g) ?? []).length, 2)
  const anonymous = renderPage(data, '/opportunities?eligibilityStatuses=ELIGIBLE')
  assert.doesNotMatch(anonymous, /<legend>신청 가능성<\/legend>|준비 중|로그인 기능과 함께|초기화한 조건도/)
  for (const status of ['checking', 'error']) {
    const pending = renderPage(data, '/opportunities?eligibilityStatuses=ELIGIBLE', undefined, { status, user: { id: 11 } })
    assert.doesNotMatch(pending, /테스트 전용 검색 결과|<legend>신청 가능성<\/legend>/)
    assert.match(pending, /로그인 상태/)
  }
})

test('loading, no results and server error produce separate accessible states', () => {
  assert.match(renderPage(undefined), /검색 결과를 불러오는 중이에요/)
  const empty = renderPage({ ...data, content: [], totalElements: 0, totalPages: 0 })
  assert.match(empty, /조건에 맞는 기회를 찾지 못했어요/)
  assert.doesNotMatch(empty, /role="alert"/)
  const error = renderPage(undefined, '/opportunities', new ApiError('http', 'test', 500))
  assert.match(error, /검색 서버에 문제가 생겼어요/)
  assert.match(error, /다시 시도/)
  assert.match(error, /role="alert"/)
})

test('filter markup restores applied values, labels native controls and exposes date range errors', () => {
  const applied = { ...defaultSearchParams(), type: 'YOUTH_POLICY', category: '테스트 분류',
    deadlineFrom: '2026-11-01', deadlineTo: '2026-10-01' }
  const html = renderToStaticMarkup(React.createElement(Filters, { applied, optionsLoading: false, optionsError: false,
    onApply: () => {}, onRetryOptions: () => {} }))
  // 선택지 목록이 오기 전에도 URL에서 복원한 대분류를 보여 준다 (선택 값은 유형:대분류라 목록이 오면 다시 맞춘다)
  assert.match(html, /<option value="current" disabled="" selected="">기존 조건: 테스트 분류/)
  assert.match(html, /신청 마감일 범위/)
  assert.doesNotMatch(html, /교육 시작일 범위/)
  assert.match(html, /aria-invalid="true"/)
  assert.match(html, /aria-describedby="[^"]+-date-error"/)
  assert.match(html, /시작 범위는 끝 범위보다 늦을 수 없어요/)
  assert.match(html, /필터 적용/)
  assert.match(html, /필터 초기화/)
  const training = renderToStaticMarkup(React.createElement(Filters, { applied: { ...defaultSearchParams(), type: 'VOCATIONAL_TRAINING' },
    optionsLoading: false, optionsError: false, onApply: () => {}, onRetryOptions: () => {} }))
  assert.match(training, /교육 시작일 범위/)
  assert.doesNotMatch(training, /신청 마감일 범위/)
})

test('applied OPEN label distinguishes policy recruitment from training start-date search', () => {
  const policy = renderPage(data, '/opportunities?type=YOUTH_POLICY&applicationStatus=OPEN')
  assert.match(policy, /aria-label="적용된 검색 조건"><span>청년정책<\/span><span>모집 중<\/span>/)
  const training = renderPage({ ...data, content: [{ ...item, type: 'VOCATIONAL_TRAINING' }] },
    '/opportunities?type=VOCATIONAL_TRAINING&applicationStatus=OPEN&startDateFrom=2026-10-25')
  assert.match(training, /aria-label="적용된 검색 조건"><span>직업훈련<\/span><span>개강 예정<\/span><span>교육 시작일 2026\.10\.25부터<\/span>/)
  assert.match(training, /교육 시작일 기준/)
})

test('mobile filter disclosure points to labelled dialog; pagination presents 0-based page as human page', () => {
  const html = renderPage({ ...data, page: 1, totalPages: 3, totalElements: 60, hasNext: true }, '/opportunities?page=1')
  assert.match(html, /aria-haspopup="dialog"/)
  assert.match(html, /aria-controls="search-filter-dialog"/)
  assert.match(html, /<dialog[^>]*aria-labelledby="mobile-filter-title"/)
  assert.match(html, /aria-current="page" aria-label="2페이지"/)
})

const policyTypes = ['YOUTH_POLICY'], trainingTypes = ['VOCATIONAL_TRAINING']
const categoryOptions = { categories: [
  { category: '교육', opportunityTypes: policyTypes, subCategories: [] },
  { category: '일자리', opportunityTypes: policyTypes, subCategories: [{ subCategory: '취업지원', detailCategories: [], opportunityTypes: policyTypes }] },
  { category: '교육･직업훈련', opportunityTypes: trainingTypes, subCategories: [
    { subCategory: '정보통신', detailCategories: ['정보기술'], opportunityTypes: trainingTypes },
    { subCategory: '건설', detailCategories: ['건축'], opportunityTypes: trainingTypes },
  ] },
] }
const { sharedCategoryOptions } = await import('./profile-fixtures.mjs')
function renderFilters(applied = defaultSearchParams(), props = {}) {
  return renderToStaticMarkup(React.createElement(Filters, {
    applied, options: categoryOptions, optionsLoading: false,
    onApply: () => {}, onRetryOptions: () => {}, ...props,
  }))
}
function classificationSelects(html) {
  return [...html.matchAll(/<select\b[^>]*>[\s\S]*?<\/select>/g)].slice(2, 5).map(([markup]) => markup)
}

test('education label stays distinct from training and category choices follow the selected opportunity type', () => {
  const all = classificationSelects(renderFilters())[0]
  // 선택 값은 (공고 유형, 원본 대분류)이고, 표시 이름만 구분한다. 전체 유형은 청년정책·직업훈련 묶음 제목(같은 optgroup)으로 나눈다.
  assert.match(all, /<optgroup label="청년정책"><option value="YOUTH_POLICY:교육">교육<\/option>/)
  assert.match(all, /<optgroup label="직업훈련"><option value="VOCATIONAL_TRAINING:교육･직업훈련">직업훈련 전체<\/option><\/optgroup><\/select>/)
  const policy = classificationSelects(renderFilters({ ...defaultSearchParams(), type: 'YOUTH_POLICY' }))[0]
  assert.match(policy, /value="YOUTH_POLICY:교육">교육<\/option>/)
  assert.doesNotMatch(policy, /교육･직업훈련|>직업훈련<|optgroup/)
  const training = classificationSelects(renderFilters({ ...defaultSearchParams(), type: 'VOCATIONAL_TRAINING' }))[0]
  assert.match(training, /value="VOCATIONAL_TRAINING:교육･직업훈련">직업훈련<\/option>/)
  assert.doesNotMatch(training, /YOUTH_POLICY|optgroup/)
})

test('classification selects expose only children of selected parents; policy with no detail disables detail selection', () => {
  const initial = classificationSelects(renderFilters())
  assert.doesNotMatch(initial[0], /disabled/)
  assert.match(initial[1], /disabled/)
  assert.match(initial[2], /disabled/)
  assert.doesNotMatch(initial[1], /취업지원|정보통신/)
  const training = classificationSelects(renderFilters({ ...defaultSearchParams(), category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술' }))
  assert.match(training[1], /정보통신/)
  assert.match(training[1], /건설/)
  assert.doesNotMatch(training[1], /취업지원/)
  assert.match(training[2], /value="정보기술" selected/)
  assert.doesNotMatch(training[2], /건축/)
  const policyHtml = renderFilters({ ...defaultSearchParams(), category: '일자리', subCategory: '취업지원' })
  assert.match(classificationSelects(policyHtml)[2], /disabled/)
  assert.match(policyHtml, /이 중분류에는 세부 분류가 없어요/)
  assert.doesNotMatch(policyHtml, /datalist|분류명 입력/)
})

test('youth policy and training sharing the raw large category 교육･직업훈련 get separate choices, children and labels', () => {
  const render = (params) => classificationSelects(renderFilters({ ...defaultSearchParams(), ...params }, { options: sharedCategoryOptions }))
  // 전체 유형: 같은 원본 대분류가 청년정책 묶음(원본 이름)과 직업훈련 묶음(‘직업훈련 전체’)에 따로 나온다
  const [all] = render({})
  assert.match(all, /<optgroup label="청년정책">[\s\S]*<option value="YOUTH_POLICY:교육･직업훈련">교육･직업훈련<\/option><\/optgroup><optgroup label="직업훈련"><option value="VOCATIONAL_TRAINING:교육･직업훈련">직업훈련 전체<\/option><\/optgroup><\/select>/)
  // 두 묶음 제목은 같은 방식(optgroup)이고, 제목과 같은 이름의 항목은 없으며 각 선택지는 한 번씩만 나온다
  assert.equal(all.match(/<optgroup /g).length, 2)
  assert.doesNotMatch(all, /· 청년정책|>직업훈련<\/option>|>청년정책<\/option>/)
  assert.equal(all.match(/>직업훈련 전체<\/option>/g).length, 1)
  assert.equal(all.match(/>교육･직업훈련<\/option>/g).length, 1)
  // 청년정책 유형: 교육･직업훈련이 청년정책 대분류로 보이고 청년정책 중분류만 나온다
  const policy = render({ type: 'YOUTH_POLICY', category: '교육･직업훈련' })
  assert.match(policy[0], /<option value="YOUTH_POLICY:교육･직업훈련" selected="">교육･직업훈련<\/option>/)
  assert.match(policy[1], /미래역량강화[\s\S]*온·오프라인교육/)
  assert.doesNotMatch(policy[1], /정보통신/)
  // 직업훈련 유형: NCS 분류만
  const training = render({ type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '정보통신' })
  assert.match(training[0], /<option value="VOCATIONAL_TRAINING:교육･직업훈련" selected="">직업훈련<\/option>/)
  assert.match(training[1], /value="정보통신" selected/)
  assert.doesNotMatch(training[1], /미래역량강화|온·오프라인교육/)
  assert.match(training[2], /정보기술/)
  // 전체 유형 + 분류 유형: 고른 유형의 하위 분류만
  const allPolicy = render({ category: '교육･직업훈련', categoryType: 'YOUTH_POLICY', subCategory: '온·오프라인교육' })
  assert.match(allPolicy[0], /value="YOUTH_POLICY:교육･직업훈련" selected=""/)
  assert.match(allPolicy[1], /value="온·오프라인교육" selected/)
  assert.doesNotMatch(allPolicy[1], /정보통신/)
  // 분류 유형 없는 기존 URL: 하나로 정할 수 없으므로 유형 선택을 요구하고 적용을 막는다
  const legacy = renderFilters({ ...defaultSearchParams(), category: '교육･직업훈련' }, { options: sharedCategoryOptions })
  assert.match(legacy, /<option value="current" disabled="" selected="">기존 조건: 교육･직업훈련 \(공고 유형 선택 필요\)<\/option>/)
  assert.match(legacy, /청년정책과 직업훈련에 같은 이름의 대분류가 있어요/)
  assert.match(legacy, /<button[^>]*type="submit"[^>]*disabled=""/)
  // 직업훈련 유형에서 청년정책 중분류를 고른 조건은 목록에 없는 조건이다
  const wrongChild = renderFilters({ ...defaultSearchParams(), type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '미래역량강화' }, { options: sharedCategoryOptions })
  assert.match(wrongChild, /기존 조건: 미래역량강화 \(목록 확인 필요\)/)
})

// 선택지 값과 표시 이름을 그려진 select에서 그대로 읽는다 (사용자가 고르는 값과 같다)
function optionsOf(markup) {
  return [...markup.matchAll(/<option value="([^"]*)"[^>]*>([^<]*)<\/option>/g)].map(([, value, label]) => ({ value, label })).filter(({ value }) => value && value !== 'current')
}
function isDisabled(markup) {
  return /^<select[^>]*disabled/.test(markup)
}
// 사용자가 대분류 → 중분류 → 세부 분류를 차례로 고르는 흐름을 SearchFilters의 onChange와 같은 함수로 재현한다
function pickChain(start, { categoryLabel, subCategory, detailCategory }) {
  let params = start
  let selects = classificationSelects(renderFilters(params, { options: sharedCategoryOptions }))
  const choice = optionsOf(selects[0]).find(({ label }) => label === categoryLabel)
  assert.ok(choice, `대분류 선택지 없음: ${categoryLabel}`)
  const picked = parseCategoryEntryKey(choice.value)
  params = changeSearchCategory(params, 'category', picked.category, picked.type)
  selects = classificationSelects(renderFilters(params, { options: sharedCategoryOptions }))
  assert.match(selects[0], new RegExp(`value="${choice.value}" selected=""`))
  assert.ok(!isDisabled(selects[1]), '중분류가 비활성화됨')
  if (subCategory) {
    assert.ok(optionsOf(selects[1]).some(({ value }) => value === subCategory), `중분류 선택지 없음: ${subCategory}`)
    params = changeSearchCategory(params, 'subCategory', subCategory)
    selects = classificationSelects(renderFilters(params, { options: sharedCategoryOptions }))
  }
  if (detailCategory) {
    assert.ok(!isDisabled(selects[2]), '세부 분류가 비활성화됨')
    params = changeSearchCategory(params, 'detailCategory', detailCategory)
    selects = classificationSelects(renderFilters(params, { options: sharedCategoryOptions }))
    assert.match(selects[2], new RegExp(`value="${detailCategory}" selected`))
  }
  return { params, selects, query: serializeSearchParams(params), picked }
}

test('category chains for all, youth policy and training types select (type, raw category) and send the matching categoryType', () => {
  const all = defaultSearchParams()
  // 전체 유형 + 청년정책 교육･직업훈련: 청년정책 중분류만 나오고 세부 분류는 쓰지 않는다
  const allPolicy = pickChain(all, { categoryLabel: '교육･직업훈련', subCategory: '온·오프라인교육' })
  assert.deepEqual(allPolicy.picked, { type: 'YOUTH_POLICY', category: '교육･직업훈련' })
  assert.doesNotMatch(allPolicy.selects[1], /정보통신/)
  assert.ok(isDisabled(allPolicy.selects[2]))
  assert.deepEqual(Object.fromEntries(allPolicy.query), { category: '교육･직업훈련', subCategory: '온·오프라인교육', categoryType: 'YOUTH_POLICY' })
  // 전체 유형 + 직업훈련: 표시 이름은 ‘직업훈련’, 보내는 값은 원본 교육･직업훈련 + NCS 1차·2차
  const allTraining = pickChain(all, { categoryLabel: '직업훈련 전체', subCategory: '정보통신', detailCategory: '정보기술' })
  assert.deepEqual(allTraining.picked, { type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련' })
  assert.doesNotMatch(allTraining.selects[1], /미래역량강화|온·오프라인교육/)
  assert.deepEqual(Object.fromEntries(allTraining.query), { category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술', categoryType: 'VOCATIONAL_TRAINING' })
  // 청년정책 유형: 청년정책 대·중분류만 고를 수 있고 직업훈련 선택지는 없다. 유형이 정해져 있으므로 categoryType은 보내지 않는다
  const policyStart = { ...all, type: 'YOUTH_POLICY' }
  const [policyCategories] = classificationSelects(renderFilters(policyStart, { options: sharedCategoryOptions }))
  assert.ok(!isDisabled(policyCategories))
  assert.deepEqual(optionsOf(policyCategories).map(({ label }) => label), ['교육', '교육･직업훈련'])
  const policy = pickChain(policyStart, { categoryLabel: '교육･직업훈련', subCategory: '미래역량강화' })
  assert.doesNotMatch(policy.selects[1], /정보통신/)
  assert.ok(isDisabled(policy.selects[2]))
  assert.deepEqual(Object.fromEntries(policy.query), { type: 'YOUTH_POLICY', category: '교육･직업훈련', subCategory: '미래역량강화' })
  // 직업훈련 유형: 대분류 ‘직업훈련’ 하나, 중분류 NCS 1차, 세부 분류 NCS 2차 (기존 동작)
  const trainingStart = { ...all, type: 'VOCATIONAL_TRAINING' }
  assert.deepEqual(optionsOf(classificationSelects(renderFilters(trainingStart, { options: sharedCategoryOptions }))[0]).map(({ label }) => label), ['직업훈련'])
  const training = pickChain(trainingStart, { categoryLabel: '직업훈련', subCategory: '정보통신', detailCategory: '정보기술' })
  assert.doesNotMatch(training.selects[1], /미래역량강화|온·오프라인교육/)
  assert.deepEqual(Object.fromEntries(training.query), { type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술' })
})

test('choosing the training type auto-selects its single category, leaving it clears training choices, and URL restore/reset keep selections consistent', () => {
  // SearchFilters의 공고 유형 onChange와 같은 함수 조합
  const chooseType = (params, type, options = sharedCategoryOptions) => selectSingleTrainingCategory(changeSearchType(params, type), options)
  const render = (params) => classificationSelects(renderFilters(params, { options: sharedCategoryOptions }))
  // 1. 직업훈련 유형을 고르면 대분류 ‘직업훈련’이 바로 선택되고 보내는 값은 원본 교육･직업훈련이다
  const training = chooseType({ ...defaultSearchParams(), keyword: '청년', sort: 'RELEVANCE', page: 3 }, 'VOCATIONAL_TRAINING')
  assert.deepEqual([training.type, training.category, training.categoryType, training.page], ['VOCATIONAL_TRAINING', '교육･직업훈련', '', 0])
  let selects = render(training)
  assert.match(selects[0], /<option value="VOCATIONAL_TRAINING:교육･직업훈련" selected="">직업훈련<\/option>/)
  assert.ok(!isDisabled(selects[1]))
  assert.ok(isDisabled(selects[2]))
  // NCS 1차 → NCS 2차 선택
  assert.deepEqual(optionsOf(selects[1]).map(({ value }) => value), ['정보통신'])
  const ncs = changeSearchCategory(changeSearchCategory(training, 'subCategory', '정보통신'), 'detailCategory', '정보기술')
  selects = render(ncs)
  assert.match(selects[1], /value="정보통신" selected/)
  assert.match(selects[2], /value="정보기술" selected/)
  assert.deepEqual(Object.fromEntries(serializeSearchParams(ncs)), { keyword: '청년', type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술' })
  // 분류 목록을 아직 못 받았거나 다른 유형이면 자동 선택하지 않는다
  assert.equal(selectSingleTrainingCategory(changeSearchType(defaultSearchParams(), 'VOCATIONAL_TRAINING'), undefined).category, '')
  assert.equal(chooseType(defaultSearchParams(), 'YOUTH_POLICY').category, '')
  // 2. 직업훈련에서 전체 유형·청년정책으로 돌아오면 직업훈련 분류가 남지 않는다
  for (const type of ['', 'YOUTH_POLICY']) {
    const back = chooseType(ncs, type)
    assert.deepEqual([back.type, back.category, back.subCategory, back.detailCategory, back.categoryType], [type, '', '', '', ''])
    const [category, sub, detail] = render(back)
    assert.match(category, /<option value="" selected="">전체 대분류<\/option>/)
    assert.equal(category.match(/selected=""/g).length, 1)
    assert.ok(isDisabled(sub) && isDisabled(detail))
    assert.equal(serializeSearchParams(back).get('categoryType'), null)
    // 다시 직업훈련을 고르면 다시 자동 선택된다
    assert.equal(chooseType(back, 'VOCATIONAL_TRAINING').category, '교육･직업훈련')
  }
  // 청년정책에서 고른 분류는 전체 유형으로 가도 그 유형(categoryType)과 함께 남는다 (기존 동작)
  const policy = changeSearchCategory({ ...defaultSearchParams(), type: 'YOUTH_POLICY' }, 'category', '교육･직업훈련')
  const policyToAll = chooseType(policy, '')
  assert.deepEqual([policyToAll.category, policyToAll.categoryType], ['교육･직업훈련', 'YOUTH_POLICY'])
  assert.match(render(policyToAll)[0], /value="YOUTH_POLICY:교육･직업훈련" selected=""/)
  // 3. URL 복원: 직업훈련 유형과 전체 유형 + categoryType 모두 같은 선택으로 그려지고 그대로 다시 직렬화된다
  for (const query of [
    { type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술' },
    { category: '교육･직업훈련', subCategory: '정보통신', detailCategory: '정보기술', categoryType: 'VOCATIONAL_TRAINING' },
  ]) {
    const { params, notices } = parseSearchParams(new URLSearchParams(query))
    assert.deepEqual(notices, [])
    const [category, sub, detail] = render(params)
    assert.match(category, new RegExp(`value="VOCATIONAL_TRAINING:교육･직업훈련" selected="">${query.type ? '직업훈련' : '직업훈련 전체'}<`))
    assert.match(sub, /value="정보통신" selected/)
    assert.match(detail, /value="정보기술" selected/)
    assert.deepEqual(Object.fromEntries(serializeSearchParams(params)), query)
  }
  // 4. 필터 초기화: 키워드·페이지 크기만 남기고 유형·분류·categoryType을 지운다
  const reset = resetSearchFilters({ ...ncs, size: 50 })
  assert.deepEqual(reset, { ...defaultSearchParams(), keyword: '청년', sort: reset.sort, size: 50 })
  assert.deepEqual([reset.type, reset.category, reset.subCategory, reset.detailCategory, reset.categoryType], ['', '', '', '', ''])
  assert.match(render(reset)[0], /<option value="" selected="">전체 대분류<\/option>/)
  assert.match(renderFilters(ncs, { options: sharedCategoryOptions }), /필터 초기화/)
})

test('all-type filter explains a missing policy category list instead of inventing choices', () => {
  // 청년정책 분류 행이 없는(백필 전) 선택지 응답: 직업훈련 분류만 있다 (로컬 DB에서 실제로 받은 응답의 형태)
  const shared = sharedCategoryOptions.categories[1]
  const trainingOnly = { categories: [{ ...shared, opportunityTypes: ['VOCATIONAL_TRAINING'], subCategories: shared.subCategories.filter((sub) => sub.opportunityTypes.includes('VOCATIONAL_TRAINING')) }] }
  const all = renderFilters(defaultSearchParams(), { options: trainingOnly })
  const [category] = classificationSelects(all)
  assert.doesNotMatch(category, /YOUTH_POLICY|label="청년정책"/)
  assert.match(category, /<optgroup label="직업훈련"><option value="VOCATIONAL_TRAINING:교육･직업훈련">직업훈련 전체<\/option><\/optgroup><\/select>/)
  assert.match(all, /청년정책 분류 목록이 아직 없어요/)
  const policy = renderFilters({ ...defaultSearchParams(), type: 'YOUTH_POLICY' }, { options: trainingOnly })
  assert.match(policy, /선택할 수 있는 분류가 아직 없어요/)
  for (const markup of classificationSelects(policy)) assert.ok(isDisabled(markup))
  // 직업훈련 유형은 그대로이고, 두 유형이 모두 있으면 안내하지 않는다
  assert.match(classificationSelects(renderFilters({ ...defaultSearchParams(), type: 'VOCATIONAL_TRAINING' }, { options: trainingOnly }))[0], /value="VOCATIONAL_TRAINING:교육･직업훈련">직업훈련<\/option>/)
  assert.doesNotMatch(renderFilters(defaultSearchParams(), { options: sharedCategoryOptions }), /분류 목록이 아직 없어요/)
})

test('applied chips and policy cards never show a youth policy 교육･직업훈련 as 직업훈련', () => {
  const chips = (html) => html.match(/<div class="chips" aria-label="적용된 검색 조건">([\s\S]*?)<\/div>/)?.[1]
  assert.match(chips(renderPage(data, '/opportunities?category=%EA%B5%90%EC%9C%A1%EF%BD%A5%EC%A7%81%EC%97%85%ED%9B%88%EB%A0%A8&categoryType=YOUTH_POLICY')),
    /<span>교육･직업훈련 · 청년정책<\/span>/)
  assert.match(chips(renderPage(data, '/opportunities?type=YOUTH_POLICY&category=%EA%B5%90%EC%9C%A1%EF%BD%A5%EC%A7%81%EC%97%85%ED%9B%88%EB%A0%A8')),
    /<span>청년정책<\/span><span>교육･직업훈련<\/span>/)
  assert.match(chips(renderPage(data, '/opportunities?type=VOCATIONAL_TRAINING&category=%EA%B5%90%EC%9C%A1%EF%BD%A5%EC%A7%81%EC%97%85%ED%9B%88%EB%A0%A8')),
    /<span>직업훈련<\/span><span>직업훈련<\/span>/)
  const policyCard = renderPage({ ...data, content: [{ ...item, category: '교육･직업훈련', subCategory: '미래역량강화' }] })
  assert.match(policyCard, /data-opportunity-type="YOUTH_POLICY">청년정책/)
  assert.match(policyCard, /class="category">교육･직업훈련 · 미래역량강화<\/p>/)
})

test('option loading, errors and successful empty lists remain distinct without free-input fallback', () => {
  const loading = renderFilters(defaultSearchParams(), { options: undefined, optionsLoading: true })
  assert.match(loading, /분류 목록을 불러오는 중/)
  assert.doesNotMatch(loading, /선택할 수 있는 분류가 아직 없어요/)
  const error = renderFilters(defaultSearchParams(), { optionsError: new ApiError('http', 'test only', 503) })
  assert.match(error, /분류 목록을 불러오지 못했어요/)
  assert.match(error, /분류 목록 다시 불러오기/)
  assert.doesNotMatch(error, /직접 입력|분류명 입력|datalist/)
  for (const markup of classificationSelects(error)) assert.match(markup, /disabled/)
  const empty = renderFilters(defaultSearchParams(), { options: { categories: [] } })
  assert.match(empty, /선택할 수 있는 분류가 아직 없어요/)
  assert.doesNotMatch(empty, /분류 목록을 불러오지 못했어요/)
})

test('region list loading or failure does not block other filters; a loaded list still rejects unknown or malformed codes', () => {
  const applyDisabled = (html) => /<button[^>]*disabled=""[^>]*>필터 적용/.test(html)
  const applied = { ...defaultSearchParams(), region: '1100000000' }
  assert.equal(applyDisabled(renderFilters(applied, { regions: { status: 'loading' } })), false)
  assert.equal(applyDisabled(renderFilters(applied, { regions: { status: 'error', onRetry: () => {} } })), false)
  assert.equal(applyDisabled(renderFilters({ ...defaultSearchParams(), region: '4111000000' }, { regions: { status: 'ready', options: regionOptions } })), false)
  assert.equal(applyDisabled(renderFilters(applied, { regions: { status: 'ready', options: regionOptions } })), true)
  for (const status of ['loading', 'error']) {
    assert.equal(applyDisabled(renderFilters({ ...defaultSearchParams(), region: '11x' }, { regions: { status, onRetry: () => {} } })), true)
  }
  // URL의 형식 오류 지역 코드는 파싱 단계에서 제외되므로 필터 적용을 막지 않는다.
  const fromUrl = parseSearchParams(new URLSearchParams('region=1100000000x&type=YOUTH_POLICY')).params
  assert.equal(fromUrl.region, '')
  assert.equal(applyDisabled(renderFilters(fromUrl, { regions: { status: 'error', onRetry: () => {} } })), false)
})

test('unavailable or mismatched URL classifications stay visible but cannot be applied as new selections', () => {
  const html = renderFilters({ ...defaultSearchParams(), category: '일자리', subCategory: '정보통신' })
  assert.match(html, /기존 조건: 정보통신/)
  assert.match(html, /상위 분류와 맞지 않아요/)
  assert.match(html, /<option value="정보통신" disabled="" selected=""/)
  assert.match(html, /<button[^>]*disabled=""[^>]*>필터 적용/)
})

test('search errors distinguish confirmed development connection refusal, browser network failure and HTTP responses', () => {
  const unavailable = new ApiError('http', 'test only', 502, { code: 'DEV_BACKEND_UNAVAILABLE', message: 'test only', fieldErrors: {} })
  assert.match(renderPage(undefined, '/opportunities', unavailable), /백엔드 실행 상태와 BACKEND_URL/)
  assert.match(renderPage(undefined, '/opportunities', new ApiError('network', 'test only')), /네트워크 연결을 확인해 주세요/)
  assert.match(renderPage(undefined, '/opportunities', new ApiError('http', 'test only', 500)), /검색 서버에 문제가 생겼어요/)
  assert.doesNotMatch(searchErrorMessage(new ApiError('http', 'test only', 502)).description, /연결이 거부/)
  assert.match(searchErrorMessage(new ApiError('timeout', 'test only')).title, /응답이 지연/)
  assert.match(searchErrorMessage(new ApiError('http', 'test only', 404)).title, /API를 찾을 수 없어요/)
})
