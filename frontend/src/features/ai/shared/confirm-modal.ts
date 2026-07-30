export interface ConfirmModalState {
  title: string
  description: string
  confirmLabel?: string
  tone?: 'danger'
  onConfirm: () => void
}
