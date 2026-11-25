import { useState, type FormEvent } from 'react'
import { getLocale } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { AccountInput } from './AccountField'
import './SignInForm.css'

export function SignInForm({ signIn, username = '' }: {
  signIn: (username: string, password: string) => Promise<void>
  username?: string
}) {
  const zh = getLocale() === 'zh-CN'
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const form = event.currentTarget
    const data = new FormData(form)
    setPending(true)
    setFailed(false)
    try {
      await signIn(String(data.get('username')), String(data.get('password')))
    } catch {
      const password = form.elements.namedItem('password') as HTMLInputElement
      password.value = ''
      password.focus()
      setFailed(true)
    } finally {
      setPending(false)
    }
  }

  return <form className="sign-in-form" onSubmit={submit}>
    {!username && <img src={`${import.meta.env.BASE_URL}favicon.svg`} alt="KK Studio" width={40} height={40} />}
    <AccountInput name="username" label={zh ? '用户名' : 'Username'} autoComplete="username" autoCapitalize="none" spellCheck={false} defaultValue={username} required />
    <AccountInput name="password" label={zh ? '密码' : 'Password'} type="password" autoComplete="current-password" required />
    {failed && <FieldError role="alert">{zh ? '用户名或密码错误。' : 'Incorrect username or password.'}</FieldError>}
    <Button type="submit" variant="primary" loading={pending}>{zh ? '登录' : 'Sign in'}</Button>
  </form>
}
