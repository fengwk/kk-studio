import { useCallback, useSyncExternalStore } from 'react'
import { messageCatalog, type TranslationKey } from '@/shared/i18n/catalogs'
import type { InterpolationValues, LocaleMessages, AppLocale } from '@/shared/i18n/types'

export type { AppLocale, InterpolationValues } from '@/shared/i18n/types'
export type { TranslationKey } from '@/shared/i18n/catalogs'
export { messageCatalog }

/** 服务端等外部输入只有在 catalog 中存在时才能作为翻译 key 使用。 */
export function isTranslationKey(value: string): value is TranslationKey {
  return Object.hasOwn(messageCatalog, value)
}

/** 收窄外部提供的翻译 key；未知 key 立即失败，避免以缺失文案继续渲染。 */
export function requireTranslationKey(value: string): TranslationKey {
  if (!isTranslationKey(value)) {
    throw new Error(`Unknown i18n key: ${value}`)
  }
  return value
}

export const LOCALE_STORAGE_KEY = 'kk-studio.locale'

const DEFAULT_LOCALE: AppLocale = 'en-US'
const localeListeners = new Set<() => void>()

let currentLocale = readStoredLocale()

syncDocumentLanguage(currentLocale)

export function getLocale(): AppLocale {
  const storedLocale = readStoredLocale()
  if (storedLocale !== currentLocale) {
    currentLocale = storedLocale
    syncDocumentLanguage(currentLocale)
    notifyLocaleListeners()
  }
  return currentLocale
}

export function setLocale(locale: AppLocale): void {
  if (!isAppLocale(locale)) {
    return
  }

  const changed = currentLocale !== locale
  currentLocale = locale
  persistLocale(locale)
  syncDocumentLanguage(locale)
  if (changed) {
    notifyLocaleListeners()
  }
}

export function translate(key: TranslationKey, values?: InterpolationValues): string {
  const locale = getLocale()
  const messages = (messageCatalog as Record<string, LocaleMessages | undefined>)[key]
  const message = messages?.[locale]
  if (typeof message !== 'string') {
    const fallback = `⟦missing:${key}⟧`
    if (isDevelopmentOrTest()) {
      console.error(`Missing i18n message: ${key}`)
    }
    return fallback
  }

  return message.replace(/\{\{\s*([\w.-]+)\s*\}\}/gu, (_match, name: string) => {
    const value = values?.[name]
    return value === undefined ? `⟦missing:${name}⟧` : String(value)
  })
}

export function useI18n() {
  const locale = useSyncExternalStore(localeListenersSubscribe, getLocaleSnapshot, getServerLocale)
  const t = useCallback(
    (key: TranslationKey, values?: InterpolationValues) => translate(key, values),
    [],
  )

  return {
    locale,
    setLocale,
    t,
  }
}

function isAppLocale(value: unknown): value is AppLocale {
  return value === 'en-US' || value === 'zh-CN'
}

function readStoredLocale(fallback: AppLocale = DEFAULT_LOCALE): AppLocale {
  try {
    const storage = globalThis.localStorage
    if (!storage) {
      return fallback
    }
    const stored = storage.getItem(LOCALE_STORAGE_KEY)
    return isAppLocale(stored) ? stored : DEFAULT_LOCALE
  } catch {
    return fallback
  }
}

function persistLocale(locale: AppLocale): void {
  try {
    globalThis.localStorage?.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    // 在隐私受限的浏览器上下文中，Storage 可能不可用。
  }
}

function syncDocumentLanguage(locale: AppLocale): void {
  if (typeof document !== 'undefined' && document.documentElement) {
    document.documentElement.lang = locale
  }
}

function notifyLocaleListeners(): void {
  localeListeners.forEach((listener) => listener())
}

function localeListenersSubscribe(listener: () => void): () => void {
  localeListeners.add(listener)
  return () => localeListeners.delete(listener)
}

function getLocaleSnapshot(): AppLocale {
  return currentLocale
}

function getServerLocale(): AppLocale {
  return DEFAULT_LOCALE
}

function isDevelopmentOrTest(): boolean {
  return import.meta.env.DEV || import.meta.env.MODE === 'test'
}
