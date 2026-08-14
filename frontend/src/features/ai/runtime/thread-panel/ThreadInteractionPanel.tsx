import { X } from 'lucide-react'
import type { KeyboardEventHandler, ReactNode } from 'react'
import { shouldDeferToBlockingOverlay } from '@/shared/ui/blocking-overlay'
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
  panelRef,
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
  /** 只读面板可把焦点挂到 section 自身（如快捷键目录），使键盘事件可达。 */
  panelRef?: React.Ref<HTMLElement>
  onClose: () => void
  onKeyDown?: KeyboardEventHandler<HTMLElement>
}) {
  const { t } = useI18n()
  return (
    <section
      ref={panelRef}
      tabIndex={panelRef ? -1 : undefined}
      className={['thread-interaction-panel', className].filter(Boolean).join(' ')}
      role="region"
      aria-label={title}
      aria-busy={busy}
      onKeyDown={(event) => {
        // Modal/alertdialog/lightbox 优先于全局 Escape；面板自身位于 overlay 内部时除外。
        if (shouldDeferToBlockingOverlay(event.currentTarget)) {
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
