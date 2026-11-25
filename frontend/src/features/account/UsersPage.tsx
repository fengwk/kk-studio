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
  const { locale } = useI18n()
  const zh = locale === 'zh-CN'
  const { user, refresh } = useAccount()
  const users = useQuery({ queryKey: ['studio-users'], queryFn: () => request('GET /api/users', {}) })
  const [editor, setEditor] = useState<{ item?: StudioUserDTO } | null>(null)
  const create = zh ? '新建用户' : 'New user'
  return <>
    {users.isPending && <StateBlock title={zh ? '加载中…' : 'Loading…'} />}
    {users.isError && <StateBlock tone="danger" title={users.error.message} action={<Button onClick={() => void users.refetch()}>{zh ? '重试' : 'Retry'}</Button>} />}
    <ResourceGrid columns="two">
      <CreateCard compact title={create} onClick={() => setEditor({})} />
      {users.data?.map((item) => <ResourceCard key={item.id} icon={<UserAvatar username={item.username} />} title={item.username}
        iconStyle="plain"
        badge={item.role === 'ADMIN' || !item.enabled ? <>
          {item.role === 'ADMIN' && <Badge>{zh ? '管理员' : 'Administrator'}</Badge>}
          {!item.enabled && <Badge>{zh ? '已停用' : 'Disabled'}</Badge>}
        </> : undefined}
        actions={<Button variant="secondary" size="compact" aria-label={`${zh ? '编辑' : 'Edit'} ${item.username}`} onClick={() => setEditor({ item })}><Pencil aria-hidden="true" />{zh ? '编辑' : 'Edit'}</Button>} />)}
    </ResourceGrid>
    {editor && <UserEditor item={editor.item} create={create} zh={zh} onClose={() => setEditor(null)} onSaved={async (saved) => {
      setEditor(null)
      if (saved.id === user?.id) {
        await refresh()
        if (!saved.enabled || saved.role !== 'ADMIN') return
      }
      void users.refetch()
    }} />}
  </>
}

function UserEditor({ item, zh, create, onClose, onSaved }: {
  item?: StudioUserDTO
  zh: boolean
  create: string
  onClose: () => void
  onSaved: (user: StudioUserDTO) => Promise<void>
}) {
  const [role, setRole] = useState<StudioRole>(item?.role ?? 'USER')
  const [enabled, setEnabled] = useState(item?.enabled ?? true)
  const [pending, setPending] = useState(false)
  const [confirm, setConfirm] = useState<{ username: string; password: string } | null>(null)
  const [errors, setErrors] = useState<Partial<Record<'username' | 'password' | 'role' | 'enabled' | 'overall', string>>>({})
  const cancel = zh ? '取消' : 'Cancel'

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
        setErrors({ username: zh ? '该用户名已存在。' : 'This username is already in use.' })
      } else if (error instanceof ApiError && error.reason === 'LAST_ADMIN') {
        setErrors({ [item?.enabled && !enabled ? 'enabled' : 'role']: zh ? '至少保留一名启用的管理员。' : 'At least one enabled administrator is required.' })
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
    if (!/^[a-z0-9][a-z0-9._-]{0,31}$/.test(username)) next.username = zh ? '输入 1–32 个小写字母、数字、点、下划线或连字符，以字母或数字开头。' : 'Use 1–32 lowercase letters, digits, dots, underscores or hyphens, starting with a letter or digit.'
    if ((!item || password) && (password.length < 8 || password.length > 128)) next.password = zh ? '密码长度须为 8–128 个字符。' : 'Use 8–128 characters.'
    setErrors(next)
    if (Object.keys(next).length) return
    if (item?.enabled && !enabled) setConfirm({ username, password })
    else void save(username, password)
  }

  return <>
    <Dialog title={item ? (zh ? '编辑用户' : 'Edit user') : create} pending={pending} onClose={onClose} className="user-editor" initialFocusSelector="[name=username]">
      <form className="user-editor-form" onSubmit={submit} noValidate>
        <AccountInput name="username" label={zh ? '用户名' : 'Username'} defaultValue={item?.username} error={errors.username} autoComplete="off" />
        <AccountInput name="password" label={zh ? '密码' : 'Password'} type="password" autoComplete="new-password" error={errors.password} placeholder={item ? (zh ? '留空不变' : 'Leave blank to keep unchanged') : undefined} />
        <AccountField label={zh ? '角色' : 'Role'} error={errors.role}>{(id, errorId) => <Select id={id} value={role} aria-invalid={!!errorId} aria-describedby={errorId} onChange={(value) => setRole(value as StudioRole)} options={[{ value: 'USER', label: zh ? '普通用户' : 'User' }, { value: 'ADMIN', label: zh ? '管理员' : 'Administrator' }]} />}</AccountField>
        {item && <AccountField label={zh ? '账号状态' : 'Account status'} error={errors.enabled}>{(id, errorId) => <Select id={id} value={String(enabled)} aria-invalid={!!errorId} aria-describedby={errorId} onChange={(value) => setEnabled(value === 'true')} options={[{ value: 'true', label: zh ? '启用' : 'Enabled' }, { value: 'false', label: zh ? '停用' : 'Disabled' }]} />}</AccountField>}
        {errors.overall && <FieldError role="alert">{errors.overall}</FieldError>}
        <div className="user-editor-actions"><Button disabled={pending} onClick={onClose}>{cancel}</Button><Button type="submit" variant="primary" loading={pending}>{zh ? '保存' : 'Save'}</Button></div>
      </form>
    </Dialog>
    {confirm && <Dialog role="alertdialog" title={zh ? '停用账号' : 'Disable account'} pending={pending} onClose={() => setConfirm(null)} className="user-editor">
      <div className="user-editor-form">
        <p>{zh ? '停用会撤销登录并暂停定时任务。' : 'Disabling revokes sign-ins and pauses scheduled tasks.'}</p>
        <div className="user-editor-actions"><Button data-autofocus disabled={pending} onClick={() => setConfirm(null)}>{cancel}</Button><Button danger variant="primary" loading={pending} onClick={() => void save(confirm.username, confirm.password)}>{zh ? '确认停用' : 'Disable'}</Button></div>
      </div>
    </Dialog>}
  </>
}
