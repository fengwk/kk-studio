import type { ReactNode } from 'react'

export function ModalBackdrop({ onClose, children }: { onClose: () => void; children: ReactNode }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={onClose}>
      {children}
    </div>
  )
}

export function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div className="modal-header">
      <h2>{title}</h2>
      <button type="button" className="ghost-btn" onClick={onClose}>
        关闭
      </button>
    </div>
  )
}
