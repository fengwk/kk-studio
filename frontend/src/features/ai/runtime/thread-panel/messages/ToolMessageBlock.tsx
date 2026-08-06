import {
  formatToolAttachmentFallback,
  getToolAttachmentHref,
  getToolAttachmentLabel,
  isPreviewableAttachment,
  toToolAttachmentSrc,
} from '@/features/ai/runtime/thread-panel/tool-attachments'
import type { ToolAttachment, ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { translate, useI18n } from '@/shared/i18n'

type ApprovalDecision = 'ALLOW' | 'DENY'

interface ToolRenderContext {
  toolName: string
  toolCallId: string
  arguments: string
  text: string
  attachments: ToolAttachment[]
  status?: ToolDialogueMessage['status']
  errorMessage?: string
  partial?: string
  partialErrorText?: string
  partialAttachments?: ToolAttachment[]
  approval?: ToolDialogueMessage['approval']
  approvalPending?: boolean
}

/** Tool turn as separate full-width call/result blocks. */
export function ToolMessageBlock({
  message,
  onDecideApproval,
  approvalPending = false,
}: {
  message: ToolDialogueMessage
  onDecideApproval?: (message: ToolDialogueMessage, decision: ApprovalDecision) => void
  approvalPending?: boolean
}) {
  const { t } = useI18n()
  const context: ToolRenderContext = {
    toolName: message.toolName || 'Tool',
    toolCallId: message.toolCallId,
    arguments: message.arguments,
    text: message.text,
    attachments: message.attachments,
    status: message.status,
    errorMessage: message.errorMessage,
    partial: message.partial,
    partialErrorText: message.partialErrorText,
    partialAttachments: message.partialAttachments,
    approval: message.approval,
    approvalPending,
  }
  const call = message.phase === 'call'

  return (
    <div className={`thread-turn thread-turn-tool ${message.status === 'error' ? 'error' : ''}`}>
      {call ? (
        <section className="thread-block thread-block-tool-call">
          <div className="thread-block-label">
            {t('ai.runtime.message.toolCall')}
            {' '}
            {context.toolName}
            <span className={`thread-tool-status ${message.status ?? 'done'}`}>
              {formatToolStatus(message.status)}
            </span>
          </div>
          <div className="thread-block-body"><DefaultToolCall context={context} /></div>
          {/* Transient result block under the active call: TOOL_PARTIAL / terminal result /
              resource attachments / error render here until the durable Tool result Entry
              arrives and the durable result phase takes over. */}
          <TransientToolResult context={context} />
          <ToolApprovalBar context={context} onDecideApproval={onDecideApproval} message={message} />
        </section>
      ) : (
        <section className="thread-block thread-block-tool-result">
          <div className="thread-block-label">
            {t('ai.runtime.message.toolResult')}
            {' '}
            {context.toolName}
            <span className={`thread-tool-status ${message.status ?? 'done'}`}>
              {formatToolStatus(message.status)}
            </span>
          </div>
          <div className="thread-block-body"><DefaultToolResult context={context} /></div>
        </section>
      )}
    </div>
  )
}

/** Pending approval gate: only when the snapshot approval is required and still undecided. */
function ToolApprovalBar({
  context,
  message,
  onDecideApproval,
}: {
  context: ToolRenderContext
  message: ToolDialogueMessage
  onDecideApproval?: (message: ToolDialogueMessage, decision: ApprovalDecision) => void
}) {
  const { t } = useI18n()
  const approval = context.approval
  if (!approval?.required) {
    return null
  }
  if (approval.decision != null) {
    // Decided: show the persisted decision (+ optional reason) instead of buttons.
    const decidedText =
      approval.decision === 'ALLOWED'
        ? t('ai.runtime.approval.allowed')
        : t('ai.runtime.approval.denied')
    return (
      <div className="thread-tool-approval is-decided" aria-label={t('ai.runtime.approval.title')}>
        <span className="thread-tool-approval-text">
          {decidedText}
          {approval.reason ? ` — ${approval.reason}` : ''}
        </span>
      </div>
    )
  }
  if (!onDecideApproval) {
    return null
  }
  return (
    <div className="thread-tool-approval" role="group" aria-label={t('ai.runtime.approval.title')}>
      <span className="thread-tool-approval-text">{t('ai.runtime.approval.requested')}</span>
      <button
        type="button"
        className="btn-primary"
        disabled={context.approvalPending}
        onClick={() => onDecideApproval(message, 'ALLOW')}
      >
        {t('ai.runtime.approval.allow')}
      </button>
      <button
        type="button"
        className="ghost-btn"
        disabled={context.approvalPending}
        onClick={() => onDecideApproval(message, 'DENY')}
      >
        {t('ai.runtime.approval.deny')}
      </button>
    </div>
  )
}

/** Streaming/terminal overlay under the active call; hidden once the durable Entry exists. */
function TransientToolResult({ context }: { context: ToolRenderContext }) {
  const hasText = Boolean(context.partial?.trim())
  const hasError = Boolean(context.partialErrorText?.trim())
  const attachments = context.partialAttachments ?? []
  const hasAttachments = attachments.length > 0
  if (!hasText && !hasError && !hasAttachments) {
    return null
  }
  return (
    <div className="thread-block thread-block-tool-result is-transient">
      <div className="thread-block-label">
        {translate('ai.runtime.message.toolResult')}
        {' '}
        {context.toolName}
        <span className={`thread-tool-status ${context.status ?? 'done'}`}>
          {formatToolStatus(context.status)}
        </span>
      </div>
      <div className="thread-block-body">
        {hasText ? <pre className="thread-tool-pre">{context.partial}</pre> : null}
        {hasError ? <p className="thread-tool-error">{context.partialErrorText}</p> : null}
        {hasAttachments ? (
          <div className="thread-tool-attachments">
            {attachments.map((attachment, index) => (
              <AttachmentPreview
                key={`${attachment.type}-${attachment.name}-${index}`}
                attachment={attachment}
              />
            ))}
          </div>
        ) : null}
      </div>
    </div>
  )
}

function DefaultToolCall({ context }: { context: ToolRenderContext }) {
  if (!context.arguments.trim()) {
    return <span className="thread-tool-placeholder">{translate('ai.runtime.message.noArguments')}</span>
  }
  return <pre className="thread-tool-pre">{context.arguments}</pre>
}

function DefaultToolResult({ context }: { context: ToolRenderContext }) {
  // Transient TOOL_PARTIAL overlay wins over the durable (possibly still empty) result text.
  const hasPartial = Boolean(context.partial?.trim())
  const text = hasPartial ? (context.partial ?? '') : context.text
  const hasText = text.trim().length > 0
  const hasAttachments = context.attachments.length > 0
  return (
    <>
      {hasText ? <pre className="thread-tool-pre">{text}</pre> : null}
      {!hasText && !hasAttachments ? <p className="thread-tool-placeholder">{placeholder(context)}</p> : null}
      {hasAttachments ? (
        <div className="thread-tool-attachments">
          {context.attachments.map((attachment, index) => (
            <AttachmentPreview
              key={`${attachment.type}-${attachment.name}-${index}`}
              attachment={attachment}
            />
          ))}
        </div>
      ) : null}
      {context.errorMessage && context.errorMessage !== context.text ? (
        <p className="thread-tool-error">{context.errorMessage}</p>
      ) : null}
    </>
  )
}

function AttachmentPreview({ attachment }: { attachment: ToolAttachment }) {
  const src = toToolAttachmentSrc(attachment)
  const href = getToolAttachmentHref(attachment)
  const label = getToolAttachmentLabel(attachment)
  const previewable = src != null && isPreviewableAttachment(attachment)
  const preview = attachment.preview?.trim()
  return (
    <figure className="thread-tool-attachment">
      <figcaption>
        <span>{attachment.type}</span>
        <span>{label}</span>
      </figcaption>
      {previewable && attachment.type === 'image' ? (
        <img src={src} alt={label} loading="lazy" />
      ) : null}
      {previewable && attachment.type === 'audio' ? (
        <audio controls src={src} aria-label={label} />
      ) : null}
      {previewable && attachment.type === 'video' ? (
        <video controls src={src} aria-label={label} />
      ) : null}
      {!previewable ? (
        <div className="thread-tool-attachment-uri">
          {preview ? (
            // Resource preview is TEXT (e.g. a JSON excerpt), never a URL; rendering it as an
            // <img src> would throw an invalid resource.
            <pre className="thread-tool-attachment-preview">{preview}</pre>
          ) : null}
          <span className="thread-tool-attachment-uri-text">{attachment.data}</span>
        </div>
      ) : null}
      {href ? (
        <a href={href} target="_blank" rel="noopener noreferrer">
          {translate('ai.runtime.message.openRaw')}
        </a>
      ) : (
        <div>{formatToolAttachmentFallback(attachment)}</div>
      )}
    </figure>
  )
}

function formatToolStatus(status?: ToolDialogueMessage['status']): string {
  if (status === 'streaming') {
    return translate('ai.runtime.message.running')
  }
  if (status === 'error') {
    return translate('ai.runtime.message.error')
  }
  return translate('ai.runtime.message.done')
}

function placeholder(context: ToolRenderContext): string {
  if (context.status === 'error') {
    return translate('ai.runtime.message.toolFailed')
  }
  if (context.status === 'streaming') {
    return translate('ai.runtime.message.waitingTool')
  }
  return translate('ai.runtime.message.noTextOutput')
}
