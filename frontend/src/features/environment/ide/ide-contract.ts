import type {
  EnvironmentIdeOpenViewDTO,
  EnvironmentIdeViewDTO,
  EnvironmentIdeViewSnapshotDTO,
} from '@/shared/api/generated/contracts'
import type { ApplicationEventConnectionIdentity } from '@/shared/app-events/protocol'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { validateIdeEntryUrl } from '@/features/environment/ide/ide-url'
import { isIdeDirectory, type IdePendingClaim } from '@/features/environment/ide/ide-tab-state'

export const CANONICAL_NON_NEGATIVE_DECIMAL = /^(0|[1-9][0-9]*)$/
export const CANONICAL_POSITIVE_DECIMAL = /^[1-9][0-9]*$/

/** 前端解析到的 IDE 契约错误；与网络结果未知严格区分，绝不触发自动重试。 */
export class IdeProtocolError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'IdeProtocolError'
  }
}

export function isIdeProtocolError(error: unknown): error is IdeProtocolError {
  return error instanceof IdeProtocolError
}

/** 任意十进制字符串比较；revision 允许 gap，不要求 +1。 */
export function compareDecimal(left: string, right: string): number {
  if (left.length !== right.length) {
    return left.length < right.length ? -1 : 1
  }
  return left < right ? -1 : left > right ? 1 : 0
}

/**
 * 确定性 canonical JSON：对象键排序，数组保持顺序。仅用于同 revision 内容比较，
 * 不引入 hash 或摘要抽象；字符串内的分隔符不会造成指纹碰撞。
 */
export function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return JSON.stringify(value) ?? 'undefined'
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalJson(item)).join(',')}]`
  }
  const record = value as Record<string, unknown>
  const entries = Object.keys(record)
    .filter((key) => record[key] !== undefined)
    .sort()
    .map((key) => `${JSON.stringify(key)}:${canonicalJson(record[key])}`)
  return `{${entries.join(',')}}`
}

/** 同一 view 在同一 daemonInstance 内的内容指纹；不存在用稳定字面量。 */
export function viewFingerprint(view: EnvironmentIdeViewDTO | null): string {
  return canonicalJson(view)
}

export interface IdeSnapshotView {
  daemonInstanceId: string
  revision: string
  view: EnvironmentIdeViewDTO | null
}

function requireSnapshotView(
  environmentId: string,
  viewId: string,
  browserSessionId: string,
  snapshot: EnvironmentIdeViewSnapshotDTO,
): IdeSnapshotView {
  if (
    snapshot == null
    || typeof snapshot !== 'object'
    || !isCanonicalUuid(snapshot.daemonInstanceId)
    || typeof snapshot.revision !== 'string'
    || !CANONICAL_NON_NEGATIVE_DECIMAL.test(snapshot.revision)
    || snapshot.view === undefined
    || (snapshot.view !== null && typeof snapshot.view !== 'object')
  ) {
    throw new IdeProtocolError('Invalid IDE view snapshot')
  }
  if (snapshot.view == null) {
    return {
      daemonInstanceId: snapshot.daemonInstanceId,
      revision: snapshot.revision,
      view: null,
    }
  }
  const view = snapshot.view
  if (
    view.viewId !== viewId
    || view.environmentId !== environmentId
    || view.browserSessionId !== browserSessionId
    || view.daemonInstanceId !== snapshot.daemonInstanceId
    || !isCanonicalUuid(view.viewId)
    || !isCanonicalUuid(view.environmentId)
    || !isCanonicalUuid(view.browserSessionId)
    || !isCanonicalUuid(view.eventNodeId)
    || !isCanonicalUuid(view.connectionId)
    || !isCanonicalUuid(view.claimId)
    || typeof view.bindingEpoch !== 'string'
    || !CANONICAL_POSITIVE_DECIMAL.test(view.bindingEpoch)
    || compareDecimal(view.bindingEpoch, snapshot.revision) > 0
    || !isIdeDirectory(view.directory)
    || typeof view.workspacePath !== 'string'
    || view.workspacePath.length === 0
    || (view.state !== 'OPEN' && view.state !== 'CLOSING')
    // 纯读不生成票据；快照出现 entryUrl 即契约错误，不能拿来 bootstrap。
    || view.entryUrl != null
  ) {
    throw new IdeProtocolError('Invalid IDE view snapshot')
  }
  return {
    daemonInstanceId: snapshot.daemonInstanceId,
    revision: snapshot.revision,
    view,
  }
}

export function validateViewSnapshot(
  environmentId: string,
  viewId: string,
  browserSessionId: string,
  snapshot: EnvironmentIdeViewSnapshotDTO,
): IdeSnapshotView {
  if (!isCanonicalUuid(environmentId) || !isCanonicalUuid(viewId) || !isCanonicalUuid(browserSessionId)) {
    throw new IdeProtocolError('Invalid IDE view identity')
  }
  return requireSnapshotView(environmentId, viewId, browserSessionId, snapshot)
}

export interface IdeBindingResult {
  viewId: string
  daemonInstanceId: string
  bindingEpoch: string
  directory: string
  workspacePath: string
  entryUrl: string
}

export function validateOpenResponse(
  environmentId: string,
  request: EnvironmentIdeOpenViewDTO,
  response: EnvironmentIdeViewDTO,
): IdeBindingResult {
  if (
    response == null
    || typeof response !== 'object'
    || response.viewId !== request.viewId
    || response.environmentId !== environmentId
    || response.browserSessionId !== request.browserSessionId
    || response.eventNodeId !== request.eventNodeId
    || response.connectionId !== request.connectionId
    || response.claimId !== request.claimId
    || response.daemonInstanceId !== request.expectedDaemonInstanceId
    || !isCanonicalUuid(response.viewId)
    || !isCanonicalUuid(response.environmentId)
    || !isCanonicalUuid(response.browserSessionId)
    || !isCanonicalUuid(response.eventNodeId)
    || !isCanonicalUuid(response.connectionId)
    || !isCanonicalUuid(response.claimId)
    || !isCanonicalUuid(response.daemonInstanceId)
    || typeof response.bindingEpoch !== 'string'
    || !CANONICAL_POSITIVE_DECIMAL.test(response.bindingEpoch)
    || (request.mode === 'resume'
      && compareDecimal(response.bindingEpoch, request.expectedBindingEpoch) < 0)
    || !isIdeDirectory(response.directory)
    || typeof response.workspacePath !== 'string'
    || response.workspacePath.length === 0
    || response.state !== 'OPEN'
  ) {
    throw new IdeProtocolError('Invalid IDE binding response')
  }
  return {
    viewId: response.viewId,
    daemonInstanceId: response.daemonInstanceId,
    bindingEpoch: response.bindingEpoch,
    directory: response.directory,
    workspacePath: response.workspacePath,
    entryUrl: validateIdeEntryUrl(response.entryUrl, environmentId, request.viewId),
  }
}

export interface IdeCommandView {
  bindingEpoch: string
  daemonInstanceId: string
  directory: string
  workspacePath: string
  eventNodeId: string
  connectionId: string
}

/**
 * 命令回执必须完整携带当前绑定身份。workspacePath 缺失不是“退回 directory”的许可，
 * 而是契约错误；否则旧快照可能被误当成新绑定。
 */
export function validateCommandView(
  environmentId: string,
  browserSessionId: string,
  viewId: string,
  response: EnvironmentIdeViewDTO,
): IdeCommandView {
  if (
    response == null
    || typeof response !== 'object'
    || response.viewId !== viewId
    || response.environmentId !== environmentId
    || response.browserSessionId !== browserSessionId
    || !isCanonicalUuid(response.viewId)
    || !isCanonicalUuid(response.environmentId)
    || !isCanonicalUuid(response.browserSessionId)
    || !isCanonicalUuid(response.eventNodeId)
    || !isCanonicalUuid(response.connectionId)
    || !isCanonicalUuid(response.claimId)
    || !isCanonicalUuid(response.daemonInstanceId)
    || typeof response.bindingEpoch !== 'string'
    || !CANONICAL_POSITIVE_DECIMAL.test(response.bindingEpoch)
    || !isIdeDirectory(response.directory)
    || typeof response.workspacePath !== 'string'
    || response.workspacePath.length === 0
    || response.state !== 'OPEN'
  ) {
    throw new IdeProtocolError('Invalid IDE command response')
  }
  return {
    bindingEpoch: response.bindingEpoch,
    daemonInstanceId: response.daemonInstanceId,
    directory: response.directory,
    workspacePath: response.workspacePath,
    eventNodeId: response.eventNodeId,
    connectionId: response.connectionId,
  }
}

/** 冻结 pending 与当前连接构成唯一 OPEN wire 请求；重放不重新冻结。 */
export function buildOpenRequest(
  pending: IdePendingClaim,
  browserSessionId: string,
  identity: ApplicationEventConnectionIdentity,
): EnvironmentIdeOpenViewDTO {
  return {
    viewId: pending.viewId,
    browserSessionId,
    eventNodeId: identity.eventNodeId,
    connectionId: identity.connectionId,
    claimId: pending.claimId,
    mode: pending.mode,
    expectedBindingEpoch: pending.expectedBindingEpoch,
    expectedDaemonInstanceId: pending.expectedDaemonInstanceId,
    ...(pending.mode === 'create' && pending.directory != null
      ? { directory: pending.directory }
      : {}),
  }
}
