import { useId } from 'react'
import type { ComponentPropsWithRef } from 'react'
import styles from './TextField.module.css'

type TextFieldProps = ComponentPropsWithRef<'input'> & {
  label: string
  error?: string
}

function TextField({
  label,
  error,
  id,
  type = 'text',
  className,
  'aria-describedby': ariaDescribedBy,
  'aria-invalid': ariaInvalid,
  ...props
}: TextFieldProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const errorId = `${inputId}-error`
  const describedBy = [ariaDescribedBy, error ? errorId : undefined]
    .filter(Boolean)
    .join(' ') || undefined

  return (
    <div className={styles.field}>
      <label className={styles.label} htmlFor={inputId}>{label}</label>
      <input
        {...props}
        id={inputId}
        type={type}
        className={`${styles.input} ${className ?? ''}`}
        aria-invalid={error ? true : ariaInvalid}
        aria-describedby={describedBy}
      />
      {error && <p id={errorId} className={styles.error}>{error}</p>}
    </div>
  )
}

export default TextField
