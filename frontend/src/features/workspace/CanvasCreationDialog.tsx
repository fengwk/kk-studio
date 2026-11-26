import { useId, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import '@/shared/ui/overlays/NameDialog.css'
import { freezeTreeCreation, isCreateRejected, type TreeCreation } from './workspace-action-retry'

export function CanvasCreationDialog({ folderId, treeVersion, onCreate, onCancel }: {
  folderId: string
  treeVersion: string
  onCreate: (request: TreeCreation<string>) => Promise<void>
  onCancel: () => void
}) {
  const { t } = useI18n()
  const errorId = useId()
  const [name, setName] = useState('')
  const [error, setError] = useState('')
  const [pending, setPending] = useState(false)
  const [frozen, setFrozen] = useState<TreeCreation<string>>()

  async function submit() {
    if (pending) return
    if (!name.trim()) { setError(t('controls.nameRequired')); return }
    const request = frozen ?? freezeTreeCreation(name.trim(), folderId, treeVersion)
    setPending(true)
    try {
      await onCreate(request)
    } catch (failure) {
      const rejected = isCreateRejected(failure)
      setFrozen(rejected ? undefined : request)
      setError(rejected && failure instanceof Error ? failure.message : t('workspace.createOutcomeUnknown'))
    } finally {
      setPending(false)
    }
  }

  return <Dialog title={t('workspace.newCanvas')} className="modal-touch-target" onClose={onCancel} pending={pending || Boolean(frozen)}>
    <form className="modal-body ui-name-dialog" onSubmit={event => { event.preventDefault(); void submit() }}>
      <TextInput autoFocus value={name} readOnly={pending || Boolean(frozen)}
        onChange={event => { setName(event.target.value); setError('') }}
        aria-label={t('controls.name')} invalid={Boolean(error)} aria-describedby={error ? errorId : undefined} />
      {error && <div id={errorId} className="field-error" role="alert">{error}</div>}
      <div className="ui-name-dialog-actions">
        <Button variant="secondary" onClick={onCancel} disabled={pending}>{t('shared.cancel')}</Button>
        <Button type="submit" loading={pending}>{t(frozen ? 'workspace.retryCreation' : 'shared.confirm')}</Button>
      </div>
    </form>
  </Dialog>
}
