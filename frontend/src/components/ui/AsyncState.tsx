import Button from './Button'
import styles from './AsyncState.module.css'

type AsyncStateProps = {
  title?: string
  description?: string
} & (
  | { status: 'loading' | 'empty'; onRetry?: never }
  | { status: 'error'; onRetry?: () => void }
)

const defaultTitles = {
  loading: '불러오는 중이에요',
  empty: '결과가 없어요',
  error: '정보를 불러오지 못했어요',
}

function AsyncState({ status, title, description, onRetry }: AsyncStateProps) {
  return (
    <div
      className={styles.state}
      role={status === 'error' ? 'alert' : 'status'}
      aria-atomic="true"
    >
      <p className={styles.title}>{title ?? defaultTitles[status]}</p>
      {description && <p className={styles.description}>{description}</p>}
      {status === 'error' && onRetry && (
        <Button variant="secondary" onClick={onRetry}>다시 시도</Button>
      )}
    </div>
  )
}

export default AsyncState
