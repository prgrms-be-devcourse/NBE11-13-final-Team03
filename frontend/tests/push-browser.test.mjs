import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import ts from 'typescript'

const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find(value => value && fs.existsSync(value))
test('Chrome with missing Firebase configuration disables enable and makes no permission, worker or endpoint request', { skip: !browser && 'Chrome/Edge가 없어 설정 누락 UI의 브라우저 검증은 실행하지 않았다.' }, async () => {
  const cache = path.join(root, 'node_modules', '.cache'); fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'push-browser-'))
  const entry = `
    import React from 'react';import {createRoot} from 'react-dom/client';import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
    import PushControls from '/src/push/PushControls.tsx';import {apiClient} from '/src/api/client.ts';import '/src/push/browser.ts';import '/src/index.css';
    const user={id:11,role:'USER'},settings={inAppEnabled:true,pushEnabled:false,pushConsentAt:null,updatedAt:null};
    let permissions=0,workers=0,requests=0;
    if('Notification' in window)Notification.requestPermission=()=>{permissions++;return Promise.resolve('denied')};
    navigator.serviceWorker.register=async()=>{workers++;throw Error('must not register without config')};
    apiClient.defaults.adapter=async()=>{requests++;throw Error('must not call backend without config')};
    const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});client.setQueryData(['auth','user'],user);
    const root=createRoot(document.querySelector('#root'));root.render(<QueryClientProvider client={client}><PushControls owner={user} settings={settings} disabled={false}/></QueryClientProvider>);
    try{
      const configHelp=()=>document.body.textContent.includes('지금은 푸시 알림을 사용할 수 없어요.');
      for(let i=0;i<1200&&!configHelp();i++)await new Promise(resolve=>setTimeout(resolve,10));
      const button=Array.from(document.querySelectorAll('button')).find(button=>button.textContent.trim()==='이 브라우저 연결');
      if(!configHelp()||!button?.disabled)throw Error('missing config must disable enable: '+document.body.textContent.slice(0,1200)+' | '+JSON.stringify(client.getQueryCache().getAll().map(query=>({key:query.queryKey,status:query.state.status,error:query.state.error?.message}))));
      button.click();await new Promise(resolve=>setTimeout(resolve,50));
      document.querySelector('#result').textContent=JSON.stringify({ok:permissions===0&&workers===0&&requests===0,permissions,workers,requests});
    }catch(error){document.querySelector('#result').textContent=JSON.stringify({ok:false,error:String(error)})}
    finally{root.unmount();client.clear()}
  `
  const server = await createServer({ root, envDir: false, configFile: false, envPrefix: 'TEST_UNUSED_', cacheDir: path.join(temporary, 'vite'), plugins: [react(), {
    name: 'test-only-push-page', resolveId: id => id === '/__test__/push-entry.tsx' ? '\0push-entry.tsx' : undefined,
    load: id => id === '\0push-entry.tsx' ? ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText : undefined,
    configureServer(server) { server.middlewares.use((request, response, next) => {
      if (request.url !== '/__test__/push') return next()
      void server.transformIndexHtml('/__test__/push', '<!doctype html><html lang="ko"><head><meta charset="utf-8"></head><body><div id="root"></div><pre id="result"></pre><script type="module" src="/__test__/push-entry.tsx"></script></body></html>').then(html => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen(); const port = server.httpServer.address().port
    await fetch(`http://127.0.0.1:${port}/__test__/push-entry.tsx`)
    const { stdout } = await promisify(execFile)(browser, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, 'browser')}`, '--virtual-time-budget=20000', '--dump-dom', `http://127.0.0.1:${port}/__test__/push`], { windowsHide: true, timeout: 60_000, maxBuffer: 5_000_000 })
    const result = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
    assert.ok(result, '설정 누락 화면의 실제 브라우저 결과가 필요하다.')
    assert.deepEqual(JSON.parse(result[1]), { ok: true, permissions: 0, workers: 0, requests: 0 })
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
})
