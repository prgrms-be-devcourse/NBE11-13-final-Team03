import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import Card from '../../components/ui/Card'
import StatusBadge from '../../components/ui/StatusBadge'
import PreferenceActions from '../../components/opportunities/PreferenceActions'
import OpportunityTypeBadge from '../../components/opportunities/OpportunityTypeBadge'
import type { PreferenceSource } from '../../api/preferences'
import type { EligibilityStatus } from '../../components/ui/StatusBadge'
import type { OpportunitySearchItem } from '../opportunities/types'
import { classificationLabel, isValidDate } from '../opportunities/searchParams'
import { formatDate } from '../../utils/date'
import styles from './HomePage.module.css'

type CardItem = Pick<OpportunitySearchItem, 'opportunityId' | 'type' | 'title' | 'category' | 'subCategory' | 'detailCategory' | 'saved'> & {
  createdAt?: string | null
  eligibilityStatus: EligibilityStatus | null
}

// 홈의 공개 목록과 추천 카드가 공유하는 실제 공고 정보만 묶는다.
function HomeOpportunityCard({ item, children, source = 'OTHER', allowHide = false, disabled = false, recommendation = false }: { item: CardItem; children?: ReactNode; source?: PreferenceSource; allowHide?: boolean; disabled?: boolean; recommendation?: boolean }) {
  const registeredDate = item.createdAt?.slice(0, 10)
  const validRegisteredDate = !recommendation && registeredDate && isValidDate(registeredDate)
  const classification = classificationLabel(item)
  return <Card className={`${styles.opportunityCard} ${recommendation ? styles.recommendationCard : ''}`} data-accent={item.type}>
    <div className={styles.cardMeta}><OpportunityTypeBadge type={item.type} /><PreferenceActions opportunityId={item.opportunityId} title={item.title} saved={item.saved} source={source} allowHide={allowHide} disabled={disabled} iconOnly /></div>
    <h3><Link to={`/opportunities/${item.opportunityId}`}>{item.title || '제목 미확인'}</Link></h3>
    {classification && <p className={styles.classification}>{classification}</p>}
    {(validRegisteredDate || item.eligibilityStatus !== null) && <div className={styles.cardFooter}>
      {validRegisteredDate && <span>최초 등록일 <time dateTime={registeredDate}>{formatDate(registeredDate)}</time></span>}
      {item.eligibilityStatus !== null && <StatusBadge status={item.eligibilityStatus} />}
    </div>}
    {children}
  </Card>
}

export default HomeOpportunityCard
