import {
  formatToolAttachmentFallback,
  getToolAttachmentLabel,
  toToolAttachmentSrc,
} from '@/features/ai/runtime/thread-panel/tool-attachments'
import type { ToolAttachment, ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { translate, useI18n } from '@/shared/i18n'

interface ToolRenderContext {
  toolName: string
  toolCallId: string
  arguments: string
  text: string
  attachments: ToolAttachment[]
  status?: ToolDialogueMessage['status']
  errorMessage?: string
}

/** Tool turn as separate full-width call/result blocks. */
export function ToolMessageBlock({ message }: { message: ToolDialogueMessage }) {
  const { t } = useI18n()
  const context: ToolRenderContext = {
    toolName: message.toolName || 'Tool',
    toolCallId: message.toolCallId,
    arguments: message.arguments,
    text: message.text,
    attachments: message.attachments,
    status: message.status,
    errorMessage: message.errorMessage,
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

function DefaultToolCall({ context }: { context: ToolRenderContext }) {
  if (!context.arguments.trim()) {
    return <span className="thread-tool-placeholder">{translate('ai.runtime.message.noArguments')}</span>
  }
  return <pre className="thread-tool-pre">{context.arguments}</pre>
}

function DefaultToolResult({ context }: { context: ToolRenderContext }) {
  const hasText = context.text.trim().length > 0
  const hasAttachments = context.attachments.length > 0
  return (
    <>
      {hasText ? <pre className="thread-tool-pre">{context.text}</pre> : null}
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
  const label = getToolAttachmentLabel(attachment)
  return (
    <figure className="thread-tool-attachment">
      <figcaption>
        <span>{attachment.type}</span>
        <span>{label}</span>
      </figcaption>
      {src && attachment.type === 'image' ? (
        <img src={src} alt={label} loading="lazy" />
      ) : (
        <div>{formatToolAttachmentFallback(attachment)}</div>
      )}
      {src ? (
        <a href={src} target="_blank" rel="noreferrer">
          {translate('ai.runtime.message.openRaw')}
        </a>
      ) : null}
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
