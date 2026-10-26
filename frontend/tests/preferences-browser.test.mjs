import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { test } from 'node:test'
import { createServer } from 'vite'
import react from '@vitejs/plugin-react'
import ts from 'typescript'
import { user, options } from './profile-fixtures.mjs'
import { policy } from './detail-fixtures.mjs'
import { latestItem, policyRecommendation } from './home-fixtures.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find((value) => value && fs.existsSync(value))
test('Chrome verifies saved paging/removal, detail and search save changes, recommendation hide and anonymous return with mocked HTTP', { skip: !browser && 'Chrome/Edge가 없어 브라우저 동작을 검증하지 않았다.' }, async () => {
  const cache = path.join(root, 'node_modules', '.cache'); fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'preferences-browser-'))
  const entry = `
    import React from 'react';import {createRoot} from 'react-dom/client';import {MemoryRouter} from 'react-router-dom';
    import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {AxiosError} from 'axios';
    import {apiClient} from '/src/api/client.ts';import AppRoutes from '/src/routes/AppRoutes.tsx';import '/src/index.css';
    const user=${JSON.stringify(user)}, options=${JSON.stringify(options)}, policy=${JSON.stringify(policy)}, latest=${JSON.stringify(latestItem)}, recommendation=${JSON.stringify(policyRecommendation)};
    const saved=new Set(Array.from({length:21},(_,index)=>index+42)),hidden=new Set(),calls=[];let loggedIn=true;
    document.cookie='XSRF-TOKEN=test-preference-browser; Path=/';
    const response=(config,data,status=200)=>({config,data,status,statusText:'',headers:{}});
    apiClient.defaults.adapter=async config=>{
      const call={url:config.url,method:config.method,params:config.params};calls.push(call);
      if(config.url==='/users/me'){if(loggedIn)return response(config,user);throw new AxiosError('fixture','ERR_BAD_RESPONSE',config,{},response(config,{code:'AUTH001',message:'fixture',fieldErrors:{}},401))}
      if(config.url==='/auth/logout'){loggedIn=false;return response(config,'',204)}
      if(config.url==='/interests/options')return response(config,options);
      if(config.url==='/users/me/saved-opportunities'){
        const page=config.params.page,size=config.params.size,ids=Array.from(saved).sort((a,b)=>a-b);
        return response(config,{content:ids.slice(page*size,(page+1)*size).map(id=>({opportunityId:id,type:'YOUTH_POLICY',title:'테스트 저장 공고 '+id,category:'일자리',subCategory:null,savedAt:'2026-10-26T09:00:00'})),page,size,totalElements:ids.length,totalPages:Math.ceil(ids.length/size),hasNext:(page+1)*size<ids.length});
      }
      const change=config.url.match(/^\\/opportunities\\/(\\d+)\\/(save|hide)$/);
      if(change){
        if(config.headers.get('X-XSRF-TOKEN')!=='test-preference-browser'||config.requiresAuth!==true)throw Error('auth/CSRF contract');
        await new Promise(resolve=>setTimeout(resolve,20));const state=change[2]==='save'?saved:hidden,id=Number(change[1]);
        if(config.method==='post')state.add(id);else state.delete(id);
        call.appliedSaved=saved.has(id);call.appliedHidden=hidden.has(id);
        return response(config,'',204);
      }
      if(config.url==='/opportunities/42')return response(config,{...policy,saved:loggedIn&&saved.has(42)});
      if(config.url==='/opportunities'){
        call.returnedSaved=loggedIn&&saved.has(42);
        const size=Number(config.params?.get?.('size')||20);
        return response(config,{content:[{...latest,opportunityId:42,saved:loggedIn&&saved.has(42)}],page:0,size,totalElements:1,totalPages:1,hasNext:false});
      }
      if(config.url==='/users/me/recommendations')return response(config,hidden.has(42)?[]:[{...recommendation,opportunityId:42,saved:saved.has(42)}]);
      throw new AxiosError('unexpected fixture endpoint '+config.url,'ERR_BAD_RESPONSE',config,{},response(config,{},404));
    };
    const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});client.setQueryData(['auth','user'],user);
    const root=createRoot(document.querySelector('#root'));
    root.render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/saved']}><AppRoutes/></MemoryRouter></QueryClientProvider>);
    const ensure=(value,message)=>{if(!value)throw Error(message)};
    const wait=async predicate=>{for(let i=0;i<600;i++){if(predicate())return;await new Promise(resolve=>setTimeout(resolve,10))}throw Error('UI timeout: '+predicate.toString()+' | '+document.querySelector('main')?.textContent.slice(0,600)+' | '+JSON.stringify(calls.slice(-6)))};
    const button=text=>Array.from(document.querySelectorAll('button')).find(element=>element.textContent.trim()===text);
    const action=label=>Array.from(document.querySelectorAll('button')).find(element=>element.getAttribute('aria-label')===label);
    const menu=text=>Array.from(document.querySelectorAll('header nav a')).find(element=>element.textContent===text);
    try{
      await wait(()=>action('테스트 저장 공고 42 저장 해제'));button('다음').click();
      await wait(()=>action('테스트 저장 공고 62 저장 해제'));
      ensure(document.querySelector('button[aria-current=page]').textContent==='2','0-based page displayed as 2');
      action('테스트 저장 공고 62 저장 해제').click();action('테스트 저장 공고 62 저장 해제')?.click();
      await wait(()=>action('테스트 저장 공고 42 저장 해제')&&button('다음').disabled);
      ensure(!saved.has(62)&&document.querySelector('button[aria-current=page]').textContent==='1','last item page correction');
      const detail=Array.from(document.querySelectorAll('main a')).find(a=>a.getAttribute('href')==='/opportunities/42');detail.click();
      await wait(()=>action(policy.title+' 저장 해제')&&!action(policy.title+' 저장 해제').disabled);
      action(policy.title+' 저장 해제').click();await wait(()=>action(policy.title+' 저장')&&!action(policy.title+' 저장').disabled);
      action(policy.title+' 저장').click();await wait(()=>action(policy.title+' 저장 해제')&&!action(policy.title+' 저장 해제').disabled);
      ensure(calls.some(call=>call.url==='/opportunities/42/save'&&call.method==='post'&&call.params.source==='OTHER'),'detail save source');
      menu('홈').click();await wait(()=>action(recommendation.title+' 추천에서 숨기기')&&!action(recommendation.title+' 추천에서 숨기기').disabled);
      await wait(()=>action(latest.title+' 저장 해제')&&!action(latest.title+' 저장 해제').disabled);
      const bookmark=action(latest.title+' 저장 해제');
      ensure(bookmark.textContent.trim()===''&&bookmark.getAttribute('aria-pressed')==='true','home uses accessible icon-only saved bookmark');
      const beforeHomeUnsave=calls.filter(call=>call.url==='/opportunities/42/save'&&call.method==='delete').length;
      bookmark.click();bookmark.click();await wait(()=>action(latest.title+' 저장')&&!action(latest.title+' 저장').disabled);
      ensure(calls.filter(call=>call.url==='/opportunities/42/save'&&call.method==='delete').length===beforeHomeUnsave+1,'home duplicate unsave prevention');
      action(latest.title+' 저장').click();await wait(()=>action(latest.title+' 저장 해제')&&!action(latest.title+' 저장 해제').disabled);
      ensure(calls.some(call=>call.url==='/opportunities/42/save'&&call.method==='post'&&call.params.source==='OTHER'),'home existing save source');
      action(recommendation.title+' 추천에서 숨기기').click();await wait(()=>document.body.textContent.includes('아직 보여드릴 추천 결과가 없어요'));
      ensure(saved.has(42)&&hidden.has(42),'hide must preserve saved state');
      ensure(calls.some(call=>call.url==='/opportunities/42/hide'&&call.params.source==='RECOMMENDATION'),'hide source');
      await wait(()=>action(latest.title+' 저장 해제')&&!action(latest.title+' 저장 해제').disabled);
      action(latest.title+' 저장 해제').click();menu('기회 탐색').click();
      await wait(()=>document.querySelector('#search-title')&&action(latest.title+' 저장')&&!action(latest.title+' 저장').disabled);
      ensure(hidden.has(42)&&!saved.has(42),'unsave must preserve hidden state');
      action(latest.title+' 저장').click();await wait(()=>action(latest.title+' 저장 해제')&&!action(latest.title+' 저장 해제').disabled);
      ensure(calls.some(call=>call.url==='/opportunities/42/save'&&call.method==='post'&&call.params.source==='SEARCH'),'search source');
      menu('저장한 기회').click();await wait(()=>action('테스트 저장 공고 42 저장 해제'));
      button('로그아웃').click();await wait(()=>document.querySelector('#home-title')&&Array.from(document.querySelectorAll('header a')).some(a=>a.textContent==='로그인'));
      menu('기회 탐색').click();await wait(()=>document.querySelector('#search-title')&&Array.from(document.querySelectorAll('main a')).some(a=>a.getAttribute('aria-label')===latest.title+' 저장하려면 로그인'));
      const login=Array.from(document.querySelectorAll('main a')).find(a=>a.getAttribute('aria-label')===latest.title+' 저장하려면 로그인');
      ensure(login.getAttribute('href')==='/login?returnTo=%2Fopportunities','anonymous return');
      ensure(!calls.some(call=>call.url.includes('behavior')),'no duplicate behavior event');
      ensure(calls.filter(call=>call.url==='/opportunities/62/save'&&call.method==='delete').length===1,'duplicate removal');
      ensure(client.getQueriesData({queryKey:['saved-opportunities']}).length===0,'logout clears saved cache');
      document.querySelector('#result').textContent=JSON.stringify({ok:true,lastPage:true,independent:true,sources:true,logout:true,noDuplicateEvents:true});
    }catch(error){document.querySelector('#result').textContent=JSON.stringify({ok:false,error:String(error)})}
    finally{root.unmount();client.clear()}
  `
  const server = await createServer({ root, cacheDir: path.join(temporary, 'vite'), configFile: false, plugins: [react(), {
    name: 'test-only-preference-page',
    resolveId(id) { if (id === '/__test__/preference-entry.tsx') return '\0preference-entry.tsx' },
    load(id) { if (id === '\0preference-entry.tsx') return ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText },
    configureServer(server) { server.middlewares.use((request, response, next) => {
      if (request.url !== '/__test__/preference') return next()
      void server.transformIndexHtml('/__test__/preference', '<!doctype html><html lang="ko"><head><meta charset="utf-8"></head><body><div id="root"></div><pre id="result"></pre><script type="module" src="/__test__/preference-entry.tsx"></script></body></html>').then((html) => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen(); const address = server.httpServer.address()
    await fetch(`http://127.0.0.1:${address.port}/__test__/preference-entry.tsx`)
    const { stdout } = await promisify(execFile)(browser, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, 'browser')}`, '--virtual-time-budget=20000', '--dump-dom', `http://127.0.0.1:${address.port}/__test__/preference`], { windowsHide: true, timeout: 60_000, maxBuffer: 5_000_000 })
    const result = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
    assert.ok(result, '브라우저 시나리오 결과가 필요하다.')
    assert.deepEqual(JSON.parse(result[1]), { ok: true, lastPage: true, independent: true, sources: true, logout: true, noDuplicateEvents: true })
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
})
