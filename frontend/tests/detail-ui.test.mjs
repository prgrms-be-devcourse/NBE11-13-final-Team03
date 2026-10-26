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
const { AuthContext } = require('../src/auth/AuthContext.ts')
const Page = require('../src/pages/opportunities/OpportunityDetailPage.tsx').default
const SearchPage = require('../src/pages/opportunities/OpportunitiesPage.tsx').default
const { ApiError } = require('../src/api/ApiError.ts')
const element = React.createElement

function renderDetail(data, { path = '/opportunities/42', state, error, auth = { status: 'anonymous' } } = {}) {
  const id = path.split('/').at(-1)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, gcTime: Infinity, staleTime: Infinity } } })
  if (data) client.setQueryData(['opportunity-detail', id], data)
  if (error) client.getQueryCache().build(client, { queryKey: ['opportunity-detail', id] }).setState({ status: 'error', error, fetchStatus: 'idle' })
  try {
    return renderToStaticMarkup(element(QueryClientProvider, { client }, element(MemoryRouter, { initialEntries: [{ pathname: path, state }] },
      element(AuthContext.Provider, { value: auth }, element(Routes, null, element(Route, { path: '/opportunities/:id', element: element(Page) }))))))
  } finally { client.clear() }
}

test('policy detail shows all application windows separately from business dates and accessible bookmark state', () => {
  const html = renderDetail(policy)
  assert.match(html, /<h1[^>]*>테스트 정책 상세<\/h1>/)
  for (const text of ['정책 소개 첫 문단', '취업 지원 내용', '온라인 신청', '서류 심사', '신분증', '테스트 주관 기관', '테스트 운영 기관',
    '2026.10.01 ~ 2026.10.31', '2026.11.01 ~ 2026.11.30', '사업기간 (신청기간과 별개)', '2026.01.01 ~ 2026.12.31', '저장하려면 로그인']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(html, /원문 기간 안내|1차 접수|2차 접수/)
  assert.match(html, /data-application-status="OPEN">모집 중<\/span>/)
  assert.doesNotMatch(html, /모집 상태:/)
  assert.doesNotMatch(html, /TEST-ONLY|policyNo|<button[^>]*>저장/)
  assert.match(renderDetail({ ...policy, saved: true }, { auth: { status: 'authenticated', user: { id: 11 } } }), /저장됨/)
})

test('policy consolidates agency roles and omits the internal application-condition section entirely', () => {
  const html = renderDetail({ ...policy, detail: { ...policy.detail, supervisingInstitutionName: '동일 기관', operatingInstitutionName: ' 동일 기관 ' } })
  const main = html.slice(html.indexOf('class="stack mainContent"'))
  const headings = ['신청기간', '정책 소개와 지원 내용', '신청 방법']
  for (let index = 1; index < headings.length; index++) assert.ok(main.indexOf(`<h2>${headings[index - 1]}</h2>`) < main.indexOf(`<h2>${headings[index]}</h2>`))
  assert.match(html, /주관·운영 기관: 동일 기관/)
  assert.equal((html.match(/동일 기관/g) ?? []).length, 1)
  assert.doesNotMatch(html, /<h2>신청 자격|<h2>운영 기관과 사업기간/)
  assert.doesNotMatch(main, /<h2>지원·참여 조건|신청 조건 확인|policyConditions|만 19세|원문 근거 보기/)
  const different = renderDetail(policy)
  assert.match(different, /주관 기관: 테스트 주관 기관/)
  assert.match(different, /운영 기관: 테스트 운영 기관/)
})

test('policy residence and implementation roles remain distinct without training advice or stale below-region directions', () => {
  const residenceOnly = renderDetail({ ...policy, regions: [policy.regions[0]], conditions: [condition({ type: 'REGION', field: 'residenceRegion', status: 'SPECIFIC', evidenceText: '실제 거주지 조건 원문' })] })
  assert.match(residenceOnly, /거주지 조건: 전국/)
  assert.doesNotMatch(residenceOnly, /시행·교육 지역|교육기관|아래 지역 정보/)
  assert.match(residenceOnly, /거주지 조건은 신청 자격 기준이며, 정책 시행 지역과 다를 수 있어요/)
  assert.doesNotMatch(residenceOnly, /실제 거주지 조건 원문|policyCondition|검증 완료/)
  const both = renderDetail(policy)
  assert.match(both, /정책 시행 지역: 서울/)
  assert.match(both, /거주지 조건은 신청 자격 기준이며, 정책 시행 지역과 다를 수 있어요/)
  assert.doesNotMatch(both, /시행·교육 지역/)
})

test('policy always-open, dated and unverified application periods stay distinct from business dates', () => {
  const always = renderDetail({ ...policy, applicationWindows: [{ periodType: 'ALWAYS', validationStatus: 'VALID', periodText: '상시 접수 원문' }] })
  assert.match(always, /class="facts">상시 신청<\/p>/)
  assert.doesNotMatch(always, /원문 기간 안내|상시 접수 원문/)
  const unknown = renderDetail({ ...policy, applicationWindows: [{ periodType: 'UNKNOWN', validationStatus: 'UNKNOWN', periodText: '접수 일정 미확인 원문' }] })
  assert.match(unknown, /class="facts">신청기간 확인 필요<\/p>/)
  assert.match(unknown, /원문 기간 안내: 접수 일정 미확인 원문/)
  assert.doesNotMatch(unknown, /class="facts">2026\.01\.01/)
  const absent = renderDetail({ ...policy, detail: {}, conditions: [], eligibility: null, detailUrl: null })
  assert.doesNotMatch(absent, /<h2>정책 소개와 지원 내용|<h2>신청 방법|<h2>운영 기관과 사업기간|target="_blank"/)
  assert.doesNotMatch(absent, /<h2>신청 자격<\/h2>/)
  assert.doesNotMatch(absent, /신청 조건 확인/)
})

test('policy judgment groups condition name, result and exact server explanation without inventing matches', () => {
  const html = renderDetail({ ...policy, eligibility: { status: 'POSSIBLY_ELIGIBLE', reason: '서버의 실제 종합 사유', conditions: [
    { type: 'REGION', requirement: 'REQUIRED', result: 'CHECK_REQUIRED', reason: '서버의 실제 지역 확인 사유' },
  ] } })
  const reason = html.slice(html.indexOf('신청 가능성 판정 근거'), html.indexOf('class="stack mainContent"'))
  assert.match(reason, /class="conditionHeader"[\s\S]*?지역 · 필수[\s\S]*?class="conditionResult">직접 확인 필요/)
  assert.match(reason, /서버의 실제 종합 사유/)
  assert.match(reason, /서버의 실제 지역 확인 사유/)
  assert.doesNotMatch(reason, /신청 조건 충족/)
})

test('training future educational dates do not change UNKNOWN recruitment; costs remain neutral source values including zero', () => {
  const html = renderDetail(training, { path: '/opportunities/43' })
  assert.match(html, /모집 상태 미확인/)
  assert.doesNotMatch(html, /data-application-status="UNKNOWN">모집 중|data-application-status="UNKNOWN">개강 예정/)
  for (const text of ['교육기간 (신청기간과 별개)', '2099.01.01 ~ 2099.03.31', '테스트 교육기관', '서울 교육 장소', '20명', '02-1234-5678',
    '실제 훈련비 (원본 제공값)', '500,000', '수강비 (원본 제공값)']) assert.ok(html.includes(text), text)
  assert.match(html, /수강비 \(원본 제공값\)<\/dt><dd>확인 필요<\/dd>/)
  assert.doesNotMatch(html, /INTERNAL-ONLY|INTERNAL-CUSTOMER|TEST-TRAINING|weekendTypeCode/)
  assert.match(html, /정확한 수강료와 본인 부담금은 교육기관에 확인해 주세요/)
  assert.equal((html.match(/교육기간과 모집기간은 다를 수 있습니다\./g) ?? []).length, 1)
  // 청년정책의 사업기간 안내와 같은 위치에서 모집기간과 교육기간의 차이를 안내한다.
  assert.match(html, /<h2>모집기간<\/h2>[\s\S]*?<p class="muted">교육기간과 모집기간은 다를 수 있습니다\.<\/p><\/div><\/section>/)
  assert.match(renderDetail(policy), /<h2>신청기간<\/h2>[\s\S]*?<p class="muted">사업기간과 신청기간은 다를 수 있습니다\.<\/p><\/div><\/section>/)
  assert.doesNotMatch(html, /모집 상태: 상태 미확인|0으로 표시되어도|교육 일정은 실제 모집기간/)
  const paid = renderDetail({ ...training, detail: { ...training.detail, courseFee: 120000 } }, { path: '/opportunities/43' })
  assert.match(paid, /수강비 \(원본 제공값\)<\/dt><dd>120,000<\/dd>/)
})

test('unknown eligibility joins the header badges and unknown application periods use concise copy with source evidence below', () => {
  const html = renderDetail({ ...training,
    eligibility: { status: 'UNKNOWN', reason: '', conditions: [] },
    applicationWindows: [{ ...training.applicationWindows[0], periodText: '모집 일정 원문' }],
  }, { path: '/opportunities/43' })
  const headerBadges = html.slice(html.indexOf('class="meta"'), html.indexOf('id="detail-title"'))
  assert.match(headerBadges, /직업훈련/)
  assert.match(headerBadges, /모집 상태 미확인/)
  assert.match(headerBadges, /판정 불가/)
  assert.match(html, /모집기간 확인 필요<\/p><p class="sourceText">원문 기간 안내: 모집 일정 원문/)
  assert.doesNotMatch(html, /신청기간 확인 필요 · 미확인|class="statuses"/)
})

test('detail keeps type colors and uses a labeled bookmark without changing save semantics', () => {
  const html = renderDetail({ ...training, category: '교육･직업훈련', subCategory: '직업훈련' }, { path: '/opportunities/43', auth: { status: 'authenticated', user: { id: 11 } } })
  assert.match(html, /class="typeBadge" data-opportunity-type="VOCATIONAL_TRAINING">직업훈련/)
  // NCS 분류가 없으면 카드와 같이 '직업훈련'을 한 번만 표시한다.
  assert.doesNotMatch(html, /교육･직업훈련|직업훈련 · 직업훈련/)
  assert.equal((html.match(/<li>직업훈련<\/li>/g) ?? []).length, 1)
  assert.match(renderDetail(training, { path: '/opportunities/43' }), /<li>정보통신<\/li>/)
  assert.match(html, /aria-pressed="(?:true|false)"/)
  assert.match(html, /lucide-bookmark/)
  assert.match(html, /저장하기|저장됨/)
  assert.doesNotMatch(html, />저장 안 함</)
  const anonymous = renderDetail(policy)
  assert.match(anonymous, /href="\/login\?returnTo=%2Fopportunities%2F42"/)
  const duplicate = renderDetail({ ...policy, category: ' 일자리 ', subCategory: '일자리' })
  assert.doesNotMatch(duplicate, /일자리 · 일자리/)
  assert.match(anonymous, /class="facts">2026\.10\.01 ~ 2026\.10\.31/)
  assert.match(anonymous, /사업기간과 신청기간은 다를 수 있습니다/)
  // 공고 카드 위쪽 유형 선: 청년정책 그린, 직업훈련 블루. 상세 상단 카드에도 적용한다.
  assert.match(html, /<div data-accent="VOCATIONAL_TRAINING" class="card default hero">/)
  assert.match(renderDetail(policy), /<div data-accent="YOUTH_POLICY" class="card default hero">/)
  const cardCss = fs.readFileSync(new URL('../src/components/ui/Card.module.css', import.meta.url), 'utf8')
  assert.match(cardCss, /\.card\[data-accent='YOUTH_POLICY'\] \{\s*box-shadow: inset 0 3px 0 var\(--color-primary\);/)
  assert.match(cardCss, /\.card\[data-accent='VOCATIONAL_TRAINING'\] \{\s*box-shadow: inset 0 3px 0 var\(--color-status-info-text\);/)
  // 유형 배지는 홈·검색·상세·저장 목록이 같은 공용 배지를 쓴다.
  const css = fs.readFileSync(new URL('../src/components/opportunities/OpportunityTypeBadge.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.typeBadge \{[^}]*background: var\(--color-primary-soft\);[^}]*font-size: 0\.75rem;/)
  assert.match(css, /data-opportunity-type='VOCATIONAL_TRAINING'[\s\S]*?var\(--color-accent-blue\)/)
  for (const page of ['home/HomePage', 'opportunities/OpportunitiesPage', 'opportunities/OpportunityDetailPage', 'saved/SavedOpportunitiesPage']) {
    assert.doesNotMatch(fs.readFileSync(new URL(`../src/pages/${page}.module.css`, import.meta.url), 'utf8'), /data-opportunity-type/, page)
  }
})

test('detail groups metadata, hides absent fields and presents an unconfirmed zero course fee as needing confirmation', () => {
  const html = renderDetail({ ...training, regions: [], detail: { institutionName: '테스트 기관', trainingStartDate: '2099-01-01', courseFee: 0 } }, { path: '/opportunities/43' })
  assert.ok(html.indexOf('data-application-status="UNKNOWN"') < html.indexOf('id="detail-title"'))
  assert.match(html, /class="headlineInfo"[\s\S]*?테스트 기관/)
  assert.match(html, /class="info trainingInfo"/)
  assert.match(html, /수강비 \(원본 제공값\)<\/dt><dd>확인 필요<\/dd>/)
  assert.match(html, /2099\.01\.01부터 \(종료일 미제공\)/)
  assert.doesNotMatch(html, /<dt>교육 장소<|<dt>기관 연락처<|<dt>교육 대상<|<dd>정보 미제공/)
  assert.match(html, /정확한 수강료와 본인 부담금은 교육기관에 확인해 주세요/)
  const empty = renderDetail({ ...training, detail: {}, eligibility: { status: 'UNKNOWN', reason: ' ', conditions: [] }, detailUrl: null }, { path: '/opportunities/43' })
  assert.match(empty, /교육 상세 정보가 제공되지 않았어요/)
  assert.match(empty, /판정 불가/)
  assert.doesNotMatch(empty, /신청 가능성 판정 근거|판정 사유 미제공|<h2>지역 정보|공식 출처와 신청 경로|target="_blank"/)
  const css = fs.readFileSync(new URL('../src/pages/opportunities/OpportunityDetailPage.module.css', import.meta.url), 'utf8')
  assert.match(css, /@media \(min-width: 1024px\)\s*\{\s*\.trainingInfo \{ grid-template-columns: repeat\(2, minmax\(0, 1fr\)\)/)
})

test('sidebar shows real unique safe links and provided eligibility evidence only, with one status badge', () => {
  const html = renderDetail({ ...policy, detailUrl: 'https://example.org/apply', detail: { ...policy.detail, applicationUrl: 'https://example.org/apply' }, eligibility: { status: 'ELIGIBLE', reason: '확인된 판정 근거', conditions: [] } }, { auth: { status: 'authenticated', user: { id: 11 } } })
  assert.equal((html.match(/href="https:\/\/example.org\/apply"/g) ?? []).length, 1)
  assert.equal((html.match(/>신청 가능<\/span>/g) ?? []).length, 1)
  assert.match(html, /신청 가능성 판정 근거/)
  assert.match(html, /확인된 판정 근거/)
  assert.match(html, /aria-label="테스트 정책 상세 저장하기" aria-pressed="false"/)
  assert.match(html, /저장하기/)
})

test('missing detail, regions, conditions and eligibility do not become fabricated unrestricted eligibility', () => {
  const html = renderDetail({ ...policy, detail: null, regions: [], conditions: [], applicationWindows: [], eligibility: null })
  assert.match(html, /유형별 상세 정보가 일부 제공되지 않았어요/)
  assert.match(html, /신청기간 정보가 제공되지 않았어요/)
  assert.match(html, /전국 대상 여부는 확인이 필요해요/)
  assert.doesNotMatch(html, /<h2>지원·참여 조건|신청 조건 확인/)
  assert.match(html, /판정 결과가 없어요\. 신청 불가를 뜻하지 않아요/)
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

test('unverified policy dates need confirmation and policy conditions stay hidden; training conditions expose evidence, not unverified numeric claims', () => {
  const conditions = [condition({ validationStatus: 'NEEDS_REVIEW', min: 999, max: 1000, evidenceText: '소득 기준은 원문 확인' }),
    condition({ type: 'INCOME', status: 'MAX_ONLY', min: null, max: 99999999, unitConfirmed: false, evidenceText: '금액 단위 미확인' })]
  const html = renderDetail({ ...policy,
    applicationWindows: [{ periodType: 'FIXED', startDate: '2098-10-01', endDate: '2098-10-31', periodText: '공식 공고 일정 확인 필요', validationStatus: 'UNVERIFIED' }], conditions })
  assert.match(html, /신청기간 확인 필요 · 공식 근거 미확인/)
  assert.doesNotMatch(html, /2098-10-01|999세|99,999,999/)
  assert.doesNotMatch(html, /<h2>지원·참여 조건|원문 확인 필요|소득 기준은 원문 확인|금액 단위 미확인/)
  const trainingHtml = renderDetail({ ...training, conditions }, { path: '/opportunities/43' })
  assert.match(trainingHtml, /<h2>지원·참여 조건<\/h2>/)
  assert.equal((trainingHtml.match(/<summary>원문 확인 필요<\/summary>/g) ?? []).length, 2)
  assert.equal((trainingHtml.match(/확정된 신청 조건으로 해석하지 마세요/g) ?? []).length, 2)
  for (const text of ['소득 기준은 원문 확인', '금액 단위 미확인']) assert.ok(trainingHtml.includes(text), text)
  assert.doesNotMatch(trainingHtml, /999세|99,999,999/)
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
  assert.match(unsafe, /공식 링크가 제공되지 않았어요/)
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
  // 마이페이지 설정 화면과 같이 화살표 아이콘과 짧은 문구를 쓰고, 보조기기에는 전체 의미를 전달한다.
  assert.match(renderDetail(policy), /<a aria-label="검색 목록으로 돌아가기" class="arrowLink backLink" href="\/opportunities"[^>]*><svg[^>]*lucide-arrow-left[^>]*aria-hidden="true"[\s\S]*?<\/svg>검색 목록<\/a>/)
  assert.doesNotMatch(renderDetail(policy), /← 검색 목록/)
})

test('search result Link carries the current search URL into detail navigation state', () => {
  const client = new QueryClient()
  const query = 'keyword=test&page=2'
  client.setQueryData(['opportunities', query, null], { content: [{ ...policy, eligibilityStatus: null, detailCategory: null }], page: 2, size: 20, totalElements: 60, totalPages: 3, hasNext: false })
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
