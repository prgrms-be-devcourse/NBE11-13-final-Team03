import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'
const require = createRequire(import.meta.url)
require.extensions['.css'] = module => { module.exports = { __esModule: true, default: new Proxy({}, { get: (_, key) => String(key) }) } }
for (const extension of ['.ts', '.tsx']) require.extensions[extension] = (module, filename) => {
  module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText, filename)
}
const React = require('react'), e = React.createElement
const { renderToStaticMarkup } = require('react-dom/server')
const { MemoryRouter } = require('react-router-dom')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const { AuthContext } = require('../src/auth/AuthContext.ts')
const { NotificationsPage, NotificationSettingsPage } = require('../src/pages/notifications/NotificationPages.tsx')
const Badge = require('../src/layouts/NotificationBadge.tsx').default
const { ApiError } = require('../src/api/ApiError.ts')
const user = { id: 11, role: 'USER' }
const base = { status: 'authenticated', user, logoutPending: false, logoutError: null, retry: () => {}, completeOAuth: async () => {}, logout: async () => {} }
const item = { id: 30, opportunityId: 42, type: 'DEADLINE_SOON', title: '테스트 마감 알림', content: '<script>실행하지 않음</script>', link: 'javascript:alert(1)', createdAt: '2026-10-26T01:00:00Z', readAt: null, clickedAt: null }
const initialSettings = { inAppEnabled: true, pushEnabled: true, pushConsentAt: '2026-10-26T01:00:00Z', updatedAt: null }
function render({ status = 'authenticated', view = 'list', items = [item], more = false, emptyCache = false, error, unread = { available: true, unreadCount: 3 } } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } })
  client.setQueryData(['auth', 'user'], user)
  const key = ['notifications', 11, view === 'badge' ? 'unread' : view === 'settings' ? 'settings' : 'list']
  if (!emptyCache) client.setQueryData(key, view === 'badge' ? unread : view === 'settings' ? initialSettings : { pages: [{ items, hasNext: more, nextCursor: more ? 30 : null }], pageParams: [null] })
  if (error) client.getQueryCache().build(client, { queryKey: key }).setState({ status: 'error', error, fetchStatus: 'idle' })
  try {
    return renderToStaticMarkup(e(QueryClientProvider, { client }, e(MemoryRouter, null, e(AuthContext.Provider, { value: { ...base, status } }, e(view === 'badge' ? Badge : view === 'settings' ? NotificationSettingsPage : NotificationsPage)))))
  } finally { client.clear() }
}

test('notification pages gate confirmed authentication and never display cached private items or settings while checking/anonymous/error', () => {
  for (const status of ['checking', 'anonymous', 'error']) for (const view of ['list', 'settings']) {
    const html = render({ status, view })
    assert.doesNotMatch(html, /테스트 마감 알림|서버의 푸시 알림 설정|인앱 알림 받기/)
    if (status === 'anonymous') assert.match(html, view === 'list' ? /returnTo=%2Fnotifications/ : /returnTo=%2Fsettings%2Fnotifications/)
    // 비로그인만 공용 그린 로그인 CTA를 보여 주고, 확인 중·오류 상태와 섞지 않는다.
    if (status === 'anonymous') assert.match(html, /<a class="loginLink" href="\/login\?returnTo=[^"]+"[^>]*>카카오로 로그인하기<\/a>/)
    else assert.doesNotMatch(html, /로그인이 필요해요|카카오로 로그인하기/)
  }
})

test('notification settings use the shared back link and list toolbar uses a forward arrow link', () => {
  const settings = render({ view: 'settings' })
  assert.match(settings, /<a aria-label="알림 목록으로 돌아가기" class="arrowLink " href="\/notifications"[^>]*><svg[^>]*lucide-arrow-left[\s\S]*?<\/svg>알림 목록<\/a><h1/)
  assert.doesNotMatch(settings, /알림 목록으로<\/a>/)
  assert.match(render({}), /<a class="arrowLink " href="\/settings\/notifications"[^>]*>알림 설정<svg[^>]*lucide-arrow-right/)
})

test('notification cards display actual type, timestamp, unread/read states and escaped text with named controls and cursor more', () => {
  const html = render({ more: true })
  assert.match(html, /마감 임박/); assert.match(html, /안 읽음/)
  assert.match(html, /dateTime="2026-10-26T01:00:00Z">2026\.10\.26 10:00<\/time>/)
  assert.doesNotMatch(html, /오전|오후/)
  assert.match(html, /&lt;script&gt;/); assert.doesNotMatch(html, /<script>|href="javascript:/)
  assert.match(html, /aria-label="테스트 마감 알림 읽음 처리"/)
  assert.match(html, /전체 읽음 처리/); assert.match(html, /더 보기/)
  const read = render({ items: [{ ...item, readAt: item.createdAt }] })
  assert.doesNotMatch(read, /aria-label="테스트 마감 알림 읽음 처리"/)
  assert.doesNotMatch(read, /더 보기/)
  // 아직 조회하지 않은 페이지에 안 읽은 알림이 있을 수 있어 전체 읽음은 허용한다.
  assert.doesNotMatch(read, /<button[^>]*disabled=""[^>]*>전체 읽음 처리/)
})

test('notification loading, genuine empty, 503 unavailable, 401 login and 410 withdrawn remain distinct', () => {
  assert.match(render({ emptyCache: true }), /알림을 불러오고 있어요/)
  assert.match(render({ items: [] }), /아직 알림이 없어요/)
  for (const [status, text] of [[401, '로그인 상태를 다시 확인'], [404, '알림을 찾을 수 없거나'], [410, '탈퇴한 사용자'], [503, '일시적으로 이용할 수 없어요']]) {
    const html = render({ emptyCache: true, error: new ApiError('http', 'secret', status) })
    assert.ok(html.includes(text)); assert.doesNotMatch(html, /아직 알림이 없어요|secret/)
    assert.match(html, /다시 시도/)
    if (status === 401) assert.match(html, /다시 로그인하기/)
  }
})

test('unread badge distinguishes zero, unavailable, loading and failure and hides every count before confirmed auth', () => {
  assert.match(render({ view: 'badge', unread: { available: true, unreadCount: 0 } }), /aria-label="미읽음 알림 0개">0/)
  assert.match(render({ view: 'badge', unread: { available: true, unreadCount: 200 } }), /미읽음 알림 200개">99\+/)
  for (const html of [render({ view: 'badge', unread: { available: false, unreadCount: null } }), render({ view: 'badge', error: new ApiError('http', 'secret', 410) })]) {
    assert.match(html, /확인 불가/); assert.doesNotMatch(html, /알림 0개/)
  }
  assert.match(render({ view: 'badge', emptyCache: true }), /확인 중/)
  for (const status of ['anonymous', 'checking', 'error']) assert.equal(render({ view: 'badge', status }), '')
})

test('settings retain server values and label push as disabled server preference rather than a completed browser subscription', () => {
  const html = render({ view: 'settings' })
  assert.match(html, /인앱 알림 받기/)
  assert.match(html, /서버의 푸시 알림 설정: 켜짐/)
  assert.match(html, /브라우저의 푸시 구독 완료를 의미하지 않아요/)
  assert.match(html, /FCM 토큰·서비스 워커·엔드포인트 등록/)
  assert.match(html, /disabled="" readOnly="" aria-describedby="push-help" checked=""/)
  assert.match(html, /서버 푸시 동의 시각/)
  assert.match(html, /disabled=""[^>]*>설정 저장/)
})

test('notification CSS retains wrapping, mobile spacing and native touch-sized labelled settings inputs', () => {
  const css = fs.readFileSync(new URL('../src/pages/notifications/NotificationPages.module.css', import.meta.url), 'utf8')
  assert.match(css, /flex-wrap: wrap/); assert.match(css, /overflow-wrap: anywhere/)
  assert.match(css, /min-block-size: 2\.75rem/); assert.match(css, /max-width: 767px/)
})
