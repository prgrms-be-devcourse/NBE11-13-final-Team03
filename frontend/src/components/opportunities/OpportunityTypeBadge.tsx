import type { OpportunityType } from '../../pages/opportunities/types'
import { typeLabels } from '../../pages/opportunities/searchParams'
import styles from './OpportunityTypeBadge.module.css'

// 공고 유형(청년정책·직업훈련)만 나타낸다. 신청 가능성(StatusBadge)·모집 상태와는 다른 배지다.
function OpportunityTypeBadge({ type }: { type: OpportunityType }) {
  return <span className={styles.typeBadge} data-opportunity-type={type}>{typeLabels[type]}</span>
}

export default OpportunityTypeBadge
