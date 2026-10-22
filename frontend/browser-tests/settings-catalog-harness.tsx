import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { SettingsPage } from '@/features/settings/SettingsPage'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { systemSettingsService } from '@/shared/api/system-settings-service'
import { makeSettingsDto, makeSettingsSchema } from '@/test-support/settings-test-fixtures'
import { setLocale } from '@/shared/i18n'
import '@/styles.css'

setLocale('zh-CN')
let settings = makeSettingsDto()
systemSettingsService.get = async () => settings
systemSettingsService.getSchema = async () => makeSettingsSchema()
systemSettingsService.update = async (update) => {
  settings = { ...settings, ...update, version: '1' }
  document.body.dataset.savedSource = update.aiRuntime.modelCatalogSource
  return settings
}
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={new QueryClient()}>
    <BrowserPreferencesProvider><SettingsPage /></BrowserPreferencesProvider>
  </QueryClientProvider>,
)
