import { useContext, useEffect, useState } from 'react'
import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'
import { AttachmentMediaPreview } from '@/features/ai/runtime/thread-panel/messages/AttachmentMediaPreview'
import {
  ResourceBlobUrlContext,
  type ResourceBlobUrls,
} from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { useI18n } from '@/shared/i18n'

/**
 * durable RESOURCE 展示：图片/视频使用完整媒体 + hover 名称 + Lightbox；
 * 其他类型使用紧凑链接。URL 只在渲染时解析，不进入 durable message。
 */
export function ResourceAttachmentChip({ attachment }: { attachment: ToolAttachment }) {
  const { t } = useI18n()
  const resolveBlobUrls = useContext(ResourceBlobUrlContext)
  const [urls, setUrls] = useState<ResourceBlobUrls | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    setUrls(null)
    setFailed(false)
    if (!attachment.blobId || !resolveBlobUrls) {
      setFailed(true)
      return
    }
    let cancelled = false
    resolveBlobUrls(attachment.blobId)
      .then((resolved) => {
        if (cancelled) {
          return
        }
        if (resolved == null) {
          setFailed(true)
        } else {
          setUrls(resolved)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setFailed(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [attachment.blobId, resolveBlobUrls])

  const authoritativeMediaType = urls?.mediaType?.trim() || null
  const mediaType = authoritativeMediaType ?? attachment.mime
  const type = attachmentType(
    mediaType,
    authoritativeMediaType == null ? attachment.type : 'file',
  )
  const label =
    attachment.name
    || mediaType
    || t('ai.runtime.message.attachment', { type: attachment.type })
  const downloadUrl = urls?.original?.trim() || null
  const previewUrl = urls?.preview?.trim() || null
  const mediaUrl =
    type === 'image'
      ? downloadUrl
      : type === 'video' && downloadUrl != null
        ? previewUrl ?? downloadUrl
        : null
  const unavailable = failed || (urls != null && downloadUrl == null)
  if (mediaUrl && downloadUrl && (type === 'image' || type === 'video')) {
    return (
      <AttachmentMediaPreview
        kind={type}
        label={label}
        previewUrl={mediaUrl}
        originalUrl={downloadUrl}
        previewMode={type === 'video' && previewUrl ? 'image' : type}
      />
    )
  }
  return (
    <span
      className={`resource-attachment-link is-${type}`}
      title={label}
    >
      {downloadUrl != null ? (
        <a
          className="resource-attachment-download"
          href={downloadUrl}
          target="_blank"
          rel="noreferrer noopener"
          aria-label={t('ai.runtime.message.downloadResource', { name: label })}
        >
          [{label}]
        </a>
      ) : <span>[{label}]</span>}
      {unavailable ? <span className="resource-attachment-failed">{t('ai.runtime.message.resourceUnavailable')}</span> : null}
    </span>
  )
}

function attachmentType(mediaType: string, fallback: ToolAttachment['type']): ToolAttachment['type'] {
  if (mediaType.startsWith('image/')) {
    return 'image'
  }
  if (mediaType.startsWith('audio/')) {
    return 'audio'
  }
  if (mediaType.startsWith('video/')) {
    return 'video'
  }
  return fallback
}
