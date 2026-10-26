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
const React = require('react'), e = React.createElement
const { renderToStaticMarkup } = require('react-dom/server')
const { MemoryRouter } = require('react-router-dom')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const { AuthContext } = require('../src/auth/AuthContext.ts')
const { ApiError } = require('../src/api/ApiError.ts')
const SavedPage = require('../src/pages/saved/SavedOpportunitiesPage.tsx').default
const Actions = require('../src/components/opportunities/PreferenceActions.tsx').default
const user = { id: 11, nickname: '테스트 회원', email: null, role: 'USER' }
const base = { status: 'authenticated', user, logoutPending: false, logoutError: null, retry: () => {}, completeOAuth: async () => {}, logout: async () => {} }
const item = { opportunityId: 42, type: 'YOUTH_POLICY', title: '테스트 저장 공고', category: '일자리', subCategory: '취업지원', savedAt: '2026-10-26T09:12:34' }
const data = { content: [item], page: 0, size: 20, totalElements: 1, totalPages: 1, hasNext: false }
function render({ auth = base, saved = data, error, actions = false, allowHide = false, value = false, iconOnly = false } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, gcTime: Infinity, staleTime: Infinity } } })
  client.setQueryData(['auth', 'user'], auth.user ?? null)
  if (saved !== undefined) client.setQueryData(['saved-opportunities', 11, 0, 20], saved)
  if (error) client.getQueryCache().build(client, { queryKey: ['saved-opportunities', 11, 0, 20] }).setState({ status: 'error', error, fetchStatus: 'idle' })
  try { return renderToStaticMarkup(e(QueryClientProvider, { client }, e(MemoryRouter, { initialEntries: [actions ? '/opportunities?keyword=test&page=2' : '/saved'] }, e(AuthContext.Provider, { value: auth }, actions ? e(Actions, { opportunityId: 42, title: item.title, saved: value, source: 'SEARCH', allowHide, iconOnly }) : e(SavedPage))))) }
  finally { client.clear() }
}
test('saved page gates on verified authentication and never renders old-account saved data while anonymous/checking/error', () => {
  for (const status of ['anonymous', 'checking', 'error']) {
    const html = render({ auth: { ...base, status, user: undefined } })
    assert.doesNotMatch(html, /테스트 저장 공고|2026-10-26/)
    if (status === 'anonymous') assert.match(html, /href="\/login\?returnTo=%2Fsaved"/)
    if (status === 'checking') assert.match(html, /로그인 상태를 확인하고/)
    if (status === 'error') assert.match(html, /로그인 상태를 확인하지 못했어요/)
  }
})
test('saved cards use only DTO fields, real detail links, saved time and accessible unsave controls', () => {
  const html = render()
  assert.match(html, /href="\/opportunities\/42"/)
  assert.match(html, /<time dateTime="2026-10-26T09:12:34">2026-10-26 09:12:34/)
  assert.match(html, /aria-label="테스트 저장 공고 저장 해제" aria-pressed="true"/)
  assert.match(html, /aria-label="1페이지"/)
  assert.doesNotMatch(html, /마감일|신청 가능성|숨김 여부|추천에서 숨기기/)
})
test('saved empty and error responses remain distinct; expired auth offers login and network failure offers retry', () => {
  assert.match(render({ saved: { ...data, content: [], totalElements: 0, totalPages: 0 } }), /아직 저장한 기회가 없어요/)
  for (const error of [new ApiError('network', 'private'), new ApiError('http', 'private', 401)]) {
    const html = render({ error })
    assert.doesNotMatch(html, /아직 저장한 기회|private/)
    assert.match(html, /다시 시도/)
    if (error.status === 401) assert.match(html, /다시 로그인하기/)
  }
})
test('anonymous save action preserves canonical public search return; authenticated actions separate save and recommendation hide', () => {
  const anonymous = render({ actions: true, auth: { ...base, status: 'anonymous', user: undefined } })
  assert.match(anonymous, /returnTo=%2Fopportunities%3Fkeyword%3Dtest%26page%3D2/)
  assert.match(anonymous, /저장하려면 로그인/)
  const authenticated = render({ actions: true, allowHide: true })
  assert.match(authenticated, /aria-label="테스트 저장 공고 저장" aria-pressed="false"/)
  assert.match(authenticated, /aria-label="테스트 저장 공고 추천에서 숨기기"/)
  assert.match(render({ actions: true, allowHide: true, iconOnly: true }), />숨기기<\/button>/)
  assert.doesNotMatch(authenticated, /숨김 해제/)
  assert.doesNotMatch(render({ actions: true }), /추천에서 숨기기/)
})
