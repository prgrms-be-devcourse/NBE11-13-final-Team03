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
const { defaultSearchParams, parseSearchParams, serializeSearchParams } = require('../src/pages/opportunities/searchParams.ts')
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
  assert.match(training, /class="type" data-opportunity-type="VOCATIONAL_TRAINING">직업훈련/)
  assert.match(training, /class="category">교육･직업훈련<\/p>/)
  assert.doesNotMatch(training, /교육･직업훈련 · 직업훈련|정보기술/)
  const policy = renderPage({ ...data, content: [{ ...item, category: ' 일자리 ', subCategory: '일자리', detailCategory: ' 취업지원 ' }] })
  assert.match(policy, /class="category">일자리 · 취업지원<\/p>/)
  const missing = renderPage({ ...data, content: [{ ...item, category: ' ', subCategory: null, detailCategory: '' }] })
  assert.doesNotMatch(missing, /class="category"|분류 미확인/)
  assert.match(policy, /<a(?=[^>]*class="iconButton")(?=[^>]*href="\/login\?returnTo=%2Fopportunities")(?=[^>]*aria-label="테스트 전용 검색 결과 저장 안 함, 저장하려면 로그인")[^>]*>/)
  const authenticated = renderPage({ ...data, content: [{ ...item, saved: true }] }, '/opportunities', undefined, { status: 'authenticated', user: { id: 11 } })
  assert.match(authenticated, /aria-label="테스트 전용 검색 결과 저장 해제" aria-pressed="true"/)
  assert.match(authenticated, /fill="currentColor"/)
  assert.doesNotMatch(authenticated, />저장됨<|>저장 해제<|>저장 안 함<|>저장하려면 로그인</)
  const css = fs.readFileSync(new URL('../src/pages/opportunities/OpportunitiesPage.module.css', import.meta.url), 'utf8')
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
  assert.match(html, /value="테스트 분류"/)
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
  assert.match(training, /aria-label="적용된 검색 조건"><span>직업훈련<\/span><span>개강 예정<\/span><span>교육 시작일 2026-10-25부터<\/span>/)
  assert.match(training, /교육 시작일 기준/)
})

test('mobile filter disclosure points to labelled dialog; pagination presents 0-based page as human page', () => {
  const html = renderPage({ ...data, page: 1, totalPages: 3, totalElements: 60, hasNext: true }, '/opportunities?page=1')
  assert.match(html, /aria-haspopup="dialog"/)
  assert.match(html, /aria-controls="search-filter-dialog"/)
  assert.match(html, /<dialog[^>]*aria-labelledby="mobile-filter-title"/)
  assert.match(html, /aria-current="page" aria-label="2페이지"/)
})

const categoryOptions = { categories: [
  { category: '교육', subCategories: [] },
  { category: '일자리', subCategories: [{ subCategory: '취업지원', detailCategories: [] }] },
  { category: '교육･직업훈련', subCategories: [
    { subCategory: '정보통신', detailCategories: ['정보기술'] },
    { subCategory: '건설', detailCategories: ['건축'] },
  ] },
] }
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
  assert.match(all, /value="교육">교육 \(정책\)<\/option>/)
  assert.match(all, /value="교육･직업훈련"/)
  const policy = classificationSelects(renderFilters({ ...defaultSearchParams(), type: 'YOUTH_POLICY' }))[0]
  assert.match(policy, /교육 \(정책\)/)
  assert.doesNotMatch(policy, /교육･직업훈련/)
  const training = classificationSelects(renderFilters({ ...defaultSearchParams(), type: 'VOCATIONAL_TRAINING' }))[0]
  assert.match(training, /value="교육･직업훈련"/)
  assert.doesNotMatch(training, /value="교육"|value="일자리"/)
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
