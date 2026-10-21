import { useEffect, useRef, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import { MediaLightbox } from '@/shared/ui/media/MediaLightbox'

type MediaKind = 'image' | 'video'
type PreviewMode = 'image' | 'video'

/**
 * 内容优先的媒体展示：正文内使用轻量预览，悬浮显示文件名，点击后才加载原件 Lightbox。
 * Blob image/video 的 preview 都是 webp 缩略图，因此可用 image preview + 原始媒体组合。
 */
export function AttachmentMediaPreview({
  kind,
  label,
  previewUrl,
  originalUrl,
  previewMode = kind,
}: {
  kind: MediaKind
  label: string
  previewUrl: string
  originalUrl: string | null
  previewMode?: PreviewMode
}) {
  const { t } = useI18n()
  const [expanded, setExpanded] = useState(false)
  const [media, setMedia] = useState({ url: previewUrl, mode: previewMode })
  const mediaSource = useRef({ kind, originalUrl, previewMode, previewUrl })

  useEffect(() => {
    const previous = mediaSource.current
    if (
      previous.kind === kind
      && previous.originalUrl === originalUrl
      && previous.previewMode === previewMode
      && previous.previewUrl === previewUrl
    ) {
      return
    }
    mediaSource.current = { kind, originalUrl, previewMode, previewUrl }
    setMedia({ url: previewUrl, mode: previewMode })
  }, [kind, originalUrl, previewMode, previewUrl])

  function fallbackToOriginal() {
    if (!originalUrl || media.url === originalUrl) {
      return
    }
    setMedia({ url: originalUrl, mode: kind })
  }

  return (
    <>
      <figure className={`resource-media-preview is-${kind}`}>
        <button
          type="button"
          className="resource-media-preview-trigger"
          aria-label={t('ai.runtime.message.previewResource', { name: label })}
          disabled={!originalUrl}
          onClick={() => {
            if (originalUrl) {
              setExpanded(true)
            }
          }}
        >
          {media.mode === 'video' ? (
            <video
              src={media.url}
              muted
              playsInline
              preload="metadata"
              onError={fallbackToOriginal}
            />
          ) : (
            <img
              src={media.url}
              alt={label}
              loading="lazy"
              decoding="async"
              onError={fallbackToOriginal}
            />
          )}
          <span className="resource-media-preview-name">{label}</span>
        </button>
      </figure>
      {expanded && originalUrl ? (
        <MediaLightbox
          kind={kind}
          label={label}
          url={originalUrl}
          ariaLabel={t('ai.runtime.message.previewResource', { name: label })}
          closeLabel={t('ai.runtime.message.closeResourcePreview')}
          openLabel={t('ai.runtime.message.openResource', { name: label })}
          onClose={() => setExpanded(false)}
        />
      ) : null}
    </>
  )
}
