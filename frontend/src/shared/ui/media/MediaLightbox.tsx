import { ExternalLink, X } from 'lucide-react'
import { useEffect } from 'react'

export type MediaLightboxKind = 'image' | 'video'

/** 图片/视频全屏预览；调用方负责触发按钮与本地化文案。 */
export function MediaLightbox({
  kind,
  label,
  url,
  ariaLabel,
  closeLabel,
  openLabel,
  onClose,
}: {
  kind: MediaLightboxKind
  label: string
  url: string
  ariaLabel: string
  closeLabel: string
  openLabel?: string
  onClose: () => void
}) {
  useEffect(() => {
    const closeOnEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape' && !event.isComposing) {
        onClose()
      }
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose])

  return (
    <div
      className="resource-media-lightbox"
      role="dialog"
      aria-modal="true"
      aria-label={ariaLabel}
      onMouseDown={onClose}
    >
      <div
        className="resource-media-lightbox-panel"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <button
          type="button"
          className="resource-media-lightbox-close"
          aria-label={closeLabel}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
        <div className="resource-media-lightbox-content">
          {kind === 'video' ? (
            <video src={url} controls autoPlay playsInline />
          ) : (
            <img src={url} alt={label} />
          )}
        </div>
        <div className="resource-media-lightbox-footer">
          <span>{label}</span>
          {openLabel ? (
            <a
              href={url}
              target="_blank"
              rel="noreferrer noopener"
              aria-label={openLabel}
            >
              <ExternalLink aria-hidden="true" />
            </a>
          ) : null}
        </div>
      </div>
    </div>
  )
}
