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
import { user, profile, options, regionRows } from './profile-fixtures.mjs'
import { policy } from './detail-fixtures.mjs'
import { latestItem, policyRecommendation, trainingRecommendation } from './home-fixtures.mjs'

const root = fileURLToPath(new URL('../', import.meta.url))
const browser = [process.env.CHROME_PATH, 'C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(value => value && fs.existsSync(value))
const paths = ['/', '/opportunities', '/opportunities/42', '/login', '/mypage', '/mypage/profile', '/onboarding', '/notifications', '/settings/notifications', '/saved', '/privacy', '/terms']

test('Chrome checks redesigned real pages at mobile/tablet/desktop widths, menu Escape/return focus and header search with test-only responses', { skip: !browser && 'Chrome/Edge 미설치: 실제 레이아웃 검증 미실행' }, async () => {
  const cache = path.join(root, 'node_modules/.cache')
  fs.mkdirSync(cache, { recursive: true })
  const temporary = fs.mkdtempSync(path.join(cache, 'redesign-browser-'))
  const entry = `
    import React from 'react'; import {createRoot} from 'react-dom/client';
    import {MemoryRouter,Routes,Route} from 'react-router-dom';
    import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
    import {AuthContext} from '/src/auth/AuthContext.ts'; import {apiClient} from '/src/api/client.ts';
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
    const params=new URLSearchParams(location.search), route=params.get('route')||'/', anonymous=route==='/login';
    const notification={id:30,opportunityId:42,type:'DEADLINE_SOON',title:'테스트 마감 알림',content:'테스트 전용 알림 내용입니다.',link:'/opportunities/42',createdAt:'2026-10-26T01:00:00Z',readAt:null,clickedAt:null};
    const page={content:[latest,{...latest,opportunityId:104,title:'테스트 청년 지원 기회'},{...latest,opportunityId:105,title:'테스트 직업훈련 기회',type:'VOCATIONAL_TRAINING'}],page:0,size:20,totalElements:3,totalPages:1,hasNext:false};
    const notes={items:[notification],nextCursor:null,hasNext:false};
    const settings={inAppEnabled:true,pushEnabled:false,pushConsentAt:null,updatedAt:null};
    const client=new QueryClient({defaultOptions:{queries:{retry:false,staleTime:Infinity},mutations:{retry:false}}});
    // 실제 서버·쿠키·FCM에 연결하지 않는다. 예기치 않은 변경 요청은 즉시 실패한다.
    apiClient.defaults.adapter=async config=>{
      if(config.method!=='get')throw Error('Unexpected mutation in layout test');
      const values={'/opportunities':page,'/opportunities/42':detail,'/interests/options':options,'/regions':{regions},'/users/me':user,'/users/me/eligibility-profile':profile,'/users/me/interests':{interests:[]},'/users/me/recommendations':recommendations,'/notifications':notes,'/notifications/unread-count':{available:true,unreadCount:1},'/users/me/notification-settings':settings,'/users/me/saved-opportunities':{...page,content:[{opportunityId:42,type:'YOUTH_POLICY',title:'테스트 저장 기회',category:'일자리',subCategory:'취업지원',savedAt:'2026-10-26T01:00:00'}],totalElements:1}};
      if(!Object.hasOwn(values,config.url))throw Error('Unexpected read in layout test');
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
      const main=rect(document.querySelector('main')),footer=rect(document.querySelector('footer'));
      ensure(Math.abs(main.left-footer.left)<1&&Math.abs(main.right-footer.right)<1,'footer alignment');
      if(params.get('capture')!=='1'){
      // 여러 iframe이 포커스를 빼앗지 않도록 상호작용은 부모가 한 화면씩 허용한다.
      await new Promise(resolve=>{const start=event=>{if(event.origin===location.origin&&event.data?.startRedesign){removeEventListener('message',start);resolve()}};addEventListener('message',start);parent.postMessage({readyRedesign:true},location.origin)});
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
          const columns=document.querySelector('main article > div:last-child');
          const body=columns.querySelector('[class*="mainContent"]');
          ensure(rect(columns.querySelector('aside')).left>=rect(body).right&&rect(columns.firstElementChild).left>=rect(body).right,'detail summary side column');
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
  const server = await createServer({ root, cacheDir: path.join(temporary, 'vite'), configFile: false, plugins: [react(), {
    name: 'test-only-redesign-pages',
    resolveId(id) { if (id === '/__test__/redesign-entry.tsx') return '\0redesign-entry.tsx' },
    load(id) { if (id === '\0redesign-entry.tsx') return ts.transpileModule(entry, { compilerOptions: { jsx: ts.JsxEmit.ReactJSX, module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText },
    configureServer(server) { server.middlewares.use((request, response, next) => {
      const url = new URL(request.url, 'http://localhost')
      if (url.pathname === '/__test__/redesign-capture') {
        const width = Number(url.searchParams.get('width')) === 390 ? 390 : 1440
        response.setHeader('Content-Type', 'text/html; charset=utf-8')
        response.end(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{margin:0}iframe{display:block;border:0}</style><iframe width="${width}" height="1100" src="/__test__/redesign-page?route=${encodeURIComponent(url.searchParams.get('route') || '/')}&capture=1"></iframe>`)
        return
      }
      if (url.pathname === '/__test__/redesign') {
        const html = `<!doctype html><html lang="ko"><meta charset="utf-8"><style>body{margin:0}iframe{display:block;border:0}</style><pre id="result"></pre><script>const results=[],queue=[];let busy=false;const next=()=>{if(busy||!queue.length)return;busy=true;queue.shift().postMessage({startRedesign:true},location.origin)};addEventListener('message',e=>{if(e.origin!==location.origin)return;if(e.data?.readyRedesign){queue.push(e.source);next()}if(e.data?.redesign){results.push(e.data);busy=false;next();if(results.length===${cases.length})document.querySelector('#result').textContent=JSON.stringify(results)}});</script>${cases.map(c => `<iframe width="${c.width}" height="1100" src="/__test__/redesign-page?route=${encodeURIComponent(c.route)}"></iframe>`).join('')}</html>`
        response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html); return
      }
      if (url.pathname !== '/__test__/redesign-page') return next()
      void server.transformIndexHtml(url.pathname, '<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><div id="root"></div><script type="module" src="/__test__/redesign-entry.tsx"></script></body></html>').then(html => { response.setHeader('Content-Type', 'text/html; charset=utf-8'); response.end(html) }).catch(next)
    }) },
  }], server: { host: '127.0.0.1', port: 0 } })
  try {
    await server.listen()
    const base = `http://127.0.0.1:${server.httpServer.address().port}`
    await fetch(`${base}/__test__/redesign-entry.tsx`)
    const { stdout } = await promisify(execFile)(browser, ['--headless', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--disable-extensions', '--disable-background-networking', '--disable-sync', `--user-data-dir=${path.join(temporary, 'browser')}`, '--virtual-time-budget=25000', '--dump-dom', `${base}/__test__/redesign`], { windowsHide: true, timeout: 60_000, maxBuffer: 5_000_000 })
    const match = stdout.match(/<pre id="result">([^<]+)<\/pre>/)
    assert.ok(match, '모든 화면의 브라우저 결과가 필요하다.')
    const results = JSON.parse(match[1])
    assert.equal(results.length, cases.length)
    assert.deepEqual(results.filter(result => !result.ok), [])
    if (process.env.YON_UI_SCREENSHOTS === '1') {
      const report = path.join(cache, 'redesign-review')
      fs.mkdirSync(report, { recursive: true })
      for (const [name, route, width] of [['home', '/', 1440], ['search', '/opportunities', 1440], ['detail', '/opportunities/42', 1440], ['login', '/login', 390], ['home-mobile', '/', 390], ['profile', '/mypage', 1440], ['notifications', '/notifications', 1440], ['privacy', '/privacy', 390]]) {
        await promisify(execFile)(browser, ['--headless', '--disable-gpu', '--no-first-run', '--disable-background-networking', '--hide-scrollbars', `--user-data-dir=${path.join(temporary, 'shots')}`, `--window-size=${width},1100`, '--virtual-time-budget=5000', `--screenshot=${path.join(report, name + '.png')}`, `${base}/__test__/redesign-capture?route=${encodeURIComponent(route)}&width=${width}`], { windowsHide: true, timeout: 30_000, maxBuffer: 500_000 })
      }
    }
  } finally {
    await server.close()
    assert.ok(path.resolve(temporary).startsWith(path.resolve(cache) + path.sep))
    fs.rmSync(temporary, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 })
  }
})
