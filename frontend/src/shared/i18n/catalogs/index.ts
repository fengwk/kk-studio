import { accountCatalog } from '@/shared/i18n/catalogs/account'
import { canvasCatalog } from '@/shared/i18n/catalogs/canvas'
import { chatCatalog } from '@/shared/i18n/catalogs/chat'
import { environmentCatalog } from '@/shared/i18n/catalogs/environment'
import { resourcesCatalog } from '@/shared/i18n/catalogs/resources'
import { settingsCatalog } from '@/shared/i18n/catalogs/settings'
import { sharedCatalog } from '@/shared/i18n/catalogs/shared'
import { shortcutsCatalog } from '@/shared/i18n/catalogs/shortcuts'
import { syncCatalog } from '@/shared/i18n/catalogs/sync'
import { workspaceCatalog } from '@/shared/i18n/catalogs/workspace'
import { workspacePanesCatalog } from '@/shared/i18n/catalogs/workspacePanes'

export const messageCatalog = {
  ...accountCatalog,
  ...sharedCatalog,
  ...chatCatalog,
  ...resourcesCatalog,
  ...environmentCatalog,
  ...canvasCatalog,
  ...settingsCatalog,
  ...shortcutsCatalog,
  ...syncCatalog,
  ...workspaceCatalog,
  ...workspacePanesCatalog,
} as const

export type TranslationKey = keyof typeof messageCatalog
