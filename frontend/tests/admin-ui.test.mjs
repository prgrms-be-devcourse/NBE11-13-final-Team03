import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import { test } from 'node:test'
import ts from 'typescript'
import { job, preview, rawRecord } from './admin-fixtures.mjs'
const require = createRequire(import.meta.url)
require.extensions['.css'] = module => { module.exports = { __esModule: true, default: new Proxy({}, { get: (_, key) => String(key) }) } }
for (const extension of ['.ts', '.tsx']) require.extensions[extension] = (module, filename) => {
  module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText, filename)
}
const React = require('react'), e = React.createElement
const { renderToStaticMarkup } = require('react-dom/server')
const { MemoryRouter, Routes, Route } = require('react-router-dom')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const { AuthContext } = require('../src/auth/AuthContext.ts')
const AdminLayout = require('../src/pages/admin/AdminLayout.tsx').default
const CollectionSection = require('../src/pages/admin/CollectionSection.tsx').default
const RawRecordSection = require('../src/pages/admin/RawRecordSection.tsx').default
const RetentionSection = require('../src/pages/admin/RetentionSection.tsx').default
const Header = require('../src/layouts/Header.tsx').default
const { seoulToday } = require('../src/api/admin.ts')

const admin = { id: 1, nickname: '관리자', role: 'ADMIN' }, member = { id: 2, nickname: '회원', role: 'USER' }
const base = { logoutPending: false, logoutError: null, retry: () => {}, completeOAuth: async () => {}, logout: async () => {} }
function render({ path = '/admin/collection', status = 'authenticated', user = admin, data = {} } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } })
  client.setQueryData(['auth', 'user'], user)
  for (const [key, value] of Object.entries(data)) client.setQueryData(JSON.parse(key), value)
  const routes = e(Routes, null, e(Route, { path: '/admin', element: e(AdminLayout) },
    e(Route, { path: 'collection', element: e(CollectionSection) }), e(Route, { path: 'raw-records', element: e(RawRecordSection) }), e(Route, { path: 'retention', element: e(RetentionSection) })))
  try {
    return renderToStaticMarkup(e(QueryClientProvider, { client }, e(MemoryRouter, { initialEntries: [path] }, e(AuthContext.Provider, { value: { ...base, status, user: status === 'authenticated' ? user : undefined } }, routes))))
  } finally { client.clear() }
}
const today = seoulToday()
const syncs = (date = today) => JSON.stringify(['admin', 1, 'syncs', date])
const adminMarkers = /id="admin-title"|관리자 메뉴|데이터 수집|수집 이력|행동 로그 보관/

test('admin content is never rendered while checking, on auth error, anonymous or for a USER', () => {
  for (const path of ['/admin/collection', '/admin/raw-records', '/admin/retention']) {
    const checking = render({ path, status: 'checking' })
    assert.match(checking, /로그인 상태를 확인하고 있어요/); assert.doesNotMatch(checking, adminMarkers)
    const error = render({ path, status: 'error' })
    assert.match(error, /로그인 상태를 확인하지 못했어요/); assert.doesNotMatch(error, adminMarkers)
    const anonymous = render({ path, status: 'anonymous' })
    assert.match(anonymous, new RegExp(`returnTo=${encodeURIComponent(path).replace(/[%]/g, '%')}`)); assert.doesNotMatch(anonymous, adminMarkers)
    const user = render({ path, user: member, data: { [syncs()]: [job] } })
    assert.match(user, /접근 권한이 없어요/); assert.doesNotMatch(user, adminMarkers)
    assert.doesNotMatch(user, /#7/)
  }
})

test('ADMIN sees the side menu and the collection history; execution needs an explicit second step', () => {
  const html = render({ data: { [syncs()]: [job] } })
  assert.match(html, /<h1 id="admin-title">관리자<\/h1>/)
  assert.match(html, /<nav class="sideNav" aria-label="관리자 메뉴">[\s\S]*?href="\/admin\/collection"[\s\S]*?href="\/admin\/raw-records"[\s\S]*?href="\/admin\/retention"/)
  assert.match(html, /수집 #7 상세 보기/)
  assert.match(html, /<span class="statusText" data-tone="danger">실패<\/span>/)
  // 첫 화면에는 실행 확인 버튼이 없고, 시작 패널을 여는 보조 버튼만 있다.
  // 신규 수집은 섹션 제목 줄의 보조 버튼이고, 이력 카드의 조회 도구(날짜·새로고침)와 분리된다.
  assert.match(html, /<div class="sectionHeadRow"><div class="sectionHead"><h2>데이터 수집<\/h2>[\s\S]*?<\/div><button[^>]*class="button secondary "[^>]*>수집 시작<\/button><\/div>/)
  assert.doesNotMatch(html, /수집 시작…/)
  assert.doesNotMatch(html.slice(html.indexOf('id="sync-list-title"')), />수집 시작<\/button>/)
  assert.doesNotMatch(html, /수집 시작 확인|원본 레코드 저장|회원 관리/)
  assert.match(render({ data: { [syncs()]: [] } }), /실행된 수집이 없어요/)
})

test('job detail offers retry only for retryable statuses and links record failures to the raw record view', () => {
  const failed = render({ path: `/admin/collection?date=2026-10-31&job=7`, data: { [syncs('2026-10-31')]: [job], [JSON.stringify(['admin', 1, 'sync', 7])]: job } })
  assert.match(failed, /수집 #7 상세/)
  assert.match(failed, />실패 작업 재시도…<\/button>/)
  assert.match(failed, /href="\/admin\/raw-records\?id=55"/)
  const done = { ...job, status: 'SUCCESS' }
  assert.doesNotMatch(render({ path: `/admin/collection?date=2026-10-31&job=7`, data: { [syncs('2026-10-31')]: [done], [JSON.stringify(['admin', 1, 'sync', 7])]: done } }), /실패 작업 재시도/)
})

test('raw records are read-only, validate the ID and escape the payload', () => {
  assert.match(render({ path: '/admin/raw-records' }), /조회할 레코드 ID를 입력해 주세요/)
  assert.match(render({ path: '/admin/raw-records?id=abc' }), /레코드 ID는 1 이상의 정수예요/)
  const html = render({ path: '/admin/raw-records?id=55', data: { [JSON.stringify(['admin', 1, 'raw', 55])]: rawRecord } })
  assert.match(html, /&lt;script&gt;x&lt;\/script&gt;/)
  assert.doesNotMatch(html, /<script>x/)
  assert.doesNotMatch(html, /type="submit"[^>]*>저장/)
})

test('retention execution requires READY preview, server permission and an explicit acknowledgement', () => {
  const key = JSON.stringify(['admin', 1, 'retention'])
  const ready = render({ path: '/admin/retention', data: { [key]: preview } })
  assert.match(ready, /파티션 1개를 삭제/)
  assert.match(ready, /삭제한 행동 로그는 복구할 수 없어요/)
  assert.match(ready, /<input type="checkbox"\/>/)
  assert.match(ready, /<button[^>]*disabled=""[^>]*>보관 정리 실행<\/button>/)
  const disabled = render({ path: '/admin/retention', data: { [key]: { ...preview, policy: { ...preview.policy, adminExecuteEnabled: false } } } })
  assert.match(disabled, /서버 설정에서 관리자 보관 정리 실행이 꺼져 있어요/)
  assert.doesNotMatch(disabled, />보관 정리 실행<\/button>/)
  const skipped = render({ path: '/admin/retention', data: { [key]: { ...preview, status: 'SKIPPED_LOCKED', planToken: null } } })
  assert.match(skipped, /<p class="statusLabel">다른 정리 진행 중<\/p>/)
  assert.doesNotMatch(skipped, />보관 정리 실행<\/button>/)
})

test('retention status is shown in Korean while the API code is preserved, and empty targets are explained instead of empty tables', () => {
  const key = JSON.stringify(['admin', 1, 'retention'])
  const notPartitioned = render({ path: '/admin/retention', data: { [key]: { ...preview, status: 'SKIPPED_NOT_PARTITIONED', toDrop: [], toCreate: [], planToken: null } } })
  assert.match(notPartitioned, /<div class="statusBlock" data-tone="muted"><p class="statusLabel">파티션 전환 전<\/p><p class="help">[^<]*월 파티션 구조가 아니라서[^<]*<\/p><p class="code">상태 코드 <code>SKIPPED_NOT_PARTITIONED<\/code><\/p><\/div>/)
  assert.match(notPartitioned, /현재 상태에서는 삭제 대상을 계산하지 않아요/)
  assert.match(notPartitioned, /현재 상태에서는 생성 대상을 계산하지 않아요/)
  assert.doesNotMatch(notPartitioned, /<table/)
  // READY인데 대상이 없으면 '계산하지 않음'이 아니라 실제로 없다는 안내다. 대상이 있으면 표를 그대로 보여 준다.
  const ready = render({ path: '/admin/retention', data: { [key]: preview } })
  assert.match(ready, /<table aria-label="삭제 대상 파티션">[\s\S]*?user_behavior_events_2026_06/)
  assert.match(ready, /새로 만들 대상이 없어요/)
  // 알 수 없는 상태는 원문 코드를 그대로 보여 준다.
  assert.match(render({ path: '/admin/retention', data: { [key]: { ...preview, status: 'SOMETHING_NEW', planToken: null } } }), /<p class="statusLabel">SOMETHING_NEW<\/p>/)
  // 정책 정보는 같은 격자에 두고 자동 정리와 일정은 나눠 보여 준다.
  assert.match(ready, /<dl class="infoGrid">[\s\S]*?<dt>자동 정리<\/dt><dd>꺼짐<\/dd>[\s\S]*?<dt>정리 일정<\/dt><dd><code>매월 1일 03:00 \(Asia\/Seoul\)<\/code><\/dd>/)
  const css = fs.readFileSync(new URL('../src/pages/admin/Admin.module.css', import.meta.url), 'utf8')
  assert.match(css, /\.infoGrid dd \{[^}]*word-break: keep-all/)
  // 정리 일정은 다른 정보값과 같은 글씨, 상태 코드는 작은 보조 텍스트를 유지한다.
  assert.match(css, /\.infoGrid dd code \{ font: inherit;/)
  assert.match(css, /\.code \{ color: var\(--admin-muted\); font-size: 0\.8125rem; \}/)
  assert.match(css, /\.admin \.sectionHead \{ display: grid; gap: var\(--space-3\); \}/)
  // 표 헤더·본문은 같은 세로 가운데 정렬, 관리자 배경은 관리자 영역이 있을 때만 바뀐다.
  assert.match(css, /\.admin th, \.admin td \{[^}]*vertical-align: middle;/)
  assert.match(css, /body:has\(\.admin\) \{ background-color: #eeefea; \}/)
})

test('a failed history request is an error with a title, never the empty result', () => {
  const { ApiError } = require('../src/api/ApiError.ts')
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } })
  client.setQueryData(['auth', 'user'], admin)
  client.getQueryCache().build(client, { queryKey: ['admin', 1, 'syncs', today] }).setState({ status: 'error', fetchStatus: 'idle', error: new ApiError('http', 'x', 503, { code: 'COLLECTION_008', message: 'x', fieldErrors: {} }) })
  const routes = e(Routes, null, e(Route, { path: '/admin', element: e(AdminLayout) }, e(Route, { path: 'collection', element: e(CollectionSection) })))
  const html = renderToStaticMarkup(e(QueryClientProvider, { client }, e(MemoryRouter, { initialEntries: ['/admin/collection'] }, e(AuthContext.Provider, { value: { ...base, status: 'authenticated', user: admin } }, routes))))
  client.clear()
  assert.match(html, /<p class="errorTitle">수집 이력을 불러오지 못했어요<\/p><p>수집 서비스에 연결할 수 없어요/)
  assert.doesNotMatch(html, /실행된 수집이 없어요/)
})

test('header shows the admin menu only for a confirmed ADMIN', () => {
  const header = (status, user) => {
    const client = new QueryClient()
    try { return renderToStaticMarkup(e(QueryClientProvider, { client }, e(MemoryRouter, null, e(AuthContext.Provider, { value: { ...base, status, user } }, e(Header))))) } finally { client.clear() }
  }
  assert.match(header('authenticated', admin), /href="\/admin"[^>]*>[\s\S]*?관리자<\/a>/)
  for (const [status, user] of [['authenticated', member], ['anonymous', undefined], ['checking', undefined], ['error', undefined]]) assert.doesNotMatch(header(status, user), /href="\/admin"/)
})

test('admin tone is scoped to .admin and does not redefine global tokens', () => {
  const css = fs.readFileSync(new URL('../src/pages/admin/Admin.module.css', import.meta.url), 'utf8')
  assert.doesNotMatch(css, /:root|--color-[a-z-]+\s*:/)
  assert.match(css, /\.admin \{\s*--admin-surface/)
  assert.doesNotMatch(css, /gradient|backdrop-filter|box-shadow: 0 /)
})
