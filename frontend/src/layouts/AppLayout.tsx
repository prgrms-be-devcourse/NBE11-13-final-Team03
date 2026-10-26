import { Link, Outlet, useLocation } from 'react-router-dom'
import Header from './Header'
import styles from './AppLayout.module.css'

function AppLayout() {
  const { pathname } = useLocation()
  return (
    <>
      <a className={styles.skipLink} href="#main-content">본문으로 건너뛰기</a>
      <Header />
      <main id="main-content" className={styles.main} tabIndex={-1}>
        <div className={`${styles.content}${pathname === '/login' ? ` ${styles.loginContent}` : ''}`}>
          <Outlet />
        </div>
      </main>
      <footer className={styles.footer}>
        <span>Y:ON · 기웃기웃</span>
        <nav aria-label="서비스 정책">
          <Link to="/privacy">개인정보처리방침</Link>
          <Link to="/terms">서비스 이용약관</Link>
        </nav>
      </footer>
    </>
  )
}

export default AppLayout
