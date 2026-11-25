import { useState, type FormEvent } from 'react'
import { ApiError, request } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
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
  const { t } = useI18n()
  const passwordLabel = t('account.changePassword')
  const logoutLabel = t('account.signOut')
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
        <p>{t('account.signOutWarning')}</p>
        {logoutError && <FieldError role="alert">{logoutError}</FieldError>}
        <div className="account-dialog-actions"><Button onClick={() => setDialog(null)} disabled={pending}>{t('shared.cancel')}</Button><Button variant="primary" loading={pending} onClick={() => void logout()}>{logoutLabel}</Button></div>
      </div>
    </Dialog>}
    {dialog !== 'logout' && logoutError && <FieldError role="alert">{logoutError}</FieldError>}
  </div>
}

function PasswordDialog({ title, onClose }: { title: string; onClose: () => void }) {
  const { t } = useI18n()
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
      setErrors({ next: t('account.passwordLength') })
      return
    }
    if (data.get('confirm') !== newPassword) {
      setErrors({ confirm: t('account.passwordMismatch') })
      return
    }
    setErrors({})
    setPending(true)
    try {
      await request('PUT /api/auth/password', { body: { currentPassword, newPassword } })
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.reason === 'WRONG_PASSWORD') {
        setErrors({ current: t('account.wrongPassword') })
        ;(form.elements.namedItem('current') as HTMLInputElement).focus()
      } else setErrors({ overall: error instanceof Error ? error.message : String(error) })
    } finally {
      setPending(false)
    }
  }

  return <Dialog title={title} pending={pending} onClose={onClose} className="account-dialog" initialFocusSelector="[name=current]">
    <form className="account-dialog-form" onSubmit={submit}>
      <AccountInput name="current" label={t('account.currentPassword')} type="password" autoComplete="current-password" required error={errors.current} />
      <AccountInput name="next" label={t('account.newPassword')} type="password" autoComplete="new-password" required error={errors.next} />
      <AccountInput name="confirm" label={t('account.confirmNewPassword')} type="password" autoComplete="new-password" required error={errors.confirm} />
      {errors.overall && <FieldError role="alert">{errors.overall}</FieldError>}
      <div className="account-dialog-actions"><Button onClick={onClose} disabled={pending}>{t('shared.cancel')}</Button><Button type="submit" variant="primary" loading={pending}>{t('settings.save')}</Button></div>
    </form>
  </Dialog>
}
