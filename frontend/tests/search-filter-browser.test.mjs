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
import { user, sharedCategoryOptions, regionRows } from './profile-fixtures.mjs'
import { latestItem } from './home-fixtures.mjs'

// 청년정책·직업훈련이 같은 원본 대분류 `교육･직업훈련`을 쓰는 테스트 전용 선택지로 실제 select 이벤트와 검색 요청을 확인한다.
const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find((value) => value && fs.existsSync(value))
const expected = { ok: true, allTypeGroups: true, policyChain: true, trainingAutoSelect: true, leavingTrainingClears: true, reset: true, urlRestore: true }
test('Chrome selects youth policy and training categories by type in the search filter, auto-selects the single training category and sends raw values with categoryType', { skip: !browser && 'Chrome/Edge가 없어 검색 분류 필터의 브라우저 동작을 검증하지 않았다.' }, async () => {
  const cache = path.join(root, 'node_modules', '.cache'); fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'search-filter-browser-'))
  const entry = `
    import React from 'react';import {createRoot} from 'react-dom/client';import {MemoryRouter} from 'react-router-dom';
    import {QueryClient,QueryClientProvider} from '@tanstack/react-query';import {AxiosError} from 'axios';
    import {apiClient} from '/src/api/client.ts';import AppRoutes from '/src/routes/AppRoutes.tsx';import '/src/index.css';
    const user=${JSON.stringify(user)}, options=${JSON.stringify(sharedCategoryOptions)}, regions=${JSON.stringify(regionRows)}, latest=${JSON.stringify(latestItem)};
    const calls=[],searches=[];
    navigator.serviceWorker.getRegistrations=async()=>[];
    Object.defineProperty(navigator,'locks',{value:{request:(_name,_options,work)=>Promise.resolve(work({}))}});
    const response=(config,data,status=200)=>({config,data,status,statusText:'',headers:{}});
    apiClient.defaults.adapter=async config=>{
      calls.push(config.url);
      if(config.url==='/users/me')return response(config,user);
      if(config.url==='/interests/options')return response(config,options);
      if(config.url==='/regions')return response(config,{regions});
      if(config.url==='/opportunities'){
        searches.push(Object.fromEntries(config.params));
        const size=Number(config.params.get('size')||20);
        return response(config,{content:[{...latest,opportunityId:42}],page:0,size,totalElements:1,totalPages:1,hasNext:false});
      }
      throw new AxiosError('unexpected fixture endpoint '+config.url,'ERR_BAD_RESPONSE',config,{},response(config,{},404));
    };
    const ensure=(value,message)=>{if(!value)throw Error(message)};
    const wait=async(predicate,label)=>{for(let i=0;i<600;i++){if(predicate())return;await new Promise(resolve=>setTimeout(resolve,10))}throw Error('UI timeout: '+label+' | '+document.querySelector('main')?.textContent.slice(0,400)+' | '+JSON.stringify(searches.slice(-3)))};
    const field=label=>Array.from(document.querySelectorAll('main label')).find(element=>element.firstChild?.textContent?.trim()===label)?.querySelector('select');
    const pick=(select,value)=>{select.value=value;select.dispatchEvent(new Event('change',{bubbles:true}))};
    const values=select=>Array.from(select.options).filter(option=>option.value&&option.value!=='current').map(option=>option.value);
    const button=text=>Array.from(document.querySelectorAll('main button')).find(element=>element.textContent.trim()===text&&!element.disabled);
    const selectedText=select=>select.options[select.selectedIndex]?.textContent;
    // 마지막 검색 요청이 정확히 이 파라미터(page·size·sort 제외)인지 확인한다
    const lastSearch=()=>{const {page,size,sort,...rest}=searches.at(-1)??{};return rest};
    const same=(a,b)=>JSON.stringify(Object.entries(a).sort())===JSON.stringify(Object.entries(b).sort());
    const apply=async(params,label)=>{const before=searches.length;button('필터 적용').click();await wait(()=>searches.length>before&&same(lastSearch(),params),label)};
    const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});client.setQueryData(['auth','user'],user);
    let root=createRoot(document.querySelector('#root'));
    const render=url=>root.render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[url]}><AppRoutes/></MemoryRouter></QueryClientProvider>);
    const result={};
    try{
      render('/opportunities');
      await wait(()=>field('대분류')&&!field('대분류').disabled,'category options');
      const type=()=>field('공고 유형'),category=()=>field('대분류'),sub=()=>field('중분류'),detail=()=>field('세부 분류');
      // 전체 유형: 청년정책·직업훈련 묶음 제목이 같은 굵기이고, 묶음 제목과 같은 이름의 선택지가 없다
      const groups=Array.from(category().querySelectorAll('optgroup'));
      ensure(groups.map(group=>group.label).join('|')==='청년정책|직업훈련','all-type group titles');
      const weights=groups.map(group=>getComputedStyle(group).fontWeight);
      ensure(weights[0]===weights[1]&&Number(weights[0])>=600,'group titles share the same bold weight: '+weights);
      ensure(Array.from(groups[1].children).map(option=>option.textContent).join('|')==='직업훈련 전체','training group item');
      ensure(!Array.from(category().options).some(option=>groups.some(group=>group.label===option.textContent)),'no item repeats a group title');
      result.allTypeGroups=true;
      // 전체 유형 + 청년정책 교육･직업훈련 → 청년정책 중분류, 세부 분류 비활성화, categoryType=YOUTH_POLICY
      pick(category(),'YOUTH_POLICY:교육･직업훈련');
      await wait(()=>!sub().disabled,'policy sub categories');
      ensure(values(sub()).join('|')==='미래역량강화|온·오프라인교육','policy sub categories only');
      pick(sub(),'온·오프라인교육');await wait(()=>sub().value==='온·오프라인교육','policy sub selected');
      ensure(detail().disabled,'policy has no detail categories');
      await apply({category:'교육･직업훈련',subCategory:'온·오프라인교육',categoryType:'YOUTH_POLICY'},'all-type policy request');
      // 청년정책 유형: 청년정책 대·중분류만 고를 수 있다
      pick(type(),'YOUTH_POLICY');await wait(()=>type().value==='YOUTH_POLICY','policy type');
      ensure(values(category()).join('|')==='YOUTH_POLICY:교육|YOUTH_POLICY:교육･직업훈련'&&!category().querySelector('optgroup'),'policy type categories');
      ensure(category().value==='YOUTH_POLICY:교육･직업훈련'&&sub().value==='온·오프라인교육','policy selection kept within the same type');
      pick(sub(),'미래역량강화');await wait(()=>sub().value==='미래역량강화','policy type sub');
      await apply({type:'YOUTH_POLICY',category:'교육･직업훈련',subCategory:'미래역량강화'},'policy type request');
      result.policyChain=true;
      // 직업훈련 유형: 대분류 ‘직업훈련’이 바로 선택되고 NCS 1차 → 2차를 고른다. 보내는 대분류는 원본 교육･직업훈련이다
      pick(type(),'VOCATIONAL_TRAINING');
      await wait(()=>category().value==='VOCATIONAL_TRAINING:교육･직업훈련'&&!sub().disabled,'training auto-select');
      ensure(selectedText(category())==='직업훈련'&&values(category()).length===1,'single training category shown as 직업훈련');
      ensure(values(sub()).join('|')==='정보통신'&&detail().disabled,'NCS level 1 only');
      pick(sub(),'정보통신');await wait(()=>!detail().disabled,'NCS level 2 enabled');
      pick(detail(),'정보기술');await wait(()=>detail().value==='정보기술','NCS level 2 selected');
      await apply({type:'VOCATIONAL_TRAINING',category:'교육･직업훈련',subCategory:'정보통신',detailCategory:'정보기술'},'training request');
      result.trainingAutoSelect=true;
      // 직업훈련에서 전체·청년정책으로 돌아오면 직업훈련 분류가 남지 않는다
      for(const next of ['','YOUTH_POLICY']){
        if(type().value!=='VOCATIONAL_TRAINING'){pick(type(),'VOCATIONAL_TRAINING');await wait(()=>category().value==='VOCATIONAL_TRAINING:교육･직업훈련','training again')}
        pick(sub(),'정보통신');await wait(()=>!detail().disabled,'training sub again');
        pick(type(),next);await wait(()=>type().value===next,'leave training');
        ensure(category().value===''&&sub().value===''&&detail().value===''&&sub().disabled&&detail().disabled,'training choices cleared for '+(next||'ALL'));
      }
      await apply({type:'YOUTH_POLICY'},'cleared request');
      result.leavingTrainingClears=true;
      // 필터 초기화: 유형·분류를 지우고, 적용하면 categoryType 없이 검색한다
      pick(type(),'');pick(category(),'VOCATIONAL_TRAINING:교육･직업훈련');await wait(()=>!sub().disabled,'all-type training');
      button('필터 초기화').click();
      await wait(()=>type().value===''&&category().value===''&&sub().disabled,'reset');
      await apply({},'reset request');
      result.reset=true;
      // URL 복원: 전체 유형 + categoryType=VOCATIONAL_TRAINING 조건이 같은 선택으로 그려지고 그대로 요청된다
      root.unmount();root=createRoot(document.querySelector('#root'));
      const restored={category:'교육･직업훈련',subCategory:'정보통신',detailCategory:'정보기술',categoryType:'VOCATIONAL_TRAINING'};
      const before=searches.length;
      render('/opportunities?'+new URLSearchParams(restored));
      await wait(()=>field('대분류')&&!field('대분류').disabled&&detail()&&detail().value==='정보기술','url restore');
      ensure(type().value===''&&category().value==='VOCATIONAL_TRAINING:교육･직업훈련'&&selectedText(category())==='직업훈련 전체'&&sub().value==='정보통신','restored selection');
      await wait(()=>searches.length>before&&same(lastSearch(),restored),'restored request');
      result.urlRestore=true;
      document.querySelector('#result').textContent=JSON.stringify({ok:true,...result});
    }catch(error){document.querySelector('#result').textContent=JSON.stringify({ok:false,...result,error:String(error),calls:calls.slice(-8)})}
    finally{root.unmount();client.clear()}
  `
  const server = await createServer({ root, envDir: false, envPrefix: 'TEST_UNUSED_', cacheDir: path.join(temporary, 'vite'), configFile: false, plugins: [react(), {
    name: 'test-only-search-filter-page',
    resolveId(id) { if (id === '/__test__/search-filter-entry.tsx') return '\0search-filter-entry.tsx' },
    load(id) { if (id === '\0search-filter-entry.tsx') return ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText },
    configureServer(server) { server.middlewares.use((request, response, next) => {
      if (request.url !== '/__test__/search-filter') return next()
      void server.transformIndexHtml('/__test__/search-filter', '<!doctype html><html lang="ko"><head><meta charset="utf-8"></head><body><div id="root"></div><pre id="result"></pre><script type="module" src="/__test__/search-filter-entry.tsx"></script></body></html>').then((html) => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen(); const address = server.httpServer.address()
    await fetch(`http://127.0.0.1:${address.port}/__test__/search-filter-entry.tsx`)
    const { stdout } = await promisify(execFile)(browser, ['--headless', ...chromeServiceFlags, '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, 'browser')}`, '--virtual-time-budget=20000', '--dump-dom', `http://127.0.0.1:${address.port}/__test__/search-filter`], { windowsHide: true, timeout: 60_000, maxBuffer: 5_000_000 })
    const result = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
    assert.ok(result, '브라우저 시나리오 결과가 필요하다.')
    assert.deepEqual(JSON.parse(result[1].replaceAll('&amp;', '&').replaceAll('&quot;', '"')), expected)
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
})
