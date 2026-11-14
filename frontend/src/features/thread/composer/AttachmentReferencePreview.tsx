import {
  File,
  FileArchive,
  FileAudio,
  FileCode2,
  FileSpreadsheet,
  FileText,
  FileVideo,
  Image as ImageIcon,
  type LucideIcon,
} from 'lucide-react'
import { useState } from 'react'
import { mediaKindOf } from '@/shared/lib/upload-file'
import { useI18n } from '@/shared/i18n'
import { MediaLightbox } from '@/shared/ui/media/MediaLightbox'
import type { AttachmentUpload } from '@/features/thread/composer/attachment-uploads'

/**
 * 上传条目的缩略图槽：图片/视频在本地预览可点击放大，其它类型按媒体种类显示图标。
 * 预览 URL 只覆盖当前 composer 生命周期，因此放大窗口直接用同一个 URL。
 */
export function AttachmentReferencePreview({ upload }: { upload: AttachmentUpload }) {
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

/** 视频缩略图定位到首个可见画面；元数据不可用时保留浏览器默认首帧。 */
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
