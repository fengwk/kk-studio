import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { CanvasCommandConflictError, CanvasCommandQueue } from '@/features/canvas/command-queue'
import type {
  CanvasChangesDTO,
  CanvasPatchDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_ID = 'c9e3b7f1-2a4d-4e6f-8a9b-0c1d2e3f4a5b'

function snapshot(version: number): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version,
      threadId: null,
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [],
    groups: [],
    links: [],
  }
}

function advancePatch(from: number, to: number): CanvasPatchDTO {
  return {
    baseVersion: from,
    version: to,
    groups: [],
    nodes: [],
    links: [],
  }
}

function changesOf(patches: CanvasPatchDTO[] = [], snapshotValue: CanvasSnapshotDTO | null = null): CanvasChangesDTO {
  return { patches, snapshot: snapshotValue }
}

describe('CanvasCommandQueue', () => {
  it('serializes batches and applies the returned patch to the latest version', async () => {
    const calls: number[] = []
    const apply = vi.fn(async (_canvasId, request) => {
      calls.push(request.expectedVersion)
      return advancePatch(request.expectedVersion, request.expectedVersion + 1)
    })
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(7),
      apply,
      refetch: vi.fn(),
      getChanges: vi.fn(),
      createCommandId: vi.fn()
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-000000000001')
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-000000000002'),
    })

    const first = queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    const second = queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])

    await expect(first).resolves.toMatchObject({ document: { version: 8 } })
    await expect(second).resolves.toMatchObject({ document: { version: 9 } })
    expect(calls).toEqual([7, 8])
    expect(apply.mock.calls[0]?.[1].commandId).toBe('aaaaaaaa-0000-4000-8000-000000000001')
    expect(apply.mock.calls[1]?.[1].commandId).toBe('aaaaaaaa-0000-4000-8000-000000000002')
  })

  it('refetches on 409, adopts the snapshot, and never replays semantic commands', async () => {
    const apply = vi.fn()
      .mockRejectedValueOnce(new ApiError('stale', 409, 'CONFLICT'))
      .mockResolvedValueOnce(advancePatch(12, 13))
    const refetch = vi.fn().mockResolvedValue(snapshot(12))
    const versions: number[] = []
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(10),
      apply,
      refetch,
      getChanges: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000003',
      onSnapshot: (value) => versions.push(value.document.version),
    })

    await expect(queue.enqueue([
      { type: 'RENAME_NODE', nodeId: NODE_ID, name: 'Changed' },
    ])).rejects.toBeInstanceOf(CanvasCommandConflictError)

    expect(apply).toHaveBeenCalledTimes(1)
    expect(refetch).toHaveBeenCalledTimes(1)
    expect(queue.currentSnapshot().document.version).toBe(12)
    expect(versions).toEqual([12])

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    expect(apply.mock.calls[1]?.[1].expectedVersion).toBe(12)
  })

  it('does not let a failed batch block later queued batches', async () => {
    const apply = vi.fn()
      .mockRejectedValueOnce(new ApiError('bad request', 400))
      .mockResolvedValueOnce(advancePatch(1, 2))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      getChanges: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000004',
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).rejects.toThrow('bad request')
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { version: 2 },
    })
  })

  it('uses UUID command ids by default and forwards AbortSignal', async () => {
    const signal = new AbortController().signal
    const randomUUID = vi.spyOn(crypto, 'randomUUID')
      .mockReturnValue('aaaaaaaa-0000-4000-8000-000000000005')
    const apply = vi.fn().mockResolvedValue(advancePatch(1, 2))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      getChanges: vi.fn(),
    })

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }], { signal })

    expect(apply).toHaveBeenCalledWith(CANVAS_ID, {
      expectedVersion: 1,
      commandId: 'aaaaaaaa-0000-4000-8000-000000000005',
      commands: [{ type: 'DELETE_NODE', nodeId: NODE_ID }],
    }, { signal })
    randomUUID.mockRestore()
  })

  it('returns the current snapshot without sending an empty batch', async () => {
    const apply = vi.fn()
    const initial = snapshot(5)
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: initial,
      apply,
      refetch: vi.fn(),
      getChanges: vi.fn(),
    })

    await expect(queue.enqueue([])).resolves.toBe(initial)
    expect(apply).not.toHaveBeenCalled()
  })

  it('does not regress to a stale query snapshot but accepts equal-version runtime updates', () => {
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(8),
      apply: vi.fn(),
      refetch: vi.fn(),
      getChanges: vi.fn(),
    })
    const stale = snapshot(7)
    const equal = {
      ...snapshot(8),
      document: {
        ...snapshot(8).document,
        title: 'runtime update',
      },
    }

    expect(queue.replaceSnapshot(stale)).toBe(queue.currentSnapshot())
    expect(queue.currentSnapshot().document.version).toBe(8)
    expect(queue.replaceSnapshot(equal)).toBe(equal)
    expect(queue.currentSnapshot().document.title).toBe('runtime update')
  })

  it('recovers a gap through changes when the command response is not continuous', async () => {
    const apply = vi.fn(async (_canvasId, request) => advancePatch(
      request.expectedVersion + 1,
      request.expectedVersion + 3,
    ))
    const getChanges = vi.fn(async (_canvasId, afterVersion) => {
      // 服务端 head 已包含本命令的效果：changes 从当前版本补全完整链
      //（他人提交 1->2 + 本命令 2->4）。
      expect(afterVersion).toBe(1)
      return changesOf([advancePatch(1, 2), advancePatch(2, 4)])
    })
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      getChanges,
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000006',
    })

    // 响应 patch base=2 -> version=4：base 与当前版本 1 不连续（gap），
    // 通过 changes 补全 1->2 与 2->4。
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { version: 4 },
    })
    expect(getChanges).toHaveBeenCalledWith(CANVAS_ID, 1, { signal: undefined })
  })

  it('syncFrom falls back to the full snapshot when changes cannot close the gap', async () => {
    const getChanges = vi.fn().mockResolvedValue(changesOf([advancePatch(2, 3)]))
    const refetch = vi.fn().mockResolvedValue(snapshot(4))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: vi.fn(),
      refetch,
      getChanges,
    })

    const synced = await queue.syncFrom(1)

    expect(synced.document.version).toBe(4)
    expect(refetch).toHaveBeenCalledWith(CANVAS_ID, { signal: undefined })
  })

  it('syncFrom adopts a returned snapshot and folds continuous patches', async () => {
    const snapshotValue = { ...snapshot(9), document: { ...snapshot(9).document, title: 'snapshot title' } }
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(7),
      apply: vi.fn(),
      refetch: vi.fn(),
      getChanges: vi.fn()
        .mockResolvedValueOnce(changesOf([advancePatch(7, 8), advancePatch(8, 9)]))
        .mockResolvedValueOnce(changesOf([], snapshotValue)),
    })

    await queue.syncFrom(7)
    expect(queue.currentSnapshot().document.version).toBe(9)

    await queue.syncFrom(9)
    expect(queue.currentSnapshot().document.title).toBe('snapshot title')
    expect(queue.currentSnapshot().document.version).toBe(9)
  })
})
