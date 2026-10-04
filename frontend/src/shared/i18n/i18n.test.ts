import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  getLocale,
  LOCALE_STORAGE_KEY,
  messageCatalog,
  setLocale,
  translate,
  useI18n,
} from '@/shared/i18n'

describe('i18n runtime', () => {
  it('defaults to en-US when the stored preference is absent or invalid', () => {
    localStorage.removeItem(LOCALE_STORAGE_KEY)
    expect(getLocale()).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')

    localStorage.setItem(LOCALE_STORAGE_KEY, 'fr-FR')
    expect(getLocale()).toBe('en-US')
  })

  it('persists locale changes and synchronizes document.lang', () => {
    setLocale('zh-CN')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh-CN')
    expect(document.documentElement.lang).toBe('zh-CN')

    setLocale('en-US')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en-US')
    expect(document.documentElement.lang).toBe('en-US')
  })

  it('rerenders hook consumers when the locale changes', () => {
    const { result } = renderHook(() => useI18n())

    expect(result.current.locale).toBe('zh-CN')
    expect(result.current.t('ai.nav.environments')).toBe('环境')

    act(() => {
      result.current.setLocale('en-US')
    })

    expect(result.current.locale).toBe('en-US')
    expect(result.current.t('ai.nav.environments')).toBe('Environment')
  })

  it('returns a visible deterministic marker for missing messages', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    try {
      expect(translate('missing.example')).toBe('⟦missing:missing.example⟧')
      expect(consoleError).toHaveBeenCalledWith('Missing i18n message: missing.example')
    } finally {
      consoleError.mockRestore()
    }
  })
})

/**
 * 双语字典是同一份产品文案的两个 locale：任何 key 的 en-US / zh-CN 都必须同时存在，
 * 且插值占位符名称集合完全一致（数量与名字），避免译文丢占位符导致运行时渲染出
 * ⟦missing:name⟧ 或吞掉变量。
 */
describe('i18n catalog locale alignment', () => {
  const catalog = messageCatalog as Record<string, Record<string, string>>
  const locales = ['en-US', 'zh-CN'] as const
  const placeholderPattern = /\{\{\s*([\w.-]+)\s*\}\}/gu

  function placeholderNames(text: string): string[] {
    return [...text.matchAll(placeholderPattern)].map((match) => match[1]!).sort()
  }

  it('defines a non-empty message for both locales under every key', () => {
    for (const [key, messages] of Object.entries(catalog)) {
      for (const locale of locales) {
        const message = messages[locale]
        expect(typeof message, `${key} ${locale}`).toBe('string')
        expect(message.trim(), `${key} ${locale}`).not.toBe('')
      }
    }
  })

  it('keeps the same placeholder names in both locales', () => {
    for (const [key, messages] of Object.entries(catalog)) {
      expect(placeholderNames(messages['zh-CN']), key).toEqual(placeholderNames(messages['en-US']))
    }
  })
})
