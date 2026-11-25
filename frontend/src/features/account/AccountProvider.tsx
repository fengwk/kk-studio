import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState, type PropsWithChildren } from 'react'
import { ApiError, request } from '@/shared/api/client'
import { sessionExpiry } from '@/shared/api/session-expiry'
import type { StudioAccountDTO } from '@/shared/api/generated/contracts'
import { ApplicationEventProvider } from '@/shared/app-events'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { AccountContext } from './account-context'
import { SignInForm } from './SignInForm'
import './AccountProvider.css'

type Session = { kind: 'loading' | 'signed-out' } | { kind: 'ready' | 'expired'; user: StudioAccountDTO } | { kind: 'error'; message: string }

function notAuthenticated(error: unknown) {
  return error instanceof ApiError && error.status === 401 && error.reason === 'NOT_AUTHENTICATED'
}

function readAccount(signal?: AbortSignal) {
  return request('GET /api/auth/me', {}, { signal })
}

export function AccountProvider({ children }: PropsWithChildren) {
  const cache = useQueryClient()
  const [session, setSession] = useState<Session>({ kind: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const { t } = useI18n()
  const user = 'user' in session ? session.user : null
  const expired = session.kind === 'expired'

  useEffect(() => {
    function expire() {
      setSession((current) => current.kind === 'ready' ? { kind: 'expired', user: current.user } : current)
    }
    sessionExpiry.addEventListener('expired', expire)
    return () => sessionExpiry.removeEventListener('expired', expire)
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void readAccount(controller.signal).then((account) => {
      if (!controller.signal.aborted) setSession({ kind: 'ready', user: account })
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return
      setSession(notAuthenticated(error) ? { kind: 'signed-out' } : {
        kind: 'error', message: error instanceof Error ? error.message : String(error),
      })
    })
    return () => controller.abort()
  }, [attempt])

  function clearSession() {
    cache.clear()
    setSession({ kind: 'signed-out' })
  }

  async function refresh() {
    try {
      const account = await readAccount()
      if (user && user.id !== account.id) {
        window.location.reload()
        return
      }
      setSession({ kind: 'ready', user: account })
    } catch (error) {
      if (!notAuthenticated(error)) throw error
      clearSession()
    }
  }

  async function signIn(username: string, password: string) {
    await request('POST /api/auth/login', { body: { username, password } })
    const account = await readAccount()
    if (user && user.id !== account.id) {
      window.location.reload()
      return
    }
    setSession({ kind: 'ready', user: account })
    if (expired) void cache.invalidateQueries()
  }

  async function signOut() {
    await request('POST /api/auth/logout', {})
    clearSession()
  }

  if (!user) return <main className="account-gate">
    {session.kind === 'signed-out' && <SignInForm signIn={signIn} />}
    {session.kind === 'loading' && <span role="status">{t('account.loading')}</span>}
    {session.kind === 'error' && <div role="alert">
      <p>{session.message}</p>
      <Button onClick={() => { setSession({ kind: 'loading' }); setAttempt(attempt + 1) }}>{t('shared.retry')}</Button>
    </div>}
  </main>

  return <AccountContext.Provider value={{ user, refresh, signOut }}>
    <ApplicationEventProvider enabled={!expired}>
      <div className="account-content" inert={expired}>{children}</div>
      {expired && <Dialog pending onClose={() => undefined} ariaLabel={t('account.sessionExpired')} initialFocusSelector="[name=password]" className="account-relogin">
        <SignInForm username={user.username} signIn={signIn} />
      </Dialog>}
    </ApplicationEventProvider>
  </AccountContext.Provider>
}
