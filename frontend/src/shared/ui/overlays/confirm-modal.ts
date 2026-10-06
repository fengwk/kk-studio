export interface ConfirmModalState {
  title: string
  description: string
  confirmLabel?: string
  icon?: 'delete' | 'refresh'
  tone?: 'danger'
  error?: string | null
  onConfirm: () => void
}
