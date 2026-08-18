/* eslint-disable react-refresh/only-export-components */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type PropsWithChildren,
} from 'react'

const STORAGE_KEY = 'kkstudio.browser-preferences.v1'
const CHANGE_EVENT = 'kkstudio:browser-preferences'

export interface BrowserPreferences {
  notificationsEnabled: boolean
}

export const DEFAULT_BROWSER_PREFERENCES: BrowserPreferences = {
  notificationsEnabled: false,
}

/**
 * 严格读取浏览器本地偏好：存储形状必须精确等于 `{notificationsEnabled: boolean}`；
 * 非法 JSON、非对象、字段缺失/类型不符或携带未知字段一律回退默认（false），
 * 绝不把未知版本的数据误读为已知偏好。
 */
export function decodeBrowserPreferences(value: string | null): BrowserPreferences {
  if (value == null) {
    return DEFAULT_BROWSER_PREFERENCES
  }
  try {
    const parsed: unknown = JSON.parse(value)
    if (!isBrowserPreferences(parsed)) {
      return DEFAULT_BROWSER_PREFERENCES
    }
    return { notificationsEnabled: parsed.notificationsEnabled }
  } catch {
    return DEFAULT_BROWSER_PREFERENCES
  }
}

interface BrowserPreferencesContextValue {
  notificationsEnabled: boolean
  setNotificationsEnabled: (enabled: boolean) => void
}

const BrowserPreferencesContext = createContext<BrowserPreferencesContextValue | null>(null)

/**
 * 全局浏览器本地偏好 Provider（AppProviders mount-once）。
 * 同 Tab 用 CustomEvent 同步，跨 Tab 用 storage 事件同步；
 * 浏览器禁用存储时偏好仍在本 Tab 内生效。
 */
export function BrowserPreferencesProvider({ children }: PropsWithChildren) {
  const [preferences, setPreferences] = useState(readStoredPreferences)
  const preferencesRef = useRef(preferences)

  useEffect(() => {
    preferencesRef.current = preferences
  }, [preferences])

  useEffect(() => {
    const apply = (next: BrowserPreferences) => {
      preferencesRef.current = next
      setPreferences(next)
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEY) {
        apply(decodeBrowserPreferences(event.newValue))
      }
    }
    const onLocalChange = (event: Event) => {
      if (event instanceof CustomEvent && isBrowserPreferences(event.detail)) {
        apply(event.detail)
      }
    }
    window.addEventListener('storage', onStorage)
    window.addEventListener(CHANGE_EVENT, onLocalChange)
    return () => {
      window.removeEventListener('storage', onStorage)
      window.removeEventListener(CHANGE_EVENT, onLocalChange)
    }
  }, [])

  const update = useCallback((patch: Partial<BrowserPreferences>) => {
    const next = { ...preferencesRef.current, ...patch }
    preferencesRef.current = next
    setPreferences(next)
    writeStoredPreferences(next)
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: next }))
  }, [])

  const setNotificationsEnabled = useCallback(
    (enabled: boolean) => update({ notificationsEnabled: enabled }),
    [update],
  )

  const contextValue = useMemo(
    () => ({
      notificationsEnabled: preferences.notificationsEnabled,
      setNotificationsEnabled,
    }),
    [preferences.notificationsEnabled, setNotificationsEnabled],
  )

  return (
    <BrowserPreferencesContext.Provider value={contextValue}>
      {children}
    </BrowserPreferencesContext.Provider>
  )
}

export function useBrowserPreferences(): BrowserPreferencesContextValue {
  const context = useContext(BrowserPreferencesContext)
  if (!context) {
    throw new Error('BrowserPreferencesProvider is required')
  }
  return context
}

function readStoredPreferences(): BrowserPreferences {
  if (typeof window === 'undefined') {
    return DEFAULT_BROWSER_PREFERENCES
  }
  try {
    return decodeBrowserPreferences(window.localStorage.getItem(STORAGE_KEY))
  } catch {
    return DEFAULT_BROWSER_PREFERENCES
  }
}

function writeStoredPreferences(preferences: BrowserPreferences): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences))
  } catch {
    // 浏览器禁用存储时偏好仍在本 Tab 内生效；不影响 runtime correctness。
  }
}

function isBrowserPreferences(value: unknown): value is BrowserPreferences {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return false
  }
  const record = value as Record<string, unknown>
  const keys = Object.keys(record)
  return (
    keys.length === 1
    && keys[0] === 'notificationsEnabled'
    && typeof record.notificationsEnabled === 'boolean'
  )
}
