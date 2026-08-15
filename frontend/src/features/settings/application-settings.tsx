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

const STORAGE_KEY = 'kkstudio.application-settings.v1'
const CHANGE_EVENT = 'kkstudio:application-settings'

export interface ApplicationSettings {
  notificationsEnabled: boolean
}

export const DEFAULT_APPLICATION_SETTINGS: ApplicationSettings = {
  notificationsEnabled: false,
}

/**
 * 严格读取应用设置：存储形状必须精确等于 `{notificationsEnabled: boolean}`；
 * 非法 JSON、非对象、字段缺失/类型不符或携带未知字段一律回退默认（false），
 * 绝不把未知版本的数据误读为已知设置。
 */
export function decodeApplicationSettings(value: string | null): ApplicationSettings {
  if (value == null) {
    return DEFAULT_APPLICATION_SETTINGS
  }
  try {
    const parsed: unknown = JSON.parse(value)
    if (!isApplicationSettings(parsed)) {
      return DEFAULT_APPLICATION_SETTINGS
    }
    return { notificationsEnabled: parsed.notificationsEnabled }
  } catch {
    return DEFAULT_APPLICATION_SETTINGS
  }
}

interface ApplicationSettingsContextValue {
  notificationsEnabled: boolean
  setNotificationsEnabled: (enabled: boolean) => void
}

const ApplicationSettingsContext = createContext<ApplicationSettingsContextValue | null>(null)

/**
 * 全局应用设置 Provider（AppProviders mount-once）。
 * 同 Tab 用 CustomEvent 同步，跨 Tab 用 storage 事件同步；
 * 浏览器禁用存储时设置仍在本 Tab 内生效。
 */
export function ApplicationSettingsProvider({ children }: PropsWithChildren) {
  const [settings, setSettings] = useState(readStoredSettings)
  const settingsRef = useRef(settings)

  useEffect(() => {
    settingsRef.current = settings
  }, [settings])

  useEffect(() => {
    const apply = (next: ApplicationSettings) => {
      settingsRef.current = next
      setSettings(next)
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEY) {
        apply(decodeApplicationSettings(event.newValue))
      }
    }
    const onLocalChange = (event: Event) => {
      if (event instanceof CustomEvent && isApplicationSettings(event.detail)) {
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

  const update = useCallback((patch: Partial<ApplicationSettings>) => {
    const next = { ...settingsRef.current, ...patch }
    settingsRef.current = next
    setSettings(next)
    writeStoredSettings(next)
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: next }))
  }, [])

  const setNotificationsEnabled = useCallback(
    (enabled: boolean) => update({ notificationsEnabled: enabled }),
    [update],
  )

  const contextValue = useMemo(
    () => ({ notificationsEnabled: settings.notificationsEnabled, setNotificationsEnabled }),
    [setNotificationsEnabled, settings.notificationsEnabled],
  )

  return (
    <ApplicationSettingsContext.Provider value={contextValue}>
      {children}
    </ApplicationSettingsContext.Provider>
  )
}

export function useApplicationSettings(): ApplicationSettingsContextValue {
  const context = useContext(ApplicationSettingsContext)
  if (!context) {
    throw new Error('ApplicationSettingsProvider is required')
  }
  return context
}

function readStoredSettings(): ApplicationSettings {
  if (typeof window === 'undefined') {
    return DEFAULT_APPLICATION_SETTINGS
  }
  try {
    return decodeApplicationSettings(window.localStorage.getItem(STORAGE_KEY))
  } catch {
    return DEFAULT_APPLICATION_SETTINGS
  }
}

function writeStoredSettings(settings: ApplicationSettings): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
  } catch {
    // 浏览器禁用存储时设置仍在本 Tab 内生效；不影响 runtime correctness。
  }
}

function isApplicationSettings(value: unknown): value is ApplicationSettings {
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
