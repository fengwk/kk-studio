import { AttachmentMediaPreview } from '@/features/ai/runtime/thread-panel/messages/AttachmentMediaPreview'
import { ResourceAttachmentChip } from '@/features/ai/runtime/thread-panel/messages/ResourceAttachmentChip'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'
import {
  formatToolAttachmentFallback,
  getToolAttachmentHref,
  getToolAttachmentLabel,
  isPreviewableAttachment,
  toToolAttachmentSrc,
} from '@/features/ai/runtime/thread-panel/tool-attachments'
import { formatJsonContent } from '@/features/ai/runtime/thread-timeline/content-utils'
import type {
  ToolAttachment,
  ToolContent,
} from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/**
 * 有序结果内容的只读渲染：Text 忠实展示、JSON 格式化、Resource 按权威 MIME 渲染。
 * 内容顺序不被重排，也不把资源挪到所有文本之后。
 *
 * `followTail` 只由持续日志（bash 流式输出）的调用方开启；默认 false 时 Text/JSON
 * 都从顶部读，内容增长也不强拉到底部。
 */
export function ToolContentView({
  contents,
  followTail = false,
}: {
  contents: ToolContent[]
  followTail?: boolean
}) {
  return (
    <div className="thread-tool-contents">
      {contents.map((content, index) => (
        <ToolContentItem key={contentKey(content, index)} content={content} followTail={followTail} />
      ))}
    </div>
  )
}

function ToolContentItem({
  content,
  followTail,
}: {
  content: ToolContent
  followTail: boolean
}) {
  if (content.type === 'text') {
    return (
      <ToolOutputViewport followKey={content.text} followTail={followTail}>
        {content.text}
      </ToolOutputViewport>
    )
  }
  if (content.type === 'json') {
    const text = formatJsonContent(content.value)
    return (
      <ToolOutputViewport followKey={text} followTail={followTail} className="is-json">
        {text}
      </ToolOutputViewport>
    )
  }
  return <ToolResourceView attachment={content.attachment} />
}

function contentKey(content: ToolContent, index: number): string {
  if (content.type === 'resource') {
    return `resource:${content.attachment.blobId ?? content.attachment.name}:${index}`
  }
  return `${content.type}:${index}`
}

/**
 * 单一资源展示：按权威 MIME（durable blob 的解析结果，其次 content.mediaType）统一
 * 分发图片/视频/音频/下载。远程 http(s) 与 file:/s3: 只保留稳定 URI 文本与显式链接，
 * 绝不自动发起请求；音视频不自动播放。
 */
function ToolResourceView({ attachment }: { attachment: ToolAttachment }) {
  const { t } = useI18n()
  const preview = attachment.preview?.trim()
  if (attachment.blobId) {
    return (
      <div className="thread-tool-resource is-blob">
        <ResourceAttachmentChip attachment={attachment} />
        {preview ? (
          <ToolOutputViewport
            followKey={preview}
            className="thread-tool-attachment-preview"
          >
            {preview}
          </ToolOutputViewport>
        ) : null}
      </div>
    )
  }
  const src = toToolAttachmentSrc(attachment)
  const href = getToolAttachmentHref(attachment)
  const label = getToolAttachmentLabel(attachment)
  const previewable = src != null && isPreviewableAttachment(attachment)
  if (previewable && src && (attachment.type === 'image' || attachment.type === 'video')) {
    return (
      <div className="thread-tool-resource">
        <AttachmentMediaPreview
          kind={attachment.type}
          label={label}
          previewUrl={src}
          originalUrl={href ?? src}
          previewMode={attachment.type}
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
            <ToolOutputViewport
              followKey={preview}
              className="thread-tool-attachment-preview"
            >
              {preview}
            </ToolOutputViewport>
          ) : null}
          <span className="thread-tool-attachment-uri-text">{attachment.data}</span>
        </div>
      ) : null}
      {href ? (
        <a
          className="resource-attachment-download"
          href={href}
          target="_blank"
          rel="noopener noreferrer"
          aria-label={t('ai.runtime.message.downloadResource', { name: label })}
        >
          [{label}]
        </a>
      ) : (
        <span className="resource-attachment-fallback">
          {formatToolAttachmentFallback(attachment)}
        </span>
      )}
    </div>
  )
}
