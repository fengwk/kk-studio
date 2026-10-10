import { useMemo, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ProviderForm } from '@/features/ai/catalog/AiProviderResourceForm'
import type { ProviderDraft } from '@/features/ai/catalog/ai-console-types'
import { SystemSettingsSchemaRenderer } from '@/features/settings/SystemSettingsSchemaRenderer'
import {
  makeSettingsDto,
  makeSettingsSchema,
} from '@/test-support/settings-test-fixtures'
import {
  settingsSectionsToDraft,
  type SystemSettingsSectionsDraft,
} from '@/features/settings/system-settings-draft'

setLocale('zh-CN')

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false },
  },
})

export function RetryControlsHarness() {
  const [providerDraft, setProviderDraft] = useState<ProviderDraft>({
    name: 'openai',
    providerType: 'openai',
    baseUrl: 'https://api.openai.com/v1',
    description: 'OpenAI official endpoints',
    credential: '',
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
    modelHttpRetryStatusCodes: [408, 429],
  })

  const schema = useMemo(() => {
    const full = makeSettingsSchema()
    const aiRuntimeSection = full.sections.find((s) => s.key === 'aiRuntime')!
    return { sections: [aiRuntimeSection] }
  }, [])

  const [settingsDraft, setSettingsDraft] = useState<SystemSettingsSectionsDraft>(() =>
    settingsSectionsToDraft(makeSettingsDto()),
  )

  return (
    <div className="harness-container">
      <section className="harness-card" aria-labelledby="provider-form-heading">
        <h2 id="provider-form-heading">Provider Form Retry Controls</h2>
        <div data-testid="provider-form-wrapper">
          <ProviderForm
            draft={providerDraft}
            mode="edit"
            onChange={setProviderDraft}
          />
        </div>
      </section>

      <section className="harness-card" aria-labelledby="system-settings-heading">
        <h2 id="system-settings-heading">System Settings Retry Controls</h2>
        <div data-testid="system-settings-wrapper">
          <SystemSettingsSchemaRenderer
            schema={schema}
            draft={settingsDraft}
            onChange={setSettingsDraft}
          />
        </div>
      </section>
    </div>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <QueryClientProvider client={queryClient}>
      <RetryControlsHarness />
    </QueryClientProvider>,
  )
}
