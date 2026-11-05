import { isCanonicalUuid } from '@/shared/lib/uuid'
import { hasExactKeys, isRecord, utf8ByteLength } from '@/shared/lib/value'
import {
  loadTabSnapshot, resolveTabStorage, serializeTabSnapshot, writeTabSnapshot,
  TAB_LAST_PREFIX, TAB_SESSION_PREFIX, TAB_STATE_MAX_BYTES,
  type TabStorage, type TabStorageArea,
} from '@/shared/lib/tab-storage'

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
  presentation: IdePresentation
  recoveryByEnvironment: Record<string, IdeRecovery>
}

export interface IdeLastSnapshot {
  presentation: IdePresentation
}

export type IdeTabStorageArea = TabStorageArea
export type IdeTabStorage = TabStorage

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

export const IDE_TAB_STATE_MAX_BYTES = TAB_STATE_MAX_BYTES
export const IDE_TAB_SESSION_KEY = `${TAB_SESSION_PREFIX}ide`
export const IDE_TAB_LAST_KEY = `${TAB_LAST_PREFIX}ide`
export const IDE_RECENT_DIRECTORIES_KEY = 'kk-studio.recent-directories:ide'
export const IDE_MAX_OPEN_ENVIRONMENTS = 64
export const IDE_MAX_DIRECTORY_CHARS = 4096
export const IDE_MAX_RECENT_DIRECTORIES = 8

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

function parseSnapshot(
  value: unknown,
  kind: 'session' | 'local',
): { presentation: IdePresentation; recoveryByEnvironment: Record<string, IdeRecovery> } | null {
  if (!isRecord(value)) {
    return null
  }
  if (kind === 'local') {
    if (!hasExactKeys(value, ['presentation'])) return null
    const presentation = parsePresentation(value.presentation)
    return presentation == null ? null : { presentation, recoveryByEnvironment: {} }
  }
  if (!hasExactKeys(value, ['presentation', 'recoveryByEnvironment'])) return null
  const presentation = parsePresentation(value.presentation)
  if (presentation == null) return null
  const recoveryByEnvironment = parseRecoveryMap(value.recoveryByEnvironment, presentation)
  return recoveryByEnvironment == null ? null : { presentation, recoveryByEnvironment }
}

function serializeSession(
  presentation: IdePresentation,
  recoveryByEnvironment: Record<string, IdeRecovery>,
): string | null {
  const snapshot: IdeTabSnapshot = {
    presentation,
    recoveryByEnvironment,
  }
  return serializeTabSnapshot(snapshot, (value) => parseSnapshot(value, 'session'))
}

function serializeLocal(presentation: IdePresentation): string | null {
  const snapshot: IdeLastSnapshot = {
    presentation,
  }
  return serializeTabSnapshot(snapshot, (value) => parseSnapshot(value, 'local'))
}

/**
 * 恢复顺序：本 tab 的 sessionStorage、最后一次展示状态、产品默认值。
 *
 * <p>初始化只把选定快照写回 sessionStorage；localStorage 只作为输入读取，绝不在加载、
 * 刷新恢复或无效引用清理时被覆盖。没有有效 session 时，local 只提供展示意图，不提供
 * viewId、epoch 或任何运行身份。
 */
export function initializeIdeTabState(storage?: IdeTabStorage): IdeTabLoadResult {
  const stores = resolveTabStorage(storage)
  const { value, source } = loadTabSnapshot(
    stores, IDE_TAB_SESSION_KEY, IDE_TAB_LAST_KEY,
    (snapshot) => parseSnapshot(snapshot, 'session'),
    (snapshot) => parseSnapshot(snapshot, 'local'),
  )
  const presentation = value?.presentation ?? createDefaultIdePresentation()
  const recoveryByEnvironment = value?.recoveryByEnvironment ?? {}
  const sessionSaved = writeTabSnapshot(
    stores.session,
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
  const { session, local } = resolveTabStorage(storage)
  return {
    sessionSaved: writeTabSnapshot(
      session,
      IDE_TAB_SESSION_KEY,
      serializeSession(presentation, recoveryByEnvironment),
    ),
    localSaved: writeTabSnapshot(local, IDE_TAB_LAST_KEY, serializeLocal(presentation)),
  }
}

/** 异步 claim 成功只补写 session 的 viewId 恢复索引，不伪造新的用户操作。 */
export function saveIdeSession(
  presentation: IdePresentation,
  recoveryByEnvironment: Record<string, IdeRecovery>,
  storage?: IdeTabStorage,
): boolean {
  const { session } = resolveTabStorage(storage)
  return writeTabSnapshot(
    session,
    IDE_TAB_SESSION_KEY,
    serializeSession(presentation, recoveryByEnvironment),
  )
}

function isDirectoryPath(value: unknown): value is string {
  return (
    typeof value === 'string'
    && value.length > 0
    && value.length <= IDE_MAX_DIRECTORY_CHARS
    && !hasControlCharacters(value)
  )
}

/** 最近目录只是目录选择器的展示便利数据；损坏或越界的条目逐条丢弃，不影响视图身份。 */
function parseRecentDirectories(raw: string | null): Record<string, string[]> {
  if (raw == null || utf8ByteLength(raw) > IDE_TAB_STATE_MAX_BYTES) {
    return {}
  }
  try {
    const value: unknown = JSON.parse(raw)
    if (
      !isRecord(value)
      || !hasExactKeys(value, ['byEnvironment'])
      || !isRecord(value.byEnvironment)
    ) {
      return {}
    }
    const parsed: Record<string, string[]> = {}
    for (const [environmentId, entries] of Object.entries(value.byEnvironment)) {
      if (!isCanonicalUuid(environmentId) || !Array.isArray(entries)) {
        continue
      }
      const directories: string[] = []
      for (const entry of entries) {
        if (!isDirectoryPath(entry) || directories.includes(entry)) {
          continue
        }
        directories.push(entry)
        if (directories.length === IDE_MAX_RECENT_DIRECTORIES) {
          break
        }
      }
      if (directories.length > 0) {
        parsed[environmentId] = directories
      }
    }
    return parsed
  } catch {
    return {}
  }
}

/** 按 Environment 读取最近目录；localStorage 不可用或内容损坏时退化为空列表。 */
export function readRecentDirectories(environmentId: string, storage?: IdeTabStorage): string[] {
  if (!isCanonicalUuid(environmentId)) {
    return []
  }
  const { local } = resolveTabStorage(storage)
  if (local == null) {
    return []
  }
  try {
    return parseRecentDirectories(local.getItem(IDE_RECENT_DIRECTORIES_KEY))[environmentId] ?? []
  } catch {
    return []
  }
}

/**
 * 只在用户确认切换成功后记录：最新目录置顶、去重、按 Environment 隔离并限制条数。
 * 持久化失败仍返回内存结果，不阻断已成功的目录切换。
 */
export function recordRecentDirectory(
  environmentId: string,
  directory: string,
  storage?: IdeTabStorage,
): string[] {
  if (!isCanonicalUuid(environmentId) || !isDirectoryPath(directory)) {
    return readRecentDirectories(environmentId, storage)
  }
  const { local } = resolveTabStorage(storage)
  let byEnvironment: Record<string, string[]>
  try {
    byEnvironment = parseRecentDirectories(local?.getItem(IDE_RECENT_DIRECTORIES_KEY) ?? null)
  } catch {
    byEnvironment = {}
  }
  const directories = [directory, ...(byEnvironment[environmentId] ?? []).filter(
    (entry) => entry !== directory,
  )].slice(0, IDE_MAX_RECENT_DIRECTORIES)
  if (local != null) {
    try {
      local.setItem(
        IDE_RECENT_DIRECTORIES_KEY,
        JSON.stringify({ byEnvironment: { ...byEnvironment, [environmentId]: directories } }),
      )
    } catch {
      // 存储写满或被禁用时保留内存结果。
    }
  }
  return directories
}
