import { create } from '@bufbuild/protobuf'

import type { IdeCommand, IdeService } from './contracts.js'
import {
  IdeResultSchema,
  IdeDirectoryListingSchema,
  IdeDirectoryEntrySchema,
  IdeViewState,
  IdeViewSummarySchema,
  type IdeResult,
} from './generated/control_pb.js'
import type { IdeReply } from './contracts.js'

/** 在途 IDE 请求条数上界，防止无界 map 与资源耗尽。 */
export const MAX_IN_FLIGHT_IDE_COMMANDS = 256

export const IDE_BUSY_MESSAGE = 'too many in-flight IDE commands'

/** IDE_LEASE 轮次在下一心跳或 15 秒后失效，二者先到为准。 */
export const IDE_LEASE_ROUND_TTL_MILLIS = 15_000

export interface ActiveIdeLeaseRound {
  round: bigint
  sentAt: number
  consumed: boolean
}

export type IdeCommandAdmission =
  | { kind: 'ignore' }
  | { kind: 'reply'; reply: IdeResult }
  | { kind: 'run'; controller: AbortController }

/**
 * 决定一条 IDE_COMMAND 是否执行。
 *
 * <p>同连接重复请求不再执行；更新门闩只拒绝 OPEN_VIEW；硬上限在去重之后判定。
 */
export function admitIdeCommand(input: {
  invocationId: string
  command: IdeCommand
  inFlight: ReadonlyMap<string, AbortController>
  updateHeld: boolean
  ide: IdeService | null
}): IdeCommandAdmission {
  if (input.inFlight.has(input.invocationId)) {
    return { kind: 'ignore' }
  }
  if (input.updateHeld && input.command.action === 'OPEN_VIEW') {
    return {
      kind: 'reply',
      reply: create(IdeResultSchema, { ok: false, code: 'BUSY', message: 'environment update is in progress' }),
    }
  }
  if (input.ide === null) {
    return {
      kind: 'reply',
      reply: create(IdeResultSchema, { ok: false, code: 'UNAVAILABLE', message: 'IDE service is unavailable' }),
    }
  }
  if (input.inFlight.size >= MAX_IN_FLIGHT_IDE_COMMANDS) {
    return {
      kind: 'reply',
      reply: create(IdeResultSchema, { ok: false, code: 'BUSY', message: IDE_BUSY_MESSAGE }),
    }
  }
  return { kind: 'run', controller: new AbortController() }
}

export function ideLeaseFresh(active: ActiveIdeLeaseRound | null, round: bigint, now: number): boolean {
  return (
    active !== null &&
    !active.consumed &&
    active.round === round &&
    now - active.sentAt < IDE_LEASE_ROUND_TTL_MILLIS
  )
}

export function requireFreshIdeLease(active: ActiveIdeLeaseRound | null, round: bigint, now: number): ActiveIdeLeaseRound {
  if (active === null || !ideLeaseFresh(active, round, now)) {
    throw new Error('IDE_LEASE round is not the current unexpired round')
  }
  return active
}

/** IDE 管理命令失败回执：取消用 BUSY，其余用 UNAVAILABLE。 */
export function ideFailureReply(error: unknown, message: string): IdeResult {
  return create(IdeResultSchema, {
    ok: false,
    code: error instanceof Error && error.name === 'AbortError' ? 'BUSY' : 'UNAVAILABLE',
    message,
  })
}

export function toProtoIdeResult(reply: IdeReply): IdeResult {
  const views = reply.views?.map((view) =>
    create(IdeViewSummarySchema, {
      viewId: view.viewId,
      documentId: view.documentId,
      eventNodeId: view.eventNodeId,
      connectionId: view.connectionId,
      claimId: view.claimId,
      bindingEpoch: BigInt(view.bindingEpoch),
      directory: view.directory,
      workspacePath: view.workspacePath,
      state: view.state === 'OPEN' ? IdeViewState.OPEN : IdeViewState.CLOSING,
    }),
  )

  return create(IdeResultSchema, {
    ok: reply.ok,
    viewId: reply.viewId,
    documentId: reply.documentId,
    eventNodeId: reply.eventNodeId,
    connectionId: reply.connectionId,
    claimId: reply.claimId,
    bindingEpoch: reply.bindingEpoch !== undefined ? BigInt(reply.bindingEpoch) : undefined,
    directory: reply.directory,
    workspacePath: reply.workspacePath,
    views: views ?? [],
    directoryListing: reply.directoryListing === undefined ? undefined : create(IdeDirectoryListingSchema, {
      path: reply.directoryListing.path,
      parentPath: reply.directoryListing.parentPath,
      truncated: reply.directoryListing.truncated,
      children: reply.directoryListing.children.map((child) => create(IdeDirectoryEntrySchema, child)),
    }),
    code: reply.code,
    message: reply.message,
  })
}
