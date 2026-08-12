import { ExternalLink, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useI18n } from '@/shared/i18n'

type MediaKind = 'image' | 'video'
type PreviewMode = 'image' | 'video'

/**
 * 内容优先的媒体展示：正文内完整呈现，悬浮显示文件名，点击打开原件 Lightbox。
 * Blob video 的 preview 是缩略图，因此可用 image preview + video original 组合。
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

  useEffect(() => {
    setMedia({ url: previewUrl, mode: previewMode })
  }, [previewMode, previewUrl])

  useEffect(() => {
    if (!expanded) {
      return
    }
    const closeOnEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        setExpanded(false)
      }
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [expanded])

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
        <div
          className="resource-media-lightbox"
          role="dialog"
          aria-modal="true"
          aria-label={t('ai.runtime.message.previewResource', { name: label })}
          onMouseDown={() => setExpanded(false)}
        >
          <div
            className="resource-media-lightbox-panel"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <button
              type="button"
              className="resource-media-lightbox-close"
              aria-label={t('ai.runtime.message.closeResourcePreview')}
              onClick={() => setExpanded(false)}
            >
              <X aria-hidden="true" />
            </button>
            <div className="resource-media-lightbox-content">
              {kind === 'video' ? (
                <video src={originalUrl} controls autoPlay playsInline />
              ) : (
                <img src={originalUrl} alt={label} />
              )}
            </div>
            <div className="resource-media-lightbox-footer">
              <span>{label}</span>
              <a
                href={originalUrl}
                target="_blank"
                rel="noreferrer noopener"
                aria-label={t('ai.runtime.message.openResource', { name: label })}
              >
                <ExternalLink aria-hidden="true" />
              </a>
            </div>
          </div>
        </div>
      ) : null}
    </>
  )
}
