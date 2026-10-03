import { aiCatalog } from '@/shared/i18n/catalogs/ai'
import { canvasCatalog } from '@/shared/i18n/catalogs/canvas'
import { platformCatalog } from '@/shared/i18n/catalogs/platform'
import { projectsCatalog } from '@/shared/i18n/catalogs/projects'
import { settingsCatalog } from '@/shared/i18n/catalogs/settings'
import { sharedCatalog } from '@/shared/i18n/catalogs/shared'
import { shortcutsCatalog } from '@/shared/i18n/catalogs/shortcuts'
import { syncCatalog } from '@/shared/i18n/catalogs/sync'

export const messageCatalog = {
  ...platformCatalog,
  ...sharedCatalog,
  ...aiCatalog,
  ...canvasCatalog,
  ...settingsCatalog,
  ...shortcutsCatalog,
  ...projectsCatalog,
  ...syncCatalog,
} as const

export type TranslationKey = keyof typeof messageCatalog
