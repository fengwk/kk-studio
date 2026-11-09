import { create } from '@bufbuild/protobuf'

import type { IdeCommand, IdeService } from './contracts.js'
import {
  IdeAction,
  IdeOpenMode,
  IdeResultSchema,
  IdeDirectoryListingSchema,
  IdeDirectoryEntrySchema,
  IdeViewState,
  IdeViewSummarySchema,
  type IdeCommand as ProtoIdeCommand,
  type IdeLease,
  type IdeResult,
} from './generated/control_pb.js'
import type { IdeReply } from './contracts.js'
import { safeMessage } from './runtime-encoding.js'

/** 在途 IDE 请求条数上界，防止无界 map 与资源耗尽。 */
const MAX_IN_FLIGHT_IDE_COMMANDS = 256

const IDE_BUSY_MESSAGE = 'too many in-flight IDE commands'

/** IDE_LEASE 轮次在下一心跳或 15 秒后失效，二者先到为准。 */
const IDE_LEASE_ROUND_TTL_MILLIS = 15_000

export interface ActiveIdeLeaseRound {
  round: bigint
  sentAt: number
  consumed: boolean
}

type IdeCommandAdmission =
  | { kind: 'ignore' }
  | { kind: 'reply'; reply: IdeResult }
  | { kind: 'run'; controller: AbortController }

/**
 * 决定一条 IDE_COMMAND 是否执行。
 *
 * <p>同连接重复请求不再执行；更新门闩只拒绝 OPEN_VIEW；硬上限在去重之后判定。
 */
function admitIdeCommand(input: {
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

function ideLeaseFresh(active: ActiveIdeLeaseRound | null, round: bigint, now: number): boolean {
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
function ideFailureReply(error: unknown, message: string): IdeResult {
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
      browserSessionId: view.browserSessionId,
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
    browserSessionId: reply.browserSessionId,
    eventNodeId: reply.eventNodeId,
    connectionId: reply.connectionId,
    claimId: reply.claimId,
    bindingEpoch: reply.bindingEpoch !== undefined ? BigInt(reply.bindingEpoch) : undefined,
    directory: reply.directory,
    workspacePath: reply.workspacePath,
    views: views ?? [],
    revision: reply.revision !== undefined ? BigInt(reply.revision) : undefined,
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

function toIdeAction(action: IdeAction): IdeCommand['action'] {
  switch (action) {
    case IdeAction.OPEN_VIEW:
      return 'OPEN_VIEW'
    case IdeAction.CHANGE_DIRECTORY:
      return 'CHANGE_DIRECTORY'
    case IdeAction.FOCUS_TERMINAL:
      return 'FOCUS_TERMINAL'
    case IdeAction.CLOSE_VIEW:
      return 'CLOSE_VIEW'
    case IdeAction.LIST_VIEWS:
      return 'LIST_VIEWS'
    case IdeAction.LIST_DIRECTORIES:
      return 'LIST_DIRECTORIES'
    case IdeAction.COLLAPSE_MOBILE:
      return 'COLLAPSE_MOBILE'
    default:
      throw new Error(`unsupported IDE action: ${action}`)
  }
}

function toIdeMode(mode: IdeOpenMode | undefined): IdeCommand['mode'] | undefined {
  if (mode === undefined || mode === IdeOpenMode.IDE_OPEN_MODE_UNSPECIFIED) {
    return undefined
  }
  switch (mode) {
    case IdeOpenMode.CREATE:
      return 'create'
    case IdeOpenMode.RESUME:
      return 'resume'
    default:
      throw new Error(`unsupported IDE open mode: ${mode}`)
  }
}

/** 把 wire 命令映射为 IDE 服务契约；未知动作在这里失败关闭。 */
function toIdeCommand(protoCmd: ProtoIdeCommand): IdeCommand {
  return {
    action: toIdeAction(protoCmd.action),
    viewId: protoCmd.viewId ?? undefined,
    browserSessionId: protoCmd.browserSessionId ?? undefined,
    eventNodeId: protoCmd.eventNodeId ?? undefined,
    connectionId: protoCmd.connectionId ?? undefined,
    claimId: protoCmd.claimId ?? undefined,
    bindingEpoch: protoCmd.bindingEpoch !== undefined ? protoCmd.bindingEpoch.toString() : undefined,
    mode: toIdeMode(protoCmd.mode),
    directory: protoCmd.directory ?? undefined,
    discard: protoCmd.discard ?? undefined,
    expectedBindingEpoch: protoCmd.expectedBindingEpoch !== undefined
      ? protoCmd.expectedBindingEpoch.toString()
      : undefined,
    expectedDaemonInstanceId: protoCmd.expectedDaemonInstanceId ?? undefined,
  }
}

/** 把 wire 使用证明转换为 IDE 服务读取的普通身份视图；只保留 round 与绑定身份。 */
export function toIdeLeaseProof(lease: IdeLease): IdeLease {
  return {
    round: String(lease.round ?? 0n),
    bindings: (lease.bindings ?? []).map((binding) => ({
      viewId: binding.viewId,
      browserSessionId: binding.browserSessionId,
      bindingEpoch: String(binding.bindingEpoch ?? 0n),
    })),
  } as unknown as IdeLease
}

export interface IdeCommandRuntimeOptions {
  ide: IdeService | null
  timeoutMillis: number
  isUpdateHeld(): boolean
  onResult(invocationId: string, reply: IdeResult): void
  track(promise: Promise<void>): void
}

/** IDE 管理命令的在途去重、准入、deadline 与回执；副作用不进入 Agent journal。 */
export class IdeCommandRuntime {
  private readonly inFlight = new Map<string, AbortController>()

  constructor(private readonly options: IdeCommandRuntimeOptions) {}

  get inFlightCount(): number {
    return this.inFlight.size
  }

  handle(invocationId: string, protoCmd: ProtoIdeCommand): void {
    const command = toIdeCommand(protoCmd)
    const admission = admitIdeCommand({
      invocationId,
      command,
      inFlight: this.inFlight,
      updateHeld: this.options.isUpdateHeld(),
      ide: this.options.ide,
    })
    if (admission.kind === 'ignore') {
      return
    }
    if (admission.kind === 'reply') {
      this.options.onResult(invocationId, admission.reply)
      return
    }
    const controller = admission.controller
    this.inFlight.set(invocationId, controller)
    // Native close waits for a person; disconnect and shutdown still abort the command.
    const timer = command.action === 'CLOSE_VIEW' && command.discard === false
      ? null
      : setTimeout(() => controller.abort('IDE_COMMAND_TIMEOUT'), this.options.timeoutMillis)
    this.options.track(this.run(invocationId, command, controller, timer))
  }

  abortAll(reason: string): void {
    for (const controller of this.inFlight.values()) {
      controller.abort(reason)
    }
    this.inFlight.clear()
  }

  private async run(
    invocationId: string,
    command: IdeCommand,
    controller: AbortController,
    timer: NodeJS.Timeout | null,
  ): Promise<void> {
    try {
      const reply = await (this.options.ide as IdeService).handle(command, controller.signal)
      if (this.inFlight.get(invocationId) === controller) {
        this.options.onResult(invocationId, toProtoIdeResult(reply))
      }
    } catch (error) {
      if (this.inFlight.get(invocationId) === controller) {
        this.options.onResult(invocationId, ideFailureReply(error, safeMessage(error)))
      }
    } finally {
      if (timer !== null) {
        clearTimeout(timer)
      }
      if (this.inFlight.get(invocationId) === controller) {
        this.inFlight.delete(invocationId)
      }
    }
  }
}
