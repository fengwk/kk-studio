import { useCallback, useEffect, useRef, useState } from 'react'

const STORAGE_KEY = 'kkstudio.ai.thread-ui-preferences.v1'
const CHANGE_EVENT = 'kkstudio:ai-thread-ui-preferences'

export interface ThreadUiPreferences {
  taskStatusEnabled: boolean
  notificationsEnabled: boolean
}

export const DEFAULT_THREAD_UI_PREFERENCES: ThreadUiPreferences = {
  taskStatusEnabled: true,
  notificationsEnabled: false,
}

/** 严格读取本地 UI 偏好；非法或缺失字段逐项回退，不影响 Thread durable state。 */
export function decodeThreadUiPreferences(value: string | null): ThreadUiPreferences {
  if (value == null) {
    return DEFAULT_THREAD_UI_PREFERENCES
  }
  try {
    const parsed: unknown = JSON.parse(value)
    if (!isRecord(parsed)) {
      return DEFAULT_THREAD_UI_PREFERENCES
    }
    return {
      taskStatusEnabled:
        typeof parsed.taskStatusEnabled === 'boolean'
          ? parsed.taskStatusEnabled
          : DEFAULT_THREAD_UI_PREFERENCES.taskStatusEnabled,
      notificationsEnabled:
        typeof parsed.notificationsEnabled === 'boolean'
          ? parsed.notificationsEnabled
          : DEFAULT_THREAD_UI_PREFERENCES.notificationsEnabled,
    }
  } catch {
    return DEFAULT_THREAD_UI_PREFERENCES
  }
}

export function useThreadUiPreferences() {
  const [preferences, setPreferences] = useState(readStoredPreferences)
  const preferencesRef = useRef(preferences)

  useEffect(() => {
    preferencesRef.current = preferences
  }, [preferences])

  useEffect(() => {
    const apply = (next: ThreadUiPreferences) => {
      preferencesRef.current = next
      setPreferences(next)
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEY) {
        apply(decodeThreadUiPreferences(event.newValue))
      }
    }
    const onLocalChange = (event: Event) => {
      if (event instanceof CustomEvent && isThreadUiPreferences(event.detail)) {
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

  const update = useCallback((patch: Partial<ThreadUiPreferences>) => {
    const next = { ...preferencesRef.current, ...patch }
    preferencesRef.current = next
    setPreferences(next)
    writeStoredPreferences(next)
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT, { detail: next }))
  }, [])

  return {
    ...preferences,
    setTaskStatusEnabled: (enabled: boolean) => update({ taskStatusEnabled: enabled }),
    setNotificationsEnabled: (enabled: boolean) => update({ notificationsEnabled: enabled }),
  }
}

function readStoredPreferences(): ThreadUiPreferences {
  if (typeof window === 'undefined') {
    return DEFAULT_THREAD_UI_PREFERENCES
  }
  try {
    return decodeThreadUiPreferences(window.localStorage.getItem(STORAGE_KEY))
  } catch {
    return DEFAULT_THREAD_UI_PREFERENCES
  }
}

function writeStoredPreferences(preferences: ThreadUiPreferences): void {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences))
  } catch {
    // 浏览器禁用存储时偏好仍在当前面板内生效；通知/状态不影响 runtime correctness。
  }
}

function isThreadUiPreferences(value: unknown): value is ThreadUiPreferences {
  return isRecord(value)
    && typeof value.taskStatusEnabled === 'boolean'
    && typeof value.notificationsEnabled === 'boolean'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}
