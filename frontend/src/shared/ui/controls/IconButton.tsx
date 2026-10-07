import type { ButtonHTMLAttributes, ReactNode } from 'react'
import './controls.css'

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** 图标按钮没有可见文本，必须提供无障碍名称。 */
  label: string
  /** danger 用于破坏性图标动作。 */
  danger?: boolean
  /** 普通 32px；紧凑 28px（不牺牲焦点、点击与阅读）。 */
  size?: 'normal' | 'compact'
  children: ReactNode
}

/**
 * 共享图标按钮：统一圆形图标按钮的尺寸、悬停、焦点与禁用态。
 * `className` 可用于叠加既有 feature 修饰类。
 */
export function IconButton({
  label,
  danger = false,
  size = 'normal',
  className,
  type = 'button',
  children,
  ...rest
}: IconButtonProps) {
  const classes = [
    'icon-button',
    danger ? 'danger' : '',
    size === 'compact' ? 'is-compact' : '',
    className,
  ]
    .filter(Boolean)
    .join(' ')
  return (
    <button type={type} className={classes} aria-label={label} {...rest}>
      {children}
    </button>
  )
}
