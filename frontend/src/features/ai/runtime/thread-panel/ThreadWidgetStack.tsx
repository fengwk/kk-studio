import { useLayoutEffect, useRef, type ReactNode } from 'react'
import { ThreadWorkingStatus } from '@/features/ai/runtime/thread-panel/ThreadWorkingStatus'
import type { QueuedThreadMessage } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/**
 * 对话 transcript 下方的小部件区域。
 *
 * Working 是固定单行状态盒；Queued 与自定义 widget 分别占用独立盒子。
 * 无 working / 排队 / 子组件时：不挂载，或挂载后由 CSS `:empty` 压成 0 高度
 * （子组件若全部 return null，DOM 为空，不会留下边框空隙）。
 */
export function ThreadWidgetStack({
  working,
  queuedMessages = [],
  children,
}: {
  working: boolean
  queuedMessages?: QueuedThreadMessage[]
  children?: ReactNode
}) {
  const { t } = useI18n()
  const queueRef = useRef<HTMLOListElement>(null)
  const hasQueuedMessages = queuedMessages.length > 0
  const latestQueuedMessageId = queuedMessages.at(-1)?.idempotencyKey
  const showWorking = working

  useLayoutEffect(() => {
    const queue = queueRef.current
    if (latestQueuedMessageId && queue && queue.scrollHeight > queue.clientHeight) {
      queue.scrollTop = queue.scrollHeight
    }
  }, [latestQueuedMessageId])

  // 连 children 都没传时直接不挂载
  if (!showWorking && !hasQueuedMessages && children == null) {
    return null
  }

  return (
    <section className="thread-widget-stack" aria-label={t('ai.runtime.thread.widgetZone')}>
      <ThreadWorkingStatus active={showWorking} />
      {hasQueuedMessages ? (
        <ol
          ref={queueRef}
          className="thread-input-queue"
          aria-label={t('ai.runtime.thread.queue')}
        >
          {queuedMessages.map((message) => (
            <li key={message.idempotencyKey} className="thread-input-queue-item">
              <span className="thread-input-queue-label">{t('ai.runtime.thread.queued')}</span>
              <span className="thread-input-queue-content">{message.text}</span>
            </li>
          ))}
        </ol>
      ) : null}
      {children == null ? null : <div className="thread-widget-zone">{children}</div>}
    </section>
  )
}
