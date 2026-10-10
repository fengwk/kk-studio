import { RefreshCw, Trash2 } from 'lucide-react'
import { Button } from '@/shared/ui/controls/Button'
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
        <Button
          variant="ghost"
          data-autofocus
          onClick={onClose}
          disabled={pending}
        >
          {t('shared.cancel')}
        </Button>
        <Button
          variant="primary"
          danger={modal.tone === 'danger'}
          onClick={modal.onConfirm}
          loading={pending}
        >
          {modal.confirmLabel ?? t('shared.confirm')}
        </Button>
      </div>
    </Dialog>
  )
}
