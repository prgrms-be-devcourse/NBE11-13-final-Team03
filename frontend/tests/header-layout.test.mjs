import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { test } from 'node:test'
import ts from 'typescript'
import { chromeServiceFlags } from './chrome-flags.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find((candidate) => candidate && fs.existsSync(candidate))
const require = createRequire(import.meta.url)
require.extensions['.css'] = (module) => { module.exports = { __esModule: true, default: new Proxy({}, { get: (_, key) => String(key) }) } }
for (const extension of ['.ts', '.tsx']) require.extensions[extension] = (module, filename) => {
  module._compile(ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true } }).outputText, filename)
}
const React = require('react'), e = React.createElement
const { renderToStaticMarkup } = require('react-dom/server')
const { MemoryRouter } = require('react-router-dom')
const { QueryClient, QueryClientProvider } = require('@tanstack/react-query')
const Header = require('../src/layouts/Header.tsx').default
const { AuthContext } = require('../src/auth/AuthContext.ts')
const base = { status: 'anonymous', logoutPending: false, logoutError: null, retry: () => {}, completeOAuth: async () => {}, logout: async () => {} }
const fixtures = []
for (const width of [1024, 1244, 1440]) for (const authenticated of [false, true]) fixtures.push({ width, auth: authenticated ? { ...base, status: 'authenticated', user: { id: 1, nickname: '아주 긴 테스트용 사용자 닉네임', role: 'USER' } } : base })
fixtures.push({ width: 1024, auth: { ...base, status: 'error' } }, { width: 1244, auth: { ...base, status: 'checking' } }, { width: 390, auth: base }, { width: 390, auth: base, open: true })

test('Chrome renders desktop brand/search/auth above centered navigation and mobile disclosure without overlap or overflow', { skip: !browser && 'Chrome/Edge가 없어 실제 레이아웃 검증을 실행하지 않았다.' }, () => {
  const cache = path.join(root, 'node_modules', '.cache')
  fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'header-layout-'))
  try {
    const css = ['src/tokens.css', 'src/index.css', 'src/components/ui/Button.module.css', 'src/layouts/Header.module.css'].map((file) => fs.readFileSync(path.join(root, file), 'utf8')).join('\n').replace("@import './tokens.css';", '')
    const escape = (value) => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    const frames = fixtures.map((fixture) => {
      const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, gcTime: Infinity } } })
      client.setQueryData(['notifications', 1, 'unread'], { available: true, unreadCount: 123 })
      let header
      try { header = renderToStaticMarkup(e(QueryClientProvider, { client }, e(MemoryRouter, null, e(AuthContext.Provider, { value: fixture.auth }, e(Header))))) }
      finally { client.clear() }
      header = header.replace('/logo/yon-symbol.png', pathToFileURL(path.join(root, 'public/logo/yon-symbol.png')).href)
      const document = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><style>${css}</style></head><body>${header}</body></html>`
      return `<iframe width="${fixture.width}" height="400" data-open="${!!fixture.open}" style="border:0;display:block" srcdoc="${escape(document)}"></iframe>`
    }).join('')
    const html = `<!doctype html><meta charset="utf-8">${frames}<pre id="result"></pre><script>
      window.addEventListener('load', () => {
        const results = Array.from(document.querySelectorAll('iframe')).map(frame => {
          const doc = frame.contentDocument, view = frame.contentWindow;
          if (frame.dataset.open === 'true') { doc.querySelector('.navigation').classList.add('open'); doc.querySelector('.auth').classList.add('authOpen'); }
          const rect = selector => { const r = doc.querySelector(selector).getBoundingClientRect(); return { left:r.left,right:r.right,top:r.top,bottom:r.bottom,cx:r.x+r.width/2,cy:r.y+r.height/2,width:r.width,height:r.height }; };
          return { width:doc.documentElement.clientWidth, scrollWidth:doc.documentElement.scrollWidth, open:frame.dataset.open === 'true', brand:rect('.brand'), search:rect('.headerSearch'), nav:rect('.primaryNavigation'), auth:rect('.auth'), toggle:rect('.menuButton'), menuDisplay:view.getComputedStyle(doc.querySelector('.navigation')).display };
        });
        document.querySelector('#result').textContent = JSON.stringify(results);
      });
    </script>`
    const fixtureFile = path.join(temporary, 'fixture.html')
    fs.writeFileSync(fixtureFile, html)
    // 격리된 임시 프로필·로컬 SSR fixture만 사용한다. 기존 브라우저/로그인/백엔드에 접근하지 않는다.
    const output = execFileSync(browser, ['--headless', ...chromeServiceFlags, '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, 'browser')}`, '--virtual-time-budget=2000', '--dump-dom', pathToFileURL(fixtureFile).href], { windowsHide: true, encoding: 'utf8', timeout: 60_000, maxBuffer: 5_000_000, stdio: ['ignore', 'pipe', 'pipe'] })
    const match = output.match(/<pre id="result">([^<]+)<\/pre>/)
    assert.ok(match, '브라우저의 실제 좌표 결과가 필요하다.')
    const results = JSON.parse(match[1])
    assert.equal(results.length, fixtures.length)
    for (const result of results) {
      const context = JSON.stringify(result)
      assert.ok(result.scrollWidth <= result.width, context)
      if (result.width >= 1024) {
        assert.ok(Math.abs(result.brand.cy - result.auth.cy) <= 1, context)
        assert.ok(Math.abs(result.brand.cy - result.search.cy) <= 1, context)
        assert.ok(result.nav.top >= result.brand.bottom && result.nav.top >= result.auth.bottom, context)
        assert.ok(Math.abs(result.nav.cx - result.width / 2) <= 1, context)
        assert.ok(result.brand.right <= result.search.left && result.search.right <= result.auth.left, context)
      } else {
        assert.ok(Math.abs(result.brand.cy - result.toggle.cy) <= 1, context)
        if (result.open) assert.ok(result.nav.top >= result.brand.bottom && result.auth.top >= result.nav.bottom, context)
        else assert.equal(result.menuDisplay, 'none', context)
      }
    }
  } finally {
    const resolved = path.resolve(temporary)
    assert.ok(resolved.startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(resolved, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
})
