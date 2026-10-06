import { ThreadLink } from '@/features/ai/runtime/ThreadLink'
import { parseSubagentReceipt } from '@/features/ai/runtime/thread-panel/messages/notification-receipt'
import { SystemMessageBody } from '@/features/ai/runtime/thread-panel/messages/SystemMessageBody'
import { SystemMessageCard } from '@/features/ai/runtime/thread-panel/messages/SystemMessageCard'
import type {
  EntryEventDialogueMessage,
  EntryNotification,
} from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'
import { MarkdownRenderer } from '@/shared/ui/markdown/MarkdownRenderer'
import './notification-entry.css'

/**
 * 系统通知卡片：全宽淡色，无图标列与左缩进，与压缩摘要共用系统消息外壳。
 *
 * <p>SUBAGENT_RESULT 解析固定 XML 信封，展示来源、Thread 链接与 result/error/partial；
 * TASK_BUDGET 直接按安全 Markdown 展示；未知 kind 只作为普通系统文本，不假定为子代理结果。
 */
export function NotificationEntryBlock({
  message,
  notification,
}: {
  message: EntryEventDialogueMessage
  notification: EntryNotification
}) {
  return (
    <SystemMessageCard
      className="thread-notification"
      data-entry-kind="notification"
      data-notification-kind={notification.kind}
      title={message.title}
    >
      {notification.kind === 'TASK_BUDGET' ? (
        <SystemMessageBody className="thread-notification-body" content={message.text} />
      ) : notification.kind === 'SUBAGENT_RESULT' ? (
        <SubagentResultBody notification={notification} xml={message.text} />
      ) : (
        <UnknownNotificationBody text={message.text} />
      )}
    </SystemMessageCard>
  )
}

function SubagentResultBody({
  notification,
  xml,
}: {
  notification: EntryNotification
  xml: string
}) {
  const { t } = useI18n()
  const parsed = parseSubagentReceipt(xml, notification.sourceThreadId)
  if (!parsed.ok) {
    return (
      <div className="thread-notification-body thread-notification-invalid">
        {t('ai.runtime.notification.entry.invalidReceipt')}
      </div>
    )
  }
  const { receipt } = parsed
  const sourceThreadId = notification.sourceThreadId ?? receipt.threadId
  return (
    <div className="thread-notification-body">
      <div className="thread-notification-source">
        <span className="thread-notification-source-label">
          {t('ai.runtime.notification.entry.source')}
        </span>
        {receipt.agent != null && (
          <span className="thread-notification-agent">{receipt.agent}</span>
        )}
        {sourceThreadId != null && (
          <ThreadLink className="thread-notification-thread" threadId={sourceThreadId}>
            {sourceThreadId}
          </ThreadLink>
        )}
      </div>
      {receipt.result != null && (
        <div className="thread-notification-section">
          <MarkdownRenderer content={receipt.result} />
        </div>
      )}
      {receipt.error != null && (
        <div className="thread-notification-section thread-notification-error">
          <div className="thread-notification-label">
            {receipt.state === 'cancelled'
              ? t('ai.runtime.notification.entry.cancelled')
              : t('ai.runtime.notification.entry.error')}
          </div>
          <MarkdownRenderer content={receipt.error} />
        </div>
      )}
      {receipt.partial != null && (
        <div className="thread-notification-section thread-notification-partial">
          <div className="thread-notification-label">
            {t('ai.runtime.notification.entry.partial')}
          </div>
          <MarkdownRenderer content={receipt.partial} />
        </div>
      )}
    </div>
  )
}

function UnknownNotificationBody({ text }: { text: string }) {
  const { t } = useI18n()
  return (
    <div className="thread-notification-body thread-notification-text">
      {text || t('ai.runtime.notification.entry.emptyText')}
    </div>
  )
}
