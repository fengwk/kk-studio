import { useState } from 'react'
import { Select } from '@/shared/ui/controls/Select'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { ResourceCard } from '@/shared/ui/cards/ResourceCard'
import { ResourceGrid } from '@/shared/ui/cards/ResourceGrid'
import { Badge } from '@/shared/ui/data/Badge'
import { CreateCard } from '@/shared/ui/feedback/CreateCard'
import { AccountActions, AccountField, AccountInput, useAccountForm, usernameInput } from '../workspace/AccountForms'
import { createUser, enabledAdminRequired, lastAdminField, passwordError, updateUser, usernameError, type Role, type User } from '../workspace/session'
import { UserAvatar } from '../workspace/UserAvatar'
import { EditButton } from './EditButton'

const roles = [{ value: 'USER', label: '普通用户' }, { value: 'ADMIN', label: '管理员' }]
const statuses = [{ value: 'enabled', label: '启用' }, { value: 'disabled', label: '禁用' }]

function UserEditor({ user, onClose }: { user: User | null; onClose: () => void }) {
  const { errors, submit } = useAccountForm()
  const [role, setRole] = useState<Role>(user?.role ?? 'USER')
  const [disabled, setDisabled] = useState(user?.disabled ?? false)
  const [pendingDisable, setPendingDisable] = useState<User | null>(null)
  const [confirmError, setConfirmError] = useState('')
  const passwordField = <AccountInput name="password" label="密码" error={errors.password} type="password"
    autoComplete="new-password" placeholder={user ? '保持不变' : undefined} />
  const editor = <Dialog title={user ? '编辑用户' : '新建用户'} className="x-account-dialog modal-touch-target"
    initialFocusSelector="[name=username]" onClose={onClose}>
    <form className="x-account-form" noValidate onSubmit={(event) => submit(event, (value) => {
      const username = value('username')
      const password = value('password')
      const invalid = {
        username: usernameError(username, user?.id),
        password: user && !password ? '' : passwordError(password),
      }
      if (invalid.username || invalid.password) return invalid
      if (user) {
        const nextUser = { ...user, username, role, disabled }
        if (disabled && !user.disabled) {
          const field = lastAdminField(nextUser)
          if (field) return { [field]: enabledAdminRequired }
          setConfirmError('')
          setPendingDisable(nextUser)
          return
        }
        const field = updateUser(nextUser)
        if (field) return { [field]: enabledAdminRequired }
      } else createUser(username, role)
      onClose()
    })}>
      <AccountInput name="username" label="用户名" defaultValue={user?.username} error={errors.username} {...usernameInput} />
      {!user && passwordField}
      <AccountField name="role" label="角色" error={errors.role}>
        {(field) => <Select {...field} value={role} options={roles} onChange={(value) => setRole(value as Role)} />}
      </AccountField>
      {user && <AccountField name="status" label="账号状态" error={errors.status}>
        {(field) => <Select {...field} value={disabled ? 'disabled' : 'enabled'} options={statuses} onChange={(value) => setDisabled(value === 'disabled')} />}
      </AccountField>}
      {user && passwordField}
      <AccountActions onClose={onClose} label={user ? '保存' : '创建'} />
    </form>
  </Dialog>
  return <>
    {editor}
    {pendingDisable && <ConfirmActionModal className="x-account-disable-confirm modal-touch-target" pending={false}
      onClose={() => setPendingDisable(null)} error={confirmError} modal={{
        title: '禁用账号',
        description: '禁用后，现有登录将失效，定时任务将暂停。已受理的运行、空间和历史记录会保留。',
        confirmLabel: '禁用', tone: 'danger',
        onConfirm: () => {
          if (updateUser(pendingDisable)) setConfirmError(enabledAdminRequired)
          else onClose()
        },
      }} />}
  </>
}

export function UsersPreview({ users }: { users: readonly User[] }) {
  const [editor, setEditor] = useState<User | null>()
  return <div className="screen-body">
    <ResourceGrid className="x-users-grid">
      <CreateCard title="新建用户" subtitle="" onClick={() => setEditor(null)} />
      {users.map((user) => <ResourceCard key={user.id} className="x-user-card" icon={<UserAvatar username={user.username} />}
        title={user.username} badge={user.role === 'ADMIN' || user.disabled ? <>
          {user.role === 'ADMIN' && <Badge tone="neutral">管理员</Badge>}
          {user.disabled && <Badge tone="neutral">已禁用</Badge>}
        </> : undefined}
        actions={<EditButton name={user.username} onClick={() => setEditor(user)} />} />)}
    </ResourceGrid>
    {editor !== undefined && <UserEditor user={editor} onClose={() => setEditor(undefined)} />}
  </div>
}
