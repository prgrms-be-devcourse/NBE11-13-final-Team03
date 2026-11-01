import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { test } from 'node:test'
import { createServer } from 'vite'
import { chromeServiceFlags } from './chrome-flags.mjs'
import react from '@vitejs/plugin-react'
import ts from 'typescript'
import { user, options } from './profile-fixtures.mjs'
import { policy } from './detail-fixtures.mjs'
import { latestItem, policyRecommendation } from './home-fixtures.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find((value) => value && fs.existsSync(value))
test('Chrome records real recommendation impressions, detail views and external link clicks once per action with sources under StrictMode and mocked HTTP', { skip: !browser && 'Chrome/Edge가 없어 브라우저 동작을 검증하지 않았다.' }, async () => {
  const cache = path.join(root, 'node_modules', '.cache'); fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'behavior-browser-'))
  const entry = `
    import React,{StrictMode} from 'react';import {createRoot} from 'react-dom/client';import {MemoryRouter} from 'react-router-dom';
    import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {AxiosError} from 'axios';
    import {apiClient} from '/src/api/client.ts';import AppRoutes from '/src/routes/AppRoutes.tsx';import '/src/index.css';
    const user=${JSON.stringify(user)}, options=${JSON.stringify(options)}, policy=${JSON.stringify(policy)}, latest=${JSON.stringify(latestItem)}, recommendation=${JSON.stringify(policyRecommendation)};
    const recommendationIds=[201,202,203,204,205],saved=new Set([301]),hidden=new Set(),calls=[],events=[],opened=[];let loggedIn=true,eventFailure=null;
    document.cookie='XSRF-TOKEN=test-behavior-browser; Path=/';
    const response=(config,data,status=200)=>({config,data,status,statusText:'',headers:{}});
    const fail=(config,status,code='TEST_001')=>{throw new AxiosError('fixture','ERR_BAD_RESPONSE',config,{},response(config,{code,message:'fixture',fieldErrors:{}},status))};
    apiClient.defaults.adapter=async config=>{
      const call={url:config.url,method:config.method,params:config.params};calls.push(call);
      if(config.url==='/users/me'){if(loggedIn)return response(config,user);fail(config,401,'AUTH001')}
      if(config.url==='/auth/logout'){loggedIn=false;return response(config,'',204)}
      if(config.url==='/interests/options')return response(config,options);
      const event=config.url.match(/^\\/opportunities\\/(\\d+)\\/events$/);
      if(event){
        if(config.method!=='post'||config.requiresAuth!==true||config.headers.get('X-XSRF-TOKEN')!=='test-behavior-browser')throw Error('event contract');
        events.push({id:Number(event[1]),...JSON.parse(config.data),loggedIn});
        if(eventFailure==='network')throw new AxiosError('network','ERR_NETWORK',config);
        if(eventFailure)fail(config,eventFailure);
        return response(config,'',204);
      }
      const change=config.url.match(/^\\/opportunities\\/(\\d+)\\/(save|hide)$/);
      if(change){const id=Number(change[1]),state=change[2]==='save'?saved:hidden;if(config.method==='post')state.add(id);else state.delete(id);return response(config,'',204)}
      const detail=config.url.match(/^\\/opportunities\\/(\\d+)$/);
      if(detail){const id=Number(detail[1]);return response(config,{...policy,opportunityId:id,title:'행동 상세 '+id,saved:loggedIn&&saved.has(id)})}
      if(config.url==='/opportunities'){const size=Number(config.params?.get?.('size')||20);return response(config,{content:[{...latest,opportunityId:42,title:'공개 공고 42',saved:loggedIn&&saved.has(42)}],page:0,size,totalElements:1,totalPages:1,hasNext:false})}
      if(config.url==='/users/me/saved-opportunities'){const ids=[...saved].sort((a,b)=>a-b);return response(config,{content:ids.map(id=>({opportunityId:id,type:'YOUTH_POLICY',title:'저장 공고 '+id,category:'일자리',subCategory:null,savedAt:'2026-10-26T09:00:00'})),page:0,size:20,totalElements:ids.length,totalPages:1,hasNext:false})}
      if(config.url==='/users/me/recommendations')return response(config,recommendationIds.filter(id=>!hidden.has(id)).map(id=>({...recommendation,opportunityId:id,title:'추천 공고 '+id,saved:saved.has(id)})));
      throw new AxiosError('unexpected fixture endpoint '+config.url,'ERR_BAD_RESPONSE',config,{},response(config,{},404));
    };
    // 헤드리스 가상 시간에서는 실제 IntersectionObserver 알림이 렌더링 프레임에 따라 불규칙하다.
    // 관찰 대상·관찰 해제만 기록하는 대역으로 바꾸고 표시 비율은 테스트가 직접 알린다. 50%·1초 판정 규칙은 단위 테스트가 검증한다.
    const observers=new Set();
    window.IntersectionObserver=class{constructor(callback,options){this.callback=callback;this.options=options;this.targets=new Set()}observe(target){this.targets.add(target);observers.add(this)}disconnect(){this.targets.clear();observers.delete(this)}};
    const show=(target,ratio)=>{for(const observer of [...observers])if(observer.targets.has(target))observer.callback([{target,isIntersecting:ratio>0,intersectionRatio:ratio}])};
    const observed=()=>[...observers].flatMap(observer=>[...observer.targets]);
    // React의 루트 리스너가 먼저 실행된 뒤 기본 동작이 막혔는지 기록하고, 테스트 중 실제 새 탭은 열지 않는다.
    document.addEventListener('click',event=>{const link=event.target.closest?.('a[target=_blank]');if(link){opened.push({text:link.textContent,prevented:event.defaultPrevented});event.preventDefault()}});
    const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});client.setQueryData(['auth','user'],user);
    const root=createRoot(document.querySelector('#root'));
    root.render(<StrictMode><QueryClientProvider client={client}><MemoryRouter initialEntries={['/']}><AppRoutes/></MemoryRouter></QueryClientProvider></StrictMode>);
    const ensure=(value,message)=>{if(!value)throw Error(message+' | '+JSON.stringify(events))};
    const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    const wait=async predicate=>{for(let i=0;i<1500;i++){if(predicate())return;await sleep(10)}throw Error('UI timeout: '+predicate.toString()+' | '+document.querySelector('main')?.textContent.slice(0,400)+' | '+JSON.stringify(calls.slice(-6)))};
    const button=text=>Array.from(document.querySelectorAll('button')).find(element=>element.textContent.trim()===text);
    const action=label=>Array.from(document.querySelectorAll('button')).find(element=>element.getAttribute('aria-label')===label);
    const menu=text=>Array.from(document.querySelectorAll('header nav a')).find(element=>element.textContent===text);
    const link=text=>Array.from(document.querySelectorAll('main a')).find(element=>element.textContent.startsWith(text));
    const cards=()=>Array.from(document.querySelectorAll('#recommendations h3 a')).map(element=>element.closest('li'));
    const of=type=>events.filter(event=>event.eventType===type);
    const ids=list=>list.map(event=>event.id).sort((a,b)=>a-b).join();
    const ready=label=>action(label)&&!action(label).disabled;
    const viewAll=async()=>{for(const card of cards())show(card,1);await sleep(1300)};
    try{
      await wait(()=>cards().length===3);
      ensure(!link('추천 공고 204')&&!link('추천 공고 205'),'only three cards are rendered');
      ensure(observed().length===3&&cards().every(card=>observed().includes(card)),'only rendered cards are observed once after StrictMode effects');
      ensure(observers.size===3&&[...observers].every(observer=>observer.options.threshold===0.5),'50% threshold');
      await sleep(1500);ensure(of('IMPRESSION').length===0,'off-screen cards are not impressions');
      show(cards()[0],0.3);await sleep(1500);ensure(of('IMPRESSION').length===0,'less than half visible is not an impression');
      show(cards()[0],0.6);await sleep(500);show(cards()[0],0);await sleep(1500);
      ensure(of('IMPRESSION').length===0,'visible under one second is not an impression');
      await viewAll();
      ensure(ids(of('IMPRESSION'))==='201,202,203','one impression per rendered card');
      ensure(of('IMPRESSION').every(event=>event.source==='RECOMMENDATION'),'impression source');
      await client.invalidateQueries({queryKey:['recommendations']});await viewAll();
      action('추천 공고 201 저장').click();await wait(()=>ready('추천 공고 201 저장 해제'));await viewAll();
      ensure(of('IMPRESSION').length===3&&observed().length===0,'refetch, re-render and saved state refresh do not repeat impressions');

      link('추천 공고 202').click();await wait(()=>document.querySelector('#detail-title')?.textContent==='행동 상세 202');await sleep(300);
      ensure(of('DETAIL_VIEW').length===1&&of('DETAIL_VIEW')[0].id===202&&of('DETAIL_VIEW')[0].source==='RECOMMENDATION','recommendation detail view');
      await client.invalidateQueries({queryKey:['opportunity-detail','202']});await wait(()=>ready('행동 상세 202 저장하기'));await sleep(300);
      ensure(of('DETAIL_VIEW').length===1,'detail refetch does not repeat the view');
      for(const [failure,text] of [[500,'정책 신청 페이지'],['network','정책 신청 페이지'],[403,'원문 공고 확인'],[401,'참고 자료 1']]){eventFailure=failure;link(text).click();await sleep(200)}
      eventFailure=404;link('원문 공고 확인').dispatchEvent(new MouseEvent('auxclick',{button:1,bubbles:true}));await sleep(200);eventFailure=null;
      ensure(of('EXTERNAL_LINK_CLICK').map(event=>event.linkType).join()==='APPLICATION,APPLICATION,DETAIL,REFERENCE,DETAIL','external link types and repeated clicks');
      ensure(of('EXTERNAL_LINK_CLICK').every(event=>event.id===202&&event.source==='RECOMMENDATION'),'external link source');
      ensure(opened.length===4&&opened.every(item=>!item.prevented),'event recording never blocks opening the link');
      action('행동 상세 202 저장하기').click();await wait(()=>ready('행동 상세 202 저장됨, 저장 해제'));
      ensure(calls.some(call=>call.url==='/opportunities/202/save'&&call.method==='post'&&call.params.source==='RECOMMENDATION'),'detail save keeps entry source');
      ensure(document.querySelector('#detail-title')?.textContent==='행동 상세 202','event failures keep the detail page');

      menu('홈').click();await wait(()=>cards().length===3);await viewAll();
      ensure(ids(of('IMPRESSION'))==='201,201,202,202,203,203','returning home is a new impression entry');
      action('추천 공고 203 추천에서 숨기기').click();await wait(()=>!link('추천 공고 203')&&cards().length===3);
      ensure(calls.filter(call=>call.url==='/opportunities/203/hide').length===1&&calls.some(call=>call.url==='/opportunities/203/hide'&&call.params.source==='RECOMMENDATION'),'hide stays a single server-recorded change');
      await viewAll();ensure(ids(of('IMPRESSION'))==='201,201,202,202,203,203,204','a card first rendered after hiding is a new impression once');
      link('공개 공고 42').click();await wait(()=>document.querySelector('#detail-title')?.textContent==='행동 상세 42');await sleep(300);
      ensure(of('DETAIL_VIEW').at(-1).id===42&&of('DETAIL_VIEW').at(-1).source==='OTHER','public home card is OTHER');

      menu('기회 탐색').click();await wait(()=>document.querySelector('#search-title')&&link('공개 공고 42'));
      eventFailure=500;link('공개 공고 42').click();await wait(()=>ready('행동 상세 42 저장하기'));await sleep(300);eventFailure=null;
      ensure(of('DETAIL_VIEW').filter(event=>event.id===42).map(event=>event.source).join()==='OTHER,SEARCH','search detail view after a failed event');
      action('행동 상세 42 저장하기').click();await wait(()=>ready('행동 상세 42 저장됨, 저장 해제'));
      ensure(calls.some(call=>call.url==='/opportunities/42/save'&&call.method==='post'&&call.params.source==='SEARCH'),'detail save from search');

      menu('저장한 기회').click();await wait(()=>link('저장 공고 301'));
      link('저장 공고 301').click();await wait(()=>document.querySelector('#detail-title')?.textContent==='행동 상세 301');await sleep(300);
      ensure(of('DETAIL_VIEW').at(-1).id===301&&of('DETAIL_VIEW').at(-1).source==='SAVED','saved list detail view');
      ensure(of('DETAIL_VIEW').length===4,'one view per detail entry');

      // 로그아웃 전환 중 미전송은 단위 테스트가 검증한다. 여기서는 푸시 정리 모듈 로드 없이 확인된 비로그인 상태로 바꾼다.
      loggedIn=false;client.setQueryData(['auth','user'],null);await wait(()=>Array.from(document.querySelectorAll('header a')).some(a=>a.textContent==='로그인'));
      const beforeAnonymous=events.length;
      menu('홈').click();await wait(()=>document.querySelector('#home-title'));ensure(observed().length===0,'anonymous home observes no recommendation');
      menu('기회 탐색').click();await wait(()=>link('공개 공고 42'));link('공개 공고 42').click();
      await wait(()=>document.querySelector('#detail-title')?.textContent==='행동 상세 42');link('정책 신청 페이지').click();await sleep(1500);
      ensure(events.length===beforeAnonymous,'anonymous users send no behavior events');
      ensure(opened.length===5&&!opened.at(-1).prevented,'anonymous link keeps default behavior');
      ensure(events.every(event=>event.loggedIn&&['IMPRESSION','DETAIL_VIEW','EXTERNAL_LINK_CLICK'].includes(event.eventType)),'only signed-in client events');
      document.querySelector('#result').textContent=JSON.stringify({ok:true,impressions:of('IMPRESSION').length,views:of('DETAIL_VIEW').length,clicks:of('EXTERNAL_LINK_CLICK').length});
    }catch(error){document.querySelector('#result').textContent=JSON.stringify({ok:false,error:String(error)})}
    finally{root.unmount();client.clear()}
  `
  const server = await createServer({ root, cacheDir: path.join(temporary, 'vite'), configFile: false, plugins: [react(), {
    name: 'test-only-behavior-page',
    resolveId(id) { if (id === '/__test__/behavior-entry.tsx') return '\0behavior-entry.tsx' },
    load(id) { if (id === '\0behavior-entry.tsx') return ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText },
    configureServer(server) { server.middlewares.use((request, response, next) => {
      if (request.url !== '/__test__/behavior') return next()
      void server.transformIndexHtml('/__test__/behavior', '<!doctype html><html lang="ko"><head><meta charset="utf-8"></head><body><div id="root"></div><pre id="result"></pre><script type="module" src="/__test__/behavior-entry.tsx"></script></body></html>').then((html) => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen(); const address = server.httpServer.address()
    await fetch(`http://127.0.0.1:${address.port}/__test__/behavior-entry.tsx`)
    const { stdout } = await promisify(execFile)(browser, ['--headless', ...chromeServiceFlags, '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, 'browser')}`, '--virtual-time-budget=90000', '--dump-dom', `http://127.0.0.1:${address.port}/__test__/behavior`], { windowsHide: true, timeout: 120_000, maxBuffer: 5_000_000 })
    const result = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
    assert.ok(result, '브라우저 시나리오 결과가 필요하다.')
    assert.deepEqual(JSON.parse(result[1]), { ok: true, impressions: 7, views: 4, clicks: 5 })
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
})
