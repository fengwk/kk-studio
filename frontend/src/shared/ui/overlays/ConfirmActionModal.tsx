import { RefreshCw, Trash2 } from 'lucide-react'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import type { ConfirmModalState } from '@/shared/ui/overlays/confirm-modal'
import { useI18n } from '@/shared/i18n'

/**
 * 通用确认弹窗。焦点管理、Escape、遮罩关闭与 pending 守卫统一由 Dialog 提供。
 */
export function ConfirmActionModal({
  modal,
  pending,
  error,
  onClose,
}: {
  modal: ConfirmModalState | null
  pending: boolean
  error?: string | null
  onClose: () => void
}) {
  const { t } = useI18n()
  if (!modal) {
    return null
  }
  const displayError = error ?? modal.error

  return (
    <Dialog
      role="alertdialog"
      className="confirm-modal-card"
      ariaLabel={modal.title}
      title={modal.title}
      pending={pending}
      onClose={onClose}
    >
      <div className="modal-body confirm-modal-body">
        <div className={`confirm-modal-icon ${modal.tone === 'danger' ? 'danger' : ''}`} aria-hidden="true">
          {modal.icon === 'refresh' ? <RefreshCw /> : <Trash2 />}
        </div>
        <p className="confirm-modal-description">{modal.description}</p>
        {displayError ? (
          <p className="field-error confirm-modal-error" role="alert">
            {displayError}
          </p>
        ) : null}
      </div>
      <div className="modal-footer">
        <button
          type="button"
          className="ghost-btn"
          data-autofocus
          onClick={onClose}
          disabled={pending}
        >
          {t('shared.cancel')}
        </button>
        <button
          type="button"
          className={`btn-primary ${modal.tone === 'danger' ? 'danger' : ''}`}
          onClick={modal.onConfirm}
          disabled={pending}
        >
          {modal.confirmLabel ?? t('shared.confirm')}
        </button>
      </div>
    </Dialog>
  )
}
