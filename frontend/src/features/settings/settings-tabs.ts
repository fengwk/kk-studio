export interface SettingsTabMeta {
  id: string
  labelKey: string
}

/** General 是唯一保留在前端本地的设置页签；server 页签来自 settings schema sections。 */
export const GENERAL_SETTINGS_TAB: SettingsTabMeta = {
  id: 'general',
  labelKey: 'settings.tabs.general',
}
