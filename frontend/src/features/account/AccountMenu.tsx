import { useState, type FormEvent } from 'react'
import { ApiError, request } from '@/shared/api/client'
import { getLocale } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { Menu } from '@/shared/ui/overlays/Menu'
import { useAccount } from './account-context'
import { AccountInput } from './AccountField'
import { UserAvatar } from './UserAvatar'
import './AccountMenu.css'

export function AccountMenu({ hasUnsentContent = false }: { hasUnsentContent?: boolean }) {
  const { user, signOut } = useAccount()
  const [trigger, setTrigger] = useState<HTMLButtonElement | null>(null)
  const [open, setOpen] = useState(false)
  const [dialog, setDialog] = useState<'password' | 'logout' | null>(null)
  const [logoutError, setLogoutError] = useState('')
  const [pending, setPending] = useState(false)
  const zh = getLocale() === 'zh-CN'
  const passwordLabel = zh ? '修改密码' : 'Change password'
  const logoutLabel = zh ? '退出登录' : 'Sign out'
  if (!user) return null

  async function logout() {
    setPending(true)
    setLogoutError('')
    try { await signOut() }
    catch (error) { setLogoutError(error instanceof Error ? error.message : String(error)) }
    finally { setPending(false) }
  }

  return <div className="account-menu" onBlur={(event) => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false)
  }}>
    <button ref={setTrigger} className="account-menu-trigger" type="button" aria-label={user.username} aria-haspopup="menu" aria-expanded={open} disabled={pending} onClick={() => setOpen(!open)}>
      <UserAvatar username={user.username} />
    </button>
    {open && <Menu placement="above-start" returnFocusTo={trigger} onClose={() => setOpen(false)} items={[
      { label: passwordLabel, onSelect: () => setDialog('password') },
      { label: logoutLabel, onSelect: () => { if (hasUnsentContent) setDialog('logout'); else void logout() } },
    ]} />}
    {dialog === 'password' && <PasswordDialog title={passwordLabel} onClose={() => setDialog(null)} />}
    {dialog === 'logout' && <Dialog title={logoutLabel} pending={pending} onClose={() => setDialog(null)} className="account-dialog">
      <div className="account-dialog-form">
        <p>{zh ? '尚有未发送的内容。退出后将丢失，确定退出？' : 'Unsent content will be lost. Sign out?'}</p>
        {logoutError && <FieldError role="alert">{logoutError}</FieldError>}
        <div className="account-dialog-actions"><Button onClick={() => setDialog(null)} disabled={pending}>{zh ? '取消' : 'Cancel'}</Button><Button variant="primary" loading={pending} onClick={() => void logout()}>{logoutLabel}</Button></div>
      </div>
    </Dialog>}
    {dialog !== 'logout' && logoutError && <FieldError role="alert">{logoutError}</FieldError>}
  </div>
}

function PasswordDialog({ title, onClose }: { title: string; onClose: () => void }) {
  const zh = getLocale() === 'zh-CN'
  const [pending, setPending] = useState(false)
  const [errors, setErrors] = useState<{ current?: string; next?: string; confirm?: string; overall?: string }>({})

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const form = event.currentTarget
    const data = new FormData(form)
    const currentPassword = String(data.get('current'))
    const newPassword = String(data.get('next'))
    if (newPassword.length < 8 || newPassword.length > 128) {
      setErrors({ next: zh ? '密码长度须为 8–128 个字符。' : 'Use 8–128 characters.' })
      return
    }
    if (data.get('confirm') !== newPassword) {
      setErrors({ confirm: zh ? '两次输入的密码不一致。' : 'The passwords do not match.' })
      return
    }
    setErrors({})
    setPending(true)
    try {
      await request('PUT /api/auth/password', { body: { currentPassword, newPassword } })
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.reason === 'WRONG_PASSWORD') {
        setErrors({ current: zh ? '当前密码错误。' : 'Incorrect current password.' })
        ;(form.elements.namedItem('current') as HTMLInputElement).focus()
      } else setErrors({ overall: error instanceof Error ? error.message : String(error) })
    } finally {
      setPending(false)
    }
  }

  return <Dialog title={title} pending={pending} onClose={onClose} className="account-dialog" initialFocusSelector="[name=current]">
    <form className="account-dialog-form" onSubmit={submit}>
      <AccountInput name="current" label={zh ? '当前密码' : 'Current password'} type="password" autoComplete="current-password" required error={errors.current} />
      <AccountInput name="next" label={zh ? '新密码' : 'New password'} type="password" autoComplete="new-password" required error={errors.next} />
      <AccountInput name="confirm" label={zh ? '确认新密码' : 'Confirm new password'} type="password" autoComplete="new-password" required error={errors.confirm} />
      {errors.overall && <FieldError role="alert">{errors.overall}</FieldError>}
      <div className="account-dialog-actions"><Button onClick={onClose} disabled={pending}>{zh ? '取消' : 'Cancel'}</Button><Button type="submit" variant="primary" loading={pending}>{zh ? '保存' : 'Save'}</Button></div>
    </form>
  </Dialog>
}
