import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import { hasExactKeys, isRecord, utf8ByteLength } from '@/shared/lib/value'
import {
  loadTabSnapshot, resolveTabStorage, serializeTabSnapshot, writeTabSnapshot,
  TAB_LAST_PREFIX, TAB_SESSION_PREFIX, TAB_STATE_MAX_BYTES,
  type TabStorage,
} from '@/shared/lib/tab-storage'
import type { EnvironmentIdeOpenMode } from '@/shared/api/generated/contracts'
import {
  CANONICAL_NON_NEGATIVE_DECIMAL,
  CANONICAL_POSITIVE_DECIMAL,
} from '@/features/environment/ide/ide-decimal'
/** 每个浏览器 tab 唯一一份 IDE 呈现状态；local 只保存非运行态展示偏好。 */
export interface IdePresentation {
  panelVisible: boolean
  selectedEnvironmentId: string | null
  openedEnvironmentIds: string[]
  viewIdsByEnvironment: Record<string, string[]>
  selectedViewId: string | null
}

/** claim 发出前冻结的恢复描述；entryUrl、cookie 和票据永不进入这里。 */
export interface IdePendingClaim {
  mode: EnvironmentIdeOpenMode
  viewId: string
  claimId: string
  /** create 固定 '0'；resume 为已确认的正 epoch。 */
  expectedBindingEpoch: string
  expectedDaemonInstanceId: string
  directory?: string | null
}

/** 只存在 sessionStorage 的运行身份；刷新复用，复制 tab 由 Web Locks 隔离。 */
export interface IdeRecovery {
  environmentId: string
  viewId: string
  daemonInstanceId: string
  /** 已确认的绑定 epoch；首次 create 发现资源后仍未绑定时缺省。 */
  bindingEpoch?: string
  directory?: string
  pending?: IdePendingClaim
}

interface IdeTabSnapshot {
  browserSessionId: string
  presentation: IdePresentation
  recoveryByView: Record<string, IdeRecovery>
  recoveryError?: boolean
}

interface IdeLastSnapshot {
  presentation: IdePresentation
}

export type IdeTabStorage = TabStorage

type IdeSnapshotSource = 'session' | 'local' | 'default'

export interface IdeTabLoadResult {
  browserSessionId: string
  presentation: IdePresentation
  recoveryByView: Record<string, IdeRecovery>
  recoveryError: boolean
  source: IdeSnapshotSource
  /** 初始化后本 tab 的 sessionStorage 是否确实可写。 */
  sessionSaved: boolean
}

export interface IdeSaveResult {
  sessionSaved: boolean
  localSaved: boolean
}

export const IDE_TAB_SESSION_KEY = `${TAB_SESSION_PREFIX}ide`
export const IDE_TAB_LAST_KEY = `${TAB_LAST_PREFIX}ide`
export const IDE_RECENT_DIRECTORIES_KEY = 'kk-studio.recent-directories:ide'
export const IDE_MAX_OPEN_ENVIRONMENTS = 64
const IDE_MAX_DIRECTORY_CHARS = 4096
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

export function isIdeDirectory(value: unknown): value is string {
  return (
    typeof value === 'string'
    && value.length > 0
    && value.length <= IDE_MAX_DIRECTORY_CHARS
    && !hasControlCharacters(value)
  )
}

export function createDefaultIdePresentation(): IdePresentation {
  return {
    panelVisible: false,
    selectedEnvironmentId: null,
    openedEnvironmentIds: [],
    viewIdsByEnvironment: {},
    selectedViewId: null,
  }
}

function parsePresentation(value: unknown): IdePresentation | null {
  if (
    !isRecord(value)
    || !hasExactKeys(value, ['panelVisible', 'selectedEnvironmentId', 'openedEnvironmentIds', 'viewIdsByEnvironment', 'selectedViewId'])
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
  if (!isRecord(value.viewIdsByEnvironment)) {
    return null
  }
  const viewIdsByEnvironment: Record<string, string[]> = {}
  const seenViewIds = new Set<string>()
  for (const [environmentId, rawViewIds] of Object.entries(value.viewIdsByEnvironment)) {
    if (!seen.has(environmentId) || !Array.isArray(rawViewIds) || rawViewIds.length === 0) {
      return null
    }
    const viewIds: string[] = []
    for (const viewId of rawViewIds) {
      if (!isCanonicalUuid(viewId) || seenViewIds.has(viewId)) {
        return null
      }
      seenViewIds.add(viewId)
      viewIds.push(viewId)
    }
    viewIdsByEnvironment[environmentId] = viewIds
  }
  const selected = value.selectedEnvironmentId
  if (selected !== null && !isCanonicalUuid(selected)) {
    return null
  }
  const selectedViewId = value.selectedViewId
  if (selectedViewId !== null && !isCanonicalUuid(selectedViewId)) {
    return null
  }
  if ((selected === null && selectedViewId !== null)
    || (selected !== null && selectedViewId !== null
      && !viewIdsByEnvironment[selected]?.includes(selectedViewId))) {
    return null
  }
  return {
    panelVisible: value.panelVisible,
    selectedEnvironmentId: selected,
    openedEnvironmentIds: opened,
    viewIdsByEnvironment,
    selectedViewId,
  }
}

function parsePending(value: unknown, viewId: string): IdePendingClaim | null {
  if (
    !isRecord(value)
    || !hasExactKeys(value, [
      'mode', 'viewId', 'claimId', 'expectedBindingEpoch',
      'expectedDaemonInstanceId', 'directory',
    ])
    || (value.mode !== 'create' && value.mode !== 'resume')
    || value.viewId !== viewId
    || !isCanonicalUuid(value.claimId)
    || typeof value.expectedBindingEpoch !== 'string'
    || !CANONICAL_NON_NEGATIVE_DECIMAL.test(value.expectedBindingEpoch)
    || !isCanonicalUuid(value.expectedDaemonInstanceId)
  ) {
    return null
  }
  if (value.mode === 'create' && value.expectedBindingEpoch !== '0') {
    return null
  }
  if (value.mode === 'resume' && !CANONICAL_POSITIVE_DECIMAL.test(value.expectedBindingEpoch)) {
    return null
  }
  if (value.directory !== null && !isIdeDirectory(value.directory)) {
    return null
  }
  return {
    mode: value.mode,
    viewId,
    claimId: value.claimId,
    expectedBindingEpoch: value.expectedBindingEpoch,
    expectedDaemonInstanceId: value.expectedDaemonInstanceId,
    ...(value.directory === null ? {} : { directory: value.directory }),
  }
}

function parseRecovery(value: unknown): IdeRecovery | null {
  if (
    !isRecord(value)
    || !hasExactKeys(value, [
      'environmentId', 'viewId', 'daemonInstanceId', 'bindingEpoch', 'directory', 'pending',
    ])
    || !isCanonicalUuid(value.environmentId)
    || !isCanonicalUuid(value.viewId)
    || !isCanonicalUuid(value.daemonInstanceId)
  ) {
    return null
  }
  if (
    value.bindingEpoch !== null
    && (typeof value.bindingEpoch !== 'string'
      || !CANONICAL_NON_NEGATIVE_DECIMAL.test(value.bindingEpoch))
  ) {
    return null
  }
  if (value.directory !== null && !isIdeDirectory(value.directory)) {
    return null
  }
  const pending = value.pending === null ? null : parsePending(value.pending, value.viewId)
  if (value.pending !== null && pending == null) {
    return null
  }
  return {
    environmentId: value.environmentId,
    viewId: value.viewId,
    daemonInstanceId: value.daemonInstanceId,
    ...(value.bindingEpoch === null ? {} : { bindingEpoch: value.bindingEpoch }),
    ...(value.directory === null ? {} : { directory: value.directory }),
    ...(pending == null ? {} : { pending }),
  }
}

function parseRecoveryMap(value: unknown): Record<string, IdeRecovery> | null {
  if (!isRecord(value)) {
    return null
  }
  const recovery: Record<string, IdeRecovery> = {}
  for (const [viewId, raw] of Object.entries(value)) {
    const parsed = parseRecovery(raw)
    if (!isCanonicalUuid(viewId) || parsed == null || parsed.viewId !== viewId) {
      return null
    }
    recovery[viewId] = parsed
  }
  return recovery
}

function migrationFailure(browserSessionId: string): IdeTabSnapshot {
  return {
    browserSessionId,
    presentation: { ...createDefaultIdePresentation(), panelVisible: true },
    recoveryByView: {},
    recoveryError: true,
  }
}

function parseSession(value: unknown): IdeTabSnapshot | null {
  if (!isRecord(value)) {
    return null
  }
  const isLegacy = hasExactKeys(value, ['browserSessionId', 'presentation', 'recoveryByEnvironment'])
  if (!isCanonicalUuid(value.browserSessionId)) {
    return isLegacy ? migrationFailure(createUuid()) : null
  }
  if (hasExactKeys(value, ['browserSessionId', 'presentation', 'recoveryByView'])) {
    const presentation = parsePresentation(value.presentation)
    const recoveryByView = parseRecoveryMap(value.recoveryByView)
    if (presentation == null || recoveryByView == null) {
      return null
    }
    for (const recovery of Object.values(recoveryByView)) {
      if (!presentation.viewIdsByEnvironment[recovery.environmentId]?.includes(recovery.viewId)) {
        return null
      }
    }
    return { browserSessionId: value.browserSessionId, presentation, recoveryByView }
  }
  if (!isLegacy) {
    return null
  }
  const legacyPresentation = parseLegacyPresentation(value.presentation)
  if (legacyPresentation == null || !isRecord(value.recoveryByEnvironment)) {
    return migrationFailure(value.browserSessionId)
  }
  if (Object.keys(value.recoveryByEnvironment).some(
    (environmentId) => !legacyPresentation.openedEnvironmentIds.includes(environmentId),
  )) {
    return migrationFailure(value.browserSessionId)
  }
  const recoveryByView: Record<string, IdeRecovery> = {}
  for (const environmentId of legacyPresentation.openedEnvironmentIds) {
    const raw = value.recoveryByEnvironment[environmentId]
    const recovery = isRecord(raw) ? parseRecovery({ ...raw, environmentId }) : null
    if (recovery == null || recoveryByView[recovery.viewId] != null) {
      return migrationFailure(value.browserSessionId)
    }
    recoveryByView[recovery.viewId] = recovery
    legacyPresentation.viewIdsByEnvironment[environmentId] = [recovery.viewId]
  }
  legacyPresentation.selectedViewId = legacyPresentation.selectedEnvironmentId == null
    ? null
    : legacyPresentation.viewIdsByEnvironment[legacyPresentation.selectedEnvironmentId]?.[0] ?? null
  return { browserSessionId: value.browserSessionId, presentation: legacyPresentation, recoveryByView }
}

function parseLegacyPresentation(value: unknown): IdePresentation | null {
  if (!isRecord(value) || !hasExactKeys(value, ['panelVisible', 'selectedEnvironmentId', 'openedEnvironmentIds'])) {
    return null
  }
  const opened = value.openedEnvironmentIds
  if (typeof value.panelVisible !== 'boolean' || !Array.isArray(opened)
    || opened.some((id) => !isCanonicalUuid(id))
    || new Set(opened).size !== opened.length) {
    return null
  }
  const selected = value.selectedEnvironmentId
  if (selected !== null && (!isCanonicalUuid(selected) || !opened.includes(selected))) {
    return null
  }
  return {
    panelVisible: value.panelVisible,
    selectedEnvironmentId: selected,
    openedEnvironmentIds: opened,
    viewIdsByEnvironment: {},
    selectedViewId: null,
  }
}

function parseLocal(value: unknown): IdeLastSnapshot | null {
  if (!isRecord(value) || !hasExactKeys(value, ['presentation'])) {
    return null
  }
  const presentation = parsePresentation(value.presentation) ?? parseLegacyPresentation(value.presentation)
  return presentation == null ? null : { presentation }
}

function encodePending(pending: IdePendingClaim): Record<string, unknown> {
  return {
    mode: pending.mode,
    viewId: pending.viewId,
    claimId: pending.claimId,
    expectedBindingEpoch: pending.expectedBindingEpoch,
    expectedDaemonInstanceId: pending.expectedDaemonInstanceId,
    directory: pending.directory ?? null,
  }
}

function encodeRecovery(recovery: IdeRecovery): Record<string, unknown> {
  return {
    environmentId: recovery.environmentId,
    viewId: recovery.viewId,
    daemonInstanceId: recovery.daemonInstanceId,
    bindingEpoch: recovery.bindingEpoch ?? null,
    directory: recovery.directory ?? null,
    pending: recovery.pending == null ? null : encodePending(recovery.pending),
  }
}

function serializeSession(
  browserSessionId: string,
  presentation: IdePresentation,
  recoveryByView: Record<string, IdeRecovery>,
): string | null {
  const snapshot = {
    browserSessionId,
    presentation,
    recoveryByView: Object.fromEntries(
      Object.entries(recoveryByView).map(([viewId, recovery]) => [
        viewId,
        encodeRecovery(recovery),
      ]),
    ),
  }
  return serializeTabSnapshot(snapshot, parseSession)
}

function serializeLocal(presentation: IdePresentation): string | null {
  const snapshot: IdeLastSnapshot = {
    presentation: {
      ...presentation,
      viewIdsByEnvironment: {},
      selectedViewId: null,
    },
  }
  return serializeTabSnapshot(snapshot, parseLocal)
}

/**
 * 恢复顺序：本 tab 的 sessionStorage、最后一次展示状态、产品默认值。
 *
 * <p>初始化只把选定快照写回 sessionStorage；localStorage 只作为输入读取，绝不在加载、
 * 刷新恢复或无效引用清理时被覆盖。没有有效 session 时，local 只提供展示意图，不提供
 * browserSessionId、viewId、epoch 或任何运行身份。browserSessionId 在首次初始化时生成，
 * 刷新复用；复制 tab 的隔离由 Web Locks 决定，不由本地时间或心跳猜测。
 */
export function initializeIdeTabState(storage?: IdeTabStorage): IdeTabLoadResult {
  const stores = resolveTabStorage(storage)
  const { value, source } = loadTabSnapshot<IdeTabSnapshot | IdeLastSnapshot>(
    stores, IDE_TAB_SESSION_KEY, IDE_TAB_LAST_KEY, parseSession, parseLocal,
  )
  const browserSessionId = value != null && 'browserSessionId' in value
    ? value.browserSessionId
    : createUuid()
  const presentation = value?.presentation ?? createDefaultIdePresentation()
  const recoveryByView = value != null && 'recoveryByView' in value
    ? value.recoveryByView
    : {}
  const recoveryError = value != null && 'recoveryError' in value && value.recoveryError === true
  const sessionSaved = recoveryError ? false : writeTabSnapshot(
    stores.session,
    IDE_TAB_SESSION_KEY,
    serializeSession(browserSessionId, presentation, recoveryByView),
  )
  return { browserSessionId, presentation, recoveryByView, recoveryError, source, sessionSaved }
}

/** 用户显式操作：完整本 tab 快照写 session，剔除运行身份后的快照写 local。 */
export function saveIdePresentation(
  browserSessionId: string,
  presentation: IdePresentation,
  recoveryByView: Record<string, IdeRecovery>,
  storage?: IdeTabStorage,
): IdeSaveResult {
  const { session, local } = resolveTabStorage(storage)
  return {
    sessionSaved: writeTabSnapshot(
      session,
      IDE_TAB_SESSION_KEY,
      serializeSession(browserSessionId, presentation, recoveryByView),
    ),
    localSaved: writeTabSnapshot(local, IDE_TAB_LAST_KEY, serializeLocal(presentation)),
  }
}

/** 异步恢复只补写 session 的浏览器会话与恢复索引，不伪造新的用户操作。 */
export function saveIdeSession(
  browserSessionId: string,
  presentation: IdePresentation,
  recoveryByView: Record<string, IdeRecovery>,
  storage?: IdeTabStorage,
): boolean {
  const { session } = resolveTabStorage(storage)
  return writeTabSnapshot(
    session,
    IDE_TAB_SESSION_KEY,
    serializeSession(browserSessionId, presentation, recoveryByView),
  )
}

function parseRecentDirectories(raw: string | null): Record<string, string[]> {
  if (raw == null || utf8ByteLength(raw) > TAB_STATE_MAX_BYTES) {
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
        if (!isIdeDirectory(entry) || directories.includes(entry)) {
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
  if (!isCanonicalUuid(environmentId) || !isIdeDirectory(directory)) {
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
