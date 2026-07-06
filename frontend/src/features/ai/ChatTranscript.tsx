import { Bot, MessageSquare, UserRound, Wrench } from 'lucide-react'
import type { RefObject } from 'react'
import type { DialogueMessage, ToolAttachment, ToolDialogueMessage } from '@/features/ai/session-events'
import { formatToolAttachmentFallback, getToolAttachmentLabel, toToolAttachmentSrc } from '@/features/ai/tool-attachments'

export function ChatTranscript({
  messages,
  loading,
  error,
  bodyRef,
}: {
  messages: DialogueMessage[]
  loading: boolean
  error: unknown
  bodyRef: RefObject<HTMLDivElement | null>
}) {
  const hasError = Boolean(error)
  const visibleMessages = messages.filter(isVisibleDialogueMessage)
  return (
    <div className="chat-body" ref={bodyRef}>
      {loading && <div className="state-block">正在加载会话</div>}
      {hasError && <div className="state-block danger">会话加载失败</div>}
      {!loading && !hasError && visibleMessages.length === 0 && (
        <div className="empty-dialogue">
          <MessageSquare aria-hidden="true" />
          <p>发送消息以开启全新对话。</p>
        </div>
      )}
      {visibleMessages.map((message) => (
        <DialogueBubble key={message.id} message={message} />
      ))}
    </div>
  )
}

function DialogueBubble({ message }: { message: DialogueMessage }) {
  const wrapperClass = message.role === 'user' ? 'user' : message.role === 'tool' ? 'tool' : 'bot'
  return (
    <div className={`msg-wrapper ${wrapperClass}`}>
      <div className="msg-avatar">
        {message.role === 'user' ? (
          <UserRound aria-hidden="true" />
        ) : message.role === 'tool' ? (
          <Wrench aria-hidden="true" />
        ) : (
          <Bot aria-hidden="true" />
        )}
      </div>
      <div className={`msg-bubble ${message.role === 'tool' ? 'tool' : ''} ${message.status === 'error' ? 'error' : ''}`}>
        {isToolMessage(message) ? <ToolBubbleContent message={message} /> : <p>{message.text || (message.status === 'streaming' ? '...' : '')}</p>}
      </div>
    </div>
  )
}

function ToolBubbleContent({ message }: { message: ToolDialogueMessage }) {
  const hasText = message.text.trim().length > 0
  const hasAttachments = message.attachments.length > 0
  return (
    <div className="tool-message">
      <div className="tool-header">
        <strong>{message.toolName || 'Tool'}</strong>
        <span className={`tool-status ${message.status ?? 'done'}`}>{formatToolStatus(message.status)}</span>
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
              <ToolAttachmentPreview key={`${attachment.type}-${attachment.name}-${index}`} attachment={attachment} />
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

function renderToolAttachmentMedia(attachment: ToolAttachment, src: string | null, label: string) {
  if (!src) {
    return <div className="tool-attachment-fallback">{formatToolAttachmentFallback(attachment)}</div>
  }
  if (attachment.type === 'image') {
    return <img className="tool-attachment-media image" src={src} alt={label} loading="lazy" />
  }
  if (attachment.type === 'audio') {
    return <audio className="tool-attachment-media audio" src={src} controls preload="metadata" />
  }
  return <video className="tool-attachment-media video" src={src} controls preload="metadata" playsInline />
}

function isVisibleDialogueMessage(message: DialogueMessage): boolean {
  return message.role !== 'assistant' || message.status === 'error' || message.text.trim().length > 0
}

function isToolMessage(message: DialogueMessage): message is ToolDialogueMessage {
  return message.role === 'tool'
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
