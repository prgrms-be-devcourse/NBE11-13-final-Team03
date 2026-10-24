import type { ComponentPropsWithRef } from 'react'
import styles from './Button.module.css'

type ButtonProps = ComponentPropsWithRef<'button'> & {
  variant?: 'primary' | 'secondary' | 'ghost'
  loading?: boolean
}

function Button({
  variant = 'primary',
  loading = false,
  disabled,
  type = 'button',
  className,
  children,
  'aria-busy': ariaBusy,
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      type={type}
      className={`${styles.button} ${styles[variant]} ${className ?? ''}`}
      disabled={disabled || loading}
      aria-busy={loading || ariaBusy}
    >
      {loading ? '처리 중…' : children}
    </button>
  )
}

export default Button
