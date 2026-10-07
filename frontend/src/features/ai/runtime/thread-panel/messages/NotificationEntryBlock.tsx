import { SubagentReceipt } from '@/features/ai/runtime/thread-panel/messages/SubagentReceipt'
import { SystemMessageBody } from '@/features/ai/runtime/thread-panel/messages/SystemMessageBody'
import { SystemMessageCard } from '@/features/ai/runtime/thread-panel/messages/SystemMessageCard'
import type {
  EntryEventDialogueMessage,
  EntryNotification,
} from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'
import './notification-entry.css'

/**
 * 系统通知卡片：全宽淡色，无图标列与左缩进，与压缩摘要共用系统消息外壳。
 *
 * <p>SUBAGENT_RESULT 交给专属的回执信息卡（SUBAGENT_RESULT 有自己的来源、终态与
 * 折叠语义，不再叠加通用系统标题）；TASK_BUDGET 直接按安全 Markdown 展示；
 * 未知 kind 只作为普通系统文本，不假定为子代理结果。
 */
export function NotificationEntryBlock({
  message,
  notification,
}: {
  message: EntryEventDialogueMessage
  notification: EntryNotification
}) {
  if (notification.kind === 'SUBAGENT_RESULT') {
    return <SubagentReceipt notification={notification} xml={message.text} />
  }
  return (
    <SystemMessageCard
      className="thread-notification"
      data-entry-kind="notification"
      data-notification-kind={notification.kind}
      title={message.title}
    >
      {notification.kind === 'TASK_BUDGET' ? (
        <SystemMessageBody className="thread-notification-body" content={message.text} />
      ) : (
        <UnknownNotificationBody text={message.text} />
      )}
    </SystemMessageCard>
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
