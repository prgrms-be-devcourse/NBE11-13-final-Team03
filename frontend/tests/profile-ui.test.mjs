import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'
import { profile, user, options, regionRows } from './profile-fixtures.mjs'
const require = createRequire(import.meta.url)
require.extensions['.svg'] = (module, filename) => { module.exports = filename }
require.extensions['.css'] = (module) => { module.exports = { __esModule: true, default: new Proxy({}, { get: (_, key) => String(key) }) } }
for (const ext of ['.ts', '.tsx']) require.extensions[ext] = (module, filename) => {
  module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText, filename)
}
const React = require('react'), e = React.createElement
const { renderToStaticMarkup } = require('react-dom/server')
const { MemoryRouter } = require('react-router-dom')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const AppRoutes = require('../src/routes/AppRoutes.tsx').default
const { ApiError } = require('../src/api/ApiError.ts')
const RegionSelect = require('../src/pages/profile/RegionSelect.tsx').default
const ProfileForm = require('../src/pages/profile/ProfileForm.tsx').default
const InterestsForm = require('../src/pages/profile/InterestsForm.tsx').default
const { toRegionOptions } = require('../src/api/regions.ts')
const regions = { status: 'ready', options: toRegionOptions(regionRows) }

function renderComponent(component, props) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: Infinity } } })
  client.setQueryData(['auth', 'user'], user)
  client.setQueryData(['interest-options'], options)
  try { return renderToStaticMarkup(e(QueryClientProvider, { client }, e(component, props))) }
  finally { client.clear() }
}

function render({ path = '/mypage', auth = user, authFetching = false, profileData = profile, interests = [], optionData = options, error, optionError, regionData, regionError } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } })
  client.setQueryData(['auth', 'user'], auth)
  if (authFetching) client.getQueryCache().find({ queryKey: ['auth', 'user'] }).setState({ fetchStatus: 'fetching' })
  if (error) client.getQueryCache().build(client, { queryKey: ['profile', user.id] }).setState({ status: 'error', error, fetchStatus: 'idle' })
  else client.setQueryData(['profile', user.id], profileData)
  client.setQueryData(['user-interests', user.id], interests)
  if (regionData) client.setQueryData(['regions'], toRegionOptions(regionData))
  if (regionError) client.getQueryCache().build(client, { queryKey: ['regions'] }).setState({ status: 'error', error: regionError, fetchStatus: 'idle' })
  if (optionError) client.getQueryCache().build(client, { queryKey: ['interest-options'] }).setState({ status: 'error', error: optionError, fetchStatus: 'idle' })
  else if (optionData) client.setQueryData(['interest-options'], optionData)
  try { return renderToStaticMarkup(e(QueryClientProvider, { client }, e(MemoryRouter, { initialEntries: [path] }, e(AppRoutes)))) }
  finally { client.clear() }
}

test('actual Provider and ProfileGate keep profile and interest forms rendered during confirmed-user background verification', () => {
  for (const path of ['/mypage/profile', '/mypage/interests', '/onboarding']) {
    const before = render({ path, regionData: regionRows })
    const refreshing = render({ path, authFetching: true, regionData: regionRows })
    assert.match(before, /<form/)
    assert.match(refreshing, /<form/)
    assert.doesNotMatch(refreshing, /로그인 상태를 확인하고 있어요|로그인이 필요해요/)
    if (path === '/mypage/interests') assert.match(refreshing, /관심 분야 저장/)
    else assert.match(refreshing, /생년월일 \(필수\)/)
  }
})
test('all profile routes require confirmed auth and never expose cached personal information to anonymous users', () => {
  for (const path of ['/mypage', '/onboarding', '/mypage/profile', '/mypage/interests']) {
    const html = render({ path, auth: null })
    assert.match(html, /로그인이 필요해요/)
    assert.doesNotMatch(html, /fixture@example.org|1998-05-15|맞춤 조건 저장/)
    assert.match(html, /<a class="loginLink" href="\/login\?returnTo=[^"]+"[^>]*>카카오로 로그인하기<\/a>/)
  }
  // 비로그인 마이페이지도 저장 목록·알림처럼 제목과 같은 폭(.page)의 안내 카드를 쓴다.
  assert.match(render({ path: '/mypage', auth: null }), /<section class="page" aria-labelledby="mypage-title"><h1 id="mypage-title">마이페이지<\/h1><div class="state"[^>]*>[\s\S]*?로그인이 필요해요/)
  // 로그인 후에도 같은 제목을 쓰고, 닉네임은 요약 카드의 소제목으로 둔다.
  assert.match(render({ path: '/mypage' }), /<section class="page" aria-labelledby="mypage-title"><h1 id="mypage-title">마이페이지<\/h1><section class="summary"/)
  assert.equal((render({ path: '/mypage' }).match(/<h1/g) ?? []).length, 1)
})
test('mypage shows actual account and registration state without invented account editing, raw profile or interest values', () => {
  const html = render({ interests: [{ type: 'NCS', code: '20010101' }] })
  for (const text of ['프로필 테스트 회원', 'fixture@example.org', '맞춤 조건이 등록되어', '관심 분야 1개', '로그아웃']) assert.ok(html.includes(text))
  assert.match(html, /href="\/mypage\/profile"/)
  assert.match(html, /href="\/mypage\/interests"/)
  assert.doesNotMatch(html, /닉네임 수정|이메일 수정|1998-05-15|20010101/)
  assert.match(render({ profileData: null }), /맞춤 조건이 아직 등록되지/)
})
test('mypage summarizes the account at the top, manages settings in cards and keeps logout in account management', () => {
  const html = render({ interests: [{ type: 'NCS', code: '20010101' }] })
  assert.match(html, /<h2 id="account-summary-title">프로필 테스트 회원님<\/h2>/)
  assert.doesNotMatch(html, /Y:ON 마이페이지|계정 유형|일반 회원|관리자/)
  assert.match(html, /<div class="lines"><p class="state">맞춤 조건이 등록되어 있어요\.<\/p><p class="note">추천과 신청 가능성 판정에 사용돼요\.<\/p><\/div>/)
  assert.match(html, /<div class="lines"><p class="state">관심 분야 1개를 설정했어요\.<\/p><p class="note">기회 추천에 반영돼요\.<\/p><\/div>/)
  assert.match(html, /href="\/mypage\/interests"[^>]*>관심 분야 수정/)
  assert.match(html, /class="manageGrid"[\s\S]*?id="profile-title"[\s\S]*?data-state="done"[^>]*>[\s\S]*?등록됨[\s\S]*?id="interests-title"[\s\S]*?1개/)
  assert.match(html, /aria-labelledby="account-title"[\s\S]*?계정 관리[\s\S]*?<button[^>]*>[\s\S]*?로그아웃<\/button>/)
  assert.match(html, /<button type="button" class="withdrawLink" aria-expanded="false" aria-controls="withdraw-confirm">회원 탈퇴<\/button>/)
  assert.doesNotMatch(html, /정말 탈퇴할까요|탈퇴하기/)
  const unset = render({ profileData: null })
  assert.match(unset, /data-state="todo"[^>]*>[\s\S]*?미등록/)
  assert.match(unset, /href="\/onboarding"[^>]*>맞춤 조건 설정 시작/)
  assert.match(unset, /<p class="state">맞춤 조건이 아직 등록되지 않았어요\.<\/p><p class="note">추천과/)
  assert.match(unset, /<p class="state">관심 분야가 설정되지 않았어요\.<\/p><p class="note">선택 사항이에요\. 설정하지 않으면 분야 제한 없이 추천해요\.<\/p>/)
  assert.match(unset, /href="\/mypage\/interests"[^>]*>관심 분야 설정/)
  assert.doesNotMatch(unset, />관심 분야 수정/)
  assert.match(render({ auth: { ...user, role: 'ADMIN' } }), /data-state="done">관리자/)
  const missing = render({ auth: { ...user, nickname: ' ', email: null } })
  assert.match(missing, /<h2 id="account-summary-title">닉네임 미제공<\/h2><p class="help">이메일 미제공<\/p>/)
  const css = fs.readFileSync(new URL('../src/pages/profile/Profile.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.manageGrid \{[^}]*repeat\(2, minmax\(0, 1fr\)\)/)
  // 맞춤 조건은 보라, 관심 분야는 연한 주황 카드로 구분한다.
  assert.match(html, /<section class="manageCard" data-tone="profile" aria-labelledby="profile-title">[\s\S]*?<section class="manageCard" data-tone="interests" aria-labelledby="interests-title">/)
  // 카드는 흰색을 유지하고, 색은 위쪽 선·아이콘·수정 링크에만 쓴다.
  assert.doesNotMatch(css, /\.manageCard\[data-tone='[a-z]+'\] \{[^}]*background/)
  assert.match(css, /\.manageCard\[data-tone='profile'\] \{ box-shadow: inset 0 3px 0 var\(--color-accent-purple-text\); \}/)
  assert.match(css, /\.manageCard\[data-tone='interests'\] \{ box-shadow: inset 0 3px 0 var\(--color-accent-orange-text\); \}/)
  assert.match(css, /\.manageCard\[data-tone='profile'\] \.cardIcon \{ background: var\(--color-accent-purple\); color: var\(--color-accent-purple-text\); \}/)
  assert.match(css, /\.manageCard\[data-tone='interests'\] \.cta \{ color: var\(--color-accent-orange-text\); \}/)
  assert.match(css, /@media \(max-width: 767px\) \{[\s\S]*?\.manageGrid, \.interestList \{ grid-template-columns: minmax\(0, 1fr\); \}/)
  assert.doesNotMatch(css, /gradient|backdrop-filter/)
  assert.match(css, /\.page \.note \{[^}]*color: var\(--color-text-secondary\);[^}]*font-size: 0\.8125rem/)
  assert.match(css, /\.withdrawLink \{[^}]*color: var\(--color-error\);[^}]*font-size: var\(--font-size-small\)/)
})
test('withdrawal shows server failures without claiming success and a completion state only after confirmed 204', () => {
  const { AuthContext } = require('../src/auth/AuthContext.ts')
  const { MyPage } = require('../src/pages/profile/ProfilePages.tsx')
  const renderMyPage = (auth) => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } } })
    client.setQueryData(['profile', user.id], profile); client.setQueryData(['user-interests', user.id], [])
    try { return renderToStaticMarkup(e(QueryClientProvider, { client }, e(MemoryRouter, { initialEntries: ['/mypage'] }, e(AuthContext.Provider, { value: auth }, e(MyPage))))) }
    finally { client.clear() }
  }
  const base = { status: 'authenticated', user, logoutPending: false, logoutError: null, completeOAuth: async () => {}, logout: async () => {}, withdraw: async () => {} }
  for (const [error, message] of [[new ApiError('network', 'private'), /탈퇴 완료 여부를 확인하지 못했어요/], [new ApiError('http', 'private', 403), /요청이 거부됐어요/], [new ApiError('http', 'private', 404), /사용자 정보를 찾을 수 없어요/]]) {
    const html = renderMyPage({ ...base, withdrawError: error })
    assert.match(html, /role="alert">/)
    assert.match(html, message)
    assert.doesNotMatch(html, /회원 탈퇴가 완료됐어요|private/)
  }
  const done = renderMyPage({ ...base, status: 'anonymous', user: undefined, withdrawn: true })
  assert.match(done, /회원 탈퇴가 완료됐어요/)
  assert.match(done, /<a class="arrowLink " href="\/"[^>]*>홈으로 이동<svg[^>]*lucide-arrow-right/)
  assert.doesNotMatch(done, /로그인이 필요해요|fixture@example.org/)
})
test('profile errors remain errors instead of unregistered and use safe 401/403/400/server messaging', () => {
  for (const status of [400, 401, 403, 500]) {
    const html = render({ error: new ApiError('http', 'private internal response', status) })
    assert.match(html, /role="alert"/)
    assert.doesNotMatch(html, /맞춤 조건이 아직 등록되지|private internal response/)
    if (status === 401) assert.match(html, /다시 로그인하기/)
  }
})
test('profile form labels required and optional fields, folds sensitive extras and honestly disables unknown regional choices', () => {
  const html = render({ path: '/mypage/profile' })
  assert.match(html, /생년월일 \(필수\)/)
  assert.match(html, /고용 상태 \(필수\)/)
  assert.match(html, /value="UNEMPLOYED" selected="">미취업/)
  assert.match(html, /<details class="extra"><summary><span>추가 정보 \(선택\)<span class="note">입력하면 신청 가능성 판정에 사용돼요\.<\/span><\/span><svg[^>]*aria-hidden="true"/)
  assert.match(html, /<fieldset class="formSection"><legend>필수 정보<\/legend>/)
  assert.match(html, /class="actions formActions"><button type="submit"/)
  assert.doesNotMatch(html, /필수 정보만으로 등록할 수 있어요|시·도부터 하위 지역을 차례로|말단 지역만 거주지로/)
  assert.match(html, /<a aria-label="마이페이지로 돌아가기" class="arrowLink " href="\/mypage"[^>]*><svg[^>]*lucide-arrow-left[^>]*aria-hidden="true"[\s\S]*?<\/svg>마이페이지<\/a><h1>맞춤 조건 설정<\/h1>/)
  assert.match(html, /<form class="form"/)
  assert.doesNotMatch(html, /<form class="page"/)
  // select와 TextField(3rem)의 입력 높이·글자 크기를 맞춘다.
  const css = fs.readFileSync(new URL('../src/pages/profile/Profile.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.field select, \.field input:not\(\[type='checkbox'\]\) \{[^}]*min-height: 3rem;[^}]*font-size: var\(--font-size-body\)/)
  assert.match(css, /\.page \.formSection, \.page \.region \{[^}]*border: 0;/)
  // 소득은 항목 이름이 곧 응답 라벨이며, 중복된 '응답 상태' 제목을 두지 않는다.
  assert.match(html, /<label[^>]*>개인 소득 \(선택\)<\/label>/)
  assert.match(html, /<label[^>]*>가구 소득 \(선택\)<\/label>/)
  assert.doesNotMatch(html, /응답 상태|<legend>개인 소득|<legend>가구 소득/)
  const provided = render({ path: '/mypage/profile', profileData: { ...profile, personalIncome: { status: 'PROVIDED', amount: 0, period: 'MONTHLY' } } })
  assert.match(provided, /value="PROVIDED" selected="">값 입력/)
  assert.match(provided, /개인 소득 금액 \(원, 0원 가능\)/)
  assert.match(provided, /<label[^>]*>개인 소득 단위 \(선택\)<\/label>/)
  assert.doesNotMatch(provided, /가구 소득 금액/)
  assert.match(html, /<select[^>]*disabled=""[^>]*><option value="11680" selected="">등록된 지역 코드 11680/)
  assert.match(html, /지역 목록을 불러오고 있어요/)
  assert.doesNotMatch(html, /type="text"[^>]*value="11680"/)
  const missing = render({ path: '/mypage/profile', profileData: null })
  assert.match(missing, /disabled=""[^>]*>맞춤 조건 저장/)
})
test('interests render parent and child relationships with preserved existing NCS and 30-selection limit', () => {
  const html = render({ path: '/mypage/interests', interests: [{ type: 'NCS', code: '20010101' }] })
  assert.match(html, /현재 선택지에 없는 기존 관심 분야/)
  assert.match(html, /checked=""/)
  assert.match(html, /20010101 \(NCS\) · 기존 값 유지/)
  assert.match(html, /<fieldset class="interestSection"><legend>일자리<\/legend><div class="chips"><label class="chip"><input type="checkbox"[^>]*\/>일자리 대분류<\/label><label class="chip"><input type="checkbox"[^>]*\/>취업지원<\/label>/)
  assert.match(html, /<details class="detailGroup"><summary><svg[^>]*aria-hidden="true"[\s\S]*?<\/svg>정보기술 세부 분야 1개<\/summary><div class="chips" role="group" aria-label="정보기술 세부 분야"><label class="chip"><input type="checkbox"[^>]*\/>정보기술개발<\/label>/)
  assert.doesNotMatch(html, /일자리 세부 분야|대분류 전체|전체 선택/)
  assert.match(html, /선택한 관심 분야 <strong>1<\/strong> \/ 30/)
  assert.match(html, /class="actions formActions"><button type="submit"[^>]*>관심 분야 저장/)
  assert.doesNotMatch(html, /type="text"/)
  const education = render({ path: '/mypage/interests', interests: [{ type: 'CATEGORY', code: '교육' }], optionData: { categories: [...options.categories, { category: '교육', subCategories: [] }] } })
  assert.match(education, /<legend>교육<\/legend><div class="chips"><label class="chip"><input type="checkbox" checked=""[^>]*\/>교육 대분류<\/label>/)
  // 직업훈련 대분류는 표시 이름만 바꾸고, 추천에 쓰이지 않으므로 새로 선택할 칩을 두지 않는다.
  assert.match(education, /<legend>직업훈련<\/legend><div class="chips"><label class="chip"><input type="checkbox"[^>]*\/>정보기술<\/label>/)
  assert.doesNotMatch(education, /교육 \(정책\)|교육･직업훈련|직업훈련 대분류|현재 선택지에 없는 기존 관심 분야/)
  const savedTrainingCategory = render({ path: '/mypage/interests', interests: [{ type: 'CATEGORY', code: '교육･직업훈련' }] })
  assert.match(savedTrainingCategory, /<input type="checkbox" checked=""[^>]*\/>직업훈련 대분류<\/label>/)
  assert.doesNotMatch(savedTrainingCategory, /현재 선택지에 없는 기존 관심 분야/)
  const picked = render({ path: '/mypage/interests', interests: [{ type: 'DETAIL_CATEGORY', code: '정보기술개발' }] })
  assert.match(picked, /정보기술 세부 분야 1개 · 1개 선택/)
  const css = fs.readFileSync(new URL('../src/pages/profile/Profile.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.interestList \{[^}]*repeat\(2, minmax\(0, 1fr\)\)/)
  assert.match(css, /\.manageGrid, \.interestList \{ grid-template-columns: minmax\(0, 1fr\); \}/)
})
test('interest option loading, failure and empty success remain distinct and never fall back to free text', () => {
  assert.match(render({ path: '/mypage/interests', optionData: null }), /관심 분야 선택지를 불러오고/)
  const failed = render({ path: '/mypage/interests', optionError: new ApiError('network', 'private') })
  assert.match(failed, /네트워크 연결/)
  assert.match(failed, /disabled=""[^>]*>관심 분야 저장/)
  assert.match(render({ path: '/mypage/interests', optionData: { categories: [] } }), /현재 제공되는 분류가 없어요/)
})
test('onboarding recognizes saved profiles, explains independent saves and never pretends missing region data can register', () => {
  const html = render({ path: '/onboarding' })
  assert.match(html, /이미 등록된 맞춤 조건/)
  assert.match(html, /기존 조건으로 다음 단계/)
  assert.match(html, /중간에 나가도 유지/)
  assert.match(render({ path: '/onboarding', profileData: null }), /거주지역은 마지막 하위 지역까지 선택/)
})
test('profile styles define mobile single column, flexible fields and minimum touch targets', () => {
  const css = fs.readFileSync(new URL('../src/pages/profile/Profile.module.css', import.meta.url), 'utf8')
  assert.match(css, /max-width: 767px/)
  assert.match(css, /grid-template-columns: minmax\(0, 1fr\)/)
  assert.match(css, /min-height: 44px/)
  assert.match(css, /min-width: 0/)
})
test('region selects restore actual parent/child labels, filter children, and permit a school province', () => {
  const residence = renderComponent(RegionSelect, { source: regions, mode: 'residence', value: '1168000000', onChange: () => {} })
  assert.match(residence, /value="1100000000" selected="">서울특별시/)
  assert.match(residence, /value="1168000000" selected="">강남구/)
  assert.doesNotMatch(residence, /<option value="4136000000"/)
  assert.match(residence, /거주 시·도/)
  assert.match(residence, /거주 시·군·구/)
  const school = renderComponent(RegionSelect, { source: regions, mode: 'school', value: '1100000000', onChange: () => {} })
  assert.doesNotMatch(school, /학교 소재지는 시·도 또는 시·군·구|하위 지역까지 선택해 주세요/)
  assert.match(school, /<label class="srOnly"[^>]*>학교 시·도<\/label>/)
  assert.match(school, /value="1100000000" selected="">서울특별시/)
  assert.match(school, /선택한 상위 지역 단위 사용/)
})
test('a top-level leaf such as Sejong needs no district select and no extra guidance', () => {
  const html = renderComponent(RegionSelect, { source: regions, mode: 'residence', value: '3611000000', onChange: () => {} })
  assert.match(html, /value="3611000000" selected="">세종특별자치시/)
  assert.doesNotMatch(html, /선택 완료|하위 지역까지 선택해 주세요|role="alert"/)
  assert.equal((html.match(/<select/g) ?? []).length, 1)
  assert.doesNotMatch(html, /<label[^>]*>거주 시·군·구<\/label>/)
})
test('region loading, empty, API failure with retry, and invalid hierarchy preserve the existing code in disabled controls', () => {
  for (const [source, expected] of [
    [{ status: 'loading' }, /지역 목록을 불러오고 있어요/],
    [{ status: 'ready', options: [] }, /제공되는 지역이 없어요/],
    [{ status: 'error', onRetry: () => {} }, /지역 목록 다시 시도/],
    [{ status: 'ready', options: [regions.options[0]] }, /지역 목록의 계층을 확인할 수 없어/],
  ]) {
    const html = renderComponent(RegionSelect, { source, mode: 'residence', value: '1168000000', onChange: () => {} })
    assert.match(html, expected)
    assert.match(html, /등록된 지역 코드 1168000000 \(유지\)/)
    assert.match(html, /<select[^>]*disabled=""/)
    if (source.status === 'loading') assert.match(html, /aria-busy="true"/)
    if (source.status === 'error') assert.match(html, /<button[^>]*type="button"/)
  }
})
test('unknown selection is explained, not replaced with the first province, and region errors are labelled accessibly', () => {
  const html = renderComponent(RegionSelect, { source: regions, mode: 'residence', value: 'unknown', onChange: () => {}, error: '테스트 선택 오류' })
  assert.match(html, /현재 지역 코드 unknown는 목록에서 찾을 수 없어요/)
  assert.doesNotMatch(html, /value="1100000000" selected=""/)
  assert.match(html, /aria-invalid="true"/)
  assert.match(html, /id="[^"]+-error"[^>]*role="alert">테스트 선택 오류/)
  assert.match(html, /aria-describedby="[^"]+-error"/)
})
test('profile form already accepts supplied region data while unchanged edits and independent interests remain usable without it', () => {
  const form = renderComponent(ProfileForm, { user, initial: { ...profile, residenceRegionCode: '1168000000' }, regions })
  assert.match(form, /value="1168000000" selected="">강남구/)
  assert.doesNotMatch(form, /지역 선택 API가 없어/)
  const preserved = render({ path: '/mypage/profile' })
  assert.match(preserved, /등록된 지역 코드 11680 \(유지\)/)
  assert.doesNotMatch(preserved, /disabled=""[^>]*>맞춤 조건 저장/)
  const blocked = render({ path: '/onboarding', profileData: null })
  assert.match(blocked, /disabled=""[^>]*>맞춤 조건 저장/)
  assert.match(blocked, /href="\/mypage\/interests"[^>]*>관심 분야만 먼저 설정/)
  // 뒤로가기(왼쪽)와 관심 분야 바로가기(오른쪽 끝)는 제목 위 같은 줄에 둔다.
  assert.match(blocked, /<div class="topLinks"><a aria-label="마이페이지로 돌아가기"[^>]*href="\/mypage"[\s\S]*?마이페이지<\/a><nav aria-label="설정 메뉴"><a [^>]*href="\/mypage\/interests"[^>]*>관심 분야만 먼저 설정[\s\S]*?<\/nav><\/div><h1>나에게 맞는 기회 준비하기<\/h1>/)
  const css = fs.readFileSync(new URL('../src/pages/profile/Profile.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.topLinks \{[^}]*justify-content: space-between;[^}]*flex-wrap: wrap;/)
  assert.doesNotMatch(blocked, /설정을 완료했어요|기존 조건으로 다음 단계/)
  const interests = renderComponent(InterestsForm, { user, initial: [], onBack: () => {} })
  assert.match(interests, /이전 단계로 돌아가기/)
  assert.doesNotMatch(interests, /disabled=""[^>]*>관심 분야 저장/)
})
test('profile routes consume the shared region query and restore three-level residence plus non-leaf school codes', () => {
  const regionData = [...regionRows, { code: '4111000000', name: '수원시', parentCode: '4100000000', leaf: false }, { code: '4111100000', name: '장안구', parentCode: '4111000000', leaf: true }]
  const html = render({ path: '/mypage/profile', regionData, profileData: { ...profile, residenceRegionCode: '4111100000', schoolRegionCode: '4111000000' } })
  for (const [code, name] of [['4100000000', '경기도'], ['4111000000', '수원시'], ['4111100000', '장안구']]) assert.ok(html.includes(`value="${code}" selected="">${name}`))
  assert.match(html, /거주 하위 지역 \(3단계\)/)
  assert.match(html, /학교 하위 지역 \(3단계\)/)
  assert.doesNotMatch(html, /지역 목록을 불러오고 있어요|disabled=""[^>]*>맞춤 조건 저장/)
  const empty = render({ path: '/onboarding', profileData: null, regionData: [] })
  assert.match(empty, /제공되는 지역이 없어요/)
  assert.match(empty, /disabled=""[^>]*>맞춤 조건 저장/)
  const failure = render({ path: '/mypage/profile', regionError: new ApiError('http', 'private', 404) })
  assert.match(failure, /지역 목록을 불러오지 못했어요/)
  assert.match(failure, /지역 목록 다시 시도/)
  assert.doesNotMatch(failure, /disabled=""[^>]*>맞춤 조건 저장/)
})
