import { Trash2 } from 'lucide-react'
import { ModalBackdrop, ModalHeader } from '@/features/ai/AiConsoleModalLayout'
import type { ConfirmModalState } from '@/features/ai/ai-console-types'

export function ConfirmActionModal({
  modal,
  pending,
  onClose,
}: {
  modal: ConfirmModalState | null
  pending: boolean
  onClose: () => void
}) {
  if (!modal) {
    return null
  }

  const effectiveClose = pending ? () => undefined : onClose
  return (
    <ModalBackdrop onClose={effectiveClose}>
      <div className="modal-card confirm-modal-card" role="alertdialog" aria-label={modal.title} onMouseDown={(event) => event.stopPropagation()}>
        <ModalHeader title={modal.title} onClose={effectiveClose} />
        <div className="modal-body confirm-modal-body">
          <div className={`confirm-modal-icon ${modal.tone === 'danger' ? 'danger' : ''}`} aria-hidden="true">
            <Trash2 />
          </div>
          <p className="confirm-modal-description">{modal.description}</p>
        </div>
        <div className="modal-footer">
          <button type="button" className="ghost-btn" onClick={onClose} disabled={pending}>
            取消
          </button>
          <button type="button" className={`btn-primary ${modal.tone === 'danger' ? 'danger' : ''}`} onClick={modal.onConfirm} disabled={pending}>
            {modal.confirmLabel || '确认'}
          </button>
        </div>
      </div>
    </ModalBackdrop>
  )
}
