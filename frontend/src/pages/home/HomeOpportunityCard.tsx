import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import Card from '../../components/ui/Card'
import StatusBadge from '../../components/ui/StatusBadge'
import type { EligibilityStatus } from '../../components/ui/StatusBadge'
import type { OpportunitySearchItem } from '../opportunities/types'
import { isValidDate, typeLabels } from '../opportunities/searchParams'
import styles from './HomePage.module.css'

type CardItem = Pick<OpportunitySearchItem, 'opportunityId' | 'type' | 'title' | 'category' | 'subCategory' | 'detailCategory' | 'saved'> & {
  createdAt?: string | null
  eligibilityStatus: EligibilityStatus | null
}

// 홈의 공개 목록과 추천 카드가 공유하는 실제 공고 정보만 묶는다.
function HomeOpportunityCard({ item, children }: { item: CardItem; children?: ReactNode }) {
  const registeredDate = item.createdAt?.slice(0, 10)
  return <Card className={styles.opportunityCard}>
    <div className={styles.cardMeta}><span className={styles.type}>{typeLabels[item.type]}</span><span>{item.saved ? '저장됨' : '저장 안 함'}</span></div>
    <h3><Link to={`/opportunities/${item.opportunityId}`}>{item.title || '제목 미확인'}</Link></h3>
    <p className={styles.classification}>{[item.category, item.subCategory, item.detailCategory].filter(Boolean).join(' · ') || '분류 미확인'}</p>
    <div className={styles.cardFooter}>
      {registeredDate && isValidDate(registeredDate) && <span>최초 등록일 <time dateTime={registeredDate}>{registeredDate}</time></span>}
      {item.eligibilityStatus !== null && <StatusBadge status={item.eligibilityStatus} />}
    </div>
    {children}
  </Card>
}

export default HomeOpportunityCard
