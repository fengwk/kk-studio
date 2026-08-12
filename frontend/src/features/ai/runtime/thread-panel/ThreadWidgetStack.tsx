import type { ReactNode } from 'react'
import { ThreadWorkingStatus } from '@/features/ai/runtime/thread-panel/ThreadWorkingStatus'
import type { QueuedThreadMessage } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/**
 * 对话 transcript 下方的小部件区域。
 *
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
  const hasQueuedMessages = queuedMessages.length > 0
  const showWorking = working

  // 连 children 都没传时直接不挂载
  if (!showWorking && !hasQueuedMessages && children == null) {
    return null
  }

  return (
    <section className="thread-widget-zone" aria-label={t('ai.runtime.thread.widgetZone')}>
      <ThreadWorkingStatus active={showWorking} />
      {hasQueuedMessages ? (
        <ol className="thread-input-queue" aria-label={t('ai.runtime.thread.queue')}>
          {queuedMessages.map((message) => (
            <li key={message.clientCommandId} className="thread-input-queue-item">
              <span className="thread-input-queue-label">{t('ai.runtime.thread.queued')}</span>
              <span className="thread-input-queue-content">{message.text}</span>
            </li>
          ))}
        </ol>
      ) : null}
      {children}
    </section>
  )
}
