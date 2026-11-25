import { createUuid, isCanonicalUuid } from '@/shared/lib/uuid'
import { hasExactKeys, isRecord, utf8ByteLength } from '@/shared/lib/value'
import { serializeTabSnapshot, writeTabSnapshot, TAB_SESSION_PREFIX, TAB_STATE_MAX_BYTES, type TabStorageArea } from '@/shared/lib/tab-storage'
import type { EnvironmentIdeOpenMode } from '@/shared/api/generated/contracts'
import { CANONICAL_NON_NEGATIVE_DECIMAL, CANONICAL_POSITIVE_DECIMAL } from '@/features/environment/ide/ide-decimal'

export interface IdePendingClaim {
  mode: EnvironmentIdeOpenMode
  viewId: string
  claimId: string
  expectedBindingEpoch: string
  expectedDaemonInstanceId: string
  directory?: string | null
}
export interface IdeRecovery {
  environmentId: string
  viewId: string
  daemonInstanceId: string
  bindingEpoch?: string
  directory?: string
  pending?: IdePendingClaim
}
export interface IdeSessionStorage {
  sessionStorage?: TabStorageArea | null
}
export interface IdeSessionLoadResult {
  browserSessionId: string
  recoveryByView: Record<string, IdeRecovery>
  recoveryError: boolean
  sessionSaved: boolean
}
export const IDE_SESSION_STORAGE_KEY = `${TAB_SESSION_PREFIX}vscode`
const MAX_DIRECTORY_CHARS = 4096

export function isIdeDirectory(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= MAX_DIRECTORY_CHARS
    && ![...value].some((c) => { const n = c.codePointAt(0) ?? 0; return n <= 0x1f || n === 0x7f })
}
function parsePending(value: unknown, viewId: string): IdePendingClaim | null {
  if (!isRecord(value) || !hasExactKeys(value, ['mode', 'viewId', 'claimId', 'expectedBindingEpoch', 'expectedDaemonInstanceId', 'directory'])
    || (value.mode !== 'create' && value.mode !== 'resume') || value.viewId !== viewId
    || !isCanonicalUuid(value.claimId) || typeof value.expectedBindingEpoch !== 'string'
    || !CANONICAL_NON_NEGATIVE_DECIMAL.test(value.expectedBindingEpoch) || !isCanonicalUuid(value.expectedDaemonInstanceId)
    || (value.mode === 'create' && value.expectedBindingEpoch !== '0')
    || (value.mode === 'resume' && !CANONICAL_POSITIVE_DECIMAL.test(value.expectedBindingEpoch))
    || (value.directory !== null && !isIdeDirectory(value.directory))) return null
  return { mode: value.mode, viewId, claimId: value.claimId, expectedBindingEpoch: value.expectedBindingEpoch,
    expectedDaemonInstanceId: value.expectedDaemonInstanceId, ...(value.directory === null ? {} : { directory: value.directory }) }
}
function parseRecovery(value: unknown): IdeRecovery | null {
  if (!isRecord(value) || !hasExactKeys(value, ['environmentId', 'viewId', 'daemonInstanceId', 'bindingEpoch', 'directory', 'pending'])
    || !isCanonicalUuid(value.environmentId) || !isCanonicalUuid(value.viewId) || !isCanonicalUuid(value.daemonInstanceId)
    || (value.bindingEpoch !== null && (typeof value.bindingEpoch !== 'string' || !CANONICAL_NON_NEGATIVE_DECIMAL.test(value.bindingEpoch)))
    || (value.directory !== null && !isIdeDirectory(value.directory))) return null
  const pending = value.pending === null ? null : parsePending(value.pending, value.viewId)
  if (value.pending !== null && pending == null) return null
  return { environmentId: value.environmentId, viewId: value.viewId, daemonInstanceId: value.daemonInstanceId,
    ...(value.bindingEpoch === null ? {} : { bindingEpoch: value.bindingEpoch }), ...(value.directory === null ? {} : { directory: value.directory }), ...(pending == null ? {} : { pending }) }
}
function parseRecoveryMap(value: unknown): Record<string, IdeRecovery> | null {
  if (!isRecord(value)) return null
  const result: Record<string, IdeRecovery> = {}
  for (const [id, raw] of Object.entries(value)) { const recovery = parseRecovery(raw); if (!isCanonicalUuid(id) || recovery == null || recovery.viewId !== id) return null; result[id] = recovery }
  return result
}
interface Snapshot { browserSessionId: string; recoveryByView: Record<string, IdeRecovery> }
function parseSession(value: unknown): Snapshot | null {
  if (!isRecord(value) || !hasExactKeys(value, ['browserSessionId', 'recoveryByView']) || !isCanonicalUuid(value.browserSessionId)) return null
  const recoveryByView = parseRecoveryMap(value.recoveryByView)
  return recoveryByView == null ? null : { browserSessionId: value.browserSessionId, recoveryByView }
}
function encodeRecovery(r: IdeRecovery): Record<string, unknown> {
  return { environmentId: r.environmentId, viewId: r.viewId, daemonInstanceId: r.daemonInstanceId, bindingEpoch: r.bindingEpoch ?? null, directory: r.directory ?? null,
    pending: r.pending == null ? null : { mode: r.pending.mode, viewId: r.pending.viewId, claimId: r.pending.claimId, expectedBindingEpoch: r.pending.expectedBindingEpoch, expectedDaemonInstanceId: r.pending.expectedDaemonInstanceId, directory: r.pending.directory ?? null } }
}
function serialize(browserSessionId: string, recoveryByView: Record<string, IdeRecovery>): string | null {
  return serializeTabSnapshot({ browserSessionId, recoveryByView: Object.fromEntries(Object.entries(recoveryByView).map(([id, r]) => [id, encodeRecovery(r)])) }, parseSession)
}
function sessionStorageArea(storage?: IdeSessionStorage): TabStorageArea | null {
  try {
    return storage?.sessionStorage === undefined ? globalThis.sessionStorage ?? null : storage.sessionStorage
  } catch {
    return null
  }
}

export function initializeIdeSession(storage?: IdeSessionStorage): IdeSessionLoadResult {
  const area = sessionStorageArea(storage)
  const browserSessionId = createUuid()
  const empty = { browserSessionId, recoveryByView: {}, recoveryError: false, sessionSaved: false }
  let raw: string | null
  try {
    raw = area?.getItem(IDE_SESSION_STORAGE_KEY) ?? null
  } catch {
    return empty
  }
  if (raw == null) return { ...empty, sessionSaved: saveIdeSession(browserSessionId, {}, storage) }
  // Do not erase unreadable recovery data or start claims under a replacement identity.
  try {
    const snapshot = utf8ByteLength(raw) <= TAB_STATE_MAX_BYTES ? parseSession(JSON.parse(raw)) : null
    if (snapshot != null) {
      return { ...snapshot, recoveryError: false, sessionSaved: saveIdeSession(snapshot.browserSessionId, snapshot.recoveryByView, storage) }
    }
  } catch {
    // Leave the stored snapshot intact.
  }
  return { ...empty, recoveryError: true }
}

export function saveIdeSession(browserSessionId: string, recoveryByView: Record<string, IdeRecovery>, storage?: IdeSessionStorage): boolean {
  return writeTabSnapshot(sessionStorageArea(storage), IDE_SESSION_STORAGE_KEY, serialize(browserSessionId, recoveryByView))
}
