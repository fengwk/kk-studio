import { useState, type ComponentProps, type ReactNode } from 'react'
import { SYSTEM_MESSAGE_DISCLOSURE } from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'
import { ThreadDisclosure } from '@/features/thread/runtime/thread-panel/messages/ThreadDisclosure'
import './system-message.css'

/**
 * 系统消息外壳适配器：把标题与正文投影到统一只读容器，并统一持有展开状态。
 *
 * <p>系统消息正文默认 0 行（含 fork 提示），收起时只显示标题，展开后完整显示。
 * 它只负责布局和展开交互，不解释具体 Entry 语义；title 同时作为容器的可访问名称。
 */
export function SystemMessageCard({
  title,
  label,
  children,
  className,
  ...rest
}: {
  title: ReactNode
  label?: string
  children?: ReactNode
  className?: string
} & Omit<ComponentProps<'section'>, 'title' | 'children' | 'className'>) {
  const [expanded, setExpanded] = useState(false)
  const hasContent = children != null && children !== false
  // 系统消息收起 0 行、展开全部；Markdown 正文不做行截取或二次解析。
  const showContent = expanded || SYSTEM_MESSAGE_DISCLOSURE.preview !== 0
  return (
    <ThreadDisclosure
      {...rest}
      className={['thread-system-message', className].filter(Boolean).join(' ')}
      header={title}
      tone="neutral"
      expanded={expanded}
      onExpandedChange={setExpanded}
      hasDetails={hasContent && !showContent}
      label={label ?? (typeof title === 'string' ? title : '')}
      preview={showContent ? children : undefined}
    />
  )
}
