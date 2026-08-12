import { RotateCcw, X } from 'lucide-react'
import {
  formatFileSize,
  uploadOccurrence,
  type AttachmentUpload,
} from '@/features/ai/composer/use-attachment-uploads'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import { useI18n } from '@/shared/i18n'

/** 附件注册表：只显示紧凑 Markdown 引用与上传状态，不重复铺设媒体预览卡片。 */
export function AttachmentStrip({
  uploads,
  parts,
  disabled,
  onRemove,
  onRetry,
}: {
  uploads: AttachmentUpload[]
  parts: ComposerPart[]
  disabled: boolean
  onRemove: (upload: AttachmentUpload) => void
  onRetry: (upload: AttachmentUpload) => void
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
            : upload.status === 'ready'
              ? formatFileSize(upload.sizeBytes)
              : upload.error ?? t('ai.runtime.composer.uploadFailed')
        return (
          <div
            key={upload.localId}
            role="listitem"
            className={[
              'attachment-reference',
              `is-${upload.status}`,
              occurrence === 0 ? 'is-unreferenced' : '',
            ].filter(Boolean).join(' ') || undefined}
          >
            <span className="attachment-reference-markdown" title={upload.filename}>
              [{displayName}](upload)
            </span>
            <span className="attachment-reference-status">{status}</span>
            {upload.status === 'error' ? (
              <button
                type="button"
                className="attachment-reference-retry"
                aria-label={t('ai.runtime.composer.retryUpload', { name: displayName })}
                disabled={disabled}
                onClick={() => onRetry(upload)}
              >
                <RotateCcw aria-hidden="true" />
              </button>
            ) : null}
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
