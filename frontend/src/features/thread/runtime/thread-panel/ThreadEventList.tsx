import type { KeyboardEvent, RefObject } from 'react'
import { Button } from '@/shared/ui/controls/Button'
import type { ThreadEventRecord } from '@/features/thread/runtime/thread-timeline/thread-events'
import { formatBackendDateSeconds } from '@/shared/lib/date-format'
import { useI18n } from '@/shared/i18n'

/**
 * Debug 事件列：`role="listbox"` 的单列滚动区，每行一条持久事件事实。
 *
 * 行只渲染事件自身的事实（时间、标题、摘要、运行中脉冲），选中与键盘导航
 * 由调用方持有；`aria-activedescendant` 指向当前选中行，焦点始终留在 listbox 上。
 */
export function ThreadEventList({
  baseId,
  events,
  selectedEventId,
  selectedIndex,
  loading,
  error,
  onRetry,
  bodyRef,
  onSelectEvent,
  onKeyDown,
}: {
  baseId: string
  events: ThreadEventRecord[]
  selectedEventId: string | null
  selectedIndex: number
  loading: boolean
  error: string | null
  onRetry?: () => void
  bodyRef: RefObject<HTMLDivElement | null>
  onSelectEvent: (id: string) => void
  onKeyDown: (event: KeyboardEvent<HTMLDivElement>) => void
}) {
  const { t } = useI18n()
  return (
    <div
      ref={bodyRef}
      className="thread-events"
      role="listbox"
      tabIndex={0}
      aria-label={t('ai.runtime.event.list')}
      aria-activedescendant={
        selectedIndex >= 0 && events[selectedIndex]
          ? `${baseId}-event-${events[selectedIndex]!.id}`
          : undefined
      }
      onKeyDown={onKeyDown}
    >
      {loading ? <div role="status">{t('ai.runtime.thread.loading')}</div> : null}
      {error ? <div role="alert"><p>{error}</p>
        {onRetry ? <Button variant="secondary" onClick={onRetry}>{t('ai.runtime.agentTree.retry')}</Button> : null}
      </div> : null}
      {!loading && !error && events.length === 0 ? (
        <div className="thread-empty">
          <p>{t('ai.runtime.event.empty')}</p>
        </div>
      ) : null}
      {events.map((event) => {
        const selected = event.id === selectedEventId
        return (
          <div
            key={event.id}
            id={`${baseId}-event-${event.id}`}
            data-event-id={event.id}
            role="option"
            aria-selected={selected}
            className={`thread-event kind-${event.kind} status-${event.status} ${selected ? 'active' : ''}`}
            onClick={() => onSelectEvent(event.id)}
          >
            <span className="thread-event-time" data-event-time>
              {formatBackendDateSeconds(event.createdAt)}
            </span>
            <span className="thread-event-badge">{event.title}</span>
            <span className="thread-event-summary">{event.summary}</span>
            {event.status === 'running' ? (
              <span className="thread-event-pulse" aria-hidden="true" />
            ) : null}
          </div>
        )
      })}
    </div>
  )
}
