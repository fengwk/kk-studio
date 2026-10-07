import { ExternalLink, X } from 'lucide-react'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import './media.css'

export type MediaLightboxKind = 'image' | 'video'

/**
 * 图片/视频全屏预览；调用方负责触发按钮与本地化文案。
 * portal、焦点陷阱、关闭后焦点归还与顶层 Escape 复用共享 Dialog，
 * 保留图片/视频尺寸约束与“查看原件”入口。
 */
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
  return (
    <Dialog className="media-lightbox-card" ariaLabel={ariaLabel} onClose={onClose}>
      <div className="media-lightbox-body">
        <IconButton label={closeLabel} className="media-lightbox-close" onClick={onClose}>
          <X aria-hidden="true" />
        </IconButton>
        <div className="media-lightbox-content">
          {kind === 'video' ? (
            <video src={url} controls autoPlay playsInline />
          ) : (
            <img src={url} alt={label} />
          )}
        </div>
        <div className="media-lightbox-footer">
          <span>{label}</span>
          {openLabel ? (
            <a href={url} target="_blank" rel="noreferrer noopener" aria-label={openLabel}>
              <ExternalLink aria-hidden="true" />
            </a>
          ) : null}
        </div>
      </div>
    </Dialog>
  )
}
