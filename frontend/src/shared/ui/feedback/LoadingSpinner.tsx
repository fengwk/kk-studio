import type { HTMLAttributes } from 'react'
import { LoaderCircle } from 'lucide-react'
import './feedback.css'

export interface LoadingSpinnerProps extends HTMLAttributes<HTMLSpanElement> {
  size?: 'sm' | 'md' | 'lg' | number
  label?: string
  className?: string
  /** 当外层容器已有 status/busy 语义时设为 true，作为纯装饰图标 */
  decorative?: boolean
}

/**
 * 主题加载旋转指示器：使用 currentColor，支持 aria-busy 与 role="status"。
 * 遵循 prefers-reduced-motion，减弱/关闭动画时展示静态提示。
 */
export function LoadingSpinner({
  size = 'sm',
  label,
  className,
  decorative = false,
  ...rest
}: LoadingSpinnerProps) {
  const pixelSize =
    typeof size === 'number'
      ? size
      : size === 'lg'
        ? 24
        : size === 'md'
          ? 18
          : 14

  return (
    <span
      role={decorative ? undefined : 'status'}
      aria-busy={decorative ? undefined : 'true'}
      className={['ui-loading-spinner', className].filter(Boolean).join(' ')}
      {...rest}
    >
      <LoaderCircle size={pixelSize} className="ui-loading-spinner-icon" aria-hidden="true" />
      {label && !decorative ? <span className="sr-only">{label}</span> : null}
    </span>
  )
}

export interface LoadingIndicatorProps extends HTMLAttributes<HTMLDivElement> {
  label?: string
  size?: 'sm' | 'md' | 'lg' | number
  className?: string
}

/**
 * 局部加载状态条：带主题 Spinner 与可选说明文案，用于替代纯文字的加载占位块。
 */
export function LoadingIndicator({
  label,
  size = 'md',
  className,
  ...rest
}: LoadingIndicatorProps) {
  return (
    <div
      role="status"
      aria-busy="true"
      className={['ui-loading-indicator', className].filter(Boolean).join(' ')}
      {...rest}
    >
      <LoadingSpinner size={size} decorative />
      {label ? <span className="ui-loading-indicator-label">{label}</span> : null}
    </div>
  )
}
