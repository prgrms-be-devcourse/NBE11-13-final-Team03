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
import { profile, user, options, regionRows } from './profile-fixtures.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'].find((value) => value && fs.existsSync(value))
test('Chrome exercises real region selects, onboarding PUT/back/complete and profile PATCH against a mocked transport', { skip: !browser && 'Chrome/Edge 미설치로 브라우저 동작 검증을 실행하지 않았다.' }, async () => {
  const cache = path.join(root, 'node_modules', '.cache')
  fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'profile-browser-'))
  const rows = [...regionRows, { code: '4111000000', name: '수원시', parentCode: '4100000000', leaf: false }, { code: '4111100000', name: '장안구', parentCode: '4111000000', leaf: true }]
  // 실제 React·Query·apiClient·폼을 사용한다. HTTP 전송만 mock하여 서버 계정을 변경하지 않는다.
  const entry = `
    import React from 'react'; import {createRoot} from 'react-dom/client';
    import {MemoryRouter} from 'react-router-dom'; import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
    import {AxiosError} from 'axios'; import {apiClient} from '/src/api/client.ts';
    import {AuthContext} from '/src/auth/AuthContext.ts';
    import {OnboardingPage,ProfileSettingsPage} from '/src/pages/profile/ProfilePages.tsx';
    import '/src/index.css';
    const user=${JSON.stringify(user)}, initial=${JSON.stringify(profile)}, rows=${JSON.stringify(rows)}, options=${JSON.stringify(options)};
    const calls=[], puts=[], patches=[]; let stored=null, interests=[];
    document.cookie='XSRF-TOKEN=test-browser-csrf; Path=/';
    const response=(config,data,status=200)=>({config,data,status,statusText:'',headers:{}});
    apiClient.defaults.adapter=async config=>{
      calls.push(config.url);
      if(config.url==='/regions') return response(config,{regions:rows});
      if(config.url==='/interests/options') return response(config,options);
      if(config.url==='/users/me/eligibility-profile') {
        if(config.method==='get') { if(stored) return response(config,stored); throw new AxiosError('fixture','ERR_BAD_RESPONSE',config,{},response(config,{code:'ELIGIBILITY_001',message:'fixture',fieldErrors:{}},404)); }
        if(config.headers.get('X-XSRF-TOKEN')!=='test-browser-csrf') throw Error('missing CSRF');
        const body=JSON.parse(config.data);
        if(config.method==='put') { puts.push(body); stored={...initial,...body}; }
        else if(config.method==='patch') { patches.push(body); stored={...stored,...body}; }
        else throw Error('unexpected method');
        return response(config,stored);
      }
      if(config.url==='/users/me/interests') { if(config.method==='put') interests=JSON.parse(config.data).interests; return response(config,{interests}); }
      throw Error('unexpected API '+config.url);
    };
    const client=new QueryClient({defaultOptions:{queries:{retry:false},mutations:{retry:false}}});
    client.setQueryData(['auth','user'],user);
    const auth={status:'authenticated',user:client.getQueryData(['auth','user']),logoutPending:false,logoutError:null,completeOAuth:async()=>{},logout:async()=>{}};
    const root=createRoot(document.querySelector('#root'));
    const render=page=>root.render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/onboarding']}><AuthContext.Provider value={auth}>{page}</AuthContext.Provider></MemoryRouter></QueryClientProvider>);
    const ensure=(ok,message)=>{if(!ok)throw Error(message)};
    const wait=async fn=>{for(let i=0;i<400;i++){if(fn())return;await new Promise(resolve=>setTimeout(resolve,10))}throw Error('UI wait timeout')};
    const field=label=>Array.from(document.querySelectorAll('label')).find(element=>element.textContent.trim()===label)?.control;
    const change=async(label,value)=>{await wait(()=>field(label));const input=field(label);Object.getOwnPropertyDescriptor(input instanceof HTMLSelectElement?HTMLSelectElement.prototype:HTMLInputElement.prototype,'value').set.call(input,value);input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));await new Promise(resolve=>setTimeout(resolve,20));};
    const button=text=>Array.from(document.querySelectorAll('button')).find(element=>element.textContent.trim()===text);
    try {
      render(<OnboardingPage/>);
      await wait(()=>field('거주 시·도')?.options.length>1);
      await change('생년월일 (필수)','1998-05-15');await change('고용 상태 (필수)','UNEMPLOYED');await change('최종 학력 (필수)','UNIVERSITY');
      await change('거주 시·도','4100000000');await change('거주 시·군·구','4111000000');
      ensure(button('맞춤 조건 저장').disabled,'intermediate residence must block save');
      await change('거주 하위 지역 (3단계)','4111100000');
      ensure(!button('맞춤 조건 저장').disabled,'leaf should enable creation');
      await change('거주 시·도','1100000000');
      ensure(!field('거주 하위 지역 (3단계)')&&button('맞춤 조건 저장').disabled,'ancestor change must clear descendants');
      await change('거주 시·도','3611000000');ensure(!field('거주 시·군·구')&&!button('맞춤 조건 저장').disabled,'top-level leaf must commit immediately');
      await change('거주 시·도','4100000000');await change('거주 시·군·구','4111000000');await change('거주 하위 지역 (3단계)','4111100000');
      document.querySelector('details summary').click();
      await change('학교 시·도','4100000000');await change('학교 시·군·구','4111000000');
      button('맞춤 조건 저장').click();button('맞춤 조건 저장')?.click();
      await wait(()=>document.querySelector('h2')?.textContent==='2. 관심 분야 (선택)');
      ensure(puts.length===1&&puts[0].residenceRegionCode==='4111100000'&&puts[0].schoolRegionCode==='4111000000','PUT with exact leaf and school intermediate');
      await wait(()=>button('이전 단계로 돌아가기'));button('이전 단계로 돌아가기').click();
      await wait(()=>field('거주 하위 지역 (3단계)')?.value==='4111100000');
      ensure(field('거주 시·군·구').value==='4111000000','restore all ancestors');
      button('기존 조건으로 다음 단계').click();await wait(()=>button('관심 분야 저장'));
      document.querySelector('input[type=checkbox]').click();button('관심 분야 저장').click();
      await wait(()=>document.querySelector('h2')?.textContent==='설정을 완료했어요');
      ensure(interests.length===1&&Array.from(document.querySelectorAll('a')).some(a=>a.textContent==='홈으로 이동'&&a.getAttribute('href')==='/'),'complete and home return');
      render(<ProfileSettingsPage kind="profile"/>);await wait(()=>field('거주 하위 지역 (3단계)'));
      await change('거주 시·도','3611000000');
      button('맞춤 조건 저장').click();await wait(()=>patches.length===1);await wait(()=>!button('맞춤 조건 저장').disabled);
      ensure(JSON.stringify(patches[0])===JSON.stringify({residenceRegionCode:'3611000000'}),'PATCH changes only selected region');
      ensure(calls.filter(url=>url==='/regions').length===1,'shared fresh region cache');
      document.querySelector('#result').textContent=JSON.stringify({ok:true,put:puts.length,patch:patches.length,regions:calls.filter(url=>url==='/regions').length,threeLevels:true,sejong:true,schoolIntermediate:true,back:true,completed:true});
    } catch(error) { document.querySelector('#result').textContent=JSON.stringify({ok:false,error:String(error)}); }
    finally { root.unmount();client.clear(); }
  `
  const server = await createServer({ root, envDir: false, envPrefix: 'TEST_UNUSED_', cacheDir: path.join(temporary, 'vite'), configFile: false, plugins: [react(), {
    name: 'test-only-profile-page',
    resolveId(id) { if (id === '/__test__/profile-entry.tsx') return '\0profile-entry.tsx' },
    load(id) { if (id === '\0profile-entry.tsx') return ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText },
    configureServer(server) { server.middlewares.use((request, response, next) => {
      if (request.url !== '/__test__/profile') return next()
      void server.transformIndexHtml('/__test__/profile', '<!doctype html><html lang="ko"><head><meta charset="utf-8"></head><body><div id="root"></div><pre id="result"></pre><script type="module" src="/__test__/profile-entry.tsx"></script></body></html>').then((html) => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen()
    const address = server.httpServer.address()
    // 병렬 프록시 테스트·개발 서버와 사전 번들 캐시를 공유하지 않는다.
    await fetch(`http://127.0.0.1:${address.port}/__test__/profile-entry.tsx`)
    const { stdout } = await promisify(execFile)(browser, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, 'browser')}`, '--virtual-time-budget=15000', '--dump-dom', `http://127.0.0.1:${address.port}/__test__/profile`], { windowsHide: true, timeout: 60_000, maxBuffer: 5_000_000 })
    const result = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
    assert.ok(result, '브라우저 시나리오 결과가 필요하다.')
    assert.deepEqual(JSON.parse(result[1]), { ok: true, put: 1, patch: 1, regions: 1, threeLevels: true, sejong: true, schoolIntermediate: true, back: true, completed: true })
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
})
