import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import '@/styles.css'
import { useI18n, setLocale } from '@/shared/i18n'
import { configSyncService } from '@/shared/api/config-sync-service'
import type {
  ConfigSyncImportCheckDTO,
  ConfigSyncItem,
} from '@/shared/api/contracts/config-sync'
import { SyncTab } from '@/features/settings/sync/SyncTab'

declare global {
  interface Window {
    __syncExportRequest?: unknown
    __syncImportYaml?: string
    __syncImportAllowPartial?: boolean
    __syncCheckYaml?: string
  }
}

setLocale('zh-CN')

/** 预检查形态：full 全量、partial 部分可用、empty 无可用项、fail 检查失败。 */
type PreviewMode = 'full' | 'partial' | 'empty' | 'fail'

let previewMode: PreviewMode = 'partial'

function buildPreview(): ConfigSyncImportCheckDTO {
  if (previewMode === 'full') {
    return {
      created: [{ kind: 'providers', name: 'openai' }],
      // settings 已初始化，同一份文件只会覆盖而不会新增。
      updated: [{ kind: 'settings', name: 'settings' }],
      skipped: [],
    }
  }
  if (previewMode === 'empty') {
    return {
      created: [],
      updated: [],
      skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
    }
  }
  return {
    created: [{ kind: 'providers', name: 'openai' }],
    updated: [{ kind: 'models', name: 'openai/gpt-4o' }],
    skipped: [{ kind: 'agents', name: 'broken', reason: 'unsupported tool' }],
  }
}

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
configSyncService.checkImport = async (yaml) => {
  window.__syncCheckYaml = yaml
  if (previewMode === 'fail') {
    throw new Error('unsupported file structure')
  }
  return buildPreview()
}
configSyncService.importConfig = async (yaml, allowPartial) => {
  window.__syncImportYaml = yaml
  window.__syncImportAllowPartial = allowPartial
  const preview = buildPreview()
  return { imported: [...preview.created, ...preview.updated], skipped: preview.skipped }
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
        <div style={{ display: 'flex', gap: '12px', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap' }}>
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
          <select
            id="preview-mode"
            className="settings-button"
            defaultValue={previewMode}
            onChange={(event) => {
              previewMode = event.target.value as PreviewMode
            }}
          >
            <option value="full">full</option>
            <option value="partial">partial</option>
            <option value="empty">empty</option>
            <option value="fail">fail</option>
          </select>
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
