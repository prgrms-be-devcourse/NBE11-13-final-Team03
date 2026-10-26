import { useRef, useState } from 'react'
import { Link, NavLink, useLocation } from 'react-router-dom'
import { Menu, X } from 'lucide-react'
import AuthControls from './AuthControls'
import styles from './Header.module.css'

const navigation = [
  { to: '/', label: '홈' },
  { to: '/opportunities', label: '기회 탐색' },
  { to: '/saved', label: '저장한 기회' },
  { to: '/notifications', label: '알림' },
  { to: '/mypage', label: '마이페이지' },
]

function Header() {
  const location = useLocation()
  const [isMenuOpen, setIsMenuOpen] = useState(false)
  const [previousLocation, setPreviousLocation] = useState(location)
  const menuButtonRef = useRef<HTMLButtonElement>(null)

  // 뒤로가기를 포함한 경로 변경 시 헤더 DOM을 유지하고 메뉴 상태만 초기화한다.
  if (location !== previousLocation) {
    setPreviousLocation(location)
    setIsMenuOpen(false)
  }

  return (
    <header
      className={styles.header}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && isMenuOpen) {
          setIsMenuOpen(false)
          menuButtonRef.current?.focus()
        }
      }}
    >
      <div className={styles.inner}>
        <Link className={styles.brand} to="/" onClick={() => setIsMenuOpen(false)} aria-label="Y:ON 홈">
          <img className={styles.logo} src="/logo/yon-symbol.png" alt="" width={80} height={80} />
          <span>Y:ON</span>
        </Link>
        <button
          ref={menuButtonRef}
          type="button"
          className={styles.menuButton}
          aria-expanded={isMenuOpen}
          aria-controls="primary-navigation"
          onClick={() => setIsMenuOpen((open) => !open)}
        >
          {isMenuOpen ? <X size={20} aria-hidden="true" /> : <Menu size={20} aria-hidden="true" />}
          {isMenuOpen ? '메뉴 닫기' : '메뉴 열기'}
        </button>
        <nav
          id="primary-navigation"
          aria-label="주요 메뉴"
          className={`${styles.navigation} ${styles.primaryNavigation} ${isMenuOpen ? styles.open : ''}`}
        >
            <ul className={styles.menuList}>
              {navigation.map(({ to, label }) => (
                <li key={to}>
                  <NavLink
                    to={to}
                    end={to === '/'}
                    className={({ isActive }) => `${styles.menuLink} ${isActive ? styles.active : ''}`}
                    onClick={() => setIsMenuOpen(false)}
                  >
                    {label}
                  </NavLink>
                </li>
              ))}
            </ul>
        </nav>
        <AuthControls menuOpen={isMenuOpen} />
      </div>
    </header>
  )
}

export default Header
