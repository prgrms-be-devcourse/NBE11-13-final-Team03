import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createRequire } from 'node:module'
import { test } from 'node:test'
import ts from 'typescript'
const require = createRequire(import.meta.url)
require.extensions['.css'] = module => { module.exports = { __esModule: true, default: new Proxy({}, { get: (_, key) => String(key) }) } }
for (const extension of ['.ts', '.tsx']) require.extensions[extension] = (module, filename) => {
  module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText, filename)
}
const React = require('react'), e = React.createElement
const { renderToStaticMarkup } = require('react-dom/server')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const Controls = require('../src/push/PushControls.tsx').default
const owner = { id: 11, role: 'USER' }, settings = { inAppEnabled: true, pushEnabled: true, pushConsentAt: null, updatedAt: null }
function render(status) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity, gcTime: Infinity } } })
  client.setQueryData(['notifications', 11, 'push-browser'], { ready: true, supported: true, permission: 'granted', connected: false, phase: null, knownEndpoints: 0, controller: {}, ...status })
  try { return renderToStaticMarkup(e(QueryClientProvider, { client }, e(Controls, { owner, settings, disabled: false }))) } finally { client.clear() }
}
test('missing configuration shows a plain unavailable message without developer settings and disables connecting', () => {
  const html = render({ ready: false })
  assert.match(html, /지금은 푸시 알림을 사용할 수 없어요/)
  assert.doesNotMatch(html, /VITE_FIREBASE|Firebase|VAPID|\.env/)
  assert.match(html, /<button[^>]*disabled=""[^>]*>이 브라우저 연결/)
  assert.match(html, /연결 확인 안 됨/)
})
test('unsupported environment and blocked permissions disable connecting without claiming a subscription', () => {
  for (const status of [{ supported: false }, { permission: 'denied' }]) {
    const html = render(status)
    assert.match(html, /<button[^>]*disabled=""[^>]*>이 브라우저 연결/)
    assert.match(html, /연결 확인 안 됨/)
  }
  assert.match(render({ supported: false }), /이 브라우저에서는 푸시 알림을 사용할 수 없어요[\s\S]*홈 화면에 추가한 Y:ON/)
  assert.match(render({ permission: 'denied' }), /알림 권한이 차단되어 있어요/)
})
test('overall push state and this browser are shown separately, including unfinished connection and cleanup recovery', () => {
  assert.match(render({ phase: 'pending' }), /연결이 끝나지 않았어요/)
  assert.match(render({ phase: 'cleanup' }), /이전 연결 정리가 끝나지 않았어요/)
  const html = render({ connected: true, phase: 'active' })
  assert.match(html, /푸시 알림: 켜짐/)
  assert.match(html, /이 브라우저: 연결됨/)
  assert.match(html, /<div class="actions"><button[^>]*>이 브라우저 연결<\/button><button[^>]*>이 브라우저 해제<\/button><button[^>]*>모든 기기 푸시 끄기<\/button><button[^>]*>상태 다시 확인<\/button><\/div>/)
})
test('push controls explain the two off actions once in plain language and hide internal implementation details', () => {
  const html = render({ ready: true, connected: false, permission: 'granted' })
  assert.match(html, /연결 확인 안 됨/)
  assert.equal((html.match(/이 기기만/g) ?? []).length, 1)
  assert.match(html, /‘모든 기기 푸시 끄기’는 모든 기기의 푸시 알림을 꺼요/)
  assert.doesNotMatch(html, /서버 엔드포인트|등록 ID|FCM|서비스 워커|Web Locks|Push API|HTTPS/)
})
