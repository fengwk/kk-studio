import type { HTMLAttributes } from 'react'
import './data.css'

/** 状态语义：success 真实成功/就绪，warning 等待/提醒，danger 失败/阻塞，info 进行中/信息，accent 身份标记。 */
export type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info'

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone
  /** 等宽：标识符、版本号和状态码等数据值。 */
  mono?: boolean
}

/** 共享状态徽标：颜色只表达语义，含义仍由文字承载。 */
export function Badge({ tone = 'neutral', mono = false, className, children, ...rest }: BadgeProps) {
  const classes = ['ui-badge', `is-${tone}`, mono ? 'is-mono' : '', className].filter(Boolean).join(' ')
  return (
    <span className={classes} {...rest}>
      {children}
    </span>
  )
}
