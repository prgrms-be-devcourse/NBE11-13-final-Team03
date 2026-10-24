import type { ComponentPropsWithoutRef } from 'react'
import styles from './StatusBadge.module.css'

// backend eligibility.domain.entity.EligibilityStatus: 신청 가능성만 나타낸다.
export type EligibilityStatus =
  | 'ELIGIBLE'
  | 'POSSIBLY_ELIGIBLE'
  | 'NOT_ELIGIBLE'
  | 'UNKNOWN'

const statuses = {
  ELIGIBLE: {
    label: '신청 가능',
    description: '확인된 필수조건을 모두 충족합니다.',
  },
  POSSIBLY_ELIGIBLE: {
    label: '신청 가능성 있음',
    description: '사용자 정보가 부족하거나 추가 확인이 필요한 조건이 있습니다.',
  },
  NOT_ELIGIBLE: {
    label: '신청 대상 아님',
    description: '충족하지 않는 필수조건이 있거나 제외 대상에 해당합니다.',
  },
  UNKNOWN: {
    label: '판정 불가',
    description: '정책 조건 정보가 불확실하거나 없어 신청 가능성을 판정할 수 없습니다.',
  },
} satisfies Record<EligibilityStatus, { label: string; description: string }>

type StatusBadgeProps = Omit<ComponentPropsWithoutRef<'span'>, 'children'> & {
  status: EligibilityStatus
}

function StatusBadge({ status, className, title, ...props }: StatusBadgeProps) {
  const { label, description } = statuses[status]

  return (
    <span
      {...props}
      className={`${styles.badge} ${styles[status]} ${className ?? ''}`}
      title={title ?? description}
    >
      {label}
    </span>
  )
}

export default StatusBadge
