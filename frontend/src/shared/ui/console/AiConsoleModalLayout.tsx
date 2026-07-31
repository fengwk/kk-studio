import type { ReactNode } from 'react'
import { useI18n } from '@/shared/i18n'

export function ModalBackdrop({ onClose, children }: { onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      {children}
    </div>
  )
}

export function ModalHeader({
  title,
  onClose,
  closeDisabled = false,
}: {
  title: string
  onClose: () => void
  closeDisabled?: boolean
}) {
  const { t } = useI18n()

  return (
    <div className="modal-header">
      <h2>{title}</h2>
      <button type="button" className="ghost-btn" onClick={onClose} disabled={closeDisabled}>
        {t('shared.close')}
      </button>
    </div>
  )
}
