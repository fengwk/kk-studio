import type { ReactNode } from 'react'
import '@/features/ai/runtime/thread-panel/thread-widgets.css'

/**
 * 通用 Widget 外壳：只负责标题、可选操作、外观与有界滚动。
 * 具体业务内容由 children 提供；不使用 Widget 注册协议或通用面板管理器。
 */
export function ThreadWidgetPanel({
  title,
  actions,
  className,
  children,
}: {
  title: string
  actions?: ReactNode
  className?: string
  children: ReactNode
}) {
  return (
    <section
      className={['thread-widget-panel', className].filter(Boolean).join(' ')}
      aria-label={title}
    >
      <header className="thread-widget-panel-header">
        <h3 className="thread-widget-panel-title">{title}</h3>
        {actions == null ? null : <div className="thread-widget-panel-actions">{actions}</div>}
      </header>
      <div className="thread-widget-panel-body">{children}</div>
    </section>
  )
}
