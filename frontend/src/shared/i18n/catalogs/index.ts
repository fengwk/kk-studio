import { canvasCatalog } from '@/shared/i18n/catalogs/canvas'
import { chatCatalog } from '@/shared/i18n/catalogs/chat'
import { environmentCatalog } from '@/shared/i18n/catalogs/environment'
import { platformCatalog } from '@/shared/i18n/catalogs/platform'
import { projectsCatalog } from '@/shared/i18n/catalogs/projects'
import { resourcesCatalog } from '@/shared/i18n/catalogs/resources'
import { settingsCatalog } from '@/shared/i18n/catalogs/settings'
import { sharedCatalog } from '@/shared/i18n/catalogs/shared'
import { shortcutsCatalog } from '@/shared/i18n/catalogs/shortcuts'
import { syncCatalog } from '@/shared/i18n/catalogs/sync'

export const messageCatalog = {
  ...platformCatalog,
  ...sharedCatalog,
  ...chatCatalog,
  ...resourcesCatalog,
  ...environmentCatalog,
  ...canvasCatalog,
  ...settingsCatalog,
  ...shortcutsCatalog,
  ...projectsCatalog,
  ...syncCatalog,
} as const

export type TranslationKey = keyof typeof messageCatalog
