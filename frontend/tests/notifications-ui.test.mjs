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

test('notification settings use the shared back link and the list header pairs the title with the settings link', () => {
  const settings = render({ view: 'settings' })
  // 제목은 헤더 메뉴 이름(알림)과 같고, 설정 화면의 뒤로가기도 같은 이름을 쓴다.
  // 맞춤 조건 설정과 같은 순서: 뒤로가기 → 제목 → 패널, 별도 묶음 없이 페이지 간격만 쓴다.
  assert.match(settings, /<section class="page"[^>]*><a aria-label="알림으로 돌아가기" class="arrowLink " href="\/notifications"[^>]*><svg[^>]*lucide-arrow-left[\s\S]*?<\/svg>알림<\/a><h1/)
  assert.doesNotMatch(settings, /알림 목록으로<\/a>/)
  const list = render({})
  // 제목과 설정 이동은 한 줄, 목록 동작은 그 아래 도구 줄이다.
  assert.match(list, /<header class="pageHeader"><h1 id="notifications-title">알림<\/h1><a class="arrowLink " href="\/settings\/notifications"[^>]*>알림 설정<svg[^>]*lucide-arrow-right[\s\S]*?<\/header><div class="toolbar"><button[^>]*class="button ghost "[^>]*>[\s\S]*?새로고침<\/button><button[^>]*class="button secondary "[^>]*>[\s\S]*?전체 읽음 처리<\/button><\/div>/)
})

test('notification cards follow the shared card pattern: badges on top, time and actions in a footer, centered more button', () => {
  const html = render({ more: true })
  assert.match(html, /<div class="meta"><span class="kind">마감 임박<\/span><span class="readState" data-read="false">안 읽음<\/span><\/div>/)
  assert.match(html, /<div class="footer"><time dateTime="2026-10-26T01:00:00Z">2026\.10\.26 10:00<\/time><div class="actions">[\s\S]*?공고 보기<svg[^>]*lucide-arrow-right/)
  assert.match(html, /<div class="more"><button[^>]*>더 보기<\/button><\/div>/)
  assert.match(render({ items: [{ ...item, readAt: '2026-10-26T02:00:00Z' }] }), /data-read="true">읽음<\/span>/)
  const css = fs.readFileSync(new URL('../src/pages/notifications/NotificationPages.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.card \{[^}]*box-shadow: none;/)
  assert.match(css, /\.more \{ display: flex; justify-content: center; \}/)
  assert.doesNotMatch(css, /\.page > \.toolbar/)
})

test('NEW_MATCH summary is labeled as a recommendation and offers a recommendation action', () => {
  const html = render({ items: [{ ...item, type: 'NEW_MATCH', title: '가능성 있는 정책 2건', link: '/' }] })
  assert.match(html, /새로운 추천/)
  assert.match(html, /가능성 있는 정책 2건 추천 보기/)
  assert.match(html, /추천 보기<svg/)
  assert.doesNotMatch(html, /가능성 있는 정책 2건 공고 보기/)
})

test('notification settings split in-app and web push into separate cards with the save action aligned right', () => {
  const html = render({ view: 'settings' })
  assert.match(html, /<div class="[^"]*panel"><form class="form" aria-labelledby="in-app-title"><div class="sectionHead"><h2 id="in-app-title">알림 받기<\/h2>/)
  // 설정이 하나라 체크박스와 저장(주요 버튼)을 한 줄에 둔다.
  assert.match(html, /<div class="settingRow"><label class="toggle">[\s\S]*?<\/label><div class="formActions"><button type="submit" class="button primary "[^>]*>설정 저장<\/button><\/div><\/div>/)
  assert.match(html, /<\/form><\/div><div class="[^"]*panel"><section class="section" aria-labelledby="web-push-title">/)
  // 브라우저 상태 확인 전에는 연결·해제를 추측해 보여 주지 않고 확인 중만 알린다.
  assert.match(html, /<dt>이 브라우저<\/dt><dd[^>]*>확인하고 있어요<\/dd>/)
  assert.doesNotMatch(html, />이 브라우저 연결<\/button>|>이 브라우저 해제<\/button>/)
  // 설정 항목은 카드 안에 별도 테두리 상자를 두지 않는다.
  const css = fs.readFileSync(new URL('../src/pages/notifications/NotificationPages.module.css', import.meta.url), 'utf8')
  assert.doesNotMatch(css, /\.toggle \{[^}]*border:/)
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

test('settings use plain language, show push state once in the push card and never claim a completed browser subscription', () => {
  const html = render({ view: 'settings' })
  assert.match(html, /<input type="checkbox" checked=""[^>]*\/>Y:ON 안에서 알림 받기<\/label>/)
  // 푸시 설정은 브라우저 푸시 카드에서 한 번만 보여 준다.
  assert.equal((html.match(/<dt>계정 푸시 알림<\/dt>/g) ?? []).length, 1)
  assert.doesNotMatch(html, /연결됨/)
  assert.doesNotMatch(html, /저장하지 않은 변경/)
  // 상태값 옆의 동의 일시는 작은 보조 텍스트로 분리한다.
  assert.match(html, /<dt>계정 푸시 알림<\/dt><dd[^>]*>켜짐<span class="consent">동의 <time/)
  assert.doesNotMatch(html, /서버의 푸시 알림 설정|FCM|서비스 워커|엔드포인트|Firebase|VAPID|인앱|readOnly/)
  assert.match(html, /disabled=""[^>]*>설정 저장/)
})

test('notification CSS retains wrapping, mobile spacing and native touch-sized labelled settings inputs', () => {
  const css = fs.readFileSync(new URL('../src/pages/notifications/NotificationPages.module.css', import.meta.url), 'utf8')
  assert.match(css, /flex-wrap: wrap/); assert.match(css, /overflow-wrap: anywhere/)
  assert.match(css, /min-block-size: 2\.75rem/); assert.match(css, /max-width: 767px/)
})
