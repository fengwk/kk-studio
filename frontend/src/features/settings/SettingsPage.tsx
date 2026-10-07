import { useMemo, useState } from 'react'
import { SettingsToolbar } from '@/features/settings/SettingsToolbar'
import { GeneralTab } from '@/features/settings/tabs/GeneralTab'
import { PluginsTab } from '@/features/ai/plugins/PluginsTab'
import {
  GENERAL_SETTINGS_TAB,
  PLUGINS_SETTINGS_TAB,
  SYNC_SETTINGS_TAB,
  type SettingsTabMeta,
} from '@/features/settings/settings-tabs'
import { SystemSettingsSchemaRenderer } from '@/features/settings/SystemSettingsSchemaRenderer'
import { SyncTab } from '@/features/settings/sync/SyncTab'
import {
  useSystemSettingsEditor,
  type SystemSettingsEditor,
} from '@/features/settings/useSystemSettingsEditor'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { Tabs, type TabItem } from '@/shared/ui/controls/Tabs'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'

/**
 * 全局设置页：General 保留本地偏好；所有 server tabs、section/group 顺序和字段控件来自
 * GET /api/settings/schema。
 */
export function SettingsPage({
  reloadPage = reloadCurrentPage,
}: {
  reloadPage?: () => void
} = {}) {
  const { t } = useI18n()
  const editor = useSystemSettingsEditor()
  const serverTabs = useMemo<SettingsTabMeta[]>(
    () =>
      (editor.schema?.sections ?? []).map((section) => ({
        id: section.key,
        labelKey: section.labelKey,
      })),
    [editor.schema],
  )
  const tabs = useMemo(
    () => [GENERAL_SETTINGS_TAB, PLUGINS_SETTINGS_TAB, ...serverTabs, SYNC_SETTINGS_TAB],
    [serverTabs],
  )
  const [activeTab, setActiveTab] = useState(GENERAL_SETTINGS_TAB.id)
  const effectiveActiveTab = tabs.some((tab) => tab.id === activeTab)
    ? activeTab
    : GENERAL_SETTINGS_TAB.id

  const tabItems = useMemo<TabItem[]>(
    () =>
      tabs.map((tab) => ({
        id: tab.id,
        label: t(tab.labelKey),
      })),
    [tabs, t],
  )

  const activeSection = editor.schema?.sections.find(
    (section) => section.key === effectiveActiveTab,
  )
  const generalError = editor.loadError ?? editor.schemaError

  return (
    <section className="screen active">
      <div className="screen-body">
        <div className="settings-body">
          <h1 className="settings-title">{t('settings.title')}</h1>

          <Tabs
            tabs={tabItems}
            activeId={effectiveActiveTab}
            onChange={setActiveTab}
            ariaLabel={t('settings.tabs.ariaLabel')}
          >
            {generalError && effectiveActiveTab === GENERAL_SETTINGS_TAB.id ? (
              <SchemaError error={generalError} onRetry={editor.retryLoad} />
            ) : null}

            {effectiveActiveTab === GENERAL_SETTINGS_TAB.id ? (
              <GeneralTab />
            ) : effectiveActiveTab === PLUGINS_SETTINGS_TAB.id ? (
              <PluginsTab />
            ) : effectiveActiveTab === SYNC_SETTINGS_TAB.id ? (
              <SyncTab reloadSettings={editor.retryLoad} settingsDirty={editor.dirty} />
            ) : (
              <ServerTabPane editor={editor} section={activeSection} />
            )}
          </Tabs>
        </div>
      </div>
      <ConflictPresenter
        conflict={editor.conflict}
        onRefresh={reloadPage}
        onClose={editor.dismissConflict}
      />
    </section>
  )
}

function reloadCurrentPage() {
  window.location.reload()
}

function ServerTabPane({
  editor,
  section,
}: {
  editor: SystemSettingsEditor
  section: NonNullable<SystemSettingsEditor['schema']>['sections'][number] | undefined
}) {
  const { t } = useI18n()

  if (editor.loading) {
    return <StateBlock title={t('settings.loading')} />
  }
  if (editor.loadError) {
    return <SchemaError error={editor.loadError} onRetry={editor.retryLoad} />
  }
  if (editor.schemaError) {
    return <SchemaError error={editor.schemaError} onRetry={editor.retryLoad} />
  }
  if (!editor.draft || section == null) {
    return <StateBlock title={t('settings.loading')} />
  }

  return (
    <div className="settings-section-stack">
      <SettingsToolbar editor={editor} />
      <SystemSettingsSchemaRenderer
        schema={{ sections: [section] }}
        draft={editor.draft}
        onChange={editor.updateDraft}
      />
    </div>
  )
}

function SchemaError({ error, onRetry }: { error: string; onRetry: () => void }) {
  const { t } = useI18n()
  return (
    <div className="settings-error-state" role="alert">
      <StateBlock title={error} tone="danger" />
      <Button variant="ghost" onClick={onRetry}>
        {t('settings.retry')}
      </Button>
    </div>
  )
}
