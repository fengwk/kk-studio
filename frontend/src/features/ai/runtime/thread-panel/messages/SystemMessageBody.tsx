import type { ComponentProps } from 'react'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

/**
 * 系统消息正文：复用现有安全 Markdown 渲染器，不做 XML/HTML 解析。
 *
 * 启用 preserveRawText：顶层/inline raw 节点渲染为安全 span.md-raw-text，
 * 保留原始换行（由 system-message.css 处理），普通 Markdown 块级子节点仍按 normal 折叠。
 */
export function SystemMessageBody({
  content,
  className,
  ...rest
}: {
  content: string
  className?: string
} & Omit<ComponentProps<'div'>, 'content' | 'children' | 'className'>) {
  return (
    <div className={['thread-system-message-body', className].filter(Boolean).join(' ')} {...rest}>
      <MarkdownRenderer content={content} preserveRawText />
    </div>
  )
}
