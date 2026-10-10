import type { ButtonHTMLAttributes } from 'react'
import { LoadingSpinner } from '@/shared/ui/feedback/LoadingSpinner'

/** 设计系统按钮变体：对应既有的三类按钮视觉。 */
export type ButtonVariant = 'primary' | 'ghost' | 'inline'
/** 普通控件 32px；紧凑控件 28px（卡片动作、工具条等）。 */
export type ButtonSize = 'normal' | 'compact'

const VARIANT_CLASS: Record<ButtonVariant, string> = {
  primary: 'btn-primary',
  ghost: 'ghost-btn',
  inline: 'ghost-inline-btn',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  /** danger 用于破坏性动作（配合主题的 danger 色）。 */
  danger?: boolean
  size?: ButtonSize
  /**
   * loading 状态：
   * 显示匹配 currentColor 的 LoadingSpinner，设置 aria-busy="true"，
   * 自动禁用以阻止重复点击提交，并保持原有按钮文本清晰稳定（无需替换为“...”）。
   */
  loading?: boolean
}

/**
 * 共享按钮：把设计系统的按钮变体收敛为单一入口，避免各处重复拼装 class。
 * 保留原生 button 语义与属性透传（type/disabled/aria-* 等）。
 */
export function Button({
  variant = 'primary',
  danger = false,
  size = 'normal',
  loading = false,
  className,
  type = 'button',
  disabled,
  children,
  ...rest
}: ButtonProps) {
  const classes = [
    VARIANT_CLASS[variant],
    danger ? 'danger' : '',
    size === 'compact' ? 'is-compact' : '',
    loading ? 'is-loading' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ')

  const spinnerSize = size === 'compact' ? 14 : 16

  return (
    <button
      type={type}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading ? 'true' : rest['aria-busy']}
      {...rest}
    >
      {loading ? <LoadingSpinner size={spinnerSize} decorative /> : null}
      {children}
    </button>
  )
}
