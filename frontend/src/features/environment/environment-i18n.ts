import type { InterpolationValues, TranslationKey } from '@/shared/i18n'

/** Environment 卡片/表单共享的翻译函数签名。 */
export type Translate = (key: TranslationKey, values?: InterpolationValues) => string
