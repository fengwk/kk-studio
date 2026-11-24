import { RotateCw, X } from 'lucide-react'
import { formatByteCount } from '@/shared/lib/format-bytes'
import { mediaKindOf } from '@/shared/lib/upload-file'
import {
  uploadOccurrence,
  type AttachmentUpload,
} from '@/features/thread/composer/attachment-uploads'
import type {
  ComposerPart,
  ImageInputTier,
} from '@/features/thread/composer/composer-parts'
import { useI18n } from '@/shared/i18n'
import { Select } from '@/shared/ui/controls/Select'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Button } from '@/shared/ui/controls/Button'
import { AttachmentReferencePreview } from '@/features/thread/composer/AttachmentReferencePreview'
import './attachment-strip.css'

const IMAGE_TIER_OPTIONS = [
  { value: '720P', label: '720P' },
  { value: '1080P', label: '1080P' },
]

/** 附件注册表：图片/视频展示本地缩略图，其它文件按类型展示通用图标。 */
export function AttachmentStrip({
  uploads,
  parts,
  disabled,
  onRemove,
  onRetry,
  onTierChange,
}: {
  uploads: AttachmentUpload[]
  parts: ComposerPart[]
  disabled: boolean
  onRemove: (upload: AttachmentUpload) => void
  onRetry?: (upload: AttachmentUpload) => void
  onTierChange?: (upload: AttachmentUpload, tier: ImageInputTier) => void
}) {
  const { t } = useI18n()
  const tierOptions = [
    ...IMAGE_TIER_OPTIONS,
    { value: 'ORIGINAL', label: t('ai.runtime.composer.tierOriginal') },
  ]
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
            : upload.status === 'complete_unknown'
              ? t('ai.runtime.composer.completeUnknown')
              : upload.status === 'failed'
                ? t('ai.runtime.composer.uploadFailed')
                : formatByteCount(upload.sizeBytes)
        const isImage = mediaKindOf(upload.mediaType) === 'image'
        return (
          <div
            key={upload.localId}
            role="listitem"
            data-filename={upload.filename}
            data-media-kind={mediaKindOf(upload.mediaType)}
            data-image-tier={upload.imageTier}
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
              <span className="attachment-reference-status-row">
                <span className="attachment-reference-status">{status}</span>
                {(upload.status === 'complete_unknown' || upload.status === 'failed') && onRetry ? (
                  <Button
                    variant="secondary"
                    className="attachment-reference-retry"
                    aria-label={t(upload.status === 'failed'
                      ? 'ai.runtime.composer.retryUpload'
                      : 'ai.runtime.composer.retryComplete', { name: displayName })}
                    disabled={disabled}
                    onClick={() => onRetry(upload)}
                  >
                    <RotateCw size={12} aria-hidden="true" />
                  </Button>
                ) : null}
                {isImage && (
                  <span title={t('ai.runtime.composer.imageTierHelp')}>
                    <Select
                      compact
                      className="attachment-reference-tier-select"
                      aria-label={t('ai.runtime.composer.imageTier', { name: displayName })}
                      value={upload.imageTier ?? ''}
                      placeholder={t('ai.runtime.composer.imageTierLoading')}
                      disabled={disabled || !upload.imageTier || !upload.sourceFileAvailable}
                      options={tierOptions}
                      onChange={(tier) => onTierChange?.(upload, tier as ImageInputTier)}
                    />
                  </span>
                )}
              </span>
            </span>
            <IconButton
              className="attachment-reference-remove"
              label={t('ai.runtime.composer.removeAttachment', { name: displayName })}
              disabled={disabled}
              onClick={() => onRemove(upload)}
            >
              <X aria-hidden="true" />
            </IconButton>
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
