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

function render({ path = '/mypage', auth = user, profileData = profile, interests = [], optionData = options, error, optionError, regionData, regionError } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } })
  client.setQueryData(['auth', 'user'], auth)
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
test('all profile routes require confirmed auth and never expose cached personal information to anonymous users', () => {
  for (const path of ['/mypage', '/onboarding', '/mypage/profile', '/mypage/interests']) {
    const html = render({ path, auth: null })
    assert.match(html, /로그인이 필요해요/)
    assert.doesNotMatch(html, /fixture@example.org|1998-05-15|맞춤 조건 저장/)
    assert.match(html, /카카오로 로그인하기/)
  }
})
test('mypage shows actual account and registration state without invented account editing, raw profile or interest values', () => {
  const html = render({ interests: [{ type: 'NCS', code: '20010101' }] })
  for (const text of ['프로필 테스트 회원', 'fixture@example.org', '맞춤 조건이 등록되어', '1개 관심 분야', '로그아웃']) assert.ok(html.includes(text))
  assert.match(html, /href="\/mypage\/profile"/)
  assert.match(html, /href="\/mypage\/interests"/)
  assert.doesNotMatch(html, /닉네임 수정|이메일 수정|1998-05-15|20010101/)
  assert.match(render({ profileData: null }), /맞춤 조건이 아직 등록되지/)
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
  assert.match(html, /<details><summary>추가 정보 \(선택\)/)
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
  assert.match(html, /일자리 › 취업지원/)
  assert.match(html, /정보기술 › 정보기술개발 · NCS 2차/)
  assert.match(html, /1 \/ 30/)
  assert.doesNotMatch(html, /type="text"/)
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
  assert.match(school, /학교 소재지는 시·도 또는 시·군·구/)
  assert.match(school, /value="1100000000" selected="">서울특별시/)
  assert.match(school, /선택한 상위 지역 단위 사용/)
})
test('a top-level leaf renders immediate completion with no fake district select', () => {
  const html = renderComponent(RegionSelect, { source: regions, mode: 'residence', value: '3611000000', onChange: () => {} })
  assert.match(html, /value="3611000000" selected="">세종특별자치시/)
  assert.match(html, /세종특별자치시 선택 완료 · 추가 지역 선택이 필요 없어요/)
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
  assert.match(html, /aria-describedby="[^"]+-help [^"]+-error"/)
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
