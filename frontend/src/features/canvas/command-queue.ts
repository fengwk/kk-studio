import { ApiError } from '@/shared/api/client'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasPatchDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import {
  getCanvas,
  postCanvasCommands,
} from '@/shared/api/studio-service'
import { compareCanvasVersions } from '@/shared/lib/canvas-version'
import { applyEntityPatch } from '@/features/canvas/entity-patch'

export class CanvasCommandConflictError extends Error {
  readonly snapshot: CanvasSnapshotDTO
  readonly cause: ApiError

  constructor(snapshot: CanvasSnapshotDTO, cause: ApiError) {
    super('Canvas changed on the server. The latest snapshot was loaded; review and retry your action.')
    this.name = 'CanvasCommandConflictError'
    this.snapshot = snapshot
    this.cause = cause
  }
}

export interface CanvasCommandQueueOptions {
  initialSnapshot: CanvasSnapshotDTO
  apply?: typeof postCanvasCommands
  refetch?: typeof getCanvas
  createCommandId?: () => UUIDString
  onSnapshot?: (snapshot: CanvasSnapshotDTO) => void
}

/**
 * Canvas graph 变更按浏览器顺序串行化。每个批次都以开始时最新的权威
 * version 作为 expectedVersion 提交；命令响应是 graph patch，直接通过
 * 本地 reducer 应用。重复/过期 patch 被忽略；baseVersion 不连续（gap）
 * 时读取权威 Snapshot 恢复。
 * 已知 409 会刷新快照但绝不自动重放语义命令。
 */
export class CanvasCommandQueue {
  private snapshot: CanvasSnapshotDTO
  private tail: Promise<void> = Promise.resolve()
  private readonly apply: typeof postCanvasCommands
  private readonly refetch: typeof getCanvas
  private readonly createCommandId: () => UUIDString
  private readonly onSnapshot?: (snapshot: CanvasSnapshotDTO) => void

  constructor(
    private readonly canvasId: UUIDString,
    options: CanvasCommandQueueOptions,
  ) {
    this.snapshot = options.initialSnapshot
    this.apply = options.apply ?? postCanvasCommands
    this.refetch = options.refetch ?? getCanvas
    this.createCommandId = options.createCommandId ?? (() => crypto.randomUUID())
    this.onSnapshot = options.onSnapshot
  }

  currentSnapshot(): CanvasSnapshotDTO {
    return this.snapshot
  }

  replaceSnapshot(snapshot: CanvasSnapshotDTO): CanvasSnapshotDTO {
    if (
      snapshot.document.id === this.snapshot.document.id
      && compareCanvasVersions(snapshot.document.version, this.snapshot.document.version) < 0
    ) {
      return this.snapshot
    }
    this.adopt(snapshot)
    return snapshot
  }

  enqueue(
    commands: ApplyCanvasCommandsRequestDTO['commands'],
    options?: { signal?: AbortSignal },
  ): Promise<CanvasSnapshotDTO> {
    if (commands.length === 0) {
      return Promise.resolve(this.snapshot)
    }
    const operation = this.tail.then(() => this.execute(commands, options?.signal))
    this.tail = operation.then(
      () => undefined,
      () => undefined,
    )
    return operation
  }

  private async execute(
    commands: ApplyCanvasCommandsRequestDTO['commands'],
    signal?: AbortSignal,
  ): Promise<CanvasSnapshotDTO> {
    try {
      const patch = await this.apply(
        this.canvasId,
        {
          expectedVersion: this.snapshot.document.version,
          idempotencyKey: this.createCommandId(),
          commands,
        },
        { signal },
      )
      return await this.ingestPatch(patch, signal)
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 409) {
        throw error
      }
      const latest = await this.refetch(this.canvasId, { signal })
      const authoritative = this.replaceSnapshot(latest)
      throw new CanvasCommandConflictError(authoritative, error)
    }
  }

  private async ingestPatch(
    patch: CanvasPatchDTO,
    signal?: AbortSignal,
  ): Promise<CanvasSnapshotDTO> {
    const next = applyEntityPatch(this.snapshot, patch)
    if (next) {
      this.adopt(next)
      return next
    }
    // 重复/过期 patch（version 未前进）直接忽略；其余情况视为 gap。
    if (compareCanvasVersions(patch.version, this.snapshot.document.version) <= 0) {
      return this.snapshot
    }
    const latest = await this.refetch(this.canvasId, { signal })
    return this.replaceSnapshot(latest)
  }

  private adopt(snapshot: CanvasSnapshotDTO): void {
    this.snapshot = snapshot
    this.onSnapshot?.(snapshot)
  }
}
