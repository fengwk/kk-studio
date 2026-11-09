import {
  ArrowUpRight,
  CircleCheck,
  CircleSlash,
  CircleX,
  type LucideIcon,
} from 'lucide-react'
import { useState } from 'react'
import { ThreadLink } from '@/features/thread/runtime/ThreadLink'
import {
  discloseText,
  disclosureHint,
  ERROR_COLLAPSED_LINES,
  SUBAGENT_RECEIPT_DISCLOSURE,
  type TextDisclosure,
} from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'
import {
  parseSubagentReceipt,
  type SubagentReceiptState,
} from '@/features/thread/runtime/thread-panel/messages/notification-receipt'
import { subagentTaskPreview } from '@/features/thread/runtime/thread-panel/messages/subagent-receipt-preview'
import { SystemMessageBody } from '@/features/thread/runtime/thread-panel/messages/SystemMessageBody'
import { ThreadDisclosure } from '@/features/thread/runtime/thread-panel/messages/ThreadDisclosure'
import { announceTranscriptReading } from '@/features/thread/runtime/transcript-reading'
import type { EntryNotification } from '@/features/thread/runtime/thread-timeline-types'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import './subagent-receipt.css'

/** 三终态的真实短标签：completed 只表示该次执行已返回，不代表目标已验收。 */
const STATE_LABEL_KEYS: Record<SubagentReceiptState, TranslationKey> = {
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
 * SUBAGENT_RESULT 专属轻量信息卡，统一使用只读消息容器。
 *
 * <p>默认 0 行：摘要展示 agent、真实终态图标与任务短预览；来源跳转是独立的
 * ThreadLink，可见文字只有「查看执行」，不重复长 UUID。展开后先展示结果，
 * 再展示任务原文与部分结果；失败原因固定为 preview 顶部的错误块，收起最多
 * 3 行且受 1500 字符限制，展开完整显示。非法信封明确显示错误，不伪装成回执正文。
 */
export function SubagentReceipt({
  notification,
  xml,
  title,
}: {
  notification: EntryNotification
  xml: string
  title: string
}) {
  const { t } = useI18n()
  const [expanded, setExpanded] = useState(false)
  const parsed = parseSubagentReceipt(xml, notification.sourceThreadId)
  const expandFromHint = (element: HTMLElement) => {
    announceTranscriptReading(element, 'disclosure-hint')
    setExpanded(true)
  }

  if (!parsed.ok) {
    return (
      <ThreadDisclosure
        className="thread-notification thread-subagent-receipt is-invalid"
        data-entry-kind="notification"
        data-notification-kind={notification.kind}
        tone="error"
        expanded={expanded}
        onExpandedChange={setExpanded}
        hasDetails={false}
        label={title}
        header={<span className="thread-subagent-receipt-agent">{title}</span>}
        preview={<DisclosureError text={t('ai.runtime.notification.entry.invalidReceipt')} />}
      />
    )
  }

  const { receipt } = parsed
  const sourceThreadId = notification.sourceThreadId ?? receipt.threadId
  const StateIcon = STATE_ICONS[receipt.state]
  const taskPreview = subagentTaskPreview(receipt.task)
  const errorText = receipt.state === 'error' && receipt.error?.trim()
    ? receipt.error
    : null
  // 回执 Markdown 分区只支持 0 行（收起）与全部（展开）；错误块单独受行数/字符限制。
  const showAll = expanded || SUBAGENT_RECEIPT_DISCLOSURE.preview === -1
  const showSections = expanded || SUBAGENT_RECEIPT_DISCLOSURE.preview !== 0
  const showBody = expanded || SUBAGENT_RECEIPT_DISCLOSURE.body !== 0
  const errorWindow = errorText == null
    ? null
    : discloseText(errorText, showAll ? -1 : ERROR_COLLAPSED_LINES)
  const errorHint = errorWindow == null ? null : disclosureHint(errorWindow, 'start')
  const cancelledText = receipt.state === 'cancelled' ? receipt.error : null
  const hasDetails = (!showSections && (receipt.result != null || cancelledText != null))
    || (!showBody && (receipt.task != null || receipt.partial != null))
    || errorHint != null
  const identity = [receipt.agent ?? t('ai.runtime.task.subagent'), t(STATE_LABEL_KEYS[receipt.state])]
  const label = [...identity, taskPreview].filter(Boolean).join(' · ')
  const preview = errorWindow != null
    || (showSections && (receipt.result != null || cancelledText != null))
    ? (
      <>
        {errorWindow != null && (
          <DisclosureError
            disclosure={errorWindow}
            hint={errorHint}
            onExpand={expandFromHint}
          />
        )}
        {showSections && receipt.result != null && (
          <ReceiptSection
            label={t('ai.runtime.event.detail.result')}
            content={receipt.result}
          />
        )}
        {showSections && cancelledText != null && (
          <ReceiptSection
            className="thread-subagent-receipt-cancelled"
            label={t('ai.runtime.notification.entry.cancelled')}
            content={cancelledText}
          />
        )}
      </>
    )
    : undefined
  const body = showBody && (receipt.task != null || receipt.partial != null)
    ? (
      <>
        {receipt.task != null && (
          <ReceiptSection
            label={t('ai.runtime.notification.entry.subagentTask')}
            content={receipt.task}
          />
        )}
        {receipt.partial != null && (
          <ReceiptSection
            label={t('ai.runtime.notification.entry.partial')}
            content={receipt.partial}
          />
        )}
      </>
    )
    : undefined

  return (
    <ThreadDisclosure
      className="thread-notification thread-subagent-receipt"
      data-entry-kind="notification"
      data-notification-kind={notification.kind}
      data-subagent-state={receipt.state}
      tone={receipt.state === 'error' ? 'error' : 'info'}
      expanded={expanded}
      onExpandedChange={setExpanded}
      hasDetails={hasDetails}
      label={label}
      header={(
        <span className="thread-subagent-receipt-summary">
          <span className="thread-subagent-receipt-agent">
            {receipt.agent ?? t('ai.runtime.task.subagent')}
          </span>{' '}
          <span
            className={`thread-subagent-receipt-state is-${receipt.state}`}
            role="img"
            aria-label={t(STATE_LABEL_KEYS[receipt.state])}
            title={t(STATE_LABEL_KEYS[receipt.state])}
          >
            <StateIcon aria-hidden="true" />
          </span>
          {taskPreview != null ? (
            <>
              {' '}
              <span className="thread-subagent-receipt-preview">{taskPreview}</span>
            </>
          ) : null}
        </span>
      )}
      actions={sourceThreadId != null ? (
        <ThreadLink
          className="thread-subagent-receipt-source"
          threadId={sourceThreadId}
          title={t('ai.runtime.disclosure.viewSubagentExecution')}
          aria-label={t('ai.runtime.disclosure.viewSubagentExecution')}
        >
          <ArrowUpRight aria-hidden="true" />
          <span>{t('ai.runtime.notification.entry.viewSubagentExecution')}</span>
        </ThreadLink>
      ) : undefined}
      preview={preview}
      body={body}
    />
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

/**
 * 固定错误块：失败原因固定在 preview 顶部，收起态按 3 行与 1500 字符截取，
 * 展开态完整显示；截断时提供与 chevron 等价的展开提示。
 */
function DisclosureError({
  text,
  disclosure,
  hint,
  onExpand,
}: {
  text?: string
  disclosure?: TextDisclosure
  hint?: string | null
  onExpand?: (element: HTMLElement) => void
}) {
  const content = disclosure?.text ?? text ?? ''
  return (
    <div className="thread-disclosure-error">
      <CircleX className="thread-disclosure-error-icon" size={12} aria-hidden="true" />
      <div>
        <p className="thread-disclosure-error-text">{content}</p>
        {hint != null && onExpand != null ? (
          <button
            type="button"
            className="thread-disclosure-hint"
            onClick={(event) => onExpand(event.currentTarget)}
          >
            {hint}
          </button>
        ) : null}
      </div>
    </div>
  )
}
