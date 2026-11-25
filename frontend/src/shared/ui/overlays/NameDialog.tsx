import { useId, useState, type FormEvent } from 'react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from './Dialog'
import './NameDialog.css'

interface NameDialogProps {
  title: string
  initialName?: string
  confirmLabel?: string
  onConfirm: (name: string) => void | Promise<void>
  onCancel: () => void
  validate?: (name: string) => string | undefined
}

export function NameDialog({ title, initialName = '', confirmLabel, onConfirm, onCancel, validate }: NameDialogProps) {
  const { t } = useI18n()
  const errorId = useId()
  const [name, setName] = useState(initialName)
  const [error, setError] = useState('')

  const [pending, setPending] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (pending) return
    const value = name.trim()
    const message = value ? validate?.(value) : t('controls.nameRequired')
    if (message) { setError(message); return }
    setPending(true)
    try { await onConfirm(value) }
    catch (failure) { setError(failure instanceof Error ? failure.message : t('shared.requestFailed')) }
    finally { setPending(false) }
  }

  return (
    <Dialog title={title} className="modal-touch-target" onClose={onCancel} pending={pending}>
      <form className="modal-body ui-name-dialog" onSubmit={event => void submit(event)}>
        <TextInput autoFocus readOnly={pending} value={name} onChange={(event) => { setName(event.target.value); setError('') }}
          aria-label={t('controls.name')} invalid={Boolean(error)} aria-describedby={error ? errorId : undefined} />
        {error && <div id={errorId} className="field-error" role="alert">{error}</div>}
        <div className="ui-name-dialog-actions">
          <Button variant="secondary" onClick={onCancel} disabled={pending}>{t('shared.cancel')}</Button>
          <Button type="submit" loading={pending}>{confirmLabel ?? t('shared.confirm')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
