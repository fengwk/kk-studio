import { create } from '@bufbuild/protobuf'

import {
  UpdatePhase,
  UpdateResultSchema,
  type Update,
  type UpdateResult,
} from './generated/control_pb.js'
import type { UpdateByteProgress } from './runtime-status.js'
import type { UpdateOutcomeLike, UpdatePort, UpdateProgressReporter } from './runtime-ports.js'

/** 内存中保留的受管更新回执条数上界：足够覆盖少量重发与重连重放，不无界累积。 */
export const MAX_TRACKED_UPDATE_OPERATIONS = 8

/**
 * 一个已准入的更新：从 ACCEPTED 起持有到明确失败或进程退出。
 *
 * <p>PREPARED 只表示独立更新器已接受交接，因此 controller 可以结束，但准入门闩与目标快照必须继续保留。
 */
export interface ActiveUpdate {
  operationId: string
  commandKey: string
  targetVersion: string
  controller: AbortController | null
  receipt: UpdateReceipt
  prepared: boolean
}

/**
 * 一条已完成 operation 的冻结事实：回执结果，加上「确实已被独立 OS 更新器接受」时才存在的许可释放步骤。
 *
 * <p>重放与重连重发只需要这一份事实：不再准备、下载或启动，但仍幂等重试许可释放。
 */
export interface UpdateReceipt {
  commandKey: string
  result: UpdateResult
  releasePermit?: () => Promise<void>
}

export type UpdateDecision =
  | { kind: 'replay-active'; active: ActiveUpdate }
  | { kind: 'status' }
  | { kind: 'replay-cached'; receipt: UpdateReceipt }
  | { kind: 'reject'; receipt: UpdateReceipt }
  | { kind: 'admit'; active: ActiveUpdate; command: Update }

/** 幂等身份只比较命令参数；operationId 是请求关联键，不是参数的一部分。 */
export function updateCommandKey(command: Update): string {
  return JSON.stringify([command.targetVersion, command.artifactUrl, command.artifactSha256])
}

export function normalizeUpdateResult(raw: UpdateOutcomeLike, operationId: string): UpdateResult {
  const phase = raw.phase === 'FAILED' ? UpdatePhase.UPDATE_FAILED : UpdatePhase.UPDATE_PREPARED
  const message = typeof raw.message === 'string' && raw.message.trim() !== '' ? raw.message : undefined
  return create(UpdateResultSchema, { operationId, phase, message })
}

export function acceptedReceipt(command: Update): UpdateReceipt {
  return {
    commandKey: updateCommandKey(command),
    result: create(UpdateResultSchema, {
      operationId: command.operationId,
      phase: UpdatePhase.UPDATE_ACCEPTED,
    }),
  }
}

export function failedReceipt(command: Update, message: string): UpdateReceipt {
  return {
    commandKey: updateCommandKey(command),
    result: create(UpdateResultSchema, {
      operationId: command.operationId,
      phase: UpdatePhase.UPDATE_FAILED,
      message,
    }),
  }
}

export function preparedReceipt(active: ActiveUpdate, command: Update, prepared: UpdateOutcomeLike): UpdateReceipt {
  return {
    commandKey: active.commandKey,
    result: create(UpdateResultSchema, {
      operationId: command.operationId || active.operationId,
      phase: UpdatePhase.UPDATE_PREPARED,
      message: prepared.message ?? undefined,
    }),
    releasePermit: prepared.releasePermit,
  }
}

export function executionFailureReceipt(
  active: ActiveUpdate,
  command: Update,
  failure: Error | null,
): UpdateReceipt {
  return {
    commandKey: active.commandKey,
    result: create(UpdateResultSchema, {
      operationId: command.operationId,
      phase: UpdatePhase.UPDATE_FAILED,
      message: messageOf(failure ?? new Error('update failed')),
    }),
  }
}

function messageOf(error: unknown): string {
  if (error instanceof Error && error.message.trim() !== '') {
    return error.message
  }
  return 'capability execution failed'
}

/**
 * 决定一条 UPDATE 在当前门闩下做什么。
 *
 * <p>同 operation 参数漂移直接抛协议错误；同目标的新请求与已经是目标版本都只刷新状态，不重复执行。
 */
export function decideUpdate(input: {
  command: Update
  active: ActiveUpdate | null
  cached: UpdateReceipt | undefined
  currentVersion: string
  busyReason: string | null
  configured: boolean
}): UpdateDecision {
  const command = input.command
  const commandKey = updateCommandKey(command)
  const active = input.active
  if (active !== null) {
    if (active.operationId === command.operationId) {
      if (active.commandKey !== commandKey) {
        throw new Error('the same update operationId must not change its arguments')
      }
      if (active.prepared) {
        return { kind: 'replay-active', active }
      }
      return { kind: 'status' }
    }
    if (active.targetVersion === command.targetVersion) {
      return { kind: 'status' }
    }
    return { kind: 'reject', receipt: failedReceipt(command, `update is busy for target ${active.targetVersion}`) }
  }
  if (input.cached !== undefined) {
    if (input.cached.commandKey !== commandKey) {
      throw new Error('the same update operationId must not change its arguments')
    }
    return { kind: 'replay-cached', receipt: input.cached }
  }
  if (input.currentVersion === command.targetVersion) {
    return { kind: 'status' }
  }
  if (input.busyReason !== null) {
    return { kind: 'reject', receipt: failedReceipt(command, `update is busy: ${input.busyReason}`) }
  }
  if (!input.configured) {
    return { kind: 'reject', receipt: failedReceipt(command, 'managed update is not configured') }
  }
  const receipt = acceptedReceipt(command)
  return {
    kind: 'admit',
    command,
    active: {
      operationId: command.operationId ?? '',
      commandKey,
      targetVersion: command.targetVersion ?? '',
      controller: new AbortController(),
      receipt,
      prepared: false,
    },
  }
}

export function rememberUpdateResult(
  results: Map<string, UpdateReceipt>,
  operationId: string,
  receipt: UpdateReceipt,
): void {
  results.delete(operationId)
  results.set(operationId, receipt)
  while (results.size > MAX_TRACKED_UPDATE_OPERATIONS) {
    const oldest = results.keys().next()
    if (oldest.done === true) {
      break
    }
    results.delete(oldest.value)
  }
}

/** 重连后优先重发仍在门闩中的回执，否则重发最近一条已完成回执。 */
export function receiptToResend(active: ActiveUpdate | null, results: Map<string, UpdateReceipt>): UpdateReceipt | null {
  if (active !== null) {
    return active.receipt
  }
  let last: UpdateReceipt | undefined
  for (const receipt of results.values()) {
    last = receipt
  }
  return last ?? null
}

export interface UpdateExecution {
  failure: Error | null
  prepared: UpdateOutcomeLike | null
}

/**
 * 在已经建立的门闩上执行一次更新：先停 IDE，再交给更新器。
 */
export async function executeUpdateAttempt(
  active: ActiveUpdate,
  command: Update,
  update: UpdatePort,
  ide: { stopForUpdate(signal: AbortSignal): Promise<void> } | null,
  config: Parameters<UpdatePort['handleUpdate']>[1],
  reportProgress: UpdateProgressReporter,
): Promise<UpdateExecution> {
  try {
    const signal = active.controller?.signal ?? AbortSignal.abort('SHUTDOWN')
    if (ide !== null) {
      await ide.stopForUpdate(signal)
    }
    const plainCommand: Record<string, unknown> = {
      operationId: command.operationId,
      targetVersion: command.targetVersion,
      artifactUrl: command.artifactUrl,
      artifactSha256: command.artifactSha256,
    }
    const raw = await update.handleUpdate(
      plainCommand,
      config,
      signal,
      reportProgress,
    )
    if (raw.phase === 'FAILED') {
      return { failure: new Error(raw.message ?? 'managed update failed'), prepared: null }
    }
    return { failure: null, prepared: raw }
  } catch (error) {
    return { failure: error instanceof Error ? error : new Error(String(error)), prepared: null }
  }
}
