import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'
import { condition, policy, training } from './detail-fixtures.mjs'

// 기존 검색 UI 테스트와 동일하게 실제 TSX를 서버 렌더링한다. 브라우저 동작·시각 검증은 아니다.
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
const { MemoryRouter, Routes, Route } = require('react-router-dom')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const Page = require('../src/pages/opportunities/OpportunityDetailPage.tsx').default
const SearchPage = require('../src/pages/opportunities/OpportunitiesPage.tsx').default
const { ApiError } = require('../src/api/ApiError.ts')
const element = React.createElement

function renderDetail(data, { path = '/opportunities/42', state, error } = {}) {
  const id = path.split('/').at(-1)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, gcTime: Infinity, staleTime: Infinity } } })
  if (data) client.setQueryData(['opportunity-detail', id], data)
  if (error) client.getQueryCache().build(client, { queryKey: ['opportunity-detail', id] }).setState({ status: 'error', error, fetchStatus: 'idle' })
  try {
    return renderToStaticMarkup(element(QueryClientProvider, { client }, element(MemoryRouter, { initialEntries: [{ pathname: path, state }] },
      element(Routes, null, element(Route, { path: '/opportunities/:id', element: element(Page) })))))
  } finally { client.clear() }
}

test('policy detail shows all application windows separately from business dates and read-only saved state', () => {
  const html = renderDetail(policy)
  assert.match(html, /<h1[^>]*>테스트 정책 상세<\/h1>/)
  for (const text of ['정책 소개 첫 문단', '취업 지원 내용', '온라인 신청', '서류 심사', '신분증', '테스트 주관 기관', '테스트 운영 기관',
    '2026-10-01 ~ 2026-10-31', '2026-11-01 ~ 2026-11-30', '1차 접수', '2차 접수', '사업기간 (신청기간과 별개)', '2026-01-01 ~ 2026-12-31', '저장 안 함']) assert.ok(html.includes(text), text)
  assert.match(html, /모집 상태: 모집 중/)
  assert.doesNotMatch(html, /TEST-ONLY|policyNo|<button[^>]*>저장/)
  assert.match(renderDetail({ ...policy, saved: true }), /저장됨/)
})

test('training future educational dates do not change UNKNOWN recruitment; costs remain neutral source values including zero', () => {
  const html = renderDetail(training, { path: '/opportunities/43' })
  assert.match(html, /모집 상태: 상태 미확인/)
  assert.doesNotMatch(html, /모집 상태: 모집 중|모집 상태: 개강 예정/)
  for (const text of ['교육기간 (신청기간과 별개)', '2099-01-01 ~ 2099-03-31', '테스트 교육기관', '서울 교육 장소', '20명', '02-1234-5678',
    '실제 훈련비 (원본 제공값)', '500,000', '수강비 (원본 제공값)']) assert.ok(html.includes(text), text)
  assert.match(html, /수강비 \(원본 제공값\)<\/dt><dd>0<\/dd>/)
  assert.doesNotMatch(html, /INTERNAL-ONLY|INTERNAL-CUSTOMER|TEST-TRAINING|weekendTypeCode/)
  assert.match(html, /비용의 단위·지원 범위·최종 본인 부담액은 공식 공고/)
})

test('missing detail, regions, conditions and eligibility do not become fabricated unrestricted eligibility', () => {
  const html = renderDetail({ ...policy, detail: null, regions: [], conditions: [], applicationWindows: [], eligibility: null })
  assert.match(html, /유형별 상세 정보가 일부 제공되지 않았어요/)
  assert.match(html, /신청기간 정보가 제공되지 않았어요/)
  assert.match(html, /전국 대상 여부는 확인이 필요해요/)
  assert.match(html, /신청 제한이 없다는 뜻은 아니므로/)
  assert.match(html, /현재 제공된 신청 가능성 판정이 없어요/)
  assert.doesNotMatch(html, /거주지 조건: 전국|신청 대상 아님|판정 불가/)
})

test('null eligibility and actual UNKNOWN stay distinct; supplied reasons use user-readable condition labels', () => {
  const html = renderDetail({ ...policy, eligibility: { status: 'UNKNOWN', reason: '저장된 판정 사유', conditions: [
    { type: 'AGE', field: 'age', requirement: 'REQUIRED', result: 'MATCHED', reason: '연령 조건 충족' },
    { type: 'OTHER', field: 'internalField', requirement: 'EXCLUSION', result: 'CHECK_REQUIRED', reason: '제외 사항 확인' },
  ] } })
  assert.match(html, /판정 불가/)
  assert.match(html, /저장된 판정 사유/)
  assert.match(html, /연령 조건 충족/)
  assert.match(html, /직접 확인 필요/)
  assert.doesNotMatch(html, /internalField|MATCHED|CHECK_REQUIRED|REQUIRED/)
})

test('unverified application dates and unsupported conditions expose evidence, not unverified numeric claims', () => {
  const html = renderDetail({ ...policy,
    applicationWindows: [{ periodType: 'FIXED', startDate: '2098-10-01', endDate: '2098-10-31', periodText: '공식 공고 일정 확인 필요', validationStatus: 'UNVERIFIED' }],
    conditions: [condition({ validationStatus: 'NEEDS_REVIEW', min: 999, max: 1000, evidenceText: '소득 기준은 원문 확인' }),
      condition({ type: 'INCOME', status: 'MAX_ONLY', min: null, max: 99999999, unitConfirmed: false, evidenceText: '금액 단위 미확인' })] })
  assert.match(html, /신청기간 확인 필요 · 공식 근거 미확인/)
  assert.doesNotMatch(html, /2098-10-01|999세|99,999,999/)
  assert.match(html, /원문 확인 필요/)
  assert.match(html, /소득 기준은 원문 확인/)
  assert.match(html, /금액 단위 미확인/)
})

test('regions distinguish verified national residence from implementation and unverified locations', () => {
  const html = renderDetail(policy)
  assert.match(html, /거주지 조건: 전국/)
  assert.match(html, /정책 시행 지역: 서울/)
  const unverified = renderDetail(training, { path: '/opportunities/43' })
  assert.match(unverified, /교육 시행 지역: 지역 확인 필요 · 검토 필요/)
  assert.doesNotMatch(unverified, /1100000000/)
})

test('only safe actual external URLs become links, with separate application/source labels and secure new tabs', () => {
  const html = renderDetail(policy)
  assert.match(html, /href="https:\/\/example.org\/policy" target="_blank" rel="noopener noreferrer"/)
  assert.match(html, /원문 공고 확인/)
  assert.match(html, /정책 신청 페이지/)
  const unsafe = renderDetail({ ...policy, detailUrl: 'javascript:alert(1)', detail: { ...policy.detail,
    description: '<script>alert(1)</script>', applicationUrl: 'data:text/html,bad', referenceUrl1: null, referenceUrl2: null } })
  assert.doesNotMatch(unsafe, /href="javascript:|href="data:|<script>/)
  assert.match(unsafe, /&lt;script&gt;/)
  assert.match(unsafe, /연결할 수 있는 공식 링크가 제공되지 않았어요/)
})

test('invalid ID, loading, not-found, network, timeout and server failures have distinct accessible states', () => {
  assert.match(renderDetail(undefined, { path: '/opportunities/invalid' }), /공고 번호를 확인해 주세요/)
  assert.match(renderDetail(undefined), /기회 상세를 불러오는 중이에요/)
  const cases = [
    [new ApiError('http', 'internal hidden', 404), '공고를 찾을 수 없어요'],
    [new ApiError('network', 'internal hidden'), '네트워크 연결을 확인해 주세요'],
    [new ApiError('timeout', 'internal hidden'), '상세 조회가 지연되고 있어요'],
    [new ApiError('http', 'internal hidden', 500), '상세 조회 서버에 문제가 생겼어요'],
  ]
  for (const [error, message] of cases) {
    const html = renderDetail(undefined, { error })
    assert.ok(html.includes(message))
    assert.match(html, /role="alert"/)
    assert.doesNotMatch(html, /internal hidden/)
    if (error.status !== 404) assert.match(html, /다시 시도/)
  }
})

test('detail back link preserves supplied search state and remains usable on direct entry', () => {
  assert.match(renderDetail(policy, { state: { searchReturn: '/opportunities?keyword=test&page=2' } }), /href="\/opportunities\?keyword=test&amp;page=2"/)
  assert.match(renderDetail(policy), /href="\/opportunities"/)
  assert.match(renderDetail(policy, { state: { searchReturn: 'https://evil.example' } }), /href="\/opportunities"/)
})

test('search result Link carries the current search URL into detail navigation state', () => {
  const client = new QueryClient()
  const query = 'keyword=test&page=2'
  client.setQueryData(['opportunities', query], { content: [{ ...policy, eligibilityStatus: null, detailCategory: null }], page: 2, size: 20, totalElements: 60, totalPages: 3, hasNext: false })
  client.setQueryData(['interest-options'], { categories: [] })
  // DOM 클릭 대신 실제 JSX의 Link에 전달되는 state를 확인한다.
  const originalLink = require('react-router-dom').Link
  const runtime = require('react/jsx-runtime')
  const originalJsx = runtime.jsx
  const links = []
  runtime.jsx = (type, props, ...rest) => {
    if (type === originalLink) links.push(props)
    return originalJsx(type, props, ...rest)
  }
  try {
    renderToStaticMarkup(element(QueryClientProvider, { client }, element(MemoryRouter, { initialEntries: [`/opportunities?${query}`] }, element(SearchPage))))
    assert.ok(links.some((link) => link.to === '/opportunities/42' && link.state?.searchReturn === `/opportunities?${query}`))
  } finally { runtime.jsx = originalJsx; client.clear() }
})
