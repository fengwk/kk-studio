import { useState, type KeyboardEvent } from 'react'
import { useI18n } from '@/shared/i18n'
import { SettingsToolbar } from '@/features/settings/SettingsToolbar'
import {
  SETTINGS_TABS,
  type SettingsTabId,
} from '@/features/settings/settings-tabs'
import { GeneralTab } from '@/features/settings/tabs/GeneralTab'
import { AiRuntimeTab } from '@/features/settings/tabs/AiRuntimeTab'
import { ToolsPermissionsTab } from '@/features/settings/tabs/ToolsPermissionsTab'
import { EnvironmentTab } from '@/features/settings/tabs/EnvironmentTab'
import { IntegrationsTab } from '@/features/settings/tabs/IntegrationsTab'
import { StorageMediaTab } from '@/features/settings/tabs/StorageMediaTab'
import { AdvancedTab } from '@/features/settings/tabs/AdvancedTab'
import {
  useSystemSettingsEditor,
  type SystemSettingsEditor,
} from '@/features/settings/useSystemSettingsEditor'
import type { SystemSettingsSectionsDraft } from '@/features/settings/system-settings-draft'
import { ConfirmActionModal } from '@/shared/ui/console/ConfirmActionModal'

type ServerSectionKey = keyof SystemSettingsSectionsDraft

const SERVER_SECTION_BY_TAB: Partial<Record<SettingsTabId, ServerSectionKey>> = {
  'ai-runtime': 'aiRuntime',
  'tools-permissions': 'tool',
  environment: 'environment',
  integrations: 'integrations',
  'storage-media': 'storageMedia',
  advanced: 'advanced',
}

/**
 * 全局设置页：General 本地偏好 + 六个 server settings tab。
 * server tab 共享同一个权威聚合 draft（加载/保存一体），每个 section 独立组件编辑。
 */
export function SettingsPage({
  reloadPage = reloadCurrentPage,
}: {
  reloadPage?: () => void
} = {}) {
  const { t } = useI18n()
  const [activeTab, setActiveTab] = useState<SettingsTabId>('general')
  const editor = useSystemSettingsEditor()

  // Tab 键盘导航：ArrowLeft/Right 相邻、Home 首个、End 末个；移动方向键时焦点跟随新选中项（roving tabindex：
  // 选中项 tabIndex=0，其余为 -1，进入 tablist 时把焦点放到选中项）。
  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    const currentIndex = SETTINGS_TABS.findIndex((tab) => tab.id === activeTab)
    let nextIndex: number | null = null
    if (event.key === 'ArrowRight') {
      nextIndex = (currentIndex + 1) % SETTINGS_TABS.length
    } else if (event.key === 'ArrowLeft') {
      nextIndex = (currentIndex - 1 + SETTINGS_TABS.length) % SETTINGS_TABS.length
    } else if (event.key === 'Home') {
      nextIndex = 0
    } else if (event.key === 'End') {
      nextIndex = SETTINGS_TABS.length - 1
    }
    if (nextIndex !== null) {
      event.preventDefault()
      const nextTab = SETTINGS_TABS[nextIndex]!
      setActiveTab(nextTab.id)
      document.getElementById(`settings-tab-${nextTab.id}`)?.focus()
    }
  }

  return (
    <section className="screen active">
      <div className="screen-body settings-body">
        <h1 className="settings-title">{t('settings.title')}</h1>

        <div className="settings-tabs" role="tablist" aria-label={t('settings.tabs.ariaLabel')}>
          {SETTINGS_TABS.map((tab) => {
            const selected = activeTab === tab.id
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

        <div
          id={`settings-tabpanel-${activeTab}`}
          role="tabpanel"
          aria-labelledby={`settings-tab-${activeTab}`}
        >
          {activeTab === 'general' ? (
            <GeneralTab />
          ) : (
            <ServerTabPane
              editor={editor}
              sectionKey={SERVER_SECTION_BY_TAB[activeTab] ?? 'tool'}
            />
          )}
        </div>
      </div>
      <ConfirmActionModal
        modal={
          editor.staleConflict
            ? {
                title: t('settings.conflict.title'),
                description: t('settings.conflict.description'),
                confirmLabel: t('settings.conflict.reload'),
                icon: 'refresh',
                onConfirm: reloadPage,
              }
            : null
        }
        pending={false}
        onClose={editor.dismissStaleConflict}
      />
    </section>
  )
}

function reloadCurrentPage() {
  window.location.reload()
}

/** server tab 的展示层：loading / 初始加载错误 / 工具条 + 对应 section 编辑器。 */
function ServerTabPane({
  editor,
  sectionKey,
}: {
  editor: SystemSettingsEditor
  sectionKey: ServerSectionKey
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
    return (
      <div className="state-block" role="alert">
        <p>{editor.loadError}</p>
        <button type="button" className="settings-button" onClick={editor.retryLoad}>
          {t('settings.retry')}
        </button>
      </div>
    )
  }
  if (!editor.draft) {
    return (
      <div className="state-block" role="status">
        {t('settings.loading')}
      </div>
    )
  }

  return (
    <>
      <SettingsToolbar editor={editor} />
      <ServerSectionTab
        sectionKey={sectionKey}
        draft={editor.draft}
        onChange={editor.updateSection}
      />
    </>
  )
}

function ServerSectionTab({
  sectionKey,
  draft,
  onChange,
}: {
  sectionKey: ServerSectionKey
  draft: SystemSettingsSectionsDraft
  onChange: <K extends keyof SystemSettingsSectionsDraft>(
    key: K,
    value: SystemSettingsSectionsDraft[K],
  ) => void
}) {
  switch (sectionKey) {
    case 'tool':
      return (
        <ToolsPermissionsTab
          value={draft.tool}
          onChange={(next) => onChange('tool', next)}
        />
      )
    case 'aiRuntime':
      return (
        <AiRuntimeTab
          value={draft.aiRuntime}
          onChange={(next) => onChange('aiRuntime', next)}
        />
      )
    case 'environment':
      return (
        <EnvironmentTab
          value={draft.environment}
          onChange={(next) => onChange('environment', next)}
        />
      )
    case 'integrations':
      return (
        <IntegrationsTab
          value={draft.integrations}
          onChange={(next) => onChange('integrations', next)}
        />
      )
    case 'storageMedia':
      return (
        <StorageMediaTab
          value={draft.storageMedia}
          onChange={(next) => onChange('storageMedia', next)}
        />
      )
    case 'advanced':
      return (
        <AdvancedTab
          value={draft.advanced}
          onChange={(next) => onChange('advanced', next)}
        />
      )
  }
}
