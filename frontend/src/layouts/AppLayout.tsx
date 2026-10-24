import { Outlet } from 'react-router-dom'
import Header from './Header'
import styles from './AppLayout.module.css'

function AppLayout() {
  return (
    <>
      <a className={styles.skipLink} href="#main-content">본문으로 건너뛰기</a>
      <Header />
      <main id="main-content" className={styles.main} tabIndex={-1}>
        <div className={styles.content}>
          <Outlet />
        </div>
      </main>
    </>
  )
}

export default AppLayout
