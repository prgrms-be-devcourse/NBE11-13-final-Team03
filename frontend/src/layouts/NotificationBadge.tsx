import { useContext } from 'react'
import { useQuery } from '@tanstack/react-query'
import { AuthContext } from '../auth/AuthContext'
import { unreadCountQuery } from '../api/notifications'
import styles from './Header.module.css'

function Count({ userId }: { userId: number }) {
  const result = useQuery(unreadCountQuery(userId))
  if (result.isPending) return <span className={styles.notificationBadge} aria-label="미읽음 알림 수 확인 중">…</span>
  if (result.isError || !result.data.available) return <span className={styles.notificationBadge} aria-label="미읽음 알림 수 확인 불가">!</span>
  const count = result.data.unreadCount
  return <span className={styles.notificationBadge} aria-label={`미읽음 알림 ${count}개`}>{count > 99 ? '99+' : count}</span>
}
export default function NotificationBadge() {
  const auth = useContext(AuthContext)
  return auth?.status === 'authenticated' && auth.user ? <Count key={auth.user.id} userId={auth.user.id} /> : null
}
