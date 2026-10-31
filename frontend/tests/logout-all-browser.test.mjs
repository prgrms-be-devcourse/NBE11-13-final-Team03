import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { createServer } from 'vite'
import { chromeServiceFlags } from './chrome-flags.mjs'
import react from '@vitejs/plugin-react'
import ts from 'typescript'

// 마이페이지 모든 기기 로그아웃을 실제 Chrome·실제 App(AuthProvider·createAuthActions·푸시 정리)으로 확인한다. HTTP adapter만 모의 처리한다.
const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find(value => value && fs.existsSync(value))
const entry = String.raw`
  import React from 'react';import {createRoot} from 'react-dom/client';import {BrowserRouter} from 'react-router-dom';import {QueryClientProvider} from '@tanstack/react-query';
  import {AxiosError} from 'axios';import {queryClient} from '/src/api/queryClient.ts';import {apiClient} from '/src/api/client.ts';import App from '/src/App.tsx';import '/src/index.css';
  document.cookie='XSRF-TOKEN=test-logout-all; path=/';
  // 헤드리스 가상 시간에서는 서비스 워커 조회가 끝나지 않을 수 있어 기존 브라우저 테스트처럼 빈 목록으로 대체한다.
  navigator.serviceWorker.getRegistrations=async()=>[];
  // 같은 이유로 Web Locks 대기가 끝나지 않을 수 있어(로그아웃 전 푸시 정리), push 단위 테스트처럼 즉시 실행하는 대역을 쓴다.
  Object.defineProperty(navigator,'locks',{configurable:true,value:{request:async(_name,_options,work)=>work({})}});
  const scenario=new URLSearchParams(location.search).get('scenario');
  const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const until=async(check,label)=>{for(let i=0;i<600;i++){if(check())return;await wait(20)}throw Error('timeout: '+label+' | '+document.body.textContent.slice(0,400))};
  let loggedIn=true;const calls={logoutAll:0,logout:0,me:0};const trace=[];const mark=label=>trace.push(label+'@'+Math.round(performance.now()));
  apiClient.defaults.adapter=async config=>{
    const ok=(data,status=200)=>({data,status,statusText:'OK',headers:{},config});
    const fail=(status,code)=>new AxiosError('fixture','ERR_BAD_RESPONSE',config,{},{data:{code,message:'fixture',fieldErrors:{}},status,statusText:'',headers:{},config});
    if(config.url==='/users/me'){calls.me++;if(!loggedIn)throw fail(401,'AUTH_001');return ok({id:1,nickname:'테스트',email:null,role:'USER'})}
    if(config.url==='/users/me/eligibility-profile')throw fail(404,'ELIGIBILITY_001');
    if(config.url==='/users/me/interests')return ok({interests:[]});
    if(config.url==='/notifications/unread-count')return ok({available:true,unreadCount:0});
    if(config.url==='/auth/logout'){calls.logout++;await wait(300);loggedIn=false;return ok('',204)}
    if(config.url==='/auth/logout-all'){
      calls.logoutAll++;mark('logout-all sent');await wait(500);mark('logout-all waited');
      if(scenario==='uncertain')throw new AxiosError('Network Error','ERR_NETWORK',config,{});
      loggedIn=false;return ok('',204);
    }
    return ok({});
  };
  createRoot(document.querySelector('#root')).render(<QueryClientProvider client={queryClient}><BrowserRouter><App/></BrowserRouter></QueryClientProvider>);
  const buttons=text=>Array.from(document.querySelectorAll('button')).filter(b=>b.textContent.trim()===text);
  const opener=()=>document.querySelector('[aria-controls="logout-all-confirm"]');
  const panel=()=>document.getElementById('logout-all-confirm');
  const confirmButton=()=>panel()?.querySelector('button');
  const headerLogout=()=>Array.from(document.querySelectorAll('header button')).find(b=>b.textContent.trim()==='로그아웃');
  const steps=[];const record=(label,value)=>steps.push([label,value]);
  async function ready(){await until(()=>opener()&&!opener().disabled,'my page')}
  async function cancel(){
    await ready();
    opener().click();await until(()=>panel(),'confirm open');await wait(50);
    record('open',{focus:document.activeElement?.id,text:panel().textContent,openerHidden:!opener()});
    buttons('취소')[0].click();await until(()=>opener(),'closed');await wait(50);
    record('cancel',{focusIsOpener:document.activeElement===opener(),panel:!!panel()});
    opener().click();await until(()=>panel(),'reopen');
    panel().dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));await until(()=>!panel(),'escape');
    const box=opener().getBoundingClientRect(),text=document.querySelector('#logout-all-title').getBoundingClientRect();
    record('layout',{height:Math.round(box.height),overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth,fits:box.right<=document.documentElement.clientWidth&&box.left>=0});
    record('calls',{...calls});
  }
  async function success(){
    await ready();
    opener().click();await until(()=>confirmButton(),'confirm');
    confirmButton().click();confirmButton().click();await wait(20);confirmButton()?.click();
    // 진행 중 헤더 로그아웃을 눌러도 다른 전환을 시작하지 않는다.
    await wait(50);headerLogout()?.click();
    await until(()=>document.body.textContent.includes('모든 기기에서 로그아웃했어요'),'done');await wait(200);
    record('done',{...calls,headerLogin:!!Array.from(document.querySelectorAll('header a')).find(a=>a.textContent.trim()==='로그인'),
      relogin:document.querySelector('main a[href^="/login?returnTo=%2Fmypage"]')?.textContent,headerError:document.body.textContent.includes('로그아웃하지 못했어요')});
  }
  async function uncertain(){
    await ready();const me=calls.me;
    opener().click();await until(()=>confirmButton(),'confirm');
    confirmButton().click();
    await until(()=>document.body.textContent.includes('모든 기기 로그아웃 완료 여부를 확인하지 못했어요'),'uncertain');await wait(3000);
    record('uncertain',{logoutAll:calls.logoutAll,rechecked:calls.me>me,stillLoggedIn:!!opener()||!!panel(),alert:Array.from(document.querySelectorAll('[role="alert"]')).map(e=>e.textContent).join(' / ')});
  }
  async function regular(){
    await ready();
    buttons('로그아웃').find(b=>!b.closest('header')).click();
    await until(()=>calls.logout===1&&document.body.textContent.includes('로그인이 필요해요'),'regular logout');
    record('regular',{...calls});
  }
  (async()=>{
    try{
      await ({cancel,success,uncertain,regular})[scenario]();
      document.querySelector('#result').textContent=JSON.stringify(Object.fromEntries(steps));
    }catch(error){document.querySelector('#result').textContent=JSON.stringify({error:String(error).slice(0,300),calls,steps:Object.fromEntries(steps),
      mutations:queryClient.getMutationCache().getAll().map(m=>[m.state.variables,m.state.status,String(m.state.error??'')]),auth:queryClient.getQueryState(['auth','user'])?.fetchStatus,trace})}
  })();
`
async function run(scenarios) {
  const cache = path.join(root, 'node_modules', '.cache'); fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'logout-all-browser-'))
  const server = await createServer({ root, envDir: false, configFile: false, envPrefix: 'TEST_UNUSED_', cacheDir: path.join(temporary, 'vite'), logLevel: 'error', plugins: [react(), {
    name: 'test-only-logout-all-page', resolveId: id => id === '/__test__/logout-all-entry.tsx' ? '\0logout-all-entry.tsx' : undefined,
    load: id => id === '\0logout-all-entry.tsx' ? ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText : undefined,
    configureServer(server) { server.middlewares.use((request, response, next) => {
      if (!request.url.startsWith('/mypage?scenario=')) return next()
      void server.transformIndexHtml('/mypage', '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><div id="root"></div><pre id="result"></pre><script type="module" src="/__test__/logout-all-entry.tsx"></script></body></html>').then(html => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen(); const port = server.httpServer.address().port
    await fetch(`http://127.0.0.1:${port}/__test__/logout-all-entry.tsx`)
    const results = {}
    for (const [index, scenario] of scenarios.entries()) {
      const { stdout } = await promisify(execFile)(browser, ['--headless', ...chromeServiceFlags, '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, `browser-${index}`)}`, '--window-size=390,1200', '--virtual-time-budget=60000', '--dump-dom', `http://127.0.0.1:${port}/mypage?scenario=${scenario}`], { windowsHide: true, timeout: 120_000, maxBuffer: 5_000_000 })
      const result = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
      assert.ok(result, `실제 브라우저 결과가 필요하다: ${scenario}`)
      results[scenario] = JSON.parse(result[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>'))
    }
    return results
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
}

test('Chrome confirms logout-all before one POST, keeps the regular logout, guards concurrent transitions and treats lost responses as uncertain', { skip: !browser && 'Chrome/Edge가 없어 모든 기기 로그아웃의 브라우저 검증은 실행하지 않았다.' }, async () => {
  const { cancel, success, uncertain, regular } = await run(['cancel', 'success', 'uncertain', 'regular'])
  for (const result of [cancel, success, uncertain, regular]) assert.equal(result.error, undefined, JSON.stringify(result))
  // 확인 열기: 포커스가 확인 영역으로 가고 열기 버튼은 감춘다. 취소·Escape는 요청 없이 닫고 포커스를 돌려준다.
  assert.equal(cancel.open.focus, 'logout-all-confirm')
  assert.match(cancel.open.text, /모든 기기에서 로그아웃할까요\?.*현재 기기를 포함해 다른 기기의 로그인 세션도 종료돼요/)
  assert.equal(cancel.open.openerHidden, true)
  assert.deepEqual([cancel.cancel.focusIsOpener, cancel.cancel.panel], [true, false])
  assert.deepEqual(cancel.calls, { logoutAll: 0, logout: 0, me: cancel.calls.me })
  // 모바일 폭: 가로 넘침 없이 버튼이 화면 안에 들어오고(좁으면 설명 아래로 줄바꿈) 터치 크기 44px 이상
  assert.deepEqual([cancel.layout.overflow, cancel.layout.fits], [false, true])
  assert.ok(cancel.layout.height >= 44, String(cancel.layout.height))
  // 확인 후: 중복 클릭에도 /auth/logout-all 1회, 진행 중 헤더 로그아웃은 시작하지 않음(/auth/logout 0회·오류 배너 없음)
  assert.deepEqual([success.done.logoutAll, success.done.logout, success.done.headerError], [1, 0, false])
  assert.equal(success.done.headerLogin, true)
  assert.equal(success.done.relogin, '다시 로그인하기')
  // 응답 유실: 1회만 보내고 서버 로그인 상태를 다시 확인, 실패로 단정하지 않는 안내
  assert.deepEqual([uncertain.uncertain.logoutAll, uncertain.uncertain.rechecked, uncertain.uncertain.stillLoggedIn], [1, true, true])
  assert.match(uncertain.uncertain.alert, /완료 여부를 확인하지 못했어요\. 서버에서 이미 처리됐을 수 있어/)
  // 계정 관리의 일반 로그아웃은 기존 /auth/logout을 그대로 쓴다.
  assert.deepEqual([regular.regular.logout, regular.regular.logoutAll], [1, 0])
})
