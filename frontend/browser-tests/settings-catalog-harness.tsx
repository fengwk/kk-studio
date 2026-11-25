import { createRoot } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SettingsRoute } from '@/app/SettingsRoute'
import { AccountContext } from '@/features/account/account-context'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { systemSettingsService } from '@/features/settings/system-settings-service'
import { makeSettingsDto, makeSettingsSchema } from '@/test-support/settings-test-fixtures'
import { setLocale } from '@/shared/i18n'
import '@/styles.css'

setLocale(
  new URLSearchParams(window.location.search).get('locale') === 'en-US' ? 'en-US' : 'zh-CN',
)
let settings = makeSettingsDto()
systemSettingsService.get = async () => settings
systemSettingsService.getSchema = async () => makeSettingsSchema()
systemSettingsService.update = async (update) => {
  settings = { ...settings, ...update, version: '1' }
  document.body.dataset.savedStrategy = update.aiRuntime.retryBackoffStrategy
  return settings
}
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={new QueryClient()}>
    <AccountContext.Provider value={{ user: { id: 'admin', username: 'admin', role: 'ADMIN', profile: {} }, refresh: async () => {}, signOut: async () => {} }}>
      <BrowserPreferencesProvider>
        <MemoryRouter initialEntries={['/settings/preferences']}><Routes><Route path="/settings/:section" element={<SettingsRoute />} /></Routes></MemoryRouter>
      </BrowserPreferencesProvider>
    </AccountContext.Provider>
  </QueryClientProvider>,
)
