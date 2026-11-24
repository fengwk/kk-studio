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
  onConfirm: (name: string) => void
  onCancel: () => void
  validate?: (name: string) => string | undefined
}

export function NameDialog({ title, initialName = '', confirmLabel, onConfirm, onCancel, validate }: NameDialogProps) {
  const { t } = useI18n()
  const errorId = useId()
  const [name, setName] = useState(initialName)
  const [error, setError] = useState('')

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = name.trim()
    const message = value ? validate?.(value) : t('controls.nameRequired')
    if (message) setError(message)
    else onConfirm(value)
  }

  return (
    <Dialog title={title} className="modal-touch-target" onClose={onCancel}>
      <form className="modal-body ui-name-dialog" onSubmit={submit}>
        <TextInput autoFocus value={name} onChange={(event) => { setName(event.target.value); setError('') }}
          aria-label={t('controls.name')} invalid={Boolean(error)} aria-describedby={error ? errorId : undefined} />
        {error && <div id={errorId} className="field-error" role="alert">{error}</div>}
        <div className="ui-name-dialog-actions">
          <Button variant="secondary" onClick={onCancel}>{t('shared.cancel')}</Button>
          <Button type="submit">{confirmLabel ?? t('shared.confirm')}</Button>
        </div>
      </form>
    </Dialog>
  )
}
