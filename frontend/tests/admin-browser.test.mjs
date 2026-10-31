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

// 관리자 실행 결과 불확실 처리와 수집 이력 자동 갱신을 실제 Chrome에서 확인한다. 모든 HTTP는 모의 응답이며 실제 보관 실행·수집 API는 호출하지 않는다.
const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find(value => value && fs.existsSync(value))
const entry = String.raw`
  import React from 'react';import {createRoot} from 'react-dom/client';import {BrowserRouter} from 'react-router-dom';import {QueryClientProvider} from '@tanstack/react-query';
  import {AxiosError} from 'axios';import {queryClient} from '/src/api/queryClient.ts';import {apiClient} from '/src/api/client.ts';import App from '/src/App.tsx';import '/src/index.css';
  document.cookie='XSRF-TOKEN=test-admin; path=/';
  // 헤드리스 가상 시간에서는 서비스 워커 조회가 끝나지 않을 수 있어(인증 확인의 푸시 정리가 멈춤) 기존 브라우저 테스트처럼 빈 목록으로 대체한다.
  navigator.serviceWorker.getRegistrations=async()=>[];
  const scenario=new URLSearchParams(location.search).get('scenario');
  const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const until=async(check,label)=>{for(let i=0;i<600;i++){if(check())return;await wait(20)}throw Error('timeout: '+label+' | '+document.body.textContent.slice(0,600))};
  const fail=(config,status,code)=>new AxiosError('fixture','ERR_BAD_RESPONSE',config,{},{data:{code,message:'fixture',fieldErrors:{}},status,statusText:'',headers:{},config});
  const plan=token=>({status:'READY',policy:{retainedMonths:4,futureMonths:12,schedule:'매월 1일 03:00 (Asia/Seoul)',schedulerEnabled:false,schedulerDryRun:true,adminExecuteEnabled:true},
    baseDate:'2026-10-31',keepFrom:'2026-07-01',toDrop:[{name:'user_behavior_events_2026_06',from:'2026-06-01T00:00:00',to:'2026-07-01T00:00:00',estimatedRows:1200,totalBytes:2048}],toCreate:[],ignored:[],defaultPartitionHasRows:false,planToken:token});
  const job=status=>({jobId:7,source:'YOUTH_POLICY',status,startedAt:'2026-10-31T01:00:00+09:00',completedAt:null,totalCount:null,fetchedCount:1,successCount:1,failedRecordCount:0,pendingCount:0,failedPageCount:0,newCount:0,updatedCount:0,unchangedCount:0,pageFailures:[],employment24PageFailures:[],incompleteNcsCodes:[],recordFailures:[],failureReason:null});
  // 요청 순서대로 응답을 꺼낸다. 마지막 응답은 이후에도 반복한다.
  const queue={preview:[],execute:[],syncs:[],sync:[]};const calls={preview:0,execute:0,syncs:0,sync:0,executeTimeout:[]};
  const take=(name,config)=>{const list=queue[name];const item=list.length>1?list.shift():list[0];return typeof item==='function'?item(config):item};
  apiClient.defaults.adapter=async config=>{
    const ok=(data,status=200)=>({data,status,statusText:'OK',headers:{},config});
    if(config.url==='/users/me')return ok({id:1,nickname:'운영자',email:null,role:'ADMIN'});
    if(config.url==='/notifications/unread-count')return ok({available:true,unreadCount:0});
    if(config.url==='/admin/behavior-events/retention/preview'){calls.preview++;const item=take('preview',config);if(item instanceof Error)throw item;return ok(item)}
    if(config.url==='/admin/behavior-events/retention/execute'){calls.execute++;calls.executeTimeout.push(config.timeout);const item=take('execute',config);if(item instanceof Error)throw item;return ok(item)}
    if(config.url==='/admin/collection/syncs'){calls.syncs++;return ok(take('syncs',config))}
    if(config.url.startsWith('/admin/collection/syncs/')){calls.sync++;return ok(take('sync',config))}
    return ok({});
  };
  createRoot(document.querySelector('#root')).render(<QueryClientProvider client={queryClient}><BrowserRouter><App/></BrowserRouter></QueryClientProvider>);
  const button=text=>Array.from(document.querySelectorAll('button')).find(item=>item.textContent.trim()===text);
  const box=()=>document.querySelector('input[type="checkbox"]');
  const text=()=>document.body.textContent;
  const uncertainPanel=()=>document.querySelector('[aria-labelledby="retention-uncertain-title"]')?.textContent??'';
  const state=()=>({checked:!!box()?.checked,boxDisabled:!!box()?.disabled,executeDisabled:!!button('보관 정리 실행')?.disabled});
  const steps=[];const record=(label,value)=>steps.push([label,value]);
  async function retention(){
    const timeout=new AxiosError('timeout of 60000ms exceeded','ECONNABORTED');
    // 공통 Query 설정이 5xx 조회를 한 번 자동 재시도하므로, 재조회 실패 상태를 만들려면 미리보기 500을 두 번 둔다.
    const previewError=config=>fail(config,500,'COMMON_001');
    queue.preview=[plan('plan-1'),previewError,previewError,plan('plan-2'),plan('plan-3'),plan('plan-4'),plan('plan-5')];
    queue.execute=[timeout,config=>fail(config,500,'COMMON_001'),config=>fail(config,409,'RETENTION_003')];
    await until(()=>box()&&!box().disabled,'preview');
    box().click();await wait(30);
    button('보관 정리 실행').click();
    // 시간 초과 → 결과 불확실 안내, 확인 체크 초기화, 미리보기 재조회(실패) 동안 실행 차단
    await until(()=>uncertainPanel()&&calls.preview===3&&text().includes('보관 정리 미리보기를 불러오지 못했어요'),'uncertain after timeout');
    await wait(200);
    record('timeout',{executeTimeout:calls.executeTimeout[0],panel:uncertainPanel(),previewCalls:calls.preview,...state()});
    // 자동 재전송 없음
    await wait(3000);
    record('noAutoRetry',{execute:calls.execute});
    // 최신 미리보기 성공 전에는 막혀 있다가, 새 planToken을 받은 뒤에만 확인할 수 있다.
    button('다시 불러오기').click();
    await until(()=>calls.preview===4&&box()&&!box().disabled,'fresh preview');
    record('afterFresh',{...state(),panel:uncertainPanel()});
    // 500 → 결과 불확실(재시도 유도 문구 없음), 체크 초기화
    box().click();await wait(30);button('보관 정리 실행').click();
    await until(()=>calls.execute===2&&uncertainPanel().includes('HTTP 500')&&calls.preview===5,'500');
    await until(()=>box()&&!box().disabled,'fresh after 500');
    record('http500',{panel:uncertainPanel(),...state()});
    // 409 → 미리보기 자동 갱신과 체크 초기화, 계획 변경 안내(결과 불확실 안내 아님)
    box().click();await wait(30);button('보관 정리 실행').click();
    await until(()=>calls.execute===3&&calls.preview===6&&box()&&!box().disabled,'409');
    record('conflict',{changed:text().includes('미리보기 이후 보관 기준이나 정리 대상이 바뀌었어요'),uncertain:!!uncertainPanel(),...state()});
    // 미리보기가 새 계획으로 바뀌면 확인 체크가 초기화된다.
    box().click();await wait(30);const before=box().checked;
    button('다시 불러오기').click();
    await until(()=>calls.preview===7&&box()&&!box().disabled,'changed preview');
    record('previewChanged',{before,after:box().checked});
    record('totalExecute',calls.execute);
  }
  async function polling(mode){
    queue.syncs=mode==='running'?[[job('RUNNING')],[job('RUNNING')],[job('SUCCESS')]]:[[job('SUCCESS')]];
    await until(()=>document.querySelector('[aria-label="수집 #7 상세 보기"]'),'list');
    await wait(45000);
    record('syncs',calls.syncs);record('sync',calls.sync);
  }
  (async()=>{
    try{
      if(scenario==='retention')await retention();else await polling(scenario);
      document.querySelector('#result').textContent=JSON.stringify(Object.fromEntries(steps));
    }catch(error){document.querySelector('#result').textContent=JSON.stringify({error:String(error).slice(0,200),calls,state:state(),panel:uncertainPanel(),steps:Object.fromEntries(steps)})}
  })();
`
async function run(paths) {
  const cache = path.join(root, 'node_modules', '.cache'); fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'admin-browser-'))
  const server = await createServer({ root, envDir: false, configFile: false, envPrefix: 'TEST_UNUSED_', cacheDir: path.join(temporary, 'vite'), logLevel: 'error', plugins: [react(), {
    name: 'test-only-admin-page', resolveId: id => id === '/__test__/admin-entry.tsx' ? '\0admin-entry.tsx' : undefined,
    load: id => id === '\0admin-entry.tsx' ? ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText : undefined,
    configureServer(server) { server.middlewares.use((request, response, next) => {
      if (!request.url.startsWith('/admin')) return next()
      void server.transformIndexHtml(request.url, '<!doctype html><html lang="ko"><head><meta charset="utf-8"></head><body><div id="root"></div><pre id="result"></pre><script type="module" src="/__test__/admin-entry.tsx"></script></body></html>').then(html => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen(); const port = server.httpServer.address().port
    await fetch(`http://127.0.0.1:${port}/__test__/admin-entry.tsx`)
    const results = []
    for (const [index, url] of paths.entries()) {
      const { stdout } = await promisify(execFile)(browser, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, `browser-${index}`)}`, '--virtual-time-budget=90000', '--dump-dom', `http://127.0.0.1:${port}${url}`], { windowsHide: true, timeout: 120_000, maxBuffer: 5_000_000 })
      const result = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
      assert.ok(result, `실제 브라우저 결과가 필요하다: ${url}`)
      results.push(JSON.parse(result[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')))
    }
    return results
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
}

test('Chrome keeps uncertain retention executions blocked until a fresh preview, resets confirmation and never re-sends automatically', { skip: !browser && 'Chrome/Edge가 없어 관리자 실행 결과 불확실 처리의 브라우저 검증은 실행하지 않았다.' }, async () => {
  const [result] = await run(['/admin/retention?scenario=retention'])
  assert.equal(result.error, undefined, JSON.stringify(result))
  // 실행 요청만 60초 제한, 결과 불확실 안내, 확인 체크 초기화, 미리보기 재조회 실패 동안 실행 차단
  assert.equal(result.timeout.executeTimeout, 60000)
  assert.match(result.timeout.panel, /실행 결과를 확인하지 못했어요/)
  assert.match(result.timeout.panel, /일부 정리가 이미 진행됐을 수 있어요/)
  assert.doesNotMatch(result.timeout.panel, /다시 시도해 주세요/)
  assert.match(result.timeout.panel, /최신 미리보기를 확인하기 전까지는 다시 실행할 수 없어요/)
  assert.deepEqual([result.timeout.checked, result.timeout.boxDisabled, result.timeout.executeDisabled], [false, true, true])
  assert.equal(result.timeout.previewCalls, 3)
  assert.equal(result.noAutoRetry.execute, 1)
  // 새 planToken을 성공적으로 받은 뒤에만 확인할 수 있고, 확인 전에는 실행 버튼이 꺼져 있다.
  assert.deepEqual([result.afterFresh.checked, result.afterFresh.boxDisabled, result.afterFresh.executeDisabled], [false, false, true])
  assert.match(result.afterFresh.panel, /최신 미리보기를 확인했어요/)
  // 500도 결과 불확실로 다루며 재시도를 유도하지 않는다.
  assert.match(result.http500.panel, /서버 오류\(HTTP 500\)/)
  assert.doesNotMatch(result.http500.panel, /다시 시도해 주세요/)
  assert.equal(result.http500.checked, false)
  // 409는 계획 변경 안내와 미리보기 자동 갱신, 결과 불확실 안내는 지운다.
  assert.deepEqual([result.conflict.changed, result.conflict.uncertain, result.conflict.checked], [true, false, false])
  // 미리보기가 새 계획으로 바뀌면 확인 체크가 풀린다.
  assert.deepEqual([result.previewChanged.before, result.previewChanged.after], [true, false])
  assert.equal(result.totalExecute, 3)
})

test('Chrome polls the collection history only while a job is pending or running', { skip: !browser && 'Chrome/Edge가 없어 수집 이력 자동 갱신의 브라우저 검증은 실행하지 않았다.' }, async () => {
  const [running, done] = await run(['/admin/collection?date=2026-10-31&scenario=running', '/admin/collection?date=2026-10-31&scenario=done'])
  assert.equal(running.error, undefined, JSON.stringify(running))
  // 실행 중 2번 → 완료 응답 이후 멈춘다(45초 동안 10초 주기여도 3번).
  assert.equal(running.syncs, 3)
  assert.equal(done.error, undefined, JSON.stringify(done))
  assert.equal(done.syncs, 1)
  assert.equal(done.sync, 0)
})
