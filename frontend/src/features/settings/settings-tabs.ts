import type { SystemSettingsSectionsDraft } from '@/features/settings/system-settings-draft'

export type SettingsTabId =
  | 'general'
  | 'ai-runtime'
  | 'tools-permissions'
  | 'environment'
  | 'integrations'
  | 'storage-media'
  | 'advanced'

export interface SettingsTabMeta {
  id: SettingsTabId
  labelKey: string
}

export const SETTINGS_TABS: SettingsTabMeta[] = [
  { id: 'general', labelKey: 'settings.tabs.general' },
  { id: 'ai-runtime', labelKey: 'settings.tabs.aiRuntime' },
  { id: 'tools-permissions', labelKey: 'settings.tabs.toolsPermissions' },
  { id: 'environment', labelKey: 'settings.tabs.environment' },
  { id: 'integrations', labelKey: 'settings.tabs.integrations' },
  { id: 'storage-media', labelKey: 'settings.tabs.storageMedia' },
  { id: 'advanced', labelKey: 'settings.tabs.advanced' },
]

export function isServerSettingsTab(id: SettingsTabId): boolean {
  return id !== 'general'
}

export type SectionEditorProps<D> = {
  value: D
  onChange: (next: D) => void
}

/** 每个 server section 的 key → draft 类型映射，供 page 动态分派 updateSection。 */
export interface SectionDraftKeys {
  tool: SystemSettingsSectionsDraft['tool']
  aiRuntime: SystemSettingsSectionsDraft['aiRuntime']
  environment: SystemSettingsSectionsDraft['environment']
  integrations: SystemSettingsSectionsDraft['integrations']
  storageMedia: SystemSettingsSectionsDraft['storageMedia']
  advanced: SystemSettingsSectionsDraft['advanced']
}
