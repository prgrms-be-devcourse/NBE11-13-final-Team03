import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { test } from 'node:test'
import ts from 'typescript'
import { readPushConfig, pushEnvironmentKeys } from '../src/push/config.ts'
import { pushTarget, pushClickPath } from '../src/push/protocol.ts'

function workerFixture(existing = true) {
  const handlers = {}, calls = []
  const source = fs.readFileSync(new URL('../src/push/firebase-messaging-sw.ts', import.meta.url), 'utf8').replace('import.meta.env', 'fixtureEnv')
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const client = { url: 'https://yon.test/', navigate: async url => { calls.push(['navigate', url]); return client }, focus: async () => { calls.push(['focus']); return client } }
  const self = { location: { origin: 'https://yon.test' }, addEventListener: (name, handler) => { handlers[name] = handler }, clients: {
    matchAll: async () => existing ? [client] : [], openWindow: async url => { calls.push(['open', url]) },
  } }
  const modules = {
    '@firebase/app': { initializeApp: () => ({}) }, '@firebase/messaging/sw': { getMessaging: () => ({}), onBackgroundMessage: (_messaging, callback) => { handlers.background = callback } },
    './config': { readPushConfig }, './protocol': { pushTarget, pushClickPath },
  }
  vm.runInNewContext(compiled, { exports: {}, require: name => modules[name], self, URL, fixtureEnv: Object.fromEntries(pushEnvironmentKeys.map(key => [key, 'fixture'])) })
  async function click(payload) {
    let waiting
    handlers.notificationclick({ notification: { data: { FCM_MSG: payload }, close: () => calls.push(['close']) }, stopImmediatePropagation: () => calls.push(['stop']), waitUntil: promise => { waiting = promise } })
    await waiting
  }
  return { click, calls, background: handlers.background }
}
test('executed worker click handler reuses a same-origin tab and passes only validated notification/opportunity IDs', async () => {
  const f = workerFixture()
  await f.click({ data: { notificationId: '7' }, fcmOptions: { link: 'https://yon.test/opportunities/42' } })
  assert.deepEqual(f.calls, [['stop'], ['close'], ['navigate', 'https://yon.test/notifications?pushId=7&opportunityId=42'], ['focus']])
})
test('executed worker opens a safe app page when no tab exists and never follows external links', async () => {
  const f = workerFixture(false)
  await f.click({ data: { notificationId: '7' }, fcmOptions: { link: 'https://evil.test/opportunities/42' } })
  assert.deepEqual(f.calls, [['stop'], ['close'], ['open', 'https://yon.test/notifications']])
})
test('background callback performs no second display or authenticated work for an SDK notification message', () => {
  const f = workerFixture()
  f.background({ notification: { title: 'fixture', body: 'fixture' }, data: { notificationId: '7' } })
  assert.deepEqual(f.calls, [])
})
