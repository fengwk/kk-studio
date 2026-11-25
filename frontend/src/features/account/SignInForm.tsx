import { useState, type FormEvent } from 'react'
import { ApiError } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { FieldError } from '@/shared/ui/controls/FieldError'
import { AccountInput } from './AccountField'
import './SignInForm.css'

export function SignInForm({ signIn, username = '' }: {
  signIn: (username: string, password: string) => Promise<void>
  username?: string
}) {
  const { t } = useI18n()
  const [pending, setPending] = useState(false)
  const [failure, setFailure] = useState<{ kind: 'credentials' } | { kind: 'other'; message: string } | null>(null)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (pending) return
    const form = event.currentTarget
    const data = new FormData(form)
    setPending(true)
    setFailure(null)
    try {
      await signIn(String(data.get('username')), String(data.get('password')))
    } catch (error) {
      const password = form.elements.namedItem('password') as HTMLInputElement
      password.value = ''
      password.focus()
      setFailure(error instanceof ApiError && error.status === 401 && error.reason === 'INVALID_CREDENTIALS'
        ? { kind: 'credentials' }
        : { kind: 'other', message: error instanceof Error ? error.message : String(error) })
    } finally {
      setPending(false)
    }
  }

  return <form className="sign-in-form" onSubmit={submit}>
    {!username && <img src={`${import.meta.env.BASE_URL}favicon.svg`} alt={t('account.brand')} width={40} height={40} />}
    <AccountInput name="username" label={t('account.username')} autoComplete="username" autoCapitalize="none" spellCheck={false} defaultValue={username} required />
    <AccountInput name="password" label={t('account.password')} type="password" autoComplete="current-password" required />
    {failure && <FieldError role="alert">{failure.kind === 'credentials' ? t('account.invalidCredentials') : failure.message}</FieldError>}
    <Button type="submit" variant="primary" loading={pending}>{t('account.signIn')}</Button>
  </form>
}
