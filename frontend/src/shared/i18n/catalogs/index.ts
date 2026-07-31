import { aiCatalog } from '@/shared/i18n/catalogs/ai'
import { canvasCatalog } from '@/shared/i18n/catalogs/canvas'
import { comfyuiCatalog } from '@/shared/i18n/catalogs/comfyui'
import { platformCatalog } from '@/shared/i18n/catalogs/platform'
import { sharedCatalog } from '@/shared/i18n/catalogs/shared'

export const messageCatalog = {
  ...platformCatalog,
  ...sharedCatalog,
  ...aiCatalog,
  ...canvasCatalog,
  ...comfyuiCatalog,
} as const

export type TranslationKey = keyof typeof messageCatalog
