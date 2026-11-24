import {
  assembleSettingsUpdate,
  settingsSectionsToDraft,
  type SystemSettingsSectionsDraft,
} from '@/features/settings/system-settings-draft'
import { validateSystemSettingsSchema } from '@/features/settings/system-settings-schema-validation'
import type { ConfigSyncInventoryDTO, ConfigSyncItem } from '@/shared/api/generated/contracts'
import { getLocale } from '@/shared/i18n'
import { makeSettingsDto, makeSettingsSchema } from '@/test-support/settings-test-fixtures'

export const previewSettingsSchema = makeSettingsSchema()
let savedSettings = settingsSectionsToDraft(makeSettingsDto())
const settingsItem = { kind: 'settings', name: 'settings', dependencies: [] } satisfies ConfigSyncItem
export const previewSyncInventory: ConfigSyncInventoryDTO = { items: [settingsItem] }
const format = 'kk-studio-workspace-preview/v1'

export function readPreviewSettings() {
  return savedSettings
}

function normalize(draft: SystemSettingsSectionsDraft) {
  return settingsSectionsToDraft(assembleSettingsUpdate(draft, '0'))
}

export function savePreviewSettings(draft: SystemSettingsSectionsDraft) {
  savedSettings = normalize(draft)
  return savedSettings
}

function proxyHasNoCredentials(draft: SystemSettingsSectionsDraft) {
  const proxy = draft.backendNetwork.proxyUrl
  if (!proxy) return true
  try {
    const url = new URL(proxy)
    return url.protocol === 'http:' && !url.username && !url.password && !url.search && !url.hash && url.pathname === '/'
  } catch {
    return false
  }
}

function readPreviewFile(yaml: string) {
  // The download uses YAML's JSON subset. Real Studio exports lack this preview format marker.
  let file: { format?: string; settings?: SystemSettingsSectionsDraft } | null
  try {
    file = JSON.parse(yaml)
  } catch {
    file = null
  }
  const zh = getLocale() === 'zh-CN'
  if (file?.format !== format || !file.settings) {
    throw new Error(zh ? '请选择此原型导出的配置文件。' : 'Choose a configuration file exported by this preview.')
  }
  try {
    if (validateSystemSettingsSchema(previewSettingsSchema, file.settings) || !proxyHasNoCredentials(file.settings)) {
      throw new Error('Invalid preview settings')
    }
    return normalize(file.settings)
  } catch {
    throw new Error(zh ? '配置无效，未导入。' : 'Invalid configuration. Nothing was imported.')
  }
}

export const configSyncService: typeof import('@/features/settings/config-sync-service').configSyncService = {
  async getInventory() {
    return previewSyncInventory
  },
  async exportConfig(items) {
    const zh = getLocale() === 'zh-CN'
    if (items.length !== 1 || items[0]?.kind !== settingsItem.kind || items[0]?.name !== settingsItem.name) {
      throw new Error(zh ? '请选择要导出的设置。' : 'Select the settings to export.')
    }
    if (!proxyHasNoCredentials(savedSettings)) {
      throw new Error(zh ? '请先改用不含凭据的 HTTP 代理地址。' : 'Use an HTTP proxy address without credentials before exporting.')
    }
    return { yaml: JSON.stringify({ format, settings: savedSettings }, null, 2) + '\n' }
  },
  async checkImport(yaml) {
    readPreviewFile(yaml)
    return { created: [], updated: [settingsItem], skipped: [], pluginCredentialResets: [] }
  },
  async importConfig(yaml) {
    savedSettings = readPreviewFile(yaml)
    return { imported: [settingsItem], skipped: [] }
  },
}
