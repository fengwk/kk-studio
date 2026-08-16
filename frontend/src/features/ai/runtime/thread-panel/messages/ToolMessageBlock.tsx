import {
  formatToolAttachmentFallback,
  getToolAttachmentHref,
  getToolAttachmentLabel,
  isPreviewableAttachment,
  toToolAttachmentSrc,
} from '@/features/ai/runtime/thread-panel/tool-attachments'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'
import { AttachmentMediaPreview } from '@/features/ai/runtime/thread-panel/messages/AttachmentMediaPreview'
import { ResourceAttachmentChip } from '@/features/ai/runtime/thread-panel/messages/ResourceAttachmentChip'
import { previewForToolCall } from '@/features/ai/runtime/thread-panel/messages/tool-previews'
import type { ToolAttachment, ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import type { ComponentType } from 'react'
import { useState } from 'react'
import { ChevronDown, ChevronRight, Copy } from 'lucide-react'
import type { ToolRendererProps } from '@/platform/extensions/types'
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

/** Tool 回合以一张卡片同时展示 call preview 与 result。 */
export function ToolMessageBlock({
  message,
  result,
  renderer: Renderer,
  onDecideApproval,
  approvalPending = false,
}: {
  message: ToolDialogueMessage
  result?: ToolDialogueMessage
  renderer?: ComponentType<ToolRendererProps>
  onDecideApproval?: (message: ToolDialogueMessage, decision: ApprovalDecision) => void
  approvalPending?: boolean
}) {
  const { t } = useI18n()
  const [expanded, setExpanded] = useState(false)
  const callMessage = message.phase === 'call' ? message : undefined
  const resultMessage = result ?? (message.phase === 'result' ? message : undefined)
  const status = resultMessage?.status ?? message.status
  const context: ToolRenderContext = {
    toolName: message.toolName || 'Tool',
    toolCallId: message.toolCallId,
    arguments: callMessage?.arguments || resultMessage?.arguments || message.arguments,
    text: resultMessage?.text ?? message.text,
    attachments: resultMessage?.attachments ?? message.attachments,
    status,
    errorMessage: resultMessage?.errorMessage ?? message.errorMessage,
    partial: callMessage?.partial ?? resultMessage?.partial ?? message.partial,
    partialErrorText: callMessage?.partialErrorText ?? resultMessage?.partialErrorText,
    partialAttachments: callMessage?.partialAttachments ?? resultMessage?.partialAttachments,
    approval: callMessage?.approval ?? resultMessage?.approval ?? message.approval,
    approvalPending,
  }
  const preview = previewForToolCall(context.toolName, context.arguments)

  return (
    <div className={`thread-turn thread-turn-tool ${status === 'error' ? 'error' : ''}`}>
      <section className="thread-block thread-block-tool">
        <div className="thread-block-label">
          <button
            type="button"
            className="thread-tool-toggle"
            aria-expanded={expanded}
            aria-label={expanded ? t('ai.runtime.message.collapseTool') : t('ai.runtime.message.expandTool')}
            onClick={() => setExpanded((current) => !current)}
          >
            {expanded ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
          </button>
          <span className="thread-tool-name">{context.toolName}</span>
          {preview?.path ? <span className="thread-tool-path">{preview.path}</span> : null}
          {preview && 'replaceAll' in preview && preview.replaceAll ? (
            <span className="thread-tool-flag">replace_all</span>
          ) : null}
          <button
            type="button"
            className="thread-tool-copy"
            aria-label={t('ai.runtime.message.copyTool')}
            onClick={() => {
              void navigator.clipboard?.writeText(copyText(context, preview))
            }}
          >
            <Copy aria-hidden="true" />
          </button>
          <span className={`thread-tool-status ${status ?? 'done'}`}>
            {formatToolStatus(status)}
          </span>
        </div>
        <div className="thread-block-body">
          {Renderer ? (
            <>
              {callMessage ? <Renderer message={callMessage} /> : null}
              {resultMessage ? <Renderer message={resultMessage} /> : null}
            </>
          ) : (
            <>
              {callMessage || preview
                ? <DefaultToolCall context={context} preview={preview} expanded={expanded} />
                : null}
              <DefaultToolResult
                context={context}
                hideEmpty={callMessage != null && resultMessage == null}
              />
            </>
          )}
        </div>
        <ToolApprovalBar context={context} onDecideApproval={onDecideApproval} message={callMessage ?? message} />
      </section>
    </div>
  )
}

/** 待决审批闸门：仅当 snapshot 中的审批为必需且尚未决定时展示。 */
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
    // 已决定：展示持久化的决策（+ 可选原因），而不是按钮。
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

function DefaultToolCall({
  context,
  preview,
  expanded,
}: {
  context: ToolRenderContext
  preview: ReturnType<typeof previewForToolCall>
  expanded: boolean
}) {
  if (preview != null) {
    return (
      <div className="thread-tool-preview">
        {preview.lines.length > 0 ? (
          <pre className={`thread-tool-pre thread-tool-input thread-tool-preview-body is-${preview.kind}${expanded ? ' is-expanded' : ''}`}>
            {preview.lines.map((line, index) => (
              <span
                key={`${preview.kind}-${index}`}
                className={diffLineClass(line)}
              >
                {line}
                {index < preview.lines.length - 1 ? '\n' : ''}
              </span>
            ))}
          </pre>
        ) : null}
      </div>
    )
  }
  if (!context.arguments.trim()) {
    return <span className="thread-tool-placeholder">{translate('ai.runtime.message.noArguments')}</span>
  }
  return <pre className="thread-tool-pre thread-tool-input">{context.arguments}</pre>
}

function DefaultToolResult({
  context,
  hideEmpty = false,
}: {
  context: ToolRenderContext
  hideEmpty?: boolean
}) {
  // 瞬态 TOOL_PARTIAL overlay 优先于持久的（可能仍为空的）结果文本。
  const hasPartial = Boolean(context.partial?.trim())
  const text = hasPartial ? (context.partial ?? '') : context.text
  const hasText = text.trim().length > 0
  const attachments = context.partialAttachments?.length
    ? context.partialAttachments
    : context.attachments
  const hasAttachments = attachments.length > 0
  const hasError = Boolean(context.partialErrorText?.trim())
  if (hideEmpty && !hasText && !hasAttachments && !hasError && !context.errorMessage) {
    return null
  }
  return (
    <>
      {hasText ? <ToolOutputViewport text={text} /> : null}
      {!hasText && !hasAttachments ? <p className="thread-tool-placeholder">{placeholder(context)}</p> : null}
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
      {hasError ? <p className="thread-tool-error">{context.partialErrorText}</p> : null}
      {context.errorMessage && context.errorMessage !== context.text ? (
        <p className="thread-tool-error">{context.errorMessage}</p>
      ) : null}
    </>
  )
}

function AttachmentPreview({ attachment }: { attachment: ToolAttachment }) {
  const preview = attachment.preview?.trim()
  if (attachment.blobId) {
    return (
      <div className="thread-tool-resource is-blob">
        <ResourceAttachmentChip attachment={attachment} />
        {preview ? <ToolOutputViewport text={preview} className="thread-tool-attachment-preview" /> : null}
      </div>
    )
  }
  const src = toToolAttachmentSrc(attachment)
  const href = getToolAttachmentHref(attachment)
  const label = getToolAttachmentLabel(attachment)
  const previewable = src != null && isPreviewableAttachment(attachment)
  if (previewable && src && attachment.type === 'image') {
    return (
      <div className="thread-tool-resource">
        <AttachmentMediaPreview
          kind="image"
          label={label}
          previewUrl={src}
          originalUrl={href ?? src}
        />
      </div>
    )
  }
  if (previewable && src && attachment.type === 'video') {
    return (
      <div className="thread-tool-resource">
        <AttachmentMediaPreview
          kind="video"
          label={label}
          previewUrl={src}
          originalUrl={href ?? src}
          previewMode="video"
        />
      </div>
    )
  }
  return (
    <div className="thread-tool-resource">
      {previewable && src && attachment.type === 'audio' ? (
        <audio controls src={src} aria-label={label} />
      ) : null}
      {!previewable ? (
        <div className="thread-tool-attachment-uri">
          {preview ? (
            // 资源 preview 是文本（例如 JSON 摘录），绝不是 URL；把它当作
            // <img src> 渲染会抛出非法资源错误。
            <ToolOutputViewport text={preview} className="thread-tool-attachment-preview" />
          ) : null}
          <span className="thread-tool-attachment-uri-text">{attachment.data}</span>
        </div>
      ) : null}
      {href ? (
        <a className="resource-attachment-download" href={href} target="_blank" rel="noopener noreferrer">
          [{label}]
        </a>
      ) : (
        <span className="resource-attachment-fallback">{formatToolAttachmentFallback(attachment)}</span>
      )}
    </div>
  )
}

function formatToolStatus(status?: ToolDialogueMessage['status']): string {
  if (status === 'streaming') {
    return 'WORKING'
  }
  if (status === 'error') {
    return 'FAILED'
  }
  return 'DONE'
}

function copyText(
  context: ToolRenderContext,
  preview: ReturnType<typeof previewForToolCall>,
): string {
  const parts = [
    preview?.path ? `${context.toolName} ${preview.path}` : context.toolName,
    preview?.lines.length ? preview.lines.join('\n') : context.arguments,
    context.partial || context.text,
  ]
  return parts.filter((part) => part.trim()).join('\n\n')
}

function diffLineClass(line: string): string {
  if (line.startsWith('+')) {
    return 'is-added'
  }
  if (line.startsWith('-')) {
    return 'is-removed'
  }
  return 'is-context'
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
