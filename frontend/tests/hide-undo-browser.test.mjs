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

// 추천 숨김 직후 되돌리기를 실제 Chrome·실제 App(인증·Query·PreferenceActions)으로 확인한다. HTTP adapter만 모의 처리하며
// 모의 서버가 숨김 상태를 보관해, 되돌리기가 화면이 아니라 서버 상태(DELETE /hide)를 바꾸는지 본다.
const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find(value => value && fs.existsSync(value))
const entry = String.raw`
  import React from 'react';import {createRoot} from 'react-dom/client';import {BrowserRouter} from 'react-router-dom';import {QueryClientProvider} from '@tanstack/react-query';
  import {AxiosError} from 'axios';import {queryClient} from '/src/api/queryClient.ts';import {apiClient} from '/src/api/client.ts';import App from '/src/App.tsx';import '/src/index.css';
  document.cookie='XSRF-TOKEN=test-undo; path=/';
  // 헤드리스 가상 시간에서는 서비스 워커 조회가 끝나지 않을 수 있어(인증 확인의 푸시 정리가 멈춤) 기존 브라우저 테스트처럼 빈 목록으로 대체한다.
  navigator.serviceWorker.getRegistrations=async()=>[];
  const scenario=new URLSearchParams(location.search).get('scenario');
  const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const until=async(check,label)=>{for(let i=0;i<600;i++){if(check())return;await wait(20)}throw Error('timeout: '+label+' | '+document.body.textContent.slice(0,500))};
  const fail=(config,status)=>new AxiosError('fixture','ERR_BAD_RESPONSE',config,{},{data:{code:'COMMON_001',message:'fixture',fieldErrors:{}},status,statusText:'',headers:{},config});
  const item=id=>({opportunityId:id,type:'VOCATIONAL_TRAINING',title:'추천 훈련 '+id,category:null,subCategory:null,detailCategory:null,createdAt:null,saved:false,regions:[],
    applicationStatus:'OPEN',eligibilityStatus:'ELIGIBLE',applicationEndDate:null,trainingStartDate:'2026-12-01',recommendationReasons:{badges:[],description:'조건에 맞아요.'}});
  const hidden=new Set();const calls={hide:[],unhide:[],recommendations:0,logout:0};
  const queue={hide:[],unhide:[]};const take=name=>queue[name].length?queue[name].shift():204;
  let recommendationDelay=0;
  apiClient.defaults.adapter=async config=>{
    const ok=(data,status=200)=>({data,status,statusText:'OK',headers:{},config});
    if(config.url==='/users/me'){if(window.__slowMe)await wait(600);return ok({id:1,nickname:'테스트',email:null,role:'USER'})}
    if(config.url==='/users/me/recommendations'){calls.recommendations++;const snapshot=[101,102,103,104,105].filter(id=>!hidden.has(id)).map(item);if(recommendationDelay)await wait(recommendationDelay);return ok(snapshot)}
    if(config.url==='/users/me/eligibility-profile')return ok({birthDate:'1998-05-15',residenceRegionCode:'11680',employmentStatus:'UNEMPLOYED',educationLevel:'UNIVERSITY'});
    if(config.url==='/opportunities')return ok({content:[],page:0,size:6,totalElements:0,totalPages:0,hasNext:false});
    if(config.url==='/notifications/unread-count')return ok({available:true,unreadCount:0});
    if(config.url==='/auth/logout'){calls.logout++;return ok('',204)}
    const change=config.url.match(/^\/opportunities\/(\d+)\/hide$/);
    if(change){
      const id=Number(change[1]);const name=config.method==='post'?'hide':'unhide';calls[name].push(id);
      const status=take(name);await wait(300);
      if(status!==204)throw fail(config,status);
      if(name==='hide')hidden.add(id);else hidden.delete(id);
      return ok('',204);
    }
    return ok({});
  };
  createRoot(document.querySelector('#root')).render(<QueryClientProvider client={queryClient}><BrowserRouter><App/></BrowserRouter></QueryClientProvider>);
  const byLabel=label=>document.querySelector('[aria-label="'+label+'"]');
  const hideButton=id=>byLabel('추천 훈련 '+id+' 추천에서 숨기기');
  const undoButton=id=>byLabel('추천 훈련 '+id+' 숨김 되돌리기');
  const region=()=>document.querySelector('[role="status"][aria-live="polite"]')?.textContent??'';
  const listed=()=>Array.from(document.querySelectorAll('#recommendations li h3')).map(h=>h.textContent);
  const steps=[];const record=(label,value)=>steps.push([label,value]);
  async function basic(){
    await until(()=>hideButton(101)&&!hideButton(101).disabled,'cards');
    // 숨김 실패에는 되돌리기 안내를 띄우지 않는다.
    queue.hide=[500];hideButton(101).click();
    await until(()=>calls.hide.length===1&&!hideButton(101)?.disabled,'hide failure');await wait(800);
    record('hideFailure',{undo:!!undoButton(101),region:region(),stillListed:listed().includes('추천 훈련 101')});
    // 숨김 성공 → 안내, 카드 제거, 포커스는 추천 제목(다음 Tab이 되돌리기)
    // 창 포커스 복귀처럼 같은 사용자의 인증 재확인이 숨김과 겹쳐도 결과를 버리지 않는다.
    hideButton(101).click();window.__slowMe=true;await wait(30);queryClient.invalidateQueries({queryKey:['auth','user']});
    await until(()=>undoButton(101),'undo shown');window.__slowMe=false;
    const undo=undoButton(101);
    const toast=undo.closest('div[class*="undo"]:not([class*="undoActions"])')??undo.parentElement.parentElement;const box=toast.getBoundingClientRect();
    record('hidden',{region:region(),listed:listed(),focus:document.activeElement?.id,undoHeight:undo.getBoundingClientRect().height,
      toast:(header=>({inHeader:header.contains(toast),position:getComputedStyle(toast).position,rightGap:Math.round(header.getBoundingClientRect().right-box.right),belowCards:box.top>document.querySelector('#recommendations ul').getBoundingClientRect().top}))(document.querySelector('#recommendations h2').parentElement),
      nextFocusable:Array.from(document.querySelectorAll('#recommendations button,#recommendations a,#recommendations [tabindex]')).indexOf(undo)});
    // 되돌리기 중복 클릭 → DELETE 1회, 서버 상태 복구, 추천 재조회로 카드 복귀, 완료 안내
    const before=calls.recommendations;
    undo.click();undo.click();await wait(20);undoButton(101)?.click();
    await until(()=>!undoButton(101)&&listed().includes('추천 훈련 101'),'undone');
    record('undone',{unhide:calls.unhide,serverHidden:hidden.has(101),refetched:calls.recommendations>before,region:region(),listed:listed()});
  }
  async function timing(){
    await until(()=>hideButton(101)&&!hideButton(101).disabled,'cards');
    // 되돌리기 실패 → 오류와 재시도, 실패 상태에서는 자동으로 닫히지 않음
    hideButton(101).click();await until(()=>undoButton(101),'undo 101');
    queue.unhide=[500];undoButton(101).click();
    await until(()=>document.body.textContent.includes('숨김을 되돌리지 못했어요'),'undo failure');await wait(6000);
    record('undoFailure',{stillShown:!!undoButton(101),label:undoButton(101)?.textContent,alert:document.querySelector('[role="alert"]')?.textContent,serverHidden:hidden.has(101)});
    undoButton(101).click();
    await until(()=>!undoButton(101)&&listed().includes('추천 훈련 101'),'retry success');
    record('retry',{unhide:calls.unhide.slice(),serverHidden:hidden.has(101)});
    // 연속 숨김 → 안내는 가장 최근 숨김만 가리킨다. 되돌리기는 그 공고만 복구한다.
    hideButton(101).click();await until(()=>undoButton(101),'undo 101 again');
    await until(()=>hideButton(102)&&!hideButton(102).disabled,'102 ready');
    hideButton(102).click();await until(()=>undoButton(102),'undo 102');
    record('latestOnly',{previous:!!undoButton(101),latest:!!undoButton(102)});
    undoButton(102).click();await until(()=>!undoButton(102)&&listed().includes('추천 훈련 102'),'undo 102 done');
    record('consecutive',{serverHidden:[...hidden],lastUnhide:calls.unhide[calls.unhide.length-1]});
    // 5초가 지나면 안내만 닫히고 서버 숨김은 유지된다.
    await until(()=>hideButton(103)&&!hideButton(103).disabled,'103 ready');
    const unhideBefore=calls.unhide.length;
    hideButton(103).click();await until(()=>undoButton(103),'undo 103');
    await wait(6000);
    record('expired',{shown:!!undoButton(103),unhide:calls.unhide.length-unhideBefore,serverHidden:hidden.has(103),listed:listed().includes('추천 훈련 103')});
  }
  async function overlap(){
    await until(()=>hideButton(101)&&!hideButton(101).disabled,'cards');
    hideButton(101).click();await until(()=>undoButton(101),'undo shown');
    // 추천 재조회가 진행 중일 때 되돌리기 → 이전 조회는 취소되고 DELETE 뒤 다시 받은 목록에 카드가 돌아온다.
    recommendationDelay=800;
    queryClient.invalidateQueries({queryKey:['recommendations']});await wait(50);
    undoButton(101).click();
    await until(()=>!undoButton(101)&&listed().includes('추천 훈련 101'),'overlap undone');
    recommendationDelay=0;
    record('overlap',{unhide:calls.unhide,serverHidden:hidden.has(101),listed:listed()});
    // 계정이 바뀌거나 로그아웃되면 이전 사용자의 안내를 지운다. 서버 숨김은 그대로 둔다.
    // 헤드리스 가상 시간에서는 로그아웃 전 푸시 정리(서비스 워커 조회)가 끝나지 않아, 인증 Query 결과를 직접 바꿔 전환을 재현한다.
    await until(()=>hideButton(102)&&!hideButton(102).disabled,'102 ready');
    hideButton(102).click();await until(()=>undoButton(102),'undo 102');
    queryClient.setQueryData(['auth','user'],{id:2,nickname:'다른 사용자',email:null,role:'USER'});
    await until(()=>!undoButton(102),'account switch');await wait(300);
    record('switched',{shown:!!undoButton(102),region:region()});
    queryClient.setQueryData(['auth','user'],null);await wait(300);
    record('logout',{shown:!!undoButton(102),region:region(),unhide:calls.unhide.length,serverHidden:hidden.has(102)});
  }
  (async()=>{
    try{
      await (scenario==='timing'?timing():scenario==='overlap'?overlap():basic());
      document.querySelector('#result').textContent=JSON.stringify(Object.fromEntries(steps));
    }catch(error){document.querySelector('#result').textContent=JSON.stringify({error:String(error).slice(0,300),calls,steps:Object.fromEntries(steps)})}
  })();
`
async function run(scenarios) {
  const cache = path.join(root, 'node_modules', '.cache'); fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'hide-undo-browser-'))
  const server = await createServer({ root, envDir: false, configFile: false, envPrefix: 'TEST_UNUSED_', cacheDir: path.join(temporary, 'vite'), logLevel: 'error', plugins: [react(), {
    name: 'test-only-hide-undo-page', resolveId: id => id === '/__test__/hide-undo-entry.tsx' ? '\0hide-undo-entry.tsx' : undefined,
    load: id => id === '\0hide-undo-entry.tsx' ? ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText : undefined,
    configureServer(server) { server.middlewares.use((request, response, next) => {
      if (!request.url.startsWith('/?scenario=')) return next()
      void server.transformIndexHtml('/', '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><div id="root"></div><pre id="result"></pre><script type="module" src="/__test__/hide-undo-entry.tsx"></script></body></html>').then(html => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen(); const port = server.httpServer.address().port
    await fetch(`http://127.0.0.1:${port}/__test__/hide-undo-entry.tsx`)
    const results = []
    for (const [index, scenario] of scenarios.entries()) {
      // 모바일 폭(390px)에서도 동작과 터치 크기를 함께 본다.
      const { stdout } = await promisify(execFile)(browser, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, `browser-${index}`)}`, '--window-size=390,1200', '--virtual-time-budget=90000', '--dump-dom', `http://127.0.0.1:${port}/?scenario=${scenario}`], { windowsHide: true, timeout: 120_000, maxBuffer: 5_000_000 })
      const result = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
      assert.ok(result, `실제 브라우저 결과가 필요하다: ${scenario}`)
      results.push(JSON.parse(result[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')))
    }
    return results
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
}

test('Chrome offers undo only after a confirmed hide and restores the server hidden state with one DELETE', { skip: !browser && 'Chrome/Edge가 없어 숨김 되돌리기의 브라우저 검증은 실행하지 않았다.' }, async () => {
  const [basic, timing, overlap] = await run(['basic', 'timing', 'overlap'])
  for (const result of [basic, timing, overlap]) assert.equal(result.error, undefined, JSON.stringify(result))
  // 숨김 실패에는 안내가 없고 카드도 그대로다.
  assert.deepEqual([basic.hideFailure.undo, basic.hideFailure.stillListed], [false, true])
  assert.doesNotMatch(basic.hideFailure.region, /공고를 숨겼어요/)
  // 숨김 성공: 안내, 카드 제거(최대 3개 유지), 포커스는 추천 제목이고 다음 포커스 대상이 되돌리기, 터치 크기 44px 이상
  // 화면에는 짧은 문구와 되돌리기만 둔다(공고 제목 없음).
  assert.equal(basic.hidden.region.trim(), '공고를 숨겼어요.되돌리기')
  // 안내는 개인화 추천 제목 줄 안 오른쪽 끝에 붙고, 카드 목록보다 위에 있다(화면 고정 아님).
  assert.deepEqual([basic.hidden.toast.inHeader, basic.hidden.toast.position, basic.hidden.toast.belowCards], [true, 'static', false], JSON.stringify(basic.hidden.toast))
  assert.ok(Math.abs(basic.hidden.toast.rightGap) <= 1, JSON.stringify(basic.hidden.toast))
  assert.deepEqual(basic.hidden.listed, ['추천 훈련 102', '추천 훈련 103', '추천 훈련 104'])
  assert.equal(basic.hidden.focus, 'recommendations-heading')
  assert.equal(basic.hidden.nextFocusable, 1)
  assert.ok(basic.hidden.undoHeight >= 44, String(basic.hidden.undoHeight))
  // 되돌리기: 중복 클릭에도 DELETE 1회, 서버 숨김 해제, 추천 재조회로 카드 복귀
  assert.deepEqual(basic.undone.unhide, [101])
  assert.equal(basic.undone.serverHidden, false)
  assert.equal(basic.undone.refetched, true)
  assert.match(basic.undone.region, /숨김을 되돌렸어요/)
  assert.deepEqual(basic.undone.listed, ['추천 훈련 101', '추천 훈련 102', '추천 훈련 103'])
  // 되돌리기 실패: 오류와 재시도, 실패 상태는 자동으로 닫히지 않고 서버 숨김도 그대로
  assert.deepEqual([timing.undoFailure.stillShown, timing.undoFailure.label, timing.undoFailure.serverHidden], [true, '다시 시도', true])
  assert.match(timing.undoFailure.alert, /숨김을 되돌리지 못했어요/)
  assert.deepEqual([timing.retry.unhide, timing.retry.serverHidden], [[101, 101], false])
  // 연속 숨김: 안내는 가장 최근 것만, 되돌리기는 그 공고만 복구한다.
  assert.deepEqual([timing.latestOnly.previous, timing.latestOnly.latest], [false, true])
  assert.deepEqual([timing.consecutive.serverHidden, timing.consecutive.lastUnhide], [[101], 102])
  // 5초 경과: 안내만 닫히고 서버 숨김은 유지, DELETE 없음
  assert.deepEqual([timing.expired.shown, timing.expired.unhide, timing.expired.serverHidden, timing.expired.listed], [false, 0, true, false])
  // 재조회와 겹쳐도 DELETE 1회 뒤 카드가 돌아온다.
  assert.deepEqual([overlap.overlap.unhide, overlap.overlap.serverHidden], [[101], false])
  assert.ok(overlap.overlap.listed.includes('추천 훈련 101'))
  // 계정 전환·로그아웃 상태에서는 이전 사용자의 안내를 지우고 서버 숨김은 건드리지 않는다.
  assert.equal(overlap.switched.shown, false)
  assert.doesNotMatch(overlap.switched.region, /공고를 숨겼어요/)
  assert.deepEqual([overlap.logout.shown, overlap.logout.unhide, overlap.logout.serverHidden], [false, 1, true])
  assert.doesNotMatch(overlap.logout.region, /공고를 숨겼어요/)
})
