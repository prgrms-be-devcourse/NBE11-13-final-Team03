import type { ComponentPropsWithoutRef } from 'react'
import styles from './StatusBadge.module.css'

// backend eligibility.domain.entity.EligibilityStatus: 신청 가능성만 나타낸다.
export type EligibilityStatus =
  | 'ELIGIBLE'
  | 'POSSIBLY_ELIGIBLE'
  | 'NOT_ELIGIBLE'
  | 'UNKNOWN'

const statuses = {
  ELIGIBLE: '신청 가능',
  POSSIBLY_ELIGIBLE: '신청 가능성 있음',
  NOT_ELIGIBLE: '신청 대상 아님',
  UNKNOWN: '판정 불가',
} satisfies Record<EligibilityStatus, string>

type StatusBadgeProps = Omit<ComponentPropsWithoutRef<'span'>, 'children'> & {
  status: EligibilityStatus
}

function StatusBadge({ status, className, ...props }: StatusBadgeProps) {
  return (
    <span
      {...props}
      className={`${styles.badge} ${styles[status]} ${className ?? ''}`}
    >
      {statuses[status]}
    </span>
  )
}

export default StatusBadge
