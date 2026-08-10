import { ApiError } from '@/shared/api/client'
import type {
  CanvasCommandDTO,
  CanvasSnapshotDTO,
  DecimalString,
} from '@/shared/api/contracts/studio'
import {
  applyCanvasCommands,
  getCanvas,
} from '@/shared/api/studio-service'

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
  apply?: typeof applyCanvasCommands
  refetch?: typeof getCanvas
  createCommandId?: () => string
  onSnapshot?: (snapshot: CanvasSnapshotDTO) => void
}

/**
 * Canvas graph mutations are serialized in browser order. Every batch reads the
 * latest authoritative revision when it starts, then adopts the returned snapshot.
 * A known 409 refreshes but never replays a semantic command automatically.
 */
export class CanvasCommandQueue {
  private snapshot: CanvasSnapshotDTO
  private tail: Promise<void> = Promise.resolve()
  private readonly apply: typeof applyCanvasCommands
  private readonly refetch: typeof getCanvas
  private readonly createCommandId: () => string
  private readonly onSnapshot?: (snapshot: CanvasSnapshotDTO) => void

  constructor(
    private readonly canvasId: DecimalString,
    options: CanvasCommandQueueOptions,
  ) {
    this.snapshot = options.initialSnapshot
    this.apply = options.apply ?? applyCanvasCommands
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
      && BigInt(snapshot.document.graphRevision) < BigInt(this.snapshot.document.graphRevision)
    ) {
      return this.snapshot
    }
    this.adopt(snapshot)
    return snapshot
  }

  enqueue(
    commands: CanvasCommandDTO[],
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
    commands: CanvasCommandDTO[],
    signal?: AbortSignal,
  ): Promise<CanvasSnapshotDTO> {
    try {
      const snapshot = await this.apply(
        this.canvasId,
        {
          expectedRevision: this.snapshot.document.graphRevision,
          commandId: this.createCommandId(),
          commands,
        },
        { signal },
      )
      this.adopt(snapshot)
      return snapshot
    } catch (error) {
      if (!(error instanceof ApiError) || error.status !== 409) {
        throw error
      }
      const latest = await this.refetch(this.canvasId, { signal })
      this.adopt(latest)
      throw new CanvasCommandConflictError(latest, error)
    }
  }

  private adopt(snapshot: CanvasSnapshotDTO): void {
    this.snapshot = snapshot
    this.onSnapshot?.(snapshot)
  }
}
