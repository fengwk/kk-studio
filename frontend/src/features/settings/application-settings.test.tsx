import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApplicationSettingsProvider,
  decodeApplicationSettings,
  useApplicationSettings,
} from '@/features/settings/application-settings'

const STORAGE_KEY = 'kkstudio.application-settings.v1'

describe('decodeApplicationSettings', () => {
  it('returns the default (false) for null, malformed JSON and non-object values', () => {
    expect(decodeApplicationSettings(null)).toEqual({ notificationsEnabled: false })
    expect(decodeApplicationSettings('not json')).toEqual({ notificationsEnabled: false })
    expect(decodeApplicationSettings('42')).toEqual({ notificationsEnabled: false })
    expect(decodeApplicationSettings('"text"')).toEqual({ notificationsEnabled: false })
    expect(decodeApplicationSettings('[]')).toEqual({ notificationsEnabled: false })
  })

  it('strictly rejects unknown shapes and non-boolean fields back to the default', () => {
    // 字段缺失 / 类型非法 / 携带未知字段：一律回默认 false，绝不误读。
    expect(decodeApplicationSettings('{}')).toEqual({ notificationsEnabled: false })
    expect(decodeApplicationSettings('{"notificationsEnabled":"yes"}')).toEqual({ notificationsEnabled: false })
    expect(decodeApplicationSettings('{"notificationsEnabled":1}')).toEqual({ notificationsEnabled: false })
    expect(decodeApplicationSettings('{"notificationsEnabled":true,"other":1}')).toEqual({
      notificationsEnabled: false,
    })
    expect(decodeApplicationSettings('{"notificationsEnabled":null}')).toEqual({ notificationsEnabled: false })
  })

  it('accepts only the exact known shape', () => {
    expect(decodeApplicationSettings('{"notificationsEnabled":true}')).toEqual({
      notificationsEnabled: true,
    })
    expect(decodeApplicationSettings('{"notificationsEnabled":false}')).toEqual({
      notificationsEnabled: false,
    })
  })
})

describe('ApplicationSettingsProvider', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('reads persisted settings on mount and writes the exact storage shape', () => {
    localStorage.setItem(STORAGE_KEY, '{"notificationsEnabled":true}')
    const { result } = renderHook(() => useApplicationSettings(), {
      wrapper: ApplicationSettingsProvider,
    })
    expect(result.current.notificationsEnabled).toBe(true)

    act(() => result.current.setNotificationsEnabled(false))
    expect(localStorage.getItem(STORAGE_KEY)).toBe('{"notificationsEnabled":false}')
    expect(result.current.notificationsEnabled).toBe(false)
  })

  it('falls back to false for illegal persisted values', () => {
    localStorage.setItem(STORAGE_KEY, '{"notificationsEnabled":"yes"}')
    const { result } = renderHook(() => useApplicationSettings(), {
      wrapper: ApplicationSettingsProvider,
    })
    expect(result.current.notificationsEnabled).toBe(false)
  })

  it('synchronizes other consumers in the same tab via CustomEvent', () => {
    function Probe() {
      const { notificationsEnabled, setNotificationsEnabled } = useApplicationSettings()
      return (
        <button type="button" onClick={() => setNotificationsEnabled(true)}>
          {notificationsEnabled ? 'on' : 'off'}
        </button>
      )
    }
    render(
      <ApplicationSettingsProvider>
        <Probe />
        <Probe />
      </ApplicationSettingsProvider>,
    )
    const buttons = screen.getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual(['off', 'off'])
    act(() => buttons[0]!.click())
    expect(buttons.map((button) => button.textContent)).toEqual(['on', 'on'])
  })

  it('synchronizes across tabs via the storage event', async () => {
    const { result } = renderHook(() => useApplicationSettings(), {
      wrapper: ApplicationSettingsProvider,
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
    const { result } = renderHook(() => useApplicationSettings(), {
      wrapper: ApplicationSettingsProvider,
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
      expect(() => renderHook(() => useApplicationSettings())).toThrow(
        'ApplicationSettingsProvider is required',
      )
    } finally {
      consoleError.mockRestore()
    }
  })
})
