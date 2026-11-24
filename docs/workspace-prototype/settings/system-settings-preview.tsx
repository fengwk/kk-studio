import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { SystemSettingsSchemaRenderer } from '@/features/settings/SystemSettingsSchemaRenderer'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Panel } from '@/shared/ui/layout/Panel'
import { Toolbar } from '@/shared/ui/layout/Toolbar'
import { DraftValidationError, type DraftValidationReason, type SystemSettingsSectionsDraft } from '@/features/settings/system-settings-draft'
import { SyncTab } from '@/features/settings/sync/SyncTab'
import { previewSettingsSchema, previewSyncInventory, readPreviewSettings, savePreviewSettings } from '../mock/local-config-sync'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ModelDTO, ToolCatalogEntryDTO } from '@/shared/api/generated/contracts'
import '@/features/settings/settings.css'

export type SystemSection = keyof SystemSettingsSectionsDraft

const draftErrorKeys: Record<DraftValidationReason, TranslationKey> = {
  blankToolName: 'settings.error.permissionToolNameRequired',
  duplicateToolName: 'settings.error.permissionDuplicateToolName',
  blankPattern: 'settings.error.permissionPatternRequired',
  emptyNumericField: 'settings.error.numericFieldRequired',
  httpStatusNotInteger: 'settings.error.httpStatusNotInteger',
  httpStatusOutOfRange: 'settings.error.httpStatusOutOfRange',
  httpStatusDuplicate: 'settings.error.httpStatusDuplicate',
}

export function usePreviewSettings() {
  const { t, locale } = useI18n()
  const [draft, setDraft] = useState(readPreviewSettings)
  const [baseline, setBaseline] = useState(readPreviewSettings)
  const [error, setError] = useState('')
  const dirty = JSON.stringify(draft) !== JSON.stringify(baseline)

  async function reloadSettings() {
    const saved = readPreviewSettings()
    setDraft(saved)
    setBaseline(saved)
    setError('')
  }

  function save() {
    if (baseline !== readPreviewSettings()) {
      setError(locale === 'zh-CN' ? '已导入新设置，请重置后再编辑。' : 'New settings were imported. Reset before editing again.')
      return
    }
    try {
      const saved = savePreviewSettings(draft)
      setDraft(saved)
      setBaseline(saved)
      setError('')
    } catch (cause) {
      if (!(cause instanceof DraftValidationError)) throw cause
      setError(t(draftErrorKeys[cause.reason]))
    }
  }

  function change(next: SystemSettingsSectionsDraft) {
    setDraft(next)
    setError('')
  }

  return { draft, dirty, error, change, save, reloadSettings }
}

export function SettingsSyncPreview({ dirty, reloadSettings }: Pick<ReturnType<typeof usePreviewSettings>, 'dirty' | 'reloadSettings'>) {
  const [queryClient] = useState(() => {
    const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity } } })
    client.setQueryData(queryKeys.configSync.inventory, previewSyncInventory)
    return client
  })
  const [visible, setVisible] = useState(true)
  useEffect(() => {
    const syncScreen = (event: Event) => setVisible((event as CustomEvent<{ screen: string }>).detail.screen === 'scr-settings')
    document.addEventListener('htmlview:change', syncScreen)
    return () => document.removeEventListener('htmlview:change', syncScreen)
  }, [])
  if (!visible) return null
  return <div className="screen-body">
    <div className="settings-panel">
      <QueryClientProvider client={queryClient}>
        <SyncTab settingsDirty={dirty} reloadSettings={reloadSettings} />
      </QueryClientProvider>
    </div>
  </div>
}

export function SystemSettingsPreview({ sectionKey, form }: { sectionKey?: SystemSection; form: ReturnType<typeof usePreviewSettings> }) {
  const { t } = useI18n()
  const [queryClient] = useState(() => {
    const client = new QueryClient({ defaultOptions: { queries: { staleTime: Infinity, gcTime: Infinity } } })
    // Seed read-only directories, including across section changes, so production pickers never fetch.
    client.setQueryData<ModelDTO[]>(queryKeys.models.list, [])
    client.setQueryData<ToolCatalogEntryDTO[]>(queryKeys.tools.list, [])
    return client
  })
  const section = previewSettingsSchema.sections.find((candidate) => candidate.key === sectionKey)
  if (!section) return null
  return <div className="screen-body">
    <div className="settings-panel settings-section-stack">
      <Panel className="settings-toolbar" aria-label={t('settings.toolbar.ariaLabel')}>
        {form.error && <div className="settings-error-banner" role="alert">{form.error}</div>}
        <Toolbar>
          <Button disabled={!form.dirty} onClick={form.save}>保存示例</Button>
          <Button variant="secondary" disabled={!form.dirty} onClick={() => { void form.reloadSettings() }}>{t('settings.reset')}</Button>
          <span className="settings-dirty" data-dirty={form.dirty} role="status">
            {form.dirty ? t('settings.unsavedChanges') : ''}
          </span>
        </Toolbar>
      </Panel>
      <QueryClientProvider client={queryClient}>
        <SystemSettingsSchemaRenderer
          schema={{ sections: [section] }}
          draft={form.draft}
          onChange={form.change}
        />
      </QueryClientProvider>
    </div>
  </div>
}
