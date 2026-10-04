import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { useI18n, setLocale } from '@/shared/i18n'
import { configSyncService } from '@/shared/api/config-sync-service'
import type { ConfigSyncItem } from '@/shared/api/contracts/config-sync'
import { SyncTab } from '@/features/settings/sync/SyncTab'

declare global {
  interface Window {
    __syncExportRequest?: unknown
    __syncImportYaml?: string
  }
}

setLocale('zh-CN')

// 固定库存：覆盖七类、依赖链与互相引用的 Agent，布局回归不触达真实后端。
const INVENTORY: ConfigSyncItem[] = [
  {
    kind: 'agents',
    name: 'reviewer',
    dependencies: [
      { kind: 'models', name: 'openai/gpt-4o' },
      { kind: 'providers', name: 'openai' },
      { kind: 'skillPackages', name: 'core-tools' },
      { kind: 'mcpServers', name: 'fetch' },
    ],
  },
  {
    kind: 'agents',
    name: 'planner',
    dependencies: [
      { kind: 'agents', name: 'reviewer' },
      { kind: 'models', name: 'anthropic/claude' },
    ],
  },
  { kind: 'models', name: 'openai/gpt-4o', dependencies: [{ kind: 'providers', name: 'openai' }] },
  { kind: 'models', name: 'anthropic/claude', dependencies: [{ kind: 'providers', name: 'anthropic' }] },
  { kind: 'providers', name: 'openai', dependencies: [] },
  { kind: 'providers', name: 'anthropic', dependencies: [] },
  { kind: 'skillPackages', name: 'core-tools', dependencies: [] },
  { kind: 'environments', name: 'dev-env', dependencies: [] },
  { kind: 'mcpServers', name: 'fetch', dependencies: [] },
  { kind: 'settings', name: 'settings', dependencies: [] },
]

configSyncService.getInventory = async () => ({ items: INVENTORY })
configSyncService.exportConfig = async (items) => {
  window.__syncExportRequest = items
  return { yaml: 'providers: []\n' }
}
configSyncService.importConfig = async (yaml) => {
  window.__syncImportYaml = yaml
  return {
    imported: [
      { kind: 'providers', name: 'openai' },
      { kind: 'settings', name: 'settings' },
    ],
    skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
  }
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, gcTime: 0 } },
})

export function HarnessApp() {
  const { locale, setLocale: switchLocale } = useI18n()
  const [dirty, setDirty] = useState(false)

  return (
    <QueryClientProvider client={queryClient}>
      <div style={{ padding: '24px', maxWidth: '760px', margin: '0 auto' }}>
        <div style={{ display: 'flex', gap: '12px', alignItems: 'center', marginBottom: '16px' }}>
          <button
            type="button"
            id="toggle-locale-btn"
            className="settings-button"
            onClick={() => switchLocale(locale === 'zh-CN' ? 'en-US' : 'zh-CN')}
          >
            Locale: {locale}
          </button>
          <button
            type="button"
            id="toggle-dirty-btn"
            className="settings-button"
            onClick={() => setDirty((prev) => !prev)}
          >
            Dirty draft: {String(dirty)}
          </button>
        </div>
        <SyncTab reloadSettings={() => undefined} settingsDirty={dirty} />
      </div>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<HarnessApp />)
}
