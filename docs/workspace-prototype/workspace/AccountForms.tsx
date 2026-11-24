import { useId, useState, type ComponentProps, type FormEvent, type ReactNode } from 'react'
import { flushSync } from 'react-dom'
import kkLogo from '../../../frontend/public/favicon.svg?inline'
import { Button } from '@/shared/ui/controls/Button'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { getSession, passwordError, reviewUI, setSessionUser } from './session'
import './account.css'

const mismatch = '两次输入的密码不一致。'
export const usernameInput = { autoComplete: 'username', autoCapitalize: 'none', spellCheck: false } as const

export function AccountField({ name, label, error, children }: {
  name: string; label: string; error?: string
  children: (props: { id: string; 'aria-invalid': boolean; 'aria-describedby'?: string }) => ReactNode
}) {
  const id = useId()
  const errorId = error ? `${id}-error` : undefined
  return <div className="x-account-field" data-account-field={name}>
    <FieldLabel htmlFor={id}>{label}</FieldLabel>
    {children({ id, 'aria-invalid': !!error, 'aria-describedby': errorId })}
    {error && <FieldError id={errorId}>{error}</FieldError>}
  </div>
}

export function AccountInput({ name, label, error, ...props }: ComponentProps<typeof TextInput> & {
  name: string; label: string; error?: string
}) {
  return <AccountField name={name} label={label} error={error}>
    {({ 'aria-invalid': invalid, ...field }) => <TextInput {...props} name={name} invalid={invalid} {...field} />}
  </AccountField>
}

export function useAccountForm() {
  const [errors, setErrors] = useState<Record<string, string | undefined>>({})
  function submit(event: FormEvent<HTMLFormElement>, validate: (value: (name: string) => string, form: HTMLFormElement) => Record<string, string | undefined> | void) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const next = validate((name) => String(data.get(name) ?? ''), form) ?? {}
    flushSync(() => setErrors(next))
    const invalid = Object.keys(next).find((name) => next[name])
    if (!invalid) return
    form.querySelector<HTMLElement>(`[data-account-field="${invalid === 'login' ? 'password' : invalid}"] :is(input, button)`)?.focus()
  }
  return { errors, submit }
}

export function AccountActions({ onClose, label }: { onClose: () => void; label: string }) {
  return <div className="x-account-actions">
    <Button type="button" variant="ghost" onClick={onClose}>取消</Button>
    <Button type="submit" variant="primary">{label}</Button>
  </div>
}

export function AuthScreen() {
  const { errors, submit } = useAccountForm()
  return <div className="x-auth-screen">
    <form className="x-auth-form x-account-form" noValidate onSubmit={(event) => submit(event, (value, form) => {
      const username = value('username')
      const password = value('password')
      const invalid = { username: username ? '' : '请输入用户名。', password: password ? '' : '请输入密码。' }
      if (invalid.username || invalid.password) return invalid
      const user = getSession().users.find((entry) => entry.username === username)
      if (!user || !setSessionUser(user.id)) {
        const input = form.elements.namedItem('password') as HTMLInputElement
        input.value = ''
        return { login: '用户名或密码错误。' }
      }
      form.reset()
      reviewUI().go('scr-work')
    })}>
      <div className="x-auth-brand">
        <img src={kkLogo} alt="KK Studio" width={40} height={40} />
      </div>
      <AccountInput name="username" label="用户名" error={errors.username} {...usernameInput} />
      <AccountInput name="password" label="密码" error={errors.password} type="password" autoComplete="current-password" />
      {errors.login && <FieldError role="alert">{errors.login}</FieldError>}
      <Button type="submit" variant="primary">登录</Button>
    </form>
  </div>
}

export function ChangePasswordDialog({ onClose }: { onClose: () => void }) {
  const { errors, submit } = useAccountForm()
  return <Dialog title="修改密码" className="x-account-dialog modal-touch-target" initialFocusSelector="[name=current]" onClose={onClose}>
    <form className="x-account-form" noValidate onSubmit={(event) => submit(event, (value) => {
      const password = value('password')
      const confirm = value('confirm')
      const invalid = {
        current: value('current') ? '' : '请输入当前密码。',
        password: password ? passwordError(password) : '请输入新密码。',
        confirm: !confirm ? '请再次输入新密码。' : confirm !== password ? mismatch : '',
      }
      if (Object.values(invalid).some(Boolean)) return invalid
      onClose()
      reviewUI().toast('密码已修改')
    })}>
      <AccountInput name="current" label="当前密码" error={errors.current} type="password" autoComplete="current-password" />
      <AccountInput name="password" label="新密码" error={errors.password} type="password" autoComplete="new-password" />
      <AccountInput name="confirm" label="确认新密码" error={errors.confirm} type="password" autoComplete="new-password" />
      <AccountActions onClose={onClose} label="保存" />
    </form>
  </Dialog>
}
