import { memo } from 'react'
import { ThinkingBlock } from '@/features/ai/runtime/thread-panel/messages/ThinkingBlock'
import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { CopyButton } from '@/shared/ui/markdown/CodeBlock'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import { useI18n } from '@/shared/i18n'

/**
 * Assistant：thinking 纯文本 + 正文 Markdown。
 * 正文块悬停右上角复制全文；悬停内部 ``` 时隐藏外层复制，避免叠两枚按钮。
 */
export const AssistantMessageBlock = memo(function AssistantMessageBlock({
  message,
}: {
  message: TextDialogueMessage
}) {
  const { t } = useI18n()
  const thinking = message.thinking ?? ''
  const text = message.text.trim()
  const streaming = message.status === 'streaming'
  const hasText = text.length > 0
  const hasThinking = thinking.trim().length > 0
  const aborted = message.aborted === true

  return (
    <div
      className={`thread-turn thread-turn-assistant ${message.status === 'error' ? 'error' : ''} ${
        aborted ? 'aborted' : ''
      }`}
    >
      {aborted ? (
        <div className="thread-block thread-block-assistant thread-assistant-aborted-tag">
          <div className="thread-block-body">{t('ai.runtime.message.stopped')}</div>
        </div>
      ) : null}
      <ThinkingBlock thinking={thinking} streaming={streaming} />
      {hasText ? (
        <section className="thread-block thread-block-assistant thread-assistant-shell">
          <CopyButton
            source={text}
            className="thread-assistant-copy"
            label={t('ai.runtime.message.copyAll')}
          />
          <div className="thread-block-body thread-assistant-text">
            {message.status === 'error' ? (
              // Provider 失败必须保持原始内容（JSON/HTML 响应体）。Markdown 会转义或重排它们。
              <pre className="thread-error-raw">{text}</pre>
            ) : (
              <MarkdownRenderer content={text} />
            )}
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
          <div className="thread-block-body">{t('ai.runtime.message.assistantFailed')}</div>
        </section>
      ) : null}
    </div>
  )
}, (prev, next) => {
  const a = prev.message
  const b = next.message
  return (
    a.id === b.id
    && a.status === b.status
    && a.text === b.text
    && a.thinking === b.thinking
    && a.aborted === b.aborted
  )
})
