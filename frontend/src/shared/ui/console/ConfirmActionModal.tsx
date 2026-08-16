import { Trash2 } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { ModalBackdrop, ModalHeader } from '@/shared/ui/console/AiConsoleModalLayout'
import type { ConfirmModalState } from '@/shared/ui/console/confirm-modal'
import { useI18n } from '@/shared/i18n'

export function ConfirmActionModal({
  modal,
  pending,
  onClose,
}: {
  modal: ConfirmModalState | null
  pending: boolean
  onClose: () => void
}) {
  const { t } = useI18n()
  const cancelRef = useRef<HTMLButtonElement>(null)
  const previousFocusRef = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (!modal) {
      return
    }
    previousFocusRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    cancelRef.current?.focus({ preventScroll: true })
    return () => {
      const previousFocus = previousFocusRef.current
      if (previousFocus?.isConnected) {
        previousFocus.focus({ preventScroll: true })
      }
    }
  }, [modal])

  useEffect(() => {
    if (!modal) {
      return
    }
    function handleEscape(event: globalThis.KeyboardEvent) {
      if (
        event.key !== 'Escape'
        || event.defaultPrevented
        || event.isComposing
        || event.keyCode === 229
      ) {
        return
      }
      event.preventDefault()
      event.stopPropagation()
      if (!pending) {
        onClose()
      }
    }
    window.addEventListener('keydown', handleEscape, true)
    return () => window.removeEventListener('keydown', handleEscape, true)
  }, [modal, onClose, pending])

  if (!modal) {
    return null
  }

  const effectiveClose = pending ? () => undefined : onClose
  return (
    <ModalBackdrop onClose={effectiveClose}>
      <div
        className="modal-card confirm-modal-card"
        role="alertdialog"
        aria-modal="true"
        aria-label={modal.title}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <ModalHeader title={modal.title} onClose={effectiveClose} closeDisabled={pending} />
        <div className="modal-body confirm-modal-body">
          <div className={`confirm-modal-icon ${modal.tone === 'danger' ? 'danger' : ''}`} aria-hidden="true">
            <Trash2 />
          </div>
          <p className="confirm-modal-description">{modal.description}</p>
        </div>
        <div className="modal-footer">
          <button
            ref={cancelRef}
            type="button"
            className="ghost-btn"
            onClick={onClose}
            disabled={pending}
          >
            {t('shared.cancel')}
          </button>
          <button type="button" className={`btn-primary ${modal.tone === 'danger' ? 'danger' : ''}`} onClick={modal.onConfirm} disabled={pending}>
            {modal.confirmLabel ?? t('shared.confirm')}
          </button>
        </div>
      </div>
    </ModalBackdrop>
  )
}
