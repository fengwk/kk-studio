import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { HashRouter, Link, Route, Routes, useLocation } from 'react-router'
import { AccountProvider } from '@/features/account/AccountProvider'
import { AccountMenu } from '@/features/account/AccountMenu'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { SettingsRoute } from '@/app/SettingsRoute'
import { request } from '@/shared/api/client'
import '@/styles.css'

export function DraftPage() {
  const { pathname, search, hash } = useLocation()
  return <div style={{ paddingBlockStart: 'var(--space-8)', marginBlockStart: 'var(--space-8)' }}>
    <AccountMenu />
    <Link to="/settings/preferences" state={{ from: pathname + search + hash }}>设置</Link>
    <textarea aria-label="草稿" />
    <button type="button" onClick={() => {
      void request('PUT /api/auth/password', { body: { currentPassword: 'test', newPassword: 'testtest' } }).catch(() => undefined)
    }}>测试写入</button>
  </div>
}

const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}>
    <BrowserPreferencesProvider>
      <AccountProvider>
        <HashRouter>
          <Routes>
            <Route path="/settings/:section" element={<SettingsRoute />} />
            <Route path="/" element={<DraftPage />} />
          </Routes>
        </HashRouter>
      </AccountProvider>
    </BrowserPreferencesProvider>
  </QueryClientProvider>,
)
