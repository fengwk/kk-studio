import type { ButtonHTMLAttributes } from 'react'

/** 设计系统按钮变体：对应既有的三类按钮视觉。 */
export type ButtonVariant = 'primary' | 'ghost' | 'inline'

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'btn-primary',
  ghost: 'ghost-btn',
  inline: 'ghost-inline-btn',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  /** danger 用于破坏性动作（配合主题的 danger 色）。 */
  danger?: boolean
}

/**
 * 共享按钮：把设计系统的按钮变体收敛为单一入口，避免各处重复拼装 class。
 * 保留原生 button 语义与属性透传（type/disabled/aria-* 等）。
 */
export function Button({
  variant = 'primary',
  danger = false,
  className,
  type = 'button',
  children,
  ...rest
}: ButtonProps) {
  const classes = [VARIANT_CLASS[variant], danger ? 'danger' : '', className]
    .filter(Boolean)
    .join(' ')
  return (
    <button type={type} className={classes} {...rest}>
      {children}
    </button>
  )
}
