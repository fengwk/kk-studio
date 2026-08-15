import { aiCatalog } from '@/shared/i18n/catalogs/ai'
import { canvasCatalog } from '@/shared/i18n/catalogs/canvas'
import { comfyuiCatalog } from '@/shared/i18n/catalogs/comfyui'
import { platformCatalog } from '@/shared/i18n/catalogs/platform'
import { settingsCatalog } from '@/shared/i18n/catalogs/settings'
import { sharedCatalog } from '@/shared/i18n/catalogs/shared'
import { shortcutsCatalog } from '@/shared/i18n/catalogs/shortcuts'

export const messageCatalog = {
  ...platformCatalog,
  ...sharedCatalog,
  ...aiCatalog,
  ...canvasCatalog,
  ...comfyuiCatalog,
  ...settingsCatalog,
  ...shortcutsCatalog,
} as const

export type TranslationKey = keyof typeof messageCatalog
