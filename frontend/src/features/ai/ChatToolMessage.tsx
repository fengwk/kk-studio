import type { DialogueMessage, ToolAttachment, ToolDialogueMessage } from '@/features/ai/session-events'
import {
  formatToolAttachmentFallback,
  getToolAttachmentLabel,
  toToolAttachmentSrc,
} from '@/features/ai/tool-attachments'

export function ChatToolMessage({ message }: { message: ToolDialogueMessage }) {
  const hasText = message.text.trim().length > 0
  const hasAttachments = message.attachments.length > 0

  return (
    <div className="tool-message">
      <div className="tool-header">
        <strong>{message.toolName || 'Tool'}</strong>
        <span className={`tool-status ${message.status ?? 'done'}`}>
          {formatToolStatus(message.status)}
        </span>
      </div>
      {message.arguments.trim() && (
        <div className="tool-section">
          <span className="tool-label">arguments</span>
          <pre className="tool-block">{message.arguments}</pre>
        </div>
      )}
      <div className="tool-section">
        <span className="tool-label">output</span>
        {hasText ? (
          <pre className="tool-block">{message.text}</pre>
        ) : hasAttachments ? null : (
          <p className="tool-placeholder">{getToolPlaceholder(message)}</p>
        )}
        {hasAttachments && (
          <div className="tool-attachments">
            {message.attachments.map((attachment, index) => (
              <ToolAttachmentPreview
                key={`${attachment.type}-${attachment.name}-${index}`}
                attachment={attachment}
              />
            ))}
          </div>
        )}
        {message.errorMessage && message.errorMessage !== message.text && (
          <p className="tool-error-text">{message.errorMessage}</p>
        )}
      </div>
    </div>
  )
}

function ToolAttachmentPreview({ attachment }: { attachment: ToolAttachment }) {
  const src = toToolAttachmentSrc(attachment)
  const label = getToolAttachmentLabel(attachment)

  return (
    <figure className="tool-attachment">
      <figcaption className="tool-attachment-header">
        <span className="tool-attachment-type">{attachment.type}</span>
        <span className="tool-attachment-name">{label}</span>
      </figcaption>
      {renderToolAttachmentMedia(attachment, src, label)}
      {src && (
        <a className="tool-attachment-link" href={src} target="_blank" rel="noreferrer">
          打开原始内容
        </a>
      )}
    </figure>
  )
}

function renderToolAttachmentMedia(
  attachment: ToolAttachment,
  src: string | null,
  label: string,
) {
  if (!src) {
    return <div className="tool-attachment-fallback">{formatToolAttachmentFallback(attachment)}</div>
  }
  if (attachment.type === 'image') {
    return <img className="tool-attachment-media image" src={src} alt={label} loading="lazy" />
  }
  if (attachment.type === 'audio') {
    return (
      <audio className="tool-attachment-media audio" src={src} controls preload="metadata" />
    )
  }
  return (
    <video className="tool-attachment-media video" src={src} controls preload="metadata" playsInline />
  )
}

function formatToolStatus(status?: DialogueMessage['status']): string {
  if (status === 'streaming') {
    return 'running'
  }
  if (status === 'error') {
    return 'error'
  }
  return 'done'
}

function getToolPlaceholder(message: ToolDialogueMessage): string {
  if (message.status === 'error') {
    return '工具执行失败。'
  }
  if (message.status === 'streaming') {
    return '等待工具结果...'
  }
  return '工具执行完成，无文本输出。'
}
