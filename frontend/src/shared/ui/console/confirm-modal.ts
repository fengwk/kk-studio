export interface ConfirmModalState {
  title: string
  description: string
  confirmLabel?: string
  icon?: 'delete' | 'refresh'
  tone?: 'danger'
  onConfirm: () => void
}
