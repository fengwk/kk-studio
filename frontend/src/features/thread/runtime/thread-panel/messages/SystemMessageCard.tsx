import type { ComponentProps, ReactNode } from 'react'
import './system-message.css'

/**
 * 全宽淡色系统消息外壳：无图标列与左缩进，通知与压缩摘要共用同一外观。
 *
 * <p>title 固定在第一行，actions 位于同一行最右（例如展开/收起按钮）；
 * children 是正文。它只承载外观与结构，不解释具体 Entry 语义。
 */
export function SystemMessageCard({
  title,
  actions,
  children,
  className,
  ...rest
}: {
  title: ReactNode
  actions?: ReactNode
  children?: ReactNode
  className?: string
} & Omit<ComponentProps<'section'>, 'title' | 'children' | 'className'>) {
  return (
    <section
      className={['thread-block', 'thread-system-message', className].filter(Boolean).join(' ')}
      {...rest}
    >
      <div className="thread-system-message-header">
        <div className="thread-system-message-title">{title}</div>
        {actions != null && <div className="thread-system-message-actions">{actions}</div>}
      </div>
      {children}
    </section>
  )
}
