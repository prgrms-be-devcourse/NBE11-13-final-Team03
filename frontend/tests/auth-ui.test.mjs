import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'

const require = createRequire(import.meta.url)
require.extensions['.css'] = (module) => { module.exports = { __esModule: true, default: new Proxy({}, { get: (_, key) => String(key) }) } }
for (const extension of ['.ts', '.tsx']) require.extensions[extension] = (module, filename) => {
  module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText, filename)
}
const React = require('react'), element = React.createElement
const { renderToStaticMarkup } = require('react-dom/server')
const { MemoryRouter } = require('react-router-dom')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const AppRoutes = require('../src/routes/AppRoutes.tsx').default
const AuthControls = require('../src/layouts/AuthControls.tsx').default
const { AuthContext } = require('../src/auth/AuthContext.ts')
const { ApiError } = require('../src/api/ApiError.ts')
const user = { id: 11, nickname: '테스트 회원', email: 'test-only@example.org', role: 'USER' }

function render({ path = '/', auth, authError, recommendations } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, gcTime: Infinity } } })
  if (auth !== undefined) client.setQueryData(['auth', 'user'], auth)
  if (authError) client.getQueryCache().build(client, { queryKey: ['auth', 'user'] }).setState({ status: 'error', error: authError, fetchStatus: 'idle' })
  client.setQueryData(['home', 'opportunities', 'size=6'], { content: [], page: 0, size: 6, totalElements: 0, totalPages: 0, hasNext: false })
  if (recommendations) client.setQueryData(['recommendations', user.id], recommendations)
  try { return renderToStaticMarkup(element(QueryClientProvider, { client }, element(MemoryRouter, { initialEntries: [path] }, element(AppRoutes)))) }
  finally { client.clear() }
}

test('header distinguishes checking, anonymous, authenticated and verification error without forcing public route login', () => {
  assert.match(render(), /로그인 상태 확인 중/)
  const anonymous = render({ auth: null })
  assert.match(anonymous, /href="\/login\?returnTo=%2F"/)
  assert.match(anonymous, /공개 기회 탐색/)
  const authenticated = render({ auth: user, recommendations: [] })
  assert.match(authenticated, /테스트 회원님/)
  assert.match(authenticated, /로그아웃/)
  assert.doesNotMatch(authenticated, /test-only@example.org/)
  assert.match(authenticated, /아직 보여드릴 추천 결과가 없어요/)
  const failure = render({ authError: new ApiError('network', 'private internal') })
  assert.match(failure, /인증 확인 실패/)
  assert.match(failure, /다시 확인/)
  assert.match(failure, /인증 상태를 확인하지 못했어요/)
  assert.doesNotMatch(failure, /private internal|카카오로 로그인하기/)
})

test('login page exposes actual OAuth entry, privacy information and public navigation with accessible title', () => {
  const html = render({ path: '/login?returnTo=%2Fopportunities%3Fpage%3D2', auth: null })
  assert.match(html, /<h1 id="login-title">/)
  assert.match(html, /href="\/oauth2\/authorization\/kakao"/)
  assert.match(html, /카카오로 로그인/)
  assert.match(html, /닉네임·이메일/)
  assert.match(html, /로그인 없이 기회 탐색하기/)
  assert.doesNotMatch(html, /type="password"/)
})

test('OAuth success URL only displays server verification, while failure has retry and public links', () => {
  const success = render({ path: '/login-success.html', auth: user })
  assert.match(success, /서버에서 로그인 상태를 확인하고 있어요/)
  assert.doesNotMatch(success, /테스트 회원님|로그인되어 있어요/)
  const failure = render({ path: '/login-failure.html', auth: null })
  assert.match(failure, /로그인을 완료하지 못했어요/)
  assert.match(failure, /카카오 로그인 다시 진행/)
  assert.match(failure, /공개 기회 탐색하기/)
})

test('logout pending disables duplicate button; uncertain completion and explicit failure have separate alerts', () => {
  const base = { status: 'authenticated', user, logoutPending: false, logoutError: null, retry: () => {}, completeOAuth: async () => {}, logout: async () => {} }
  const controls = (patch) => renderToStaticMarkup(element(MemoryRouter, null, element(AuthContext.Provider, { value: { ...base, ...patch } }, element(AuthControls))))
  assert.match(controls({ logoutPending: true, status: 'checking' }), /disabled="" aria-busy="true">로그아웃 중/)
  assert.match(controls({ logoutError: new ApiError('network', 'private') }), /서버 로그아웃 완료 여부를 확인하지 못했어요/)
  assert.match(controls({ logoutError: new ApiError('http', 'private', 401) }), /로그아웃 요청이 완료되지 않았어요/)
  assert.match(controls({ logoutError: new ApiError('http', 'private', 403) }), /role="alert"/)
})

test('OAuth completion error offers the page-owned retry instead of a header user-query retry', () => {
  const auth = { status: 'error', logoutPending: false, logoutError: null, retry: undefined, completeOAuth: async () => {}, logout: async () => {} }
  const html = renderToStaticMarkup(element(MemoryRouter, null, element(AuthContext.Provider, { value: auth }, element(AuthControls))))
  assert.match(html, /로그인 완료 화면에서 다시 확인해 주세요/)
  assert.doesNotMatch(html, /<button/)
})

test('public search/detail remain accessible during auth errors and login return preserves search context', () => {
  const error = new ApiError('http', 'private', 503)
  const search = render({ path: '/opportunities?keyword=test&page=2', authError: error })
  assert.match(search, /통합 기회 탐색/)
  const detail = render({ path: '/opportunities/42', auth: null })
  assert.match(detail, /기회 상세를 불러오는 중이에요/)
  const anonymousSearch = render({ path: '/opportunities?keyword=test&page=2', auth: null })
  assert.match(anonymousSearch, /returnTo=%2Fopportunities%3Fkeyword%3Dtest%26page%3D2/)
})
