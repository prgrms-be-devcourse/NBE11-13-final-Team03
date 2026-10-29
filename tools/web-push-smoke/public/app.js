import { waitForWorkerActivation } from './worker-ready.mjs';

const SDK = 'https://www.gstatic.com/firebasejs/13.0.0/';
const form = document.querySelector('#setup');
const connect = document.querySelector('#connect');
const copy = document.querySelector('#copy');
const disconnect = document.querySelector('#disconnect');
const tokenField = document.querySelector('#token');
const status = document.querySelector('#status');
const messages = document.querySelector('#messages');
let session;
const received = [];

function report(message, error = false) {
  status.textContent = message;
  status.dataset.error = String(error);
}

function recordMessage(source, payload) {
  received.unshift({ time: new Date().toISOString(), source, payload });
  received.splice(20);
  messages.textContent = JSON.stringify(received, null, 2);
}

async function cleanup() {
  if (!session) return;
  await session.messagingApi.deleteToken(session.messaging);
  await session.registration.unregister();
  session.unsubscribe();
  await session.appApi.deleteApp(session.app);
  session = undefined;
  tokenField.value = '';
  copy.disabled = true;
  disconnect.disabled = true;
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!window.isSecureContext || !('serviceWorker' in navigator) || !('Notification' in window)) {
    report('HTTPS 또는 localhost에서 지원 브라우저로 접속하세요. Chrome 일반 창을 권장합니다.', true);
    return;
  }
  connect.disabled = true;
  try {
    // Request permission from the user's click before loading network dependencies.
    if (await Notification.requestPermission() !== 'granted') {
      throw new Error('브라우저 사이트 설정에서 알림 권한을 허용하세요.');
    }
    report('Firebase SDK와 서비스 워커를 준비하고 있습니다…');
    const values = new FormData(form);
    const config = Object.fromEntries(['apiKey', 'authDomain', 'projectId', 'messagingSenderId', 'appId']
      .map((key) => [key, String(values.get(key)).trim()]));
    const vapidKey = String(values.get('vapidKey')).trim();
    if (Object.values(config).some((value) => !value) || !vapidKey) throw new Error('설정값을 모두 입력하세요.');
    const [appApi, messagingApi] = await Promise.all([
      import(`${SDK}firebase-app.js`), import(`${SDK}firebase-messaging.js`),
    ]);
    if (!await messagingApi.isSupported()) throw new Error('현재 브라우저는 FCM 웹 푸시를 지원하지 않습니다.');
    await cleanup();
    const app = appApi.initializeApp(config);
    let registration;
    try {
      const workerUrl = new URL('firebase-messaging-sw.js', location.href);
      // Public configuration persists in the worker script URL so it can restart without an open page.
      workerUrl.searchParams.set('config', JSON.stringify(config));
      registration = await navigator.serviceWorker.register(workerUrl.href);
      report('서비스 워커가 활성화될 때까지 기다리고 있습니다…');
      await waitForWorkerActivation(registration);
      const messaging = messagingApi.getMessaging(app);
      const unsubscribe = messagingApi.onMessage(messaging, (payload) => recordMessage('foreground', payload));
      session = { app, appApi, messaging, messagingApi, registration, unsubscribe };
      disconnect.disabled = false;
      const token = await messagingApi.getToken(messaging, { vapidKey, serviceWorkerRegistration: registration });
      if (!token) throw new Error('FCM 토큰이 반환되지 않았습니다.');
      tokenField.value = token;
      copy.disabled = false;
      report('토큰 발급 완료. 같은 Firebase 프로젝트에서 테스트 메시지를 보내세요.');
    } catch (error) {
      if (!session) {
        await registration?.unregister();
        await appApi.deleteApp(app);
      }
      throw error;
    }
  } catch (error) {
    report(`준비 실패: ${error.code || ''} ${error.message}\n키·VAPID 공개 키·프로젝트 일치 여부와 사이트 권한을 확인하세요.`, true);
  } finally {
    connect.disabled = false;
  }
});

copy.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(tokenField.value);
    report('토큰을 복사했습니다. 테스트 담당자에게만 전달하세요.');
  } catch {
    tokenField.select();
    report('자동 복사가 제한됐습니다. 선택된 토큰을 직접 복사하세요.');
  }
});

disconnect.addEventListener('click', async () => {
  disconnect.disabled = true;
  try {
    await cleanup();
    report('테스트 토큰과 서비스 워커를 해제했습니다.');
  } catch (error) {
    disconnect.disabled = false;
    report(`해제 실패: ${error.message}. 다시 시도하거나 브라우저 사이트 데이터를 삭제하세요.`, true);
  }
});

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.addEventListener('message', (event) => {
    if (event.data?.type === 'YON_PUSH_BACKGROUND') recordMessage('background', event.data.payload);
  });
}
