import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BrowserPreferencesProvider,
  decodeBrowserPreferences,
  useBrowserPreferences,
} from '@/features/settings/browser-preferences'

const STORAGE_KEY = 'kkstudio.browser-preferences.v1'

describe('decodeBrowserPreferences', () => {
  it('returns the default (false) for null, malformed JSON and non-object values', () => {
    expect(decodeBrowserPreferences(null)).toEqual({ notificationsEnabled: false })
    expect(decodeBrowserPreferences('not json')).toEqual({ notificationsEnabled: false })
    expect(decodeBrowserPreferences('42')).toEqual({ notificationsEnabled: false })
    expect(decodeBrowserPreferences('"text"')).toEqual({ notificationsEnabled: false })
    expect(decodeBrowserPreferences('[]')).toEqual({ notificationsEnabled: false })
  })

  it('strictly rejects unknown shapes and non-boolean fields back to the default', () => {
    // 字段缺失 / 类型非法 / 携带未知字段：一律回默认 false，绝不误读。
    expect(decodeBrowserPreferences('{}')).toEqual({ notificationsEnabled: false })
    expect(decodeBrowserPreferences('{"notificationsEnabled":"yes"}')).toEqual({ notificationsEnabled: false })
    expect(decodeBrowserPreferences('{"notificationsEnabled":1}')).toEqual({ notificationsEnabled: false })
    expect(decodeBrowserPreferences('{"notificationsEnabled":true,"other":1}')).toEqual({
      notificationsEnabled: false,
    })
    expect(decodeBrowserPreferences('{"notificationsEnabled":null}')).toEqual({ notificationsEnabled: false })
  })

  it('accepts only the exact known shape', () => {
    expect(decodeBrowserPreferences('{"notificationsEnabled":true}')).toEqual({
      notificationsEnabled: true,
    })
    expect(decodeBrowserPreferences('{"notificationsEnabled":false}')).toEqual({
      notificationsEnabled: false,
    })
  })
})

describe('BrowserPreferencesProvider', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('reads persisted preferences on mount and writes the exact storage shape', () => {
    localStorage.setItem(STORAGE_KEY, '{"notificationsEnabled":true}')
    const { result } = renderHook(() => useBrowserPreferences(), {
      wrapper: BrowserPreferencesProvider,
    })
    expect(result.current.notificationsEnabled).toBe(true)

    act(() => result.current.setNotificationsEnabled(false))
    expect(localStorage.getItem(STORAGE_KEY)).toBe('{"notificationsEnabled":false}')
    expect(result.current.notificationsEnabled).toBe(false)
  })

  it('falls back to false for illegal persisted values', () => {
    localStorage.setItem(STORAGE_KEY, '{"notificationsEnabled":"yes"}')
    const { result } = renderHook(() => useBrowserPreferences(), {
      wrapper: BrowserPreferencesProvider,
    })
    expect(result.current.notificationsEnabled).toBe(false)
  })

  it('synchronizes other consumers in the same tab via CustomEvent', () => {
    function Probe() {
      const { notificationsEnabled, setNotificationsEnabled } = useBrowserPreferences()
      return (
        <button type="button" onClick={() => setNotificationsEnabled(true)}>
          {notificationsEnabled ? 'on' : 'off'}
        </button>
      )
    }
    render(
      <BrowserPreferencesProvider>
        <Probe />
        <Probe />
      </BrowserPreferencesProvider>,
    )
    const buttons = screen.getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual(['off', 'off'])
    act(() => buttons[0]!.click())
    expect(buttons.map((button) => button.textContent)).toEqual(['on', 'on'])
  })

  it('synchronizes across tabs via the storage event', async () => {
    const { result } = renderHook(() => useBrowserPreferences(), {
      wrapper: BrowserPreferencesProvider,
    })
    expect(result.current.notificationsEnabled).toBe(false)

    // 模拟另一个 Tab 写入并广播 storage 事件。
    act(() => {
      window.dispatchEvent(
        new StorageEvent('storage', {
          key: STORAGE_KEY,
          newValue: '{"notificationsEnabled":true}',
        }),
      )
    })
    await waitFor(() => expect(result.current.notificationsEnabled).toBe(true))
  })

  it('ignores storage events for unrelated keys', () => {
    const { result } = renderHook(() => useBrowserPreferences(), {
      wrapper: BrowserPreferencesProvider,
    })
    act(() => {
      window.dispatchEvent(
        new StorageEvent('storage', {
          key: 'some-other-key',
          newValue: '{"notificationsEnabled":true}',
        }),
      )
    })
    expect(result.current.notificationsEnabled).toBe(false)
  })

  it('requires the provider', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    try {
      expect(() => renderHook(() => useBrowserPreferences())).toThrow(
        'BrowserPreferencesProvider is required',
      )
    } finally {
      consoleError.mockRestore()
    }
  })
})
