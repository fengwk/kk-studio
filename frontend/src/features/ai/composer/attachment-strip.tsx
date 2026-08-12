import { useEffect, useState } from 'react'
import { File, FileAudio, FileImage, FileText, FileVideo, X } from 'lucide-react'
import {
  formatFileSize,
  uploadOccurrence,
  type AttachmentUpload,
} from '@/features/ai/composer/use-attachment-uploads'
import type { ComposerPart } from '@/features/ai/composer/composer-parts'
import { useI18n } from '@/shared/i18n'

/**
 * 附件注册表 strip：位于 editor 上方，横向滚动。
 * - image/video 有本地预览（点击打开预览 modal）；其余显示通用图标；
 * - 上传中显示进度，失败显示错误 + 重试；
 * - tile X 移除该上传的全部 occurrences（由父组件释放句柄）。
 */
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
  const [previewing, setPreviewing] = useState<AttachmentUpload | null>(null)
  const visible = uploads.filter((upload) => !upload.detached)
  // 同名文件按顺序派生展示后缀：身份始终由 uploadId 决定。
  const nameCounts = new Map<string, number>()
  for (const upload of visible) {
    nameCounts.set(upload.filename, (nameCounts.get(upload.filename) ?? 0) + 1)
  }
  const displayIndex = new Map<string, number>()

  useEffect(() => {
    if (previewing && !visible.some((upload) => upload.localId === previewing.localId)) {
      setPreviewing(null)
    }
  }, [previewing, visible])

  if (visible.length === 0) {
    return null
  }

  return (
    <>
      <div className="thread-composer-strip" role="list" aria-label={t('ai.runtime.composer.strip')}>
        {visible.map((upload) => {
          const occurrence = uploadOccurrence(upload, parts)
          const nameIndex = (displayIndex.get(upload.filename) ?? 0) + 1
          displayIndex.set(upload.filename, nameIndex)
          const displayName =
            (nameCounts.get(upload.filename) ?? 1) > 1
              ? `${upload.filename} (${nameIndex})`
              : upload.filename
          const previewable = upload.previewUrl != null
          return (
            <div
              key={upload.localId}
              role="listitem"
              className={[
                'attachment-tile',
                `is-${upload.status}`,
                occurrence === 0 ? 'is-unreferenced' : '',
              ].filter(Boolean).join(' ') || undefined}
            >
              <button
                type="button"
                className="attachment-tile-main"
                aria-label={t('ai.runtime.composer.previewAttachment', { name: displayName })}
                disabled={disabled || !previewable}
                onClick={() => {
                  if (previewable) {
                    setPreviewing(upload)
                  }
                }}
              >
                {previewable && upload.previewUrl ? (
                  upload.mediaKind === 'video' ? (
                    <video className="attachment-tile-preview" src={upload.previewUrl} muted />
                  ) : (
                    <img className="attachment-tile-preview" src={upload.previewUrl} alt="" />
                  )
                ) : (
                  <span className="attachment-tile-icon" aria-hidden="true">
                    <AttachmentKindIcon kind={upload.mediaKind} />
                  </span>
                )}
                <span className="attachment-tile-caption">
                  <span className="attachment-tile-name" title={upload.filename}>
                    {displayName}
                  </span>
                  <span className="attachment-tile-meta">
                    {upload.status === 'uploading' ? t('ai.runtime.composer.uploading') : ''}
                    {upload.status === 'ready' ? formatFileSize(upload.sizeBytes) : ''}
                    {upload.status === 'error' ? (upload.error ?? t('ai.runtime.composer.uploadFailed')) : ''}
                  </span>
                </span>
                {upload.status === 'uploading' ? (
                  <span className="attachment-tile-progress" aria-hidden="true">
                    <span style={{ width: `${Math.round(upload.progress * 100)}%` }} />
                  </span>
                ) : null}
              </button>
              {upload.status === 'error' ? (
                <button
                  type="button"
                  className="attachment-tile-retry"
                  aria-label={t('ai.runtime.composer.retryUpload', { name: displayName })}
                  disabled={disabled}
                  onClick={() => onRetry(upload)}
                >
                  {t('ai.runtime.composer.retry')}
                </button>
              ) : null}
              <button
                type="button"
                className="attachment-tile-remove"
                aria-label={t('ai.runtime.composer.removeAttachment', { name: displayName })}
                disabled={disabled}
                onClick={() => onRemove(upload)}
              >
                <X aria-hidden="true" />
              </button>
            </div>
          )
        })}
      </div>
      {previewing?.previewUrl ? (
        <div
          className="attachment-preview-backdrop"
          role="dialog"
          aria-label={t('ai.runtime.composer.previewAttachment', { name: previewing.filename })}
          onMouseDown={() => setPreviewing(null)}
        >
          <div className="attachment-preview-modal" onMouseDown={(event) => event.stopPropagation()}>
            {previewing.mediaKind === 'video' ? (
              <video src={previewing.previewUrl} controls autoPlay />
            ) : (
              <img src={previewing.previewUrl} alt={previewing.filename} />
            )}
            <div className="attachment-preview-footer">
              <span>{previewing.filename}</span>
              <button
                type="button"
                aria-label={t('ai.runtime.composer.closePreview')}
                onClick={() => setPreviewing(null)}
              >
                <X aria-hidden="true" />
              </button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  )
}

function AttachmentKindIcon({ kind }: { kind: AttachmentUpload['mediaKind'] }) {
  if (kind === 'image') {
    return <FileImage />
  }
  if (kind === 'video') {
    return <FileVideo />
  }
  if (kind === 'audio') {
    return <FileAudio />
  }
  if (kind === 'file') {
    return <FileText />
  }
  return <File />
}
