import type { ReactNode } from 'react'

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
  return (
    <div className="modal-header">
      <h2>{title}</h2>
      <button type="button" className="ghost-btn" onClick={onClose} disabled={closeDisabled}>
        关闭
      </button>
    </div>
  )
}
