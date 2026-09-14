import {
  File,
  FileArchive,
  FileAudio,
  FileCode2,
  FileSpreadsheet,
  FileText,
  FileVideo,
  Image as ImageIcon,
  X,
  type LucideIcon,
} from 'lucide-react'
import { useState } from 'react'
import {
  formatFileSize,
  mediaKindOf,
  uploadOccurrence,
  type AttachmentUpload,
} from '@/features/ai/composer/use-attachment-uploads'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import { useI18n } from '@/shared/i18n'
import { MediaLightbox } from '@/shared/ui/media/MediaLightbox'

/** 附件注册表：图片/视频展示本地缩略图，其它文件按类型展示通用图标。 */
export function AttachmentStrip({
  uploads,
  parts,
  disabled,
  onRemove,
}: {
  uploads: AttachmentUpload[]
  parts: ComposerPart[]
  disabled: boolean
  onRemove: (upload: AttachmentUpload) => void
}) {
  const { t } = useI18n()
  const visible = uploads.filter((upload) => !upload.detached)
  // 同名文件按顺序派生展示后缀：身份始终由 uploadId 决定。
  const nameCounts = new Map<string, number>()
  for (const upload of visible) {
    nameCounts.set(upload.filename, (nameCounts.get(upload.filename) ?? 0) + 1)
  }
  const displayIndex = new Map<string, number>()

  if (visible.length === 0) {
    return null
  }

  return (
    <div className="thread-composer-strip" role="list" aria-label={t('ai.runtime.composer.strip')}>
      {visible.map((upload) => {
        const occurrence = uploadOccurrence(upload, parts)
        const nameIndex = (displayIndex.get(upload.filename) ?? 0) + 1
        displayIndex.set(upload.filename, nameIndex)
        const displayName =
          (nameCounts.get(upload.filename) ?? 1) > 1
            ? `${upload.filename} (${nameIndex})`
            : upload.filename
        const progress = Math.round(upload.progress * 100)
        const status =
          upload.status === 'uploading'
            ? `${t('ai.runtime.composer.uploading')} ${progress}%`
            : formatFileSize(upload.sizeBytes)
        return (
          <div
            key={upload.localId}
            role="listitem"
            data-filename={upload.filename}
            data-media-kind={mediaKindOf(upload.mediaType)}
            className={[
              'attachment-reference',
              `is-${upload.status}`,
              occurrence === 0 ? 'is-unreferenced' : '',
            ].filter(Boolean).join(' ') || undefined}
          >
            <AttachmentReferencePreview upload={upload} />
            <span className="attachment-reference-details">
              <span className="attachment-reference-name" title={upload.filename}>
                [{displayName}]
              </span>
              <span className="attachment-reference-status">{status}</span>
            </span>
            <button
              type="button"
              className="attachment-reference-remove"
              aria-label={t('ai.runtime.composer.removeAttachment', { name: displayName })}
              disabled={disabled}
              onClick={() => onRemove(upload)}
            >
              <X aria-hidden="true" />
            </button>
            {upload.status === 'uploading' ? (
              <span className="attachment-reference-progress" aria-hidden="true">
                <span style={{ width: `${progress}%` }} />
              </span>
            ) : null}
          </div>
        )
      })}
    </div>
  )
}

function AttachmentReferencePreview({ upload }: { upload: AttachmentUpload }) {
  const { t } = useI18n()
  const [expanded, setExpanded] = useState(false)
  const kind = mediaKindOf(upload.mediaType)
  if (kind === 'image' && upload.previewUrl) {
    return (
      <>
        <button
          type="button"
          className="attachment-reference-preview is-image"
          data-kind="image"
          aria-label={t('ai.runtime.message.previewResource', { name: upload.filename })}
          onClick={() => setExpanded(true)}
        >
          <img src={upload.previewUrl} alt="" />
        </button>
        {expanded ? (
          <MediaLightbox
            kind="image"
            label={upload.filename}
            url={upload.previewUrl}
            ariaLabel={t('ai.runtime.message.previewResource', { name: upload.filename })}
            closeLabel={t('ai.runtime.message.closeResourcePreview')}
            onClose={() => setExpanded(false)}
          />
        ) : null}
      </>
    )
  }
  if (kind === 'video' && upload.previewUrl) {
    return (
      <>
        <button
          type="button"
          className="attachment-reference-preview is-video"
          data-kind="video"
          aria-label={t('ai.runtime.message.previewResource', { name: upload.filename })}
          onClick={() => setExpanded(true)}
        >
          <video
            src={upload.previewUrl}
            muted
            playsInline
            preload="auto"
            onLoadedMetadata={(event) => seekFirstVisibleFrame(event.currentTarget)}
          />
        </button>
        {expanded ? (
          <MediaLightbox
            kind="video"
            label={upload.filename}
            url={upload.previewUrl}
            ariaLabel={t('ai.runtime.message.previewResource', { name: upload.filename })}
            closeLabel={t('ai.runtime.message.closeResourcePreview')}
            onClose={() => setExpanded(false)}
          />
        ) : null}
      </>
    )
  }
  const { icon: Icon, name } = fileIcon(upload.mediaType, upload.filename, kind)
  return (
    <span
      className={`attachment-reference-file-icon is-${kind}`}
      data-kind={kind}
      data-file-icon={name}
      aria-hidden="true"
    >
      <Icon />
    </span>
  )
}

function seekFirstVisibleFrame(video: HTMLVideoElement): void {
  if (!Number.isFinite(video.duration) || video.duration <= 0) {
    return
  }
  try {
    video.currentTime = Math.min(0.05, video.duration / 2)
  } catch {
    // 浏览器仍会展示可用的默认首帧；预览失败不影响上传。
  }
}

function fileIcon(
  mediaType: string,
  filename: string,
  kind: ReturnType<typeof mediaKindOf>,
): { icon: LucideIcon; name: string } {
  const normalizedType = mediaType.toLowerCase()
  const extension = filename.toLowerCase().split('.').pop() ?? ''
  if (kind === 'audio') {
    return { icon: FileAudio, name: 'audio' }
  }
  if (kind === 'image') {
    return { icon: ImageIcon, name: 'image' }
  }
  if (kind === 'video') {
    return { icon: FileVideo, name: 'video' }
  }
  if (
    normalizedType.includes('zip')
    || normalizedType.includes('compressed')
    || ['7z', 'bz2', 'gz', 'rar', 'tar', 'xz', 'zip'].includes(extension)
  ) {
    return { icon: FileArchive, name: 'archive' }
  }
  if (
    normalizedType.includes('spreadsheet')
    || normalizedType.includes('csv')
    || ['csv', 'ods', 'xls', 'xlsx'].includes(extension)
  ) {
    return { icon: FileSpreadsheet, name: 'spreadsheet' }
  }
  if (
    normalizedType.includes('json')
    || normalizedType.includes('javascript')
    || normalizedType.includes('xml')
    || ['css', 'go', 'html', 'java', 'js', 'json', 'jsx', 'py', 'rs', 'sh', 'sql', 'ts', 'tsx', 'xml'].includes(extension)
  ) {
    return { icon: FileCode2, name: 'code' }
  }
  if (
    normalizedType.startsWith('text/')
    || normalizedType === 'application/pdf'
    || ['doc', 'docx', 'md', 'pdf', 'rtf', 'txt'].includes(extension)
  ) {
    return { icon: FileText, name: 'text' }
  }
  return { icon: File, name: 'file' }
}
