import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'
import { latestItem, latestResponse, policyRecommendation, trainingRecommendation } from './home-fixtures.mjs'

// 기존 UI 테스트와 같은 실제 TSX 서버 렌더링이다. 클릭·포커스·시각 검증을 대신하지 않는다.
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
const AppRoutes = require('../src/routes/AppRoutes.tsx').default
const RecommendationsSection = require('../src/pages/home/RecommendationsSection.tsx').default
const { homeLatestQuery } = require('../src/pages/home/homeQueries.ts')
const { recommendationsQuery } = require('../src/api/recommendations.ts')
const { ApiError } = require('../src/api/ApiError.ts')
const element = React.createElement

function render({ recommendationsOnly = false, userId, data, error, cachedRecommendations } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, gcTime: Infinity, staleTime: Infinity } } })
  const key = recommendationsOnly ? recommendationsQuery(userId).queryKey : homeLatestQuery().queryKey
  if (data !== undefined) client.setQueryData(key, data)
  if (error) client.getQueryCache().build(client, { queryKey: key }).setState({ status: 'error', error, fetchStatus: 'idle' })
  if (cachedRecommendations) {
    client.setQueryData(recommendationsQuery().queryKey, cachedRecommendations)
    client.setQueryData(recommendationsQuery(99).queryKey, cachedRecommendations)
  }
  try {
    return renderToStaticMarkup(element(QueryClientProvider, { client }, element(MemoryRouter, { initialEntries: ['/'] },
      recommendationsOnly ? element(RecommendationsSection, { confirmedUserId: userId }) : element(AppRoutes))))
  } finally { client.clear() }
}

test('actual root route renders home hero, labelled search and working destination links rather than the placeholder', () => {
  const html = render({ data: latestResponse })
  assert.match(html, /<h1 id="home-title">/)
  assert.match(html, /사용자가 기회를 찾는 것이 아니라, 기회가 사용자를 찾아오게 한다/)
  assert.doesNotMatch(html, /홈 화면을 준비하고 있어요/)
  assert.match(html, /href="\/opportunities"[^>]*>기회 탐색하기/)
  assert.match(html, /href="#recommendations"/)
  assert.match(html, /<form[^>]*role="search"[^>]*aria-label="공고 제목 검색"/)
  assert.match(html, /name="keyword"/)
  assert.match(html, /type="submit"/)
  assert.match(html, /<h2 id="latest-heading">최근 등록된 기회/)
  assert.match(html, /<h2 id="recommendations-heading">개인화 추천/)
  assert.doesNotMatch(html, /href="\/login|href="\/signup/)
})

test('public home cards render only actual search fields and connect to detail without invented status/dates/regions', () => {
  const html = render({ data: { ...latestResponse, content: [{ ...latestItem, applicationEndDate: '2099-12-31', regionName: '표시하면 안 되는 지역', grantAmount: 123456789 }] } })
  assert.match(html, /테스트 공개 공고/)
  assert.match(html, /href="\/opportunities\/101"/)
  assert.match(html, /최초 등록일/)
  assert.match(html, /2026-10-25/)
  assert.match(html, /일자리 · 취업지원/)
  assert.doesNotMatch(html, /2099-12-31|표시하면 안 되는 지역|123456789|모집 상태:/)
  assert.doesNotMatch(html, /신청 가능성 있음|판정 불가/)
  const withEligibility = render({ data: { ...latestResponse, content: [{ ...latestItem, eligibilityStatus: 'POSSIBLY_ELIGIBLE' }] } })
  assert.match(withEligibility, /신청 가능성 있음/)
})

test('public loading, empty, network, server and connection-refusal states remain distinct', () => {
  assert.match(render(), /새로운 기회를 불러오는 중이에요/)
  const empty = render({ data: { ...latestResponse, content: [], totalElements: 0, totalPages: 0 } })
  assert.match(empty, /아직 등록된 기회가 없어요/)
  assert.doesNotMatch(empty, /role="alert"/)
  for (const [error, title] of [
    [new ApiError('network', 'internal hidden'), '네트워크 연결을 확인해 주세요'],
    [new ApiError('http', 'internal hidden', 503), '서버에 문제가 생겼어요'],
    [new ApiError('http', 'internal hidden', 502, { code: 'DEV_BACKEND_UNAVAILABLE', message: 'hidden', fieldErrors: {} }), '개발 백엔드에 연결할 수 없어요'],
  ]) {
    const html = render({ error })
    assert.ok(html.includes(title))
    assert.match(html, /다시 시도/)
    assert.match(html, /role="alert"/)
    assert.doesNotMatch(html, /아직 등록된 기회가 없어요|internal hidden/)
  }
})

test('unknown authentication always shows recommendation guidance even if disabled or another-account caches contain data', () => {
  const html = render({ data: latestResponse, cachedRecommendations: [policyRecommendation] })
  assert.match(html, /나에게 맞는 기회를 놓치지 마세요/)
  assert.match(html, /현재 로그인 연결을 준비하고 있어요/)
  assert.doesNotMatch(html, /테스트 정책 추천|서버가 제공한 실제 판정/)
  const section = render({ recommendationsOnly: true, data: [trainingRecommendation] })
  assert.doesNotMatch(section, /테스트 훈련 추천/)
  assert.match(section, /공개 기회 먼저 살펴보기/)
})

test('recommendation cards preserve server order, reasons and server status; policy deadline differs from training start', () => {
  const html = render({ recommendationsOnly: true, userId: 11, data: [trainingRecommendation, policyRecommendation] })
  assert.ok(html.indexOf('테스트 훈련 추천') < html.indexOf('테스트 정책 추천'))
  for (const text of ['신청 조건 확인 필요', '교육 시작 D-14', '마감 D-7', '테스트 서버가 제공한 교육 시작 안내입니다.',
    '테스트 서버가 제공한 실제 판정·관심 분야 추천 근거입니다.', '교육 시행 지역: 서울', '거주지 조건: 전국']) assert.ok(html.includes(text), text)
  assert.match(html, /모집 상태: 상태 미확인/)
  assert.match(html, /교육 시작일 <time dateTime="2026-11-08"/)
  assert.match(html, /신청기간 마감일 <time dateTime="2026-11-01"/)
  assert.match(html, /여러 신청기간이 있을 수 있어요/)
  assert.match(html, /href="\/opportunities\/103"/)
  const trainingOnly = render({ recommendationsOnly: true, userId: 11, data: [trainingRecommendation] })
  assert.doesNotMatch(trainingOnly, /<p>신청기간 마감일|모집 상태: 모집 중|최초 등록일/)
  const suppliedReasons = render({ recommendationsOnly: true, userId: 11, data: [{ ...policyRecommendation, recommendationReasons: { badges: ['서버의 추천 근거'], description: '서버의 설명 원문' } }] })
  assert.match(suppliedReasons, /서버의 추천 근거/)
  assert.match(suppliedReasons, /서버의 설명 원문/)
  assert.doesNotMatch(suppliedReasons, /마감 D-|관심 분야 일치/)
})

test('future authenticated states separate loading, empty recommendations, missing profile, expired auth and HTTP failures', () => {
  const base = { recommendationsOnly: true, userId: 11 }
  assert.match(render(base), /내 조건에 맞는 기회를 불러오는 중이에요/)
  assert.match(render({ ...base, data: [] }), /아직 보여드릴 추천 결과가 없어요/)
  assert.match(render({ ...base, data: [] }), /신청 가능한 기회가 없다는 뜻은 아니에요/)
  const noProfile = render({ ...base, error: new ApiError('http', 'test only', 404, { code: 'ELIGIBILITY_001', message: 'test only', fieldErrors: {} }) })
  assert.match(noProfile, /맞춤 조건 설정이 필요해요/)
  const expired = render({ ...base, error: new ApiError('http', 'test only', 401) })
  assert.match(expired, /로그인 상태를 다시 확인해 주세요/)
  assert.doesNotMatch(expired, /아직 보여드릴 추천 결과가 없어요/)
  const generic404 = render({ ...base, error: new ApiError('http', 'test only', 404, { code: 'OTHER', message: 'test only', fieldErrors: {} }) })
  assert.doesNotMatch(generic404, /맞춤 조건 설정이 필요해요/)
  assert.match(generic404, /role="alert"/)
  assert.match(render({ ...base, error: new ApiError('network', 'test only') }), /네트워크 연결을 확인해 주세요/)
  assert.match(render({ ...base, error: new ApiError('http', 'test only', 500) }), /서버에 문제가 생겼어요/)
})

test('home CSS defines single-column mobile, two-column tablet and three-column desktop cards with touch-sized links', () => {
  const css = fs.readFileSync(new URL('../src/pages/home/HomePage.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.grid\s*\{[^}]*grid-template-columns: minmax\(0, 1fr\)/)
  assert.match(css, /@media \(min-width: 768px\)[\s\S]*grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/)
  assert.match(css, /@media \(min-width: 1024px\)[\s\S]*grid-template-columns: repeat\(3, minmax\(0, 1fr\)\)/)
  assert.match(css, /min-block-size: 2\.75rem/)
  assert.match(css, /overflow-wrap: anywhere/)
})
