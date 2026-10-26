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
test('missing public configuration renders required env guidance and a disabled enable action', () => {
  const html = render({ ready: false })
  assert.match(html, /VITE_FIREBASE_\*/)
  assert.match(html, /<button[^>]*disabled=""[^>]*>이 브라우저 푸시 연결·재확인/)
  assert.match(html, /연결 완료 미확인/)
})
test('unsupported environment and denied permissions disable permission-requesting action without claiming subscription', () => {
  for (const status of [{ supported: false }, { permission: 'denied' }]) {
    const html = render(status)
    assert.match(html, /<button[^>]*disabled=""[^>]*>이 브라우저 푸시 연결·재확인/)
    assert.match(html, /연결 완료 미확인/)
  }
  assert.match(render({ supported: false }), /iOS/)
  assert.match(render({ permission: 'denied' }), /권한이 거부/)
})
test('server-global preference and browser binding are shown separately, including partial registration and cleanup recovery', () => {
  assert.match(render({ phase: 'pending' }), /일부 등록이 진행됐지만/)
  assert.match(render({ phase: 'cleanup' }), /서버 엔드포인트 정리/)
  const html = render({ connected: true, phase: 'active' })
  assert.match(html, /사용자 전체 푸시 설정: 켜짐/)
  assert.match(html, /이 브라우저: 연결됨/)
  assert.match(html, /이 브라우저 해제/); assert.match(html, /모든 기기 푸시 끄기/)
  assert.match(html, /등록 ID가 없는 서버 엔드포인트/)
})
test('push controls use button actions instead of claiming permission or server preference alone is a completed subscription', () => {
  const html = render({ ready: true, connected: false, permission: 'granted' })
  assert.match(html, /연결 완료 미확인/)
  assert.match(html, /다른 기기를 끄지 않아요/)
  assert.match(html, /브라우저 권한 자체는 브라우저 설정/)
})
