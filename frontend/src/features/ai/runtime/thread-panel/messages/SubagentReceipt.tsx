import {
  ArrowUpRight,
  ChevronDown,
  ChevronRight,
  CircleCheck,
  CircleSlash,
  CircleX,
  type LucideIcon,
} from 'lucide-react'
import { useState } from 'react'
import { ThreadLink } from '@/features/ai/runtime/ThreadLink'
import {
  parseSubagentReceipt,
  type SubagentReceiptState,
} from '@/features/ai/runtime/thread-panel/messages/notification-receipt'
import { subagentTaskPreview } from '@/features/ai/runtime/thread-panel/messages/subagent-receipt-preview'
import { SystemMessageBody } from '@/features/ai/runtime/thread-panel/messages/SystemMessageBody'
import { announceTranscriptReading } from '@/features/ai/runtime/transcript-reading'
import type { EntryNotification } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'
import './subagent-receipt.css'

/** 三终态的真实短标签：completed 只表示该次执行已返回，不代表目标已验收。 */
const STATE_LABEL_KEYS: Record<SubagentReceiptState, string> = {
  completed: 'ai.runtime.notification.entry.subagentState.completed',
  error: 'ai.runtime.task.state.error',
  cancelled: 'ai.runtime.notification.entry.cancelled',
}

const STATE_ICONS: Record<SubagentReceiptState, LucideIcon> = {
  completed: CircleCheck,
  error: CircleX,
  cancelled: CircleSlash,
}

/**
 * SUBAGENT_RESULT 专属轻量信息卡：浅青蓝 info 染色 + 细边框 + 小回执图标。
 *
 * <p>默认折叠，摘要展示 agent、真实终态图标、任务短预览；最后的箭头负责展开，来源跳转是独立的
 * ThreadLink，与展开动作语义分开且各自键盘可达。展开后按安全 Markdown 展示完整
 * task/result/error/partial_result；partial_result 只是内容分区，不是第四种终态。
 * 非法信封明确显示解析失败，不伪装成回执正文。
 */
export function SubagentReceipt({
  notification,
  xml,
}: {
  notification: EntryNotification
  xml: string
}) {
  const { t } = useI18n()
  const [expanded, setExpanded] = useState(false)
  const parsed = parseSubagentReceipt(xml, notification.sourceThreadId)
  if (!parsed.ok) {
    return (
      <section
        className="thread-block thread-notification thread-subagent-receipt is-invalid"
        data-entry-kind="notification"
        data-notification-kind={notification.kind}
      >
        <p className="thread-subagent-receipt-invalid">
          {t('ai.runtime.notification.entry.invalidReceipt')}
        </p>
      </section>
    )
  }

  const { receipt } = parsed
  const sourceThreadId = notification.sourceThreadId ?? receipt.threadId
  const StateIcon = STATE_ICONS[receipt.state]
  const preview = subagentTaskPreview(receipt.task)

  return (
    <section
      className="thread-block thread-notification thread-subagent-receipt"
      data-entry-kind="notification"
      data-notification-kind={notification.kind}
      data-subagent-state={receipt.state}
    >
      <div className="thread-subagent-receipt-summary">
        <div className="thread-subagent-receipt-identity">
          <span className="thread-subagent-receipt-agent">
            {receipt.agent ?? t('ai.runtime.task.subagent')}
          </span>
          <span className={`thread-subagent-receipt-state is-${receipt.state}`}
            role="img" aria-label={t(STATE_LABEL_KEYS[receipt.state])} title={t(STATE_LABEL_KEYS[receipt.state])}>
            <StateIcon aria-hidden="true" />
          </span>
          {preview != null && (
            <span className="thread-subagent-receipt-preview">{preview}</span>
          )}
        </div>
        {sourceThreadId != null && (
          <ThreadLink
            className="thread-subagent-receipt-source"
            threadId={sourceThreadId}
            title={t('ai.runtime.notification.entry.viewSubagentExecution')}
          >
            <ArrowUpRight aria-hidden="true" />
            {t('ai.runtime.notification.entry.viewSubagentExecution')}
          </ThreadLink>
        )}
        <button
          type="button"
          className="thread-subagent-receipt-toggle"
          aria-expanded={expanded}
          aria-label={[receipt.agent ?? t('ai.runtime.task.subagent'), t(STATE_LABEL_KEYS[receipt.state]), preview].filter(Boolean).join(' · ')}
          onClick={(event) => {
            announceTranscriptReading(event.currentTarget, 'subagent-receipt-toggle')
            setExpanded((current) => !current)
          }}
        >
          <span className="thread-subagent-receipt-chevron" aria-hidden="true">
            {expanded ? <ChevronDown /> : <ChevronRight />}
          </span>
        </button>
      </div>
      {expanded && (
        <div className="thread-subagent-receipt-detail">
          {receipt.task != null && (
            <ReceiptSection
              label={t('ai.runtime.notification.entry.subagentTask')}
              content={receipt.task}
            />
          )}
          {receipt.result != null && (
            <ReceiptSection
              label={t('ai.runtime.event.detail.result')}
              content={receipt.result}
            />
          )}
          {receipt.error != null && (
            <ReceiptSection
              className="thread-subagent-receipt-error"
              label={receipt.state === 'cancelled'
                ? t('ai.runtime.notification.entry.cancelled')
                : t('ai.runtime.notification.entry.error')}
              content={receipt.error}
            />
          )}
          {receipt.partial != null && (
            <ReceiptSection
              className="thread-subagent-receipt-partial"
              label={t('ai.runtime.notification.entry.partial')}
              content={receipt.partial}
            />
          )}
        </div>
      )}
    </section>
  )
}

/** 回执内容分区：标签 + 安全 Markdown；raw 标签按文本呈现，不做二次反转义或 HTML 注入。 */
function ReceiptSection({
  label,
  content,
  className,
}: {
  label: string
  content: string
  className?: string
}) {
  return (
    <section className={['thread-subagent-receipt-section', className].filter(Boolean).join(' ')}>
      <div className="thread-subagent-receipt-label">{label}</div>
      <SystemMessageBody content={content} />
    </section>
  )
}
