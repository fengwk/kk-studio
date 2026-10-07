import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { translate } from '@/shared/i18n'
import type { ConflictPresentation } from '@/shared/conflict/conflict-presenter'

/**
 * Durable acceptance/control/settings conflicts share this presenter. Callers retain their own
 * refresh and optional exact-retry callbacks, while reason and detail are never discarded.
 * 弹层复用共享 Dialog 的 portal/焦点陷阱/关闭后焦点归还/顶层 Escape，不再是裸 alertdialog。
 */
export function ConflictPresenter({
  conflict,
  onRefresh,
  onRetry,
  onClose,
}: {
  conflict: ConflictPresentation | null
  onRefresh: () => void
  onRetry?: () => void
  onClose: () => void
}) {
  if (conflict == null) {
    return null
  }
  return (
    <Dialog
      role="alertdialog"
      title={translate('shared.conflict.title')}
      onClose={onClose}
    >
      <div className="modal-body">
        <p className="modal-body-text">
          {translate('shared.conflict.reason', { reason: conflict.reason })}
        </p>
        <p className="modal-body-text">{conflict.detail}</p>
      </div>
      <div className="modal-footer">
        <Button variant="ghost" onClick={onClose}>
          {translate('shared.cancel')}
        </Button>
        <Button variant="ghost" onClick={onRefresh}>
          {translate('shared.conflict.refresh')}
        </Button>
        {onRetry ? <Button onClick={onRetry}>{translate('shared.conflict.retry')}</Button> : null}
      </div>
    </Dialog>
  )
}
