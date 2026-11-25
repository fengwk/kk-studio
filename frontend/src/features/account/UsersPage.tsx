import { useQuery } from '@tanstack/react-query'
import { Pencil } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError, request } from '@/shared/api/client'
import type { StudioRole, StudioUserDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { Select } from '@/shared/ui/controls/Select'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { Badge } from '@/shared/ui/data/Badge'
import { AccountField, AccountInput } from './AccountField'
import { useAccount } from './account-context'
import { UserAvatar } from './UserAvatar'
import './UsersPage.css'

export function UsersPage() {
  const { t } = useI18n()
  const { user, refresh } = useAccount()
  const users = useQuery({ queryKey: ['studio-users'], queryFn: () => request('GET /api/users', {}) })
  const [editor, setEditor] = useState<{ item?: StudioUserDTO } | null>(null)
  const create = t('users.create')
  return <>
    {users.isPending && <StateBlock title={t('account.loading')} />}
    {users.isError && <StateBlock tone="danger" title={users.error.message} action={<Button onClick={() => void users.refetch()}>{t('shared.retry')}</Button>} />}
    <ResourceGrid columns="two">
      <CreateCard compact title={create} onClick={() => setEditor({})} />
      {users.data?.map((item) => <ResourceCard key={item.id} icon={<UserAvatar username={item.username} />} title={item.username}
        iconStyle="plain"
        badge={item.role === 'ADMIN' || !item.enabled ? <>
          {item.role === 'ADMIN' && <Badge>{t('users.administrator')}</Badge>}
          {!item.enabled && <Badge>{t('users.disabled')}</Badge>}
        </> : undefined}
        actions={<Button variant="secondary" size="compact" aria-label={t('users.editLabel', { username: item.username })} onClick={() => setEditor({ item })}><Pencil aria-hidden="true" />{t('users.edit')}</Button>} />)}
    </ResourceGrid>
    {editor && <UserEditor item={editor.item} create={create} onClose={() => setEditor(null)} onSaved={async (saved) => {
      setEditor(null)
      if (saved.id === user?.id) {
        await refresh()
        if (!saved.enabled || saved.role !== 'ADMIN') return
      }
      void users.refetch()
    }} />}
  </>
}

function UserEditor({ item, create, onClose, onSaved }: {
  item?: StudioUserDTO
  create: string
  onClose: () => void
  onSaved: (user: StudioUserDTO) => Promise<void>
}) {
  const { t } = useI18n()
  const [role, setRole] = useState<StudioRole>(item?.role ?? 'USER')
  const [enabled, setEnabled] = useState(item?.enabled ?? true)
  const [pending, setPending] = useState(false)
  const [confirm, setConfirm] = useState<{ username: string; password: string } | null>(null)
  const [errors, setErrors] = useState<Partial<Record<'username' | 'password' | 'role' | 'enabled' | 'overall', string>>>({})
  const cancel = t('shared.cancel')

  async function save(username: string, password: string) {
    setPending(true)
    try {
      const saved = item
        ? await request('PATCH /api/users/{id}', { path: { id: item.id }, body: { username, role, enabled, ...(password ? { password } : {}) } })
        : await request('POST /api/users', { body: { username, role, password } })
      await onSaved(saved)
    } catch (error) {
      setConfirm(null)
      if (error instanceof ApiError && error.reason === 'USERNAME_EXISTS') {
        setErrors({ username: t('users.usernameExists') })
      } else if (error instanceof ApiError && error.reason === 'LAST_ADMIN') {
        setErrors({ [item?.enabled && !enabled ? 'enabled' : 'role']: t('users.lastAdminRequired') })
      } else setErrors({ overall: error instanceof Error ? error.message : String(error) })
    } finally {
      setPending(false)
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const data = new FormData(event.currentTarget)
    const username = String(data.get('username')).trim()
    const password = String(data.get('password'))
    const next: typeof errors = {}
    if (!/^[a-z0-9][a-z0-9._-]{0,31}$/.test(username)) next.username = t('users.usernameFormat')
    if ((!item || password) && (password.length < 8 || password.length > 128)) next.password = t('account.passwordLength')
    setErrors(next)
    if (Object.keys(next).length) return
    if (item?.enabled && !enabled) setConfirm({ username, password })
    else void save(username, password)
  }

  return <>
    <Dialog title={item ? t('users.editUser') : create} pending={pending} onClose={onClose} className="user-editor" initialFocusSelector="[name=username]">
      <form className="user-editor-form" onSubmit={submit} noValidate>
        <AccountInput name="username" label={t('account.username')} defaultValue={item?.username} error={errors.username} autoComplete="off" />
        <AccountInput name="password" label={t('account.password')} type="password" autoComplete="new-password" error={errors.password} placeholder={item ? t('users.passwordUnchanged') : undefined} />
        <AccountField label={t('users.role')} error={errors.role}>{(id, errorId) => <Select id={id} value={role} aria-invalid={!!errorId} aria-describedby={errorId} onChange={(value) => setRole(value as StudioRole)} options={[{ value: 'USER', label: t('users.regularUser') }, { value: 'ADMIN', label: t('users.administrator') }]} />}</AccountField>
        {item && <AccountField label={t('users.accountStatus')} error={errors.enabled}>{(id, errorId) => <Select id={id} value={String(enabled)} aria-invalid={!!errorId} aria-describedby={errorId} onChange={(value) => setEnabled(value === 'true')} options={[{ value: 'true', label: t('users.enabled') }, { value: 'false', label: t('users.disable') }]} />}</AccountField>}
        {errors.overall && <FieldError role="alert">{errors.overall}</FieldError>}
        <div className="user-editor-actions"><Button disabled={pending} onClick={onClose}>{cancel}</Button><Button type="submit" variant="primary" loading={pending}>{t('settings.save')}</Button></div>
      </form>
    </Dialog>
    {confirm && <Dialog role="alertdialog" title={t('users.disableAccount')} pending={pending} onClose={() => setConfirm(null)} className="user-editor">
      <div className="user-editor-form">
        <p>{t('users.disableWarning')}</p>
        <div className="user-editor-actions"><Button data-autofocus disabled={pending} onClick={() => setConfirm(null)}>{cancel}</Button><Button danger variant="primary" loading={pending} onClick={() => void save(confirm.username, confirm.password)}>{t('users.confirmDisable')}</Button></div>
      </div>
    </Dialog>}
  </>
}
