import { act, render, renderHook, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BrowserPreferencesProvider,
  useBrowserPreferences,
} from '@/features/settings/browser-preferences'

const STORAGE_KEY = 'kk-studio.browser-preferences'

/** 只通过 Provider 读回的真实存储读取路径；没有独立导出的解码入口。 */
describe('BrowserPreferencesProvider storage decoding', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  /** 通过 Provider 的初始读取断言 decodeBrowserPreferences 的严格边界。 */
  function readStored(raw: string | null): boolean {
    localStorage.clear()
    if (raw !== null) {
      localStorage.setItem(STORAGE_KEY, raw)
    }
    const { result, unmount } = renderHook(() => useBrowserPreferences(), {
      wrapper: BrowserPreferencesProvider,
    })
    const enabled = result.current.notificationsEnabled
    unmount()
    return enabled
  }

  it('returns the default (false) for null, malformed JSON and non-object values', () => {
    expect(readStored(null)).toBe(false)
    expect(readStored('not json')).toBe(false)
    expect(readStored('42')).toBe(false)
    expect(readStored('"text"')).toBe(false)
    expect(readStored('[]')).toBe(false)
  })

  it('strictly rejects unknown shapes and non-boolean fields back to the default', () => {
    // 字段缺失 / 类型非法 / 携带未知字段：一律回默认 false，绝不误读。
    expect(readStored('{}')).toBe(false)
    expect(readStored('{"notificationsEnabled":"yes"}')).toBe(false)
    expect(readStored('{"notificationsEnabled":1}')).toBe(false)
    expect(readStored('{"notificationsEnabled":true,"other":1}')).toBe(false)
    expect(readStored('{"notificationsEnabled":null}')).toBe(false)
  })

  it('accepts only the exact known shape', () => {
    expect(readStored('{"notificationsEnabled":true}')).toBe(true)
    expect(readStored('{"notificationsEnabled":false}')).toBe(false)
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
