import { useRef, useState, type FormEvent } from 'react'
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { Menu, X, Search, House, Bookmark, Bell, UserRound } from 'lucide-react'
import { defaultSearchParams, serializeSearchParams } from '../pages/opportunities/searchParams'
import AuthControls from './AuthControls'
import NotificationBadge from './NotificationBadge'
import styles from './Header.module.css'

const navigation = [
  { to: '/', label: '홈', icon: House },
  { to: '/opportunities', label: '기회 탐색', icon: Search },
  { to: '/saved', label: '저장한 기회', icon: Bookmark },
  { to: '/notifications', label: '알림', icon: Bell },
  { to: '/mypage', label: '마이페이지', icon: UserRound },
]

function Header() {
  const location = useLocation()
  const navigate = useNavigate()
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const keyword = String(new FormData(event.currentTarget).get('keyword') ?? '').trim()
    const query = serializeSearchParams({ ...defaultSearchParams(), keyword }).toString()
    navigate(`/opportunities${query ? `?${query}` : ''}`)
    setIsMenuOpen(false)
  }
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
        <form className={styles.headerSearch} role="search" aria-label="헤더 공고 제목 검색" onSubmit={search}>
          <label className={styles.searchLabel} htmlFor="header-keyword">공고 제목</label>
          <input id="header-keyword" name="keyword" type="search" placeholder="어떤 기회를 찾고 있나요?" autoComplete="off" />
          <button type="submit" aria-label="기회 검색"><Search size={20} aria-hidden="true" /></button>
        </form>
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
              {navigation.map(({ to, label, icon: Icon }) => (
                <li key={to}>
                  <NavLink
                    to={to}
                    end={to === '/'}
                    className={({ isActive }) => `${styles.menuLink} ${isActive ? styles.active : ''}`}
                    onClick={() => setIsMenuOpen(false)}
                  >
                    <Icon size={18} aria-hidden="true" />
                    {label}
                    {to === '/notifications' && <NotificationBadge />}
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
