import { useContext, useEffect, useState } from 'react'
import { Download, FileText, Image, Video } from 'lucide-react'
import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'
import {
  ResourceBlobUrlContext,
  type ResourceBlobUrls,
} from '@/features/ai/runtime/thread-panel/messages/ResourceBlobUrlContext'
import { useI18n } from '@/shared/i18n'

/**
 * durable RESOURCE 附件 chip：只在渲染时通过注入的 blob URL 解析器换取地址，
 * 成功后按 media type 提供预览（image/video，presigned-preview）与下载链接
 * （presigned-original）；解析失败降级为名称 + 不可用提示。绝不内联
 * kkstudio:// 或 s3:// 之类的宿主 URI。
 */
export function ResourceAttachmentChip({ attachment }: { attachment: ToolAttachment }) {
  const { t } = useI18n()
  const resolveBlobUrls = useContext(ResourceBlobUrlContext)
  const [urls, setUrls] = useState<ResourceBlobUrls | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    if (!attachment.blobId || !resolveBlobUrls) {
      setFailed(true)
      return
    }
    let cancelled = false
    setUrls(null)
    setFailed(false)
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

  const label = attachment.name || attachment.mime || t('ai.runtime.message.attachment')
  const previewUrl =
    urls?.preview != null && (attachment.type === 'image' || attachment.type === 'video')
      ? urls.preview
      : null
  const downloadUrl = urls?.original ?? null
  const unavailable = urls == null && failed
  return (
    <span
      className={`resource-attachment-chip is-${attachment.type}`}
      title={label}
    >
      {previewUrl != null ? (
        attachment.type === 'video' ? (
          <video className="resource-attachment-preview" src={previewUrl} muted tabIndex={-1} />
        ) : (
          <img className="resource-attachment-preview" src={previewUrl} alt="" tabIndex={-1} />
        )
      ) : (
        <span className="resource-attachment-icon" aria-hidden="true">
          {attachment.type === 'image' ? <Image /> : attachment.type === 'video' ? <Video /> : <FileText />}
        </span>
      )}
      <span className="resource-attachment-name">{label}</span>
      {downloadUrl != null ? (
        <a
          className="resource-attachment-download"
          href={downloadUrl}
          target="_blank"
          rel="noreferrer"
          aria-label={t('ai.runtime.message.downloadResource', { name: label })}
        >
          <Download aria-hidden="true" />
        </a>
      ) : null}
      {unavailable ? <span className="resource-attachment-failed">{t('ai.runtime.message.resourceUnavailable')}</span> : null}
    </span>
  )
}
