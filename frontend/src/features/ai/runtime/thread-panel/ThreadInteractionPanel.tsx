import { X } from 'lucide-react'
import type { KeyboardEventHandler, ReactNode } from 'react'
import { useI18n } from '@/shared/i18n'

/**
 * Composer 区域的轻量交互面板外壳。
 *
 * 面板不创建 backdrop，也不抢占整个页面；由 ThreadPanel/调用方与 Composer
 * 互斥展示。选择器、历史树等交互只需复用统一的标题、控制区、正文与 footer。
 */
export function ThreadInteractionPanel({
  title,
  controls,
  children,
  footer,
  className,
  bodyClassName,
  closeDisabled = false,
  busy = false,
  onClose,
  onKeyDown,
}: {
  title: string
  controls?: ReactNode
  children: ReactNode
  footer?: ReactNode
  className?: string
  bodyClassName?: string
  closeDisabled?: boolean
  busy?: boolean
  onClose: () => void
  onKeyDown?: KeyboardEventHandler<HTMLElement>
}) {
  const { t } = useI18n()
  return (
    <section
      className={['thread-interaction-panel', className].filter(Boolean).join(' ')}
      role="region"
      aria-label={title}
      aria-busy={busy}
      onKeyDown={(event) => {
        const blockingModal = document.querySelector<HTMLElement>(
          '.modal-backdrop, [aria-modal="true"], [role="alertdialog"]',
        )
        if (blockingModal && !blockingModal.contains(event.currentTarget)) {
          return
        }
        onKeyDown?.(event)
      }}
    >
      <header className="thread-interaction-header">
        <h2>{title}</h2>
        <button
          type="button"
          className="thread-interaction-close"
          aria-label={t('shared.close')}
          disabled={closeDisabled}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </header>
      {controls == null ? null : (
        <div className="thread-interaction-controls">{controls}</div>
      )}
      <div
        className={[
          'thread-interaction-body',
          bodyClassName,
        ].filter(Boolean).join(' ')}
      >
        {children}
      </div>
      {footer == null ? null : (
        <footer className="thread-interaction-footer">{footer}</footer>
      )}
    </section>
  )
}
