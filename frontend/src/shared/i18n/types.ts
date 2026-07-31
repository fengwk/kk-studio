export type AppLocale = 'en-US' | 'zh-CN'

export type LocaleMessages = Record<AppLocale, string>

export type LocaleCatalog = Record<string, LocaleMessages>

export type InterpolationValues = Record<string, string | number>
