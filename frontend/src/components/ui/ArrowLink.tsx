import { Link } from 'react-router-dom'
import type { LinkProps } from 'react-router-dom'
import { ArrowLeft, ArrowRight } from 'lucide-react'
import styles from './ArrowLink.module.css'

type ArrowLinkProps = LinkProps & { direction?: 'back' | 'forward' }

// 뒤로가기는 왼쪽 화살표 + 목적지 이름, 일반 이동은 문구 + 오른쪽 화살표로 통일한다.
function ArrowLink({ direction = 'forward', className, children, ...props }: ArrowLinkProps) {
  return <Link {...props} className={`${styles.arrowLink} ${className ?? ''}`}>
    {direction === 'back' && <ArrowLeft size={18} aria-hidden="true" />}{children}{direction === 'forward' && <ArrowRight size={16} aria-hidden="true" />}
  </Link>
}

export default ArrowLink
