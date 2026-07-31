import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import {
  getLocale,
  LOCALE_STORAGE_KEY,
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
    expect(result.current.t('ai.nav.setting')).toBe('设置')

    act(() => {
      result.current.setLocale('en-US')
    })

    expect(result.current.locale).toBe('en-US')
    expect(result.current.t('ai.nav.setting')).toBe('Setting')
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
