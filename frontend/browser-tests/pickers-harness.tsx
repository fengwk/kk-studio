import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { AgentForm } from '@/features/catalog/AiAgentResourceForm'
import { ModelGroupForm } from '@/features/catalog/AiModelGroupResourceForm'
import { emptyAgentDraft } from '@/features/catalog/ai-resource-draft-codecs'
import { emptyModelGroupDraft } from '@/features/catalog/ai-model-group-draft-codec'
import { SettingsModelSelectionField } from '@/features/settings/SettingsModelSelectionField'
import { setLocale } from '@/shared/i18n'
import { FIXTURE_MODELS } from './model-browser-fixtures'
import type { SkillPackageSummaryDTO, ToolCatalogEntryDTO } from '@/shared/api/generated/contracts'

setLocale('en-US')

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
const tools: ToolCatalogEntryDTO[] = [
  { name: 'read', description: 'Read workspace files', provider: { kind: 'BUILTIN', id: 'builtin', name: 'Built-in' } },
  { name: 'web_search', description: 'Search the web', provider: { kind: 'MCP', id: 'search', name: 'Search MCP' } },
]
const skills: SkillPackageSummaryDTO[] = [
  {
    packageName: 'engineering',
    description: null,
    packageVersion: 'fixture',
    publishedAt: 0,
    skills: [{ name: 'review', description: 'Review code changes' }],
    source: 'GIT',
    updateAvailable: false,
    version: '1',
    createdAt: 0,
    updatedAt: 0,
  },
]

export function PickerHarness() {
  const [agent, setAgent] = useState({ ...emptyAgentDraft(), name: 'fixture-agent', model: 'main' })
  const [group, setGroup] = useState(emptyModelGroupDraft())
  const [settingsModel, setSettingsModel] = useState({ modelName: 'main', variant: '' })

  return (
    <main style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 360px), 1fr))', gap: 24, padding: 24 }}>
      <section className="settings-card" aria-label="Agent configuration fixture">
        <h2>Agent configuration</h2>
        <AgentForm draft={agent} models={FIXTURE_MODELS} toolCatalog={tools} skills={skills} onChange={setAgent} />
      </section>
      <section className="settings-card" aria-label="Compression model setting fixture">
        <h2>System settings · Compression model</h2>
        <SettingsModelSelectionField
          fieldPath="aiRuntime.compactionModel"
          nullable
          label="Compression model"
          unsetLabel="Not set"
          value={settingsModel}
          onChange={setSettingsModel}
        />
      </section>
      <section className="settings-card" aria-label="Model group edit fixture">
        <h2>Model group edit</h2>
        <ModelGroupForm draft={group} models={FIXTURE_MODELS} onChange={setGroup} />
      </section>
    </main>
  )
}

const root = document.getElementById('root')
if (root) {
  createRoot(root).render(
    <QueryClientProvider client={queryClient}>
      <PickerHarness />
    </QueryClientProvider>,
  )
}
