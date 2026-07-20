import { memo } from 'react'
import { ThinkingBlock } from '@/features/ai/thread-panel/messages/ThinkingBlock'
import type { TextDialogueMessage } from '@/features/ai/thread-events'
import { CopyButton } from '@/shared/ui/markdown/CodeBlock'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'

/**
 * Assistant：thinking 纯文本 + 正文 Markdown。
 * 正文块悬停右上角复制全文；悬停内部 ``` 时隐藏外层复制，避免叠两枚按钮。
 */
export const AssistantMessageBlock = memo(function AssistantMessageBlock({
  message,
}: {
  message: TextDialogueMessage
}) {
  const thinking = message.thinking ?? ''
  const text = message.text.trim()
  const streaming = message.status === 'streaming'
  const hasText = text.length > 0
  const hasThinking = thinking.trim().length > 0

  return (
    <div className={`thread-turn thread-turn-assistant ${message.status === 'error' ? 'error' : ''}`}>
      <ThinkingBlock thinking={thinking} streaming={streaming && !hasText} />
      {hasText ? (
        <section className="thread-block thread-block-assistant thread-assistant-shell">
          <CopyButton source={text} className="thread-assistant-copy" label="复制全文" />
          <div className="thread-block-body thread-assistant-text">
            <MarkdownRenderer content={text} />
          </div>
        </section>
      ) : null}
      {streaming && !hasText && !hasThinking ? (
        <section className="thread-block thread-block-assistant streaming">
          <div className="thread-block-body thread-streaming-hint">…</div>
        </section>
      ) : null}
      {!hasText && !hasThinking && message.status === 'error' ? (
        <section className="thread-block thread-block-assistant error">
          <div className="thread-block-body">助手回复失败</div>
        </section>
      ) : null}
    </div>
  )
}, (prev, next) => {
  const a = prev.message
  const b = next.message
  return a.id === b.id && a.status === b.status && a.text === b.text && a.thinking === b.thinking
})
