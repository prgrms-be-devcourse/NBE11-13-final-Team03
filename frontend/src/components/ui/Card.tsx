import type { ComponentPropsWithoutRef } from 'react'
import styles from './Card.module.css'

type CardProps = ComponentPropsWithoutRef<'div'> & {
  variant?: 'default' | 'sage'
}

function Card({ variant = 'default', className, ...props }: CardProps) {
  return (
    <div {...props} className={`${styles.card} ${styles[variant]} ${className ?? ''}`} />
  )
}

export default Card
