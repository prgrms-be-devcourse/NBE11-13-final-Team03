export type PushConfig = { apiKey: string; authDomain: string; projectId: string; messagingSenderId: string; appId: string; vapidKey: string }
export const pushEnvironmentKeys = ['VITE_FIREBASE_API_KEY', 'VITE_FIREBASE_AUTH_DOMAIN', 'VITE_FIREBASE_PROJECT_ID', 'VITE_FIREBASE_MESSAGING_SENDER_ID', 'VITE_FIREBASE_APP_ID', 'VITE_FIREBASE_VAPID_KEY'] as const
export function readPushConfig(env: Record<string, unknown>): PushConfig | null {
  const values = pushEnvironmentKeys.map(key => env[key])
  if (values.some(value => typeof value !== 'string' || !value.trim())) return null
  const [apiKey, authDomain, projectId, messagingSenderId, appId, vapidKey] = values as string[]
  return { apiKey, authDomain, projectId, messagingSenderId, appId, vapidKey }
}
export function supportsPush(env: { secure: boolean; notification: boolean; serviceWorker: boolean; pushManager: boolean; locks: boolean }): boolean {
  return Object.values(env).every(Boolean)
}
