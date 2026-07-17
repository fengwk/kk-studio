import {
  formatToolAttachmentFallback,
  getToolAttachmentLabel,
  toToolAttachmentSrc,
} from '@/features/ai/tool-attachments'
import { getToolRenderer, type ToolRenderContext } from '@/features/ai/session-panel/tool-renderers'
import type { ToolAttachment, ToolDialogueMessage } from '@/features/ai/session-events'

/**
 * Tool row with optional per-tool renderCall / renderResult overrides (pi ToolDefinition).
 */
export function ToolMessageBlock({ message }: { message: ToolDialogueMessage }) {
  const context: ToolRenderContext = {
    toolName: message.toolName || 'Tool',
    toolCallId: message.toolCallId,
    arguments: message.arguments,
    text: message.text,
    attachments: message.attachments,
    status: message.status,
    errorMessage: message.errorMessage,
  }
  const renderer = getToolRenderer(context.toolName)
  const callNode = renderer?.renderCall?.(context)
  const resultNode = renderer?.renderResult?.(context)

  return (
    <article className={`session-row tool ${message.status === 'error' ? 'error' : ''}`}>
      <div className="session-bubble tool">
        <div className="session-tool-head">
          <strong>{context.toolName}</strong>
          <span className={`session-tool-status ${message.status ?? 'done'}`}>
            {formatToolStatus(message.status)}
          </span>
        </div>
        <div className="session-tool-call">
          {callNode ?? <DefaultToolCall context={context} />}
        </div>
        <div className="session-tool-result">
          {resultNode ?? <DefaultToolResult context={context} />}
        </div>
      </div>
    </article>
  )
}

function DefaultToolCall({ context }: { context: ToolRenderContext }) {
  if (!context.arguments.trim()) {
    return null
  }
  return (
    <div className="session-tool-section">
      <span className="session-tool-label">call</span>
      <pre className="session-tool-pre">{context.arguments}</pre>
    </div>
  )
}

function DefaultToolResult({ context }: { context: ToolRenderContext }) {
  const hasText = context.text.trim().length > 0
  const hasAttachments = context.attachments.length > 0
  return (
    <div className="session-tool-section">
      <span className="session-tool-label">result</span>
      {hasText ? <pre className="session-tool-pre">{context.text}</pre> : null}
      {!hasText && !hasAttachments ? (
        <p className="session-tool-placeholder">{placeholder(context)}</p>
      ) : null}
      {hasAttachments ? (
        <div className="session-tool-attachments">
          {context.attachments.map((attachment, index) => (
            <AttachmentPreview
              key={`${attachment.type}-${attachment.name}-${index}`}
              attachment={attachment}
            />
          ))}
        </div>
      ) : null}
      {context.errorMessage && context.errorMessage !== context.text ? (
        <p className="session-tool-error">{context.errorMessage}</p>
      ) : null}
    </div>
  )
}

function AttachmentPreview({ attachment }: { attachment: ToolAttachment }) {
  const src = toToolAttachmentSrc(attachment)
  const label = getToolAttachmentLabel(attachment)
  return (
    <figure className="session-tool-attachment">
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
          打开原始内容
        </a>
      ) : null}
    </figure>
  )
}

function formatToolStatus(status?: ToolDialogueMessage['status']): string {
  if (status === 'streaming') {
    return 'running'
  }
  if (status === 'error') {
    return 'error'
  }
  return 'done'
}

function placeholder(context: ToolRenderContext): string {
  if (context.status === 'error') {
    return '工具执行失败。'
  }
  if (context.status === 'streaming') {
    return '等待工具结果…'
  }
  return '无文本输出'
}
