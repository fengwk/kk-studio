import { utf8ByteLength } from '@/shared/lib/value'

export type TabStorageArea = Pick<Storage, 'getItem' | 'setItem'>
export interface TabStorage {
  sessionStorage?: TabStorageArea | null
  localStorage?: TabStorageArea | null
}

export const TAB_STATE_MAX_BYTES = 64 * 1024
export const TAB_SESSION_PREFIX = 'kk-studio.tab-state:'
export const TAB_LAST_PREFIX = 'kk-studio.last-state:'

function resolveGlobalStorage(kind: 'sessionStorage' | 'localStorage'): TabStorageArea | null {
  try {
    return globalThis[kind] ?? null
  } catch {
    return null
  }
}

export function resolveTabStorage(storage?: TabStorage): {
  session: TabStorageArea | null
  local: TabStorageArea | null
} {
  return {
    session: storage?.sessionStorage === undefined ? resolveGlobalStorage('sessionStorage') : storage.sessionStorage,
    local: storage?.localStorage === undefined ? resolveGlobalStorage('localStorage') : storage.localStorage,
  }
}

export function parseTabSnapshot<T>(raw: string | null, parse: (value: unknown) => T | null): T | null {
  if (raw == null || utf8ByteLength(raw) > TAB_STATE_MAX_BYTES) return null
  try {
    return parse(JSON.parse(raw) as unknown)
  } catch {
    return null
  }
}

export function serializeTabSnapshot<T>(value: unknown, parse: (value: unknown) => T | null): string | null {
  const raw = JSON.stringify(value)
  return parseTabSnapshot(raw, parse) == null ? null : raw
}

export function readTabSnapshot<T>(
  storage: TabStorageArea | null, key: string, parse: (value: unknown) => T | null,
): T | null {
  if (storage == null) return null
  try {
    return parseTabSnapshot(storage.getItem(key), parse)
  } catch {
    return null
  }
}

export function writeTabSnapshot(storage: TabStorageArea | null, key: string, value: string | null): boolean {
  if (storage == null || value == null) return false
  try {
    storage.setItem(key, value)
    return true
  } catch {
    return false
  }
}

export function loadTabSnapshot<T>(
  storage: ReturnType<typeof resolveTabStorage>,
  sessionKey: string,
  localKey: string,
  parseSession: (value: unknown) => T | null,
  parseLocal: (value: unknown) => T | null,
): { value: T | null; source: 'session' | 'local' | 'default' } {
  const session = readTabSnapshot(storage.session, sessionKey, parseSession)
  if (session != null) return { value: session, source: 'session' }
  const local = readTabSnapshot(storage.local, localKey, parseLocal)
  return local != null ? { value: local, source: 'local' } : { value: null, source: 'default' }
}
