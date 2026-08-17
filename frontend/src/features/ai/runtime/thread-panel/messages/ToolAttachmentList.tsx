import {
  formatToolAttachmentFallback,
  getToolAttachmentHref,
  getToolAttachmentLabel,
  isPreviewableAttachment,
  toToolAttachmentSrc,
} from '@/features/ai/runtime/thread-panel/tool-attachments'
import { AttachmentMediaPreview } from '@/features/ai/runtime/thread-panel/messages/AttachmentMediaPreview'
import { ResourceAttachmentChip } from '@/features/ai/runtime/thread-panel/messages/ResourceAttachmentChip'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'
import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'

/** Tool 结果附件列表：统一分发 durable blob、媒体和普通文件资源。 */
export function ToolAttachmentList({
  attachments,
}: {
  attachments: ToolAttachment[]
}) {
  return (
    <div className="thread-tool-attachments">
      {attachments.map((attachment, index) => (
        <ToolAttachmentPreview
          key={`${attachment.type}-${attachment.name}-${index}`}
          attachment={attachment}
        />
      ))}
    </div>
  )
}

function ToolAttachmentPreview({ attachment }: { attachment: ToolAttachment }) {
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
            // ResourceMessageContent.preview 是文本摘录，不是媒体 URL。
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
