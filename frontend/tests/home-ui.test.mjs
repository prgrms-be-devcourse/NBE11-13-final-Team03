import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'
import { latestItem, latestResponse, policyRecommendation, trainingRecommendation } from './home-fixtures.mjs'
import { profile as completeProfile, user as profileUser } from './profile-fixtures.mjs'
import { policy } from './detail-fixtures.mjs'

// 기존 UI 테스트와 같은 실제 TSX 서버 렌더링이다. 클릭·포커스·시각 검증을 대신하지 않는다.
const require = createRequire(import.meta.url)
require.extensions['.svg'] = (module, filename) => { module.exports = filename }
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
const HomeOpportunityCard = require('../src/pages/home/HomeOpportunityCard.tsx').default
const { AuthContext } = require('../src/auth/AuthContext.ts')
const { homeLatestQuery } = require('../src/pages/home/homeQueries.ts')
const { recommendationsQuery } = require('../src/api/recommendations.ts')
const { ApiError } = require('../src/api/ApiError.ts')
const element = React.createElement

function render({ recommendationsOnly = false, userId, authStatus, data, error, cachedRecommendations, authUser = null, profileData, profileError, profileFetchStatus, checkingAuth = false, details = [] } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, gcTime: Infinity, staleTime: Infinity } } })
  if (!checkingAuth) client.setQueryData(['auth', 'user'], authUser)
  for (const detail of details) client.setQueryData(['opportunity-detail', String(detail.opportunityId)], detail)
  if (authUser && profileData !== undefined) client.setQueryData(['profile', authUser.id], profileData)
  if (authUser && profileError) client.getQueryCache().build(client, { queryKey: ['profile', authUser.id] }).setState({ status: 'error', error: profileError, fetchStatus: 'idle' })
  if (authUser && profileFetchStatus) client.getQueryCache().build(client, { queryKey: ['profile', authUser.id] }).setState({ fetchStatus: profileFetchStatus })
  const key = recommendationsOnly ? recommendationsQuery(userId).queryKey : homeLatestQuery().queryKey
  if (data !== undefined) client.setQueryData(key, data)
  if (error) client.getQueryCache().build(client, { queryKey: key }).setState({ status: 'error', error, fetchStatus: 'idle' })
  if (cachedRecommendations) {
    client.setQueryData(recommendationsQuery().queryKey, cachedRecommendations)
    client.setQueryData(recommendationsQuery(99).queryKey, cachedRecommendations)
  }
  try {
    return renderToStaticMarkup(element(QueryClientProvider, { client }, element(MemoryRouter, { initialEntries: ['/'] },
      recommendationsOnly ? element(RecommendationsSection, { confirmedUserId: userId, authStatus }) : element(AppRoutes))))
  } finally { client.clear() }
}

test('home hero keeps its title and actual CTA destinations without duplicate copy or an inner search', () => {
  const html = render({ data: latestResponse })
  assert.match(html, /<h1 id="home-title">/)
  assert.match(html, /나에게 맞는 기회를<br\/>Y:ON에서 발견하세요/)
  assert.match(html, /청년정책과 직업훈련을 한곳에서 탐색하세요/)
  const hero = html.match(/<section[^>]*aria-labelledby="home-title"[\s\S]*?<\/section>/)[0]
  assert.doesNotMatch(hero, /기회를 발견하고|사용자가 기회를|<form|<input/)
  assert.doesNotMatch(html, /홈 화면을 준비하고 있어요/)
  assert.match(html, /href="\/opportunities"[^>]*>기회 탐색하기/)
  assert.doesNotMatch(hero, /맞춤 추천 보기|맞춤 조건 설정하기/)
  assert.match(html, /id="recommendations"/)
  assert.match(html, /<h2 id="latest-heading">최근 등록된 기회/)
  assert.match(html, /<h2 id="recommendations-heading">개인화 추천/)
  assert.match(html, /href="\/login\?returnTo=/)
  assert.doesNotMatch(html, /href="\/signup/)
})

test('hero secondary CTA follows confirmed account profile and stays hidden while auth/profile is unresolved or refetching', () => {
  const hero = props => render({ data: latestResponse, ...props }).match(/<section[^>]*aria-labelledby="home-title"[\s\S]*?<\/section>/)[0]
  assert.match(hero({ authUser: profileUser, profileData: null }), /href="\/onboarding"[^>]*>맞춤 조건 설정하기/)
  assert.doesNotMatch(hero({ authUser: profileUser, profileData: null }), /맞춤 추천 보기/)
  assert.match(hero({ authUser: profileUser, profileData: completeProfile }), /href="#recommendations"[^>]*>맞춤 추천 보기/)
  for (const props of [
    { checkingAuth: true },
    { authUser: profileUser },
    { authUser: profileUser, profileData: completeProfile, profileFetchStatus: 'fetching' },
    { authUser: profileUser, profileData: completeProfile, profileFetchStatus: 'paused' },
    { authUser: profileUser, profileData: completeProfile, profileError: new ApiError('network', 'test only') },
    { authUser: { ...profileUser, id: 22 } },
  ]) {
    const html = hero(props)
    assert.match(html, /기회 탐색하기/)
    assert.doesNotMatch(html, /맞춤 추천 보기|맞춤 조건 설정하기/)
  }
})

test('public home cards render only actual search fields and connect to detail without invented status/dates/regions', () => {
  const html = render({ data: { ...latestResponse, content: [{ ...latestItem, applicationEndDate: '2099-12-31', regionName: '표시하면 안 되는 지역', grantAmount: 123456789 }] } })
  assert.match(html, /테스트 공개 공고/)
  assert.match(html, /href="\/opportunities\/101"/)
  assert.match(html, /최초 등록일/)
  assert.match(html, /2026-10-25/)
  assert.match(html, /class="classification">일자리 · 취업지원<\/p>/)
  assert.doesNotMatch(html, /2099-12-31|표시하면 안 되는 지역|123456789|모집 상태:/)
  assert.doesNotMatch(html, /신청 가능성 있음|판정 불가/)
  const withEligibility = render({ data: { ...latestResponse, content: [{ ...latestItem, eligibilityStatus: 'POSSIBLY_ELIGIBLE' }] } })
  assert.match(withEligibility, /신청 가능성 있음/)
})

function renderCard(item, auth = { status: 'anonymous' }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
  try {
    return renderToStaticMarkup(element(QueryClientProvider, { client }, element(MemoryRouter, null,
      element(AuthContext.Provider, { value: auth }, element(HomeOpportunityCard, { item })))))
  } finally { client.clear() }
}

test('training cards show only the top category while policy cards retain their category hierarchy', () => {
  const training = renderCard({ ...latestItem, type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '직업훈련', detailCategory: '정보기술' })
  assert.match(training, /data-opportunity-type="VOCATIONAL_TRAINING">직업훈련/)
  assert.match(training, /class="classification">교육･직업훈련<\/p>/)
  const localTest = renderCard({ ...latestItem, type: 'VOCATIONAL_TRAINING', category: '교육･직업훈련', subCategory: '직업훈련', detailCategory: null })
  assert.match(localTest, /class="classification">교육･직업훈련<\/p>/)
  const repeated = renderCard({ ...latestItem, category: '일자리', subCategory: ' 일자리 ', detailCategory: '취업지원' })
  assert.match(repeated, /class="classification">일자리 · 취업지원<\/p>/)
  const fallback = renderCard({ ...latestItem, category: '일자리', subCategory: ' ', detailCategory: null })
  assert.match(fallback, /class="classification">일자리<\/p>/)
  const duplicate = renderCard({ ...latestItem, category: '일자리', subCategory: ' 일자리 ', detailCategory: '일자리' })
  assert.match(duplicate, /class="classification">일자리<\/p>/)
  const absent = renderCard({ ...latestItem, category: null, subCategory: null, detailCategory: ' ', createdAt: null })
  assert.doesNotMatch(absent, /class="classification"|class="cardFooter"|분류 미확인| · /)
})

test('home bookmark uses icon-only controls with accessible saved state and existing anonymous return path', () => {
  const anonymous = renderCard(latestItem)
  assert.match(anonymous, /aria-label="테스트 공개 공고 저장 안 함, 저장하려면 로그인"/)
  assert.match(anonymous, /href="\/login\?returnTo=%2F"/)
  assert.doesNotMatch(anonymous, />저장 안 함<|>저장하려면 로그인</)
  const user = { id: 11, role: 'USER' }
  for (const saved of [false, true]) {
    const html = renderCard({ ...latestItem, saved }, { status: 'authenticated', user })
    assert.match(html, new RegExp(`aria-label="테스트 공개 공고 ${saved ? '저장 해제' : '저장'}" aria-pressed="${saved}"`))
    assert.match(html, /<svg[^>]*aria-hidden="true"/)
    assert.doesNotMatch(html, />저장됨<|>저장 안 함<|>저장 해제<|>저장</)
  }
  const checking = renderCard(latestItem, { status: 'checking' })
  assert.match(checking, /aria-label="테스트 공개 공고 로그인 확인 중"[^>]*disabled=""/)
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

test('anonymous authentication shows recommendation guidance even if disabled or another-account caches contain data', () => {
  const html = render({ data: latestResponse, cachedRecommendations: [policyRecommendation] })
  assert.match(html, /나에게 맞는 기회를 추천받으세요/)
  assert.doesNotMatch(html, /내 조건을 바탕으로|나에게 맞는 기회를 놓치지 마세요|로그인하고 관심 분야와/)
  assert.doesNotMatch(html, /테스트 정책 추천|서버가 제공한 실제 판정/)
  const section = render({ recommendationsOnly: true, data: [trainingRecommendation] })
  assert.doesNotMatch(section, /테스트 훈련 추천/)
  assert.doesNotMatch(section, /공개 기회 먼저 살펴보기|개인화 추천은 로그인과 맞춤 조건|class="card/)
  assert.equal((section.match(/<a /g) || []).length, 1)
  assert.match(section, /href="\/login\?returnTo=%2F"[^>]*>카카오로 로그인하기/)
})

test('recommendation cards preserve server order, reasons and server status; policy deadline differs from training start', () => {
  const detail = { ...policy, opportunityId: policyRecommendation.opportunityId, applicationWindows: [{ periodType: 'FIXED', startDate: '2026-10-01', endDate: '2026-11-01', periodText: null, validationStatus: 'VALID' }] }
  const html = render({ recommendationsOnly: true, userId: 11, data: [trainingRecommendation, policyRecommendation], details: [detail] })
  assert.ok(html.indexOf('테스트 훈련 추천') < html.indexOf('테스트 정책 추천'))
  assert.match(html, /class="grid recommendationGrid"/)
  assert.equal((html.match(/class="vacantSlot"/g) || []).length, 1)
  assert.doesNotMatch(html, /data-badge="신청 조건 충족"/)
  assert.doesNotMatch(html, /저장된 최신 판정과 실제 추천 근거를 바탕으로 제공해요|최초 등록일/)
  for (const text of ['신청 조건 확인 필요', '교육 시작 D-14', '마감 D-7', '테스트 서버가 제공한 교육 시작 안내입니다.',
    '테스트 서버가 제공한 실제 판정·관심 분야 추천 근거입니다.', '교육 시행 지역: 서울', '거주지 조건: 전국']) assert.ok(html.includes(text), text)
  assert.match(html, /모집 상태: 상태 미확인/)
  assert.match(html, /교육 시작일 <time dateTime="2026-11-08"/)
  assert.match(html, /신청기간 2026\.10\.01 ~ 2026\.11\.01/)
  assert.match(html, /여러 신청기간이 있을 수 있어요/)
  assert.match(html, /href="\/opportunities\/103"/)
  const trainingOnly = render({ recommendationsOnly: true, userId: 11, data: [trainingRecommendation] })
  assert.doesNotMatch(trainingOnly, /<p>신청기간 마감일|모집 상태: 모집 중|최초 등록일/)
  const suppliedReasons = render({ recommendationsOnly: true, userId: 11, data: [{ ...policyRecommendation, recommendationReasons: { badges: ['서버의 추천 근거'], description: '서버의 설명 원문' } }] })
  assert.match(suppliedReasons, /서버의 추천 근거/)
  assert.match(suppliedReasons, /서버의 설명 원문/)
  assert.match(suppliedReasons, /class="reasonDescription"><span>서버의 설명 원문/)
  assert.equal((suppliedReasons.match(/class="vacantSlot"/g) || []).length, 2)
  assert.doesNotMatch(suppliedReasons, /마감 D-|관심 분야 일치/)
  const policyOnly = render({ recommendationsOnly: true, userId: 11, data: [policyRecommendation], details: [detail] })
  assert.doesNotMatch(policyOnly, /모집 상태: 모집 중/)
  assert.ok(policyOnly.indexOf('신청 가능</span>') < policyOnly.indexOf('신청기간 2026.'))
  assert.ok(policyOnly.indexOf('신청기간 2026.') < policyOnly.indexOf('aria-label="추천 이유"'))
  assert.ok(policyOnly.indexOf('aria-label="추천 이유"') < policyOnly.indexOf('class="reasonDescription"'))
})

test('recommendations reserve three slots, limit actual results to the first three and break server explanations by sentence', () => {
  const data = Array.from({ length: 5 }, (_, index) => ({ ...policyRecommendation, opportunityId: 201 + index, title: `추천 ${index + 1}`, recommendationReasons: { badges: ['관심 분야 일치'], description: '신청 조건을 충족합니다; 관심 분야와 일치합니다. 신청 마감까지 3일 남았습니다.' } }))
  const html = render({ recommendationsOnly: true, userId: 11, data })
  assert.match(html, /추천 1/); assert.match(html, /추천 3/)
  assert.doesNotMatch(html, /추천 4|추천 5/)
  assert.match(html, /<span>신청 조건을 충족합니다\.<\/span><span>관심 분야와 일치합니다\.<\/span><span>신청 마감까지 3일 남았습니다\.<\/span>/)
  const empty = render({ recommendationsOnly: true, userId: 11, data: [] })
  assert.equal((empty.match(/class="vacantSlot"/g) || []).length, 3)
  assert.match(empty, /아직 보여드릴 추천 결과가 없어요/)
})

test('future authenticated states separate loading, empty recommendations, missing profile, expired auth and HTTP failures', () => {
  const base = { recommendationsOnly: true, userId: 11 }
  assert.match(render(base), /내 조건에 맞는 기회를 불러오는 중이에요/)
  assert.match(render({ ...base, data: [] }), /아직 보여드릴 추천 결과가 없어요/)
  assert.match(render({ ...base, data: [] }), /신청 가능한 기회가 없다는 뜻은 아니에요/)
  const noProfile = render({ ...base, error: new ApiError('http', 'test only', 404, { code: 'ELIGIBILITY_001', message: 'test only', fieldErrors: {} }) })
  assert.match(noProfile, /맞춤 조건을 설정해 주세요/)
  assert.match(noProfile, /href="\/onboarding"/)
  assert.doesNotMatch(noProfile, /class="state"|판정 프로필이 등록되어 있지/)
  const expired = render({ ...base, error: new ApiError('http', 'test only', 401) })
  assert.match(expired, /로그인 상태를 다시 확인해 주세요/)
  assert.doesNotMatch(expired, /아직 보여드릴 추천 결과가 없어요/)
  const generic404 = render({ ...base, error: new ApiError('http', 'test only', 404, { code: 'OTHER', message: 'test only', fieldErrors: {} }) })
  assert.doesNotMatch(generic404, /맞춤 조건을 설정해 주세요/)
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

test('unconfirmed authentication never renders cached recommendations even when a previous user ID remains', () => {
  for (const authStatus of ['checking', 'anonymous', 'error']) {
    const html = render({ recommendationsOnly: true, userId: 11, authStatus, data: [policyRecommendation], cachedRecommendations: [policyRecommendation] })
    assert.doesNotMatch(html, /테스트 정책 추천|서버가 제공한 실제 판정/)
    if (authStatus === 'checking') assert.match(html, /로그인 상태를 확인하고/)
    if (authStatus === 'anonymous') assert.match(html, /카카오로 로그인하기/)
    if (authStatus === 'error') assert.match(html, /인증 상태를 확인하지 못했어요/)
  }
})

test('nullable recommendation metadata renders safely without invented region, deadline or interest-match badges', () => {
  const html = render({ recommendationsOnly: true, userId: 11, authStatus: 'authenticated', data: [{ ...policyRecommendation,
    title: '', category: null, subCategory: '', detailCategory: null, regions: [], createdAt: null,
    applicationEndDate: null, trainingStartDate: null,
    recommendationReasons: { badges: ['신청 조건 충족'], description: '저장된 최신 판정에서 신청 조건을 충족합니다.' } }] })
  assert.match(html, /제목 미확인/)
  assert.doesNotMatch(html, /class="classification"|분류 미확인/)
  assert.match(html, /저장된 최신 판정에서 신청 조건을 충족합니다/)
  assert.doesNotMatch(html, /신청기간 마감일|교육 시작일|최초 등록일|거주지 조건|관심 분야 일치|관심 분야 관련/)
})
