import { useMemo, useState, type KeyboardEvent } from 'react'
import { SettingsToolbar } from '@/features/settings/SettingsToolbar'
import { GeneralTab } from '@/features/settings/tabs/GeneralTab'
import {
  GENERAL_SETTINGS_TAB,
  type SettingsTabMeta,
} from '@/features/settings/settings-tabs'
import { SystemSettingsSchemaRenderer } from '@/features/settings/SystemSettingsSchemaRenderer'
import {
  useSystemSettingsEditor,
  type SystemSettingsEditor,
} from '@/features/settings/useSystemSettingsEditor'
import { ConflictPresenter } from '@/shared/conflict/ConflictPresenter'
import { useI18n } from '@/shared/i18n'

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
  const tabs = useMemo(() => [GENERAL_SETTINGS_TAB, ...serverTabs], [serverTabs])
  const [activeTab, setActiveTab] = useState(GENERAL_SETTINGS_TAB.id)
  const effectiveActiveTab = tabs.some((tab) => tab.id === activeTab)
    ? activeTab
    : GENERAL_SETTINGS_TAB.id

  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const currentIndex = tabs.findIndex((tab) => tab.id === effectiveActiveTab)
    let nextIndex: number | null = null
    if (event.key === 'ArrowRight') {
      nextIndex = (currentIndex + 1) % tabs.length
    } else if (event.key === 'ArrowLeft') {
      nextIndex = (currentIndex - 1 + tabs.length) % tabs.length
    } else if (event.key === 'Home') {
      nextIndex = 0
    } else if (event.key === 'End') {
      nextIndex = tabs.length - 1
    }
    if (nextIndex !== null) {
      event.preventDefault()
      const nextTab = tabs[nextIndex]!
      setActiveTab(nextTab.id)
      document.getElementById(`settings-tab-${nextTab.id}`)?.focus()
    }
  }

  const activeSection = editor.schema?.sections.find(
    (section) => section.key === effectiveActiveTab,
  )
  const generalError = editor.loadError ?? editor.schemaError

  return (
    <section className="screen active">
      <div className="screen-body">
        <div className="settings-body">
          <h1 className="settings-title">{t('settings.title')}</h1>

          <div className="settings-tabs" role="tablist" aria-label={t('settings.tabs.ariaLabel')}>
          {tabs.map((tab) => {
            const selected = effectiveActiveTab === tab.id
            return (
              <button
                key={tab.id}
                type="button"
                role="tab"
                id={`settings-tab-${tab.id}`}
                className={`settings-tab${selected ? ' active' : ''}`}
                aria-selected={selected}
                aria-controls={`settings-tabpanel-${tab.id}`}
                tabIndex={selected ? 0 : -1}
                onClick={() => setActiveTab(tab.id)}
                onKeyDown={onTabKeyDown}
              >
                {t(tab.labelKey)}
              </button>
            )
          })}
        </div>

        {generalError && effectiveActiveTab === GENERAL_SETTINGS_TAB.id ? (
          <SchemaError error={generalError} onRetry={editor.retryLoad} />
        ) : null}

        <div
          id={`settings-tabpanel-${effectiveActiveTab}`}
          role="tabpanel"
          aria-labelledby={`settings-tab-${effectiveActiveTab}`}
        >
          {effectiveActiveTab === GENERAL_SETTINGS_TAB.id ? (
            <GeneralTab />
          ) : (
            <ServerTabPane
              editor={editor}
              section={activeSection}
            />
          )}
        </div>
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
    return (
      <div className="state-block" role="status">
        {t('settings.loading')}
      </div>
    )
  }
  if (editor.loadError) {
    return <SchemaError error={editor.loadError} onRetry={editor.retryLoad} />
  }
  if (editor.schemaError) {
    return <SchemaError error={editor.schemaError} onRetry={editor.retryLoad} />
  }
  if (!editor.draft || section == null) {
    return (
      <div className="state-block" role="status">
        {t('settings.loading')}
      </div>
    )
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
    <div className="state-block" role="alert">
      <p>{error}</p>
      <button type="button" className="settings-button" onClick={onRetry}>
        {t('settings.retry')}
      </button>
    </div>
  )
}
