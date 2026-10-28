import { CANONICAL_UUID_PATTERN } from '@/shared/lib/uuid'

/** 每个浏览器 tab 唯一一份 IDE 呈现状态；新 tab 可继承同 scope 的最后状态。 */
export interface IdePresentation {
  panelVisible: boolean
  selectedEnvironmentId: string | null
  openedEnvironmentIds: string[]
}

/** 只存在 sessionStorage 的运行身份；epoch、claimId、iframe 与票据永不持久化。 */
export interface IdeRecovery {
  viewId: string
  daemonInstanceId: string
  directory: string
}

export interface IdeTabSnapshot {
  version: 1
  presentation: IdePresentation
  recoveryByEnvironment: Record<string, IdeRecovery>
}

export interface IdeLastSnapshot {
  version: 1
  presentation: IdePresentation
}

export type IdeTabStorageArea = Pick<Storage, 'getItem' | 'setItem'>

export interface IdeTabStorage {
  sessionStorage?: IdeTabStorageArea | null
  localStorage?: IdeTabStorageArea | null
}

export type IdeSnapshotSource = 'session' | 'local' | 'default'

export interface IdeTabLoadResult {
  presentation: IdePresentation
  recoveryByEnvironment: Record<string, IdeRecovery>
  source: IdeSnapshotSource
  /** 初始化后本 tab 的 sessionStorage 是否确实可写。 */
  sessionSaved: boolean
}

export interface IdeSaveResult {
  sessionSaved: boolean
  localSaved: boolean
}

export const IDE_TAB_STATE_VERSION = 1 as const
export const IDE_TAB_STATE_MAX_BYTES = 64 * 1024
export const IDE_TAB_SESSION_KEY = 'kk-studio.tab-state.v1:ide'
export const IDE_TAB_LAST_KEY = 'kk-studio.last-state.v1:ide'
export const IDE_MAX_OPEN_ENVIRONMENTS = 64
export const IDE_MAX_DIRECTORY_CHARS = 4096

function hasControlCharacters(value: string): boolean {
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0
    if (codePoint <= 0x1f || codePoint === 0x7f) {
      return true
    }
  }
  return false
}

export function createDefaultIdePresentation(): IdePresentation {
  return {
    panelVisible: false,
    selectedEnvironmentId: null,
    openedEnvironmentIds: [],
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function hasExactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const keys = Object.keys(value)
  return keys.length === expected.length && keys.every((key) => expected.includes(key))
}

function isCanonicalUuid(value: unknown): value is string {
  return typeof value === 'string' && CANONICAL_UUID_PATTERN.test(value)
}

function parsePresentation(value: unknown): IdePresentation | null {
  if (
    !isRecord(value)
    || !hasExactKeys(value, ['panelVisible', 'selectedEnvironmentId', 'openedEnvironmentIds'])
    || typeof value.panelVisible !== 'boolean'
    || !Array.isArray(value.openedEnvironmentIds)
    || value.openedEnvironmentIds.length > IDE_MAX_OPEN_ENVIRONMENTS
  ) {
    return null
  }
  const opened: string[] = []
  const seen = new Set<string>()
  for (const environmentId of value.openedEnvironmentIds) {
    if (!isCanonicalUuid(environmentId) || seen.has(environmentId)) {
      return null
    }
    seen.add(environmentId)
    opened.push(environmentId)
  }
  const selected = value.selectedEnvironmentId
  if (selected !== null && !isCanonicalUuid(selected)) {
    return null
  }
  if (selected !== null && !seen.has(selected)) {
    return null
  }
  return {
    panelVisible: value.panelVisible,
    selectedEnvironmentId: selected,
    openedEnvironmentIds: opened,
  }
}

function parseRecovery(value: unknown): IdeRecovery | null {
  if (
    !isRecord(value)
    || !hasExactKeys(value, ['viewId', 'daemonInstanceId', 'directory'])
    || !isCanonicalUuid(value.viewId)
    || !isCanonicalUuid(value.daemonInstanceId)
    || typeof value.directory !== 'string'
    || value.directory.length === 0
    || value.directory.length > IDE_MAX_DIRECTORY_CHARS
    || hasControlCharacters(value.directory)
  ) {
    return null
  }
  return {
    viewId: value.viewId,
    daemonInstanceId: value.daemonInstanceId,
    directory: value.directory,
  }
}

function parseRecoveryMap(
  value: unknown,
  presentation: IdePresentation,
): Record<string, IdeRecovery> | null {
  if (!isRecord(value)) {
    return null
  }
  const opened = new Set(presentation.openedEnvironmentIds)
  const recovery: Record<string, IdeRecovery> = {}
  for (const [environmentId, raw] of Object.entries(value)) {
    if (!isCanonicalUuid(environmentId) || !opened.has(environmentId)) {
      return null
    }
    const parsed = parseRecovery(raw)
    if (parsed == null) {
      return null
    }
    recovery[environmentId] = parsed
  }
  return recovery
}

function utf8ByteLength(value: string): number {
  return new TextEncoder().encode(value).length
}

function parseSnapshot(
  raw: string | null,
  kind: 'session' | 'local',
): { presentation: IdePresentation; recoveryByEnvironment: Record<string, IdeRecovery> } | null {
  if (raw == null || utf8ByteLength(raw) > IDE_TAB_STATE_MAX_BYTES) {
    return null
  }
  try {
    const value: unknown = JSON.parse(raw)
    if (!isRecord(value) || value.version !== IDE_TAB_STATE_VERSION) {
      return null
    }
    if (kind === 'local') {
      if (!hasExactKeys(value, ['version', 'presentation'])) {
        return null
      }
      const presentation = parsePresentation(value.presentation)
      return presentation == null ? null : { presentation, recoveryByEnvironment: {} }
    }
    if (!hasExactKeys(value, ['version', 'presentation', 'recoveryByEnvironment'])) {
      return null
    }
    const presentation = parsePresentation(value.presentation)
    if (presentation == null) {
      return null
    }
    const recoveryByEnvironment = parseRecoveryMap(value.recoveryByEnvironment, presentation)
    return recoveryByEnvironment == null ? null : { presentation, recoveryByEnvironment }
  } catch {
    return null
  }
}

function serializeSession(
  presentation: IdePresentation,
  recoveryByEnvironment: Record<string, IdeRecovery>,
): string | null {
  const snapshot: IdeTabSnapshot = {
    version: IDE_TAB_STATE_VERSION,
    presentation,
    recoveryByEnvironment,
  }
  const serialized = JSON.stringify(snapshot)
  if (
    parseSnapshot(serialized, 'session') == null
    || utf8ByteLength(serialized) > IDE_TAB_STATE_MAX_BYTES
  ) {
    return null
  }
  return serialized
}

function serializeLocal(presentation: IdePresentation): string | null {
  const snapshot: IdeLastSnapshot = {
    version: IDE_TAB_STATE_VERSION,
    presentation,
  }
  const serialized = JSON.stringify(snapshot)
  if (
    parseSnapshot(serialized, 'local') == null
    || utf8ByteLength(serialized) > IDE_TAB_STATE_MAX_BYTES
  ) {
    return null
  }
  return serialized
}

function resolveGlobalStorage(kind: 'sessionStorage' | 'localStorage'): IdeTabStorageArea | null {
  try {
    return globalThis[kind] ?? null
  } catch {
    return null
  }
}

function resolveStorage(storage?: IdeTabStorage): {
  session: IdeTabStorageArea | null
  local: IdeTabStorageArea | null
} {
  const session = storage?.sessionStorage === undefined
    ? resolveGlobalStorage('sessionStorage')
    : storage.sessionStorage
  const local = storage?.localStorage === undefined
    ? resolveGlobalStorage('localStorage')
    : storage.localStorage
  return { session: session ?? null, local: local ?? null }
}

function readSnapshot(
  storage: IdeTabStorageArea | null,
  key: string,
  kind: 'session' | 'local',
): { presentation: IdePresentation; recoveryByEnvironment: Record<string, IdeRecovery> } | null {
  if (storage == null) {
    return null
  }
  try {
    return parseSnapshot(storage.getItem(key), kind)
  } catch {
    return null
  }
}

function writeSnapshot(storage: IdeTabStorageArea | null, key: string, value: string | null): boolean {
  if (storage == null || value == null) {
    return false
  }
  try {
    storage.setItem(key, value)
    return true
  } catch {
    return false
  }
}

/**
 * 恢复顺序：本 tab 的 sessionStorage、最后一次展示状态、产品默认值。
 *
 * <p>初始化只把选定快照写回 sessionStorage；localStorage 只作为输入读取，绝不在加载、
 * 刷新恢复或无效引用清理时被覆盖。没有有效 session 时，local 只提供展示意图，不提供
 * viewId、epoch 或任何运行身份。
 */
export function initializeIdeTabState(storage?: IdeTabStorage): IdeTabLoadResult {
  const { session, local } = resolveStorage(storage)
  const sessionSnapshot = readSnapshot(session, IDE_TAB_SESSION_KEY, 'session')
  const localSnapshot = sessionSnapshot == null
    ? readSnapshot(local, IDE_TAB_LAST_KEY, 'local')
    : null
  const source: IdeSnapshotSource = sessionSnapshot != null
    ? 'session'
    : localSnapshot != null ? 'local' : 'default'
  const presentation = sessionSnapshot?.presentation
    ?? localSnapshot?.presentation
    ?? createDefaultIdePresentation()
  const recoveryByEnvironment = sessionSnapshot?.recoveryByEnvironment ?? {}
  const sessionSaved = writeSnapshot(
    session,
    IDE_TAB_SESSION_KEY,
    serializeSession(presentation, recoveryByEnvironment),
  )
  return { presentation, recoveryByEnvironment, source, sessionSaved }
}

/** 用户显式操作：完整本 tab 快照写 session，剔除运行身份后的快照写 local。 */
export function saveIdePresentation(
  presentation: IdePresentation,
  recoveryByEnvironment: Record<string, IdeRecovery>,
  storage?: IdeTabStorage,
): IdeSaveResult {
  const { session, local } = resolveStorage(storage)
  return {
    sessionSaved: writeSnapshot(
      session,
      IDE_TAB_SESSION_KEY,
      serializeSession(presentation, recoveryByEnvironment),
    ),
    localSaved: writeSnapshot(local, IDE_TAB_LAST_KEY, serializeLocal(presentation)),
  }
}

/** 异步 claim 成功只补写 session 的 viewId 恢复索引，不伪造新的用户操作。 */
export function saveIdeSession(
  presentation: IdePresentation,
  recoveryByEnvironment: Record<string, IdeRecovery>,
  storage?: IdeTabStorage,
): boolean {
  const { session } = resolveStorage(storage)
  return writeSnapshot(
    session,
    IDE_TAB_SESSION_KEY,
    serializeSession(presentation, recoveryByEnvironment),
  )
}
