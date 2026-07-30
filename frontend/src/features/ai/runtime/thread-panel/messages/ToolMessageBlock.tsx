import {
  formatToolAttachmentFallback,
  getToolAttachmentLabel,
  toToolAttachmentSrc,
} from '@/features/ai/runtime/thread-panel/tool-attachments'
import { getToolRenderer, type ToolRenderContext } from '@/features/ai/runtime/thread-panel/tool-renderers'
import type { ToolAttachment, ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

/**
 * Tool turn as separate full-width call/result blocks.
 * Custom tools may override via registerToolRenderer(name, { renderCall, renderResult }).
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
  const call = message.phase === 'call'

  return (
    <div className={`thread-turn thread-turn-tool ${message.status === 'error' ? 'error' : ''}`}>
      {call ? (
        <section className="thread-block thread-block-tool-call">
          <div className="thread-block-label">
            tool call ·
            {' '}
            {context.toolName}
            <span className={`thread-tool-status ${message.status ?? 'done'}`}>
              {formatToolStatus(message.status)}
            </span>
          </div>
          <div className="thread-block-body">{callNode ?? <DefaultToolCall context={context} />}</div>
        </section>
      ) : (
        <section className="thread-block thread-block-tool-result">
          <div className="thread-block-label">
            tool result ·
            {' '}
            {context.toolName}
            <span className={`thread-tool-status ${message.status ?? 'done'}`}>
              {formatToolStatus(message.status)}
            </span>
          </div>
          <div className="thread-block-body">{resultNode ?? <DefaultToolResult context={context} />}</div>
        </section>
      )}
    </div>
  )
}

function DefaultToolCall({ context }: { context: ToolRenderContext }) {
  if (!context.arguments.trim()) {
    return <span className="thread-tool-placeholder">（无参数）</span>
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
