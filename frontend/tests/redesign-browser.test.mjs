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
import { user, profile, options, regionRows } from './profile-fixtures.mjs'
import { policy } from './detail-fixtures.mjs'
import { latestItem, policyRecommendation, trainingRecommendation } from './home-fixtures.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(value => value && fs.existsSync(value))
const paths = ['/', '/opportunities', '/opportunities/42', '/login', '/mypage', '/mypage/profile', '/onboarding', '/notifications', '/settings/notifications', '/saved', '/privacy', '/terms']

test('Chrome checks redesigned real pages at mobile/tablet/desktop widths, menu Escape/return focus and header search with test-only responses', { skip: !browser && 'Chrome/Edge 미설치: 실제 레이아웃 검증 미실행' }, async t => {
  const cache = path.join(root, 'node_modules/.cache')
  fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'redesign-browser-'))
  const entry = `
    import React from 'react'; import {createRoot} from 'react-dom/client';
    import {MemoryRouter,Routes,Route} from 'react-router-dom';
    import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
    import {AuthContext} from '/src/auth/AuthContext.ts'; import {apiClient} from '/src/api/client.ts';
    import {AxiosError} from 'axios';
    import AppLayout from '/src/layouts/AppLayout.tsx';
    import HomePage from '/src/pages/home/HomePage.tsx';
    import OpportunitiesPage from '/src/pages/opportunities/OpportunitiesPage.tsx';
    import OpportunityDetailPage from '/src/pages/opportunities/OpportunityDetailPage.tsx';
    import LoginPage from '/src/pages/auth/LoginPage.tsx';
    import {MyPage,OnboardingPage,ProfileSettingsPage} from '/src/pages/profile/ProfilePages.tsx';
    import {NotificationsPage,NotificationSettingsPage} from '/src/pages/notifications/NotificationPages.tsx';
    import SavedOpportunitiesPage from '/src/pages/saved/SavedOpportunitiesPage.tsx';
    import {PrivacyPage,TermsPage} from '/src/pages/legal/LegalPages.tsx'; import '/src/index.css';
    const user=${JSON.stringify(user)},profile=${JSON.stringify(profile)},options=${JSON.stringify(options)},regions=${JSON.stringify(regionRows)};
    const latest=${JSON.stringify(latestItem)},detail=${JSON.stringify(policy)},recommendations=${JSON.stringify([policyRecommendation, trainingRecommendation])};
    const params=new URLSearchParams(location.search), route=params.get('route')||'/', anonymous=route==='/login'||params.get('anonymous')==='1', state=params.get('state');
    const notification={id:30,opportunityId:42,type:'DEADLINE_SOON',title:'테스트 마감 알림',content:'테스트 전용 알림 내용입니다.',link:'/opportunities/42',createdAt:'2026-10-26T01:00:00Z',readAt:null,clickedAt:null};
    const page={content:[latest,{...latest,opportunityId:104,title:'테스트 청년 지원 기회'},{...latest,opportunityId:105,title:'테스트 직업훈련 기회',type:'VOCATIONAL_TRAINING'}],page:0,size:20,totalElements:3,totalPages:1,hasNext:false};
    const notes={items:[notification],nextCursor:null,hasNext:false};
    const settings={inAppEnabled:true,pushEnabled:false,pushConsentAt:null,updatedAt:null};
    const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity},mutations:{retry:false}}});
    // 실제 서버·쿠키·FCM에 연결하지 않는다. 예기치 않은 변경 요청은 즉시 실패한다.
    apiClient.defaults.adapter=async config=>{
      if(config.method!=='get')throw Error('Unexpected mutation in layout test');
      if(config.url==='/opportunities'&&state==='error')throw new AxiosError('test only','ERR_NETWORK',config);
      const values={'/opportunities':page,'/opportunities/42':detail,'/interests/options':options,'/regions':{regions},'/users/me':user,'/users/me/eligibility-profile':profile,'/users/me/interests':{interests:[]},'/users/me/recommendations':recommendations,'/notifications':notes,'/notifications/unread-count':{available:true,unreadCount:1},'/users/me/notification-settings':settings,'/users/me/saved-opportunities':{...page,content:[{opportunityId:42,type:'YOUTH_POLICY',title:'테스트 저장 기회',category:'일자리',subCategory:'취업지원',savedAt:'2026-10-26T01:00:00'}],totalElements:1}};
      if(!Object.hasOwn(values,config.url))throw Error('Unexpected read in layout test');
      if(state==='empty'&&config.url==='/opportunities')return {config,data:{...page,content:[],totalElements:0,totalPages:0},status:200,statusText:'',headers:{}};
      if(state==='empty'&&config.url==='/users/me/recommendations')return {config,data:[],status:200,statusText:'',headers:{}};
      return {config,data:values[config.url],status:200,statusText:'',headers:{}};
    };
    client.setQueryData(['auth','user'],anonymous?null:user);
    const auth={status:anonymous?'anonymous':'authenticated',user:anonymous?undefined:client.getQueryData(['auth','user']),logoutPending:false,logoutError:null,retry:()=>{},completeOAuth:async()=>{},logout:async()=>{}};
    const app=createRoot(document.querySelector('#root'));
    app.render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[route]}><AuthContext.Provider value={auth}><Routes><Route element={<AppLayout/>}>
      <Route index element={<HomePage/>}/><Route path="opportunities" element={<OpportunitiesPage/>}/><Route path="opportunities/:id" element={<OpportunityDetailPage/>}/>
      <Route path="login" element={<LoginPage/>}/><Route path="mypage" element={<MyPage/>}/><Route path="mypage/profile" element={<ProfileSettingsPage kind="profile"/>}/><Route path="onboarding" element={<OnboardingPage/>}/>
      <Route path="notifications" element={<NotificationsPage/>}/><Route path="settings/notifications" element={<NotificationSettingsPage/>}/><Route path="saved" element={<SavedOpportunitiesPage/>}/><Route path="privacy" element={<PrivacyPage/>}/><Route path="terms" element={<TermsPage/>}/>
    </Route></Routes></AuthContext.Provider></MemoryRouter></QueryClientProvider>);
    const wait=async fn=>{for(let i=0;i<800;i++){if(fn())return;await new Promise(r=>setTimeout(r,10))}throw Error('UI readiness timeout: '+route)};
    const ensure=(ok,label)=>{if(!ok)throw Error(label+': '+route+' width='+innerWidth)};
    const rect=e=>e.getBoundingClientRect();
    try{
      await wait(()=>document.querySelector('main h1')&&!document.querySelector('main [aria-busy="true"]'));
      await wait(()=>!document.querySelector('main').textContent.includes('불러오'));
      await new Promise(r=>setTimeout(r,100));
      ensure(document.documentElement.scrollWidth<=innerWidth,'horizontal overflow');
      if(route==='/'){
        const hero=document.querySelector('section[aria-labelledby="home-title"]');
        ensure(!hero.querySelector('form'),'compact hero without duplicate search');
        // '맞춤 추천 보기'는 맞춤 조건이 확인된 로그인 사용자에게만 보인다. 비로그인은 추천 영역의 로그인 안내를 사용한다.
        const recommend=hero.querySelector('a[href="#recommendations"]');
        ensure(hero.querySelector('a[href="/opportunities"]')&&document.querySelector('#recommendations')&&(anonymous?!recommend&&!hero.textContent.includes('맞춤 추천 보기'):recommend?.textContent.includes('맞춤 추천 보기')),'real hero CTA destinations');
        if(anonymous){const intro=document.querySelector('[class*="recommendationIntro"]');ensure(intro&&intro.querySelectorAll('a').length===1&&intro.querySelector('a').getAttribute('href')==='/login?returnTo=%2F'&&getComputedStyle(intro).borderTopWidth==='0px','single-card anonymous recommendation');}
        if(state==='empty')ensure(document.querySelector('main').textContent.includes('아직 등록된 기회가 없어요'),'home empty state');
        if(state==='error')ensure(document.querySelector('main [role="alert"]')?.textContent.includes('네트워크 연결을 확인해 주세요'),'home network error');
      }
      const main=rect(document.querySelector('main')),footer=rect(document.querySelector('footer'));
      ensure(Math.abs(main.left-footer.left)<1&&Math.abs(main.right-footer.right)<1,'footer alignment');
      if(params.get('capture')!=='1'){
      // 여러 iframe이 포커스를 빼앗지 않도록 상호작용은 부모가 한 화면씩 허용한다.
      await new Promise(resolve=>{const start=event=>{if(event.origin===location.origin&&event.data?.startRedesign){removeEventListener('message',start);resolve()}};addEventListener('message',start);parent.postMessage({readyRedesign:true},location.origin)});
      if(route==='/'&&anonymous&&!state){
        const bookmark=document.querySelector('main a[aria-label$="저장하려면 로그인"]');
        ensure(bookmark&&rect(bookmark).height>=44&&bookmark.textContent.trim()==='','anonymous bookmark touch target');
        bookmark.focus();ensure(document.activeElement===bookmark&&getComputedStyle(bookmark).outlineStyle==='solid','bookmark keyboard focus');
        bookmark.click();await wait(()=>document.querySelector('#login-title'));
        document.querySelector('#primary-navigation a[href="/"]').click();await wait(()=>document.querySelector('#home-title'));
        document.querySelector('#recommendations a[href="/login?returnTo=%2F"]').click();await wait(()=>document.querySelector('#login-title'));
      }
      const toggle=document.querySelector('button[aria-controls="primary-navigation"]'),nav=document.querySelector('#primary-navigation');
      if(innerWidth<1024){
        ensure(getComputedStyle(nav).display==='none','collapsed mobile navigation');toggle.click();await wait(()=>toggle.getAttribute('aria-expanded')==='true');
        toggle.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));await wait(()=>toggle.getAttribute('aria-expanded')==='false');ensure(document.activeElement===toggle,'Escape returns focus');
        toggle.click();await wait(()=>toggle.getAttribute('aria-expanded')==='true');
        nav.querySelector('a[href="/opportunities"]').click();await wait(()=>toggle.getAttribute('aria-expanded')==='false');
        await wait(()=>document.querySelector('main h1').textContent==='통합 기회 탐색');
      }else{
        ensure(getComputedStyle(nav).display!=='none','desktop navigation');
        if(route==='/opportunities/42'){
          const columns=document.querySelector('main article > [class*="columns"]');
          const body=columns?.querySelector(':scope > [class*="mainContent"]'),summary=columns?.querySelector(':scope > [class*="summary"]');
          ensure(body&&summary&&rect(summary).left>=rect(body).right,'detail summary side column');
        }
        const form=document.querySelector('header form'),input=form.querySelector('input');
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'테스트');form.requestSubmit();
        await wait(()=>document.querySelector('main form[role="search"] input')?.value==='테스트');
      }
      const footerLinks=[...document.querySelectorAll('footer a')];footerLinks[0].focus();
      ensure(document.activeElement===footerLinks[0]&&getComputedStyle(footerLinks[0]).outlineStyle==='solid','visible link focus');
      ensure(footerLinks.every(a=>rect(a).height>=44),'footer touch targets');
      parent.postMessage({redesign:true,route,width:innerWidth,ok:true},location.origin);
      }
    }catch(error){parent.postMessage({redesign:true,route,width:innerWidth,ok:false,error:String(error)},location.origin);}
  `
  const cases = [390, 768, 1440].flatMap(width => paths.map(route => ({ width, route })))
  for (const width of [390, 1440]) cases.push({ width, route: '/', anonymous: true }, { width, route: '/', anonymous: true, state: 'empty' }, { width, route: '/', state: 'error' })
  const server = await createServer({ root, envDir: false, envPrefix: 'TEST_UNUSED_', cacheDir: path.join(temporary, 'vite'), configFile: false, plugins: [react(), {
    name: 'test-only-redesign-pages',
    resolveId(id) { if (id === '/__test__/redesign-entry.tsx') return '\0redesign-entry.tsx' },
    load(id) { if (id === '\0redesign-entry.tsx') return ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText },
    configureServer(server) { server.middlewares.use((request, response, next) => {
      const url = new URL(request.url, 'http://localhost')
      if (url.pathname === '/__test__/redesign-capture') {
        const width = Number(url.searchParams.get('width')) === 390 ? 390 : 1440
        response.setHeader('Content-Type', 'text/html; charset=utf-8')
        response.end(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{margin:0}iframe{display:block;border:0}</style><iframe width="${width}" height="1100" src="/__test__/redesign-page?route=${encodeURIComponent(url.searchParams.get('route') || '/')}&anonymous=${url.searchParams.get('anonymous') === '1' ? '1' : '0'}&capture=1"></iframe>`)
        return
      }
      if (url.pathname === '/__test__/redesign') {
        const frames = cases.map(c => ({ name: `${c.route}@${c.width}${c.anonymous ? ' anonymous' : ''}${c.state ? ' ' + c.state : ''}`, width: c.width, src: `/__test__/redesign-page?route=${encodeURIComponent(c.route)}&anonymous=${c.anonymous ? '1' : '0'}&state=${c.state || ''}` }))
        // 42개 화면을 한꺼번에 띄우면 같은 렌더러에서 수백 개 모듈을 동시에 요청해 느린 환경에서 일부 화면의 모듈 로드가 실패한다.
        // 동시에 띄우는 화면을 제한하고 결과가 온 만큼 다음 화면을 띄운다. 상호작용은 기존처럼 한 화면씩 허용한다.
        // #progress에는 결과가 모자랄 때 어디서 멈췄는지 보이도록 준비·완료 시각(가상 시간 ms), 현재·대기·미준비·미시작 화면을 남긴다.
        const script = `
          const frames=${JSON.stringify(frames)},limit=4,results=[],queue=[],log=[],seen=new Set(),t0=performance.now();
          let busy=false,current=null,opened=0,active=0;
          const ms=()=>Math.round(performance.now()-t0);
          const frameOf=source=>[...document.querySelectorAll('iframe')].find(f=>f.contentWindow===source);
          const label=source=>frameOf(source)?.dataset.case||'unknown';
          const report=()=>{document.querySelector('#progress').textContent=JSON.stringify({elapsedMs:ms(),done:results.length,total:frames.length,current:current&&label(current),waiting:queue.map(label),
            notReady:[...document.querySelectorAll('iframe')].filter(f=>!seen.has(f.dataset.case)).map(f=>{const d=f.contentDocument;return {case:f.dataset.case,readyState:d?.readyState,h1:d?.querySelector('main h1')?.textContent}}),
            notStarted:frames.length-opened,log})};
          const open=()=>{while(active<limit&&opened<frames.length){const c=frames[opened++],f=document.createElement('iframe');f.dataset.case=c.name;f.width=c.width;f.height=1100;f.src=c.src;document.body.append(f);active++}};
          const next=()=>{if(busy||!queue.length)return;busy=true;current=queue.shift();current.postMessage({startRedesign:true},location.origin)};
          const finish=(source,result)=>{seen.add(label(source));log.push((result.ok?'done ':'fail ')+label(source)+' '+ms()+(result.ok?'':' '+result.error));results.push(result);active--;
            if(source===current){busy=false;current=null}next();open();if(results.length===frames.length)document.querySelector('#result').textContent=JSON.stringify(results)};
          addEventListener('message',e=>{if(e.origin!==location.origin)return;
            if(e.data?.redesignLoadError)finish(e.source,{redesign:true,route:label(e.source),ok:false,error:'page load failed: '+e.data.redesignLoadError});
            if(e.data?.readyRedesign){seen.add(label(e.source));log.push('ready '+label(e.source)+' '+ms());queue.push(e.source);next()}
            if(e.data?.redesign)finish(e.source,e.data);
            report()});
          setInterval(report,250);open();`
        const html = `<!doctype html><html lang="ko"><meta charset="utf-8"><style>body{margin:0}iframe{display:block;border:0}</style><pre id="result"></pre><pre id="progress"></pre><script>${script}</script></html>`
        response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html); return
      }
      if (url.pathname !== '/__test__/redesign-page') return next()
      void server.transformIndexHtml(url.pathname, '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><div id="root"></div><script>addEventListener("error",e=>{if(e.target instanceof HTMLScriptElement)parent.postMessage({redesignLoadError:e.target.src+" "+JSON.stringify(performance.getEntriesByType("resource").filter(r=>!(r.responseStatus>=200&&r.responseStatus<400)).map(r=>r.name.replace(location.origin,"")+" "+r.responseStatus))},location.origin)},true)</script><script type="module" src="/__test__/redesign-entry.tsx"></script></body></html>').then(html => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen()
    const base = `http://127.0.0.1:${server.httpServer.address().port}`
    await fetch(`${base}/__test__/redesign-entry.tsx`)
    const { stdout } = await promisify(execFile)(browser, ['--headless', ...chromeServiceFlags, '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, 'browser')}`, '--virtual-time-budget=25000', '--dump-dom', `${base}/__test__/redesign`], { windowsHide: true, timeout: 60_000, maxBuffer: 5_000_000 })
    const match = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
    const progress = stdout.match(/<pre id="progress">([^<]*)<\/pre>/)?.[1].replaceAll('&lt;', '<').replaceAll('&gt;', '>').replaceAll('&amp;', '&') || '(진행 기록 없음)'
    t.diagnostic(`redesign progress: ${progress}`)
    assert.ok(match, `모든 화면의 브라우저 결과가 필요하다. 진행 상태: ${progress}`)
    const results = JSON.parse(match[1])
    assert.equal(results.length, cases.length)
    assert.deepEqual(results.filter(result => !result.ok), [])
    if (process.env.YON_UI_SCREENSHOTS === '1') {
      const report = path.join(cache, 'redesign-review')
      fs.mkdirSync(report, { recursive: true })
      for (const [name, route, width, anonymous] of [['home', '/', 1440], ['search', '/opportunities', 1440], ['detail', '/opportunities/42', 1440], ['login', '/login', 390], ['home-mobile', '/', 390], ['home-anonymous', '/', 1440, true], ['home-anonymous-mobile', '/', 390, true], ['profile', '/mypage', 1440], ['notifications', '/notifications', 1440], ['privacy', '/privacy', 390]]) {
        await promisify(execFile)(browser, ['--headless', ...chromeServiceFlags, '--disable-gpu', '--no-first-run', '--disable-background-networking', '--hide-scrollbars', `--user-data-dir=${path.join(temporary, 'shots')}`, `--window-size=${width},1100`, '--virtual-time-budget=5000', `--screenshot=${path.join(report, name + '.png')}`, `${base}/__test__/redesign-capture?route=${encodeURIComponent(route)}&width=${width}&anonymous=${anonymous ? '1' : '0'}`], { windowsHide: true, timeout: 30_000, maxBuffer: 500_000 })
      }
    }
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
})
