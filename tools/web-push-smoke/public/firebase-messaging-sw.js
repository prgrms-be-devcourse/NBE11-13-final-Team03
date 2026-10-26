/* Public web configuration only. No service account JSON or private key belongs in this worker. */
importScripts('https://www.gstatic.com/firebasejs/13.0.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/13.0.0/firebase-messaging-compat.js');

const config = JSON.parse(new URL(self.location.href).searchParams.get('config'));
if (!config?.projectId || !config?.apiKey || !config?.messagingSenderId || !config?.appId) {
  throw new Error('Firebase public web configuration is missing');
}
firebase.initializeApp(config);
firebase.messaging().onBackgroundMessage(async (payload) => {
  const pages = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  for (const page of pages) page.postMessage({ type: 'YON_PUSH_BACKGROUND', payload });
  // FCM displays notification payloads automatically; do not display them twice.
  if (!payload.notification) {
    await self.registration.showNotification(payload.data?.title || 'Y:ON 테스트 메시지', {
      body: payload.data?.body || '데이터 메시지를 수신했습니다.',
    });
  }
});
