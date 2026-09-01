import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { CanvasCommandConflictError, CanvasCommandQueue } from '@/features/canvas/command-queue'
import type {
  CanvasPatchDTO,
  CanvasSnapshotDTO,
} from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_ID = 'c9e3b7f1-2a4d-4e6f-8a9b-0c1d2e3f4a5b'

/** 测试内版本前进：bigint-safe，支持超过 Number.MAX_SAFE_INTEGER 的用例。 */
function nextVersion(version: string): string {
  return String(BigInt(version) + 1n)
}

function snapshot(version: number | string): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: String(version),
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [],
    groups: [],
    links: [],
  }
}

function advancePatch(from: number | string, to: number | string): CanvasPatchDTO {
  return {
    baseVersion: String(from),
    version: String(to),
    groups: [],
    nodes: [],
    links: [],
  }
}

describe('CanvasCommandQueue', () => {
  it('serializes batches and applies the returned patch to the latest version', async () => {
    const calls: string[] = []
    const apply = vi.fn(async (_canvasId, request) => {
      calls.push(request.expectedVersion)
      return advancePatch(request.expectedVersion, nextVersion(request.expectedVersion))
    })
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(7),
      apply,
      refetch: vi.fn(),
      createCommandId: vi.fn()
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-000000000001')
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-000000000002'),
    })

    const first = queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    const second = queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])

    await expect(first).resolves.toMatchObject({ document: { version: '8' } })
    await expect(second).resolves.toMatchObject({ document: { version: '9' } })
    expect(calls).toEqual(['7', '8'])
    expect(apply.mock.calls[0]?.[1].idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-000000000001')
    expect(apply.mock.calls[1]?.[1].idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-000000000002')
  })

  it('refetches on 409, adopts the snapshot, and never replays semantic commands', async () => {
    const apply = vi.fn()
      .mockRejectedValueOnce(new ApiError('stale', 409, 'CONFLICT'))
      .mockResolvedValueOnce(advancePatch(12, 13))
    const refetch = vi.fn().mockResolvedValue(snapshot(12))
    const versions: string[] = []
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(10),
      apply,
      refetch,
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000003',
      onSnapshot: (value) => versions.push(value.document.version),
    })

    await expect(queue.enqueue([
      { type: 'RENAME_NODE', nodeId: NODE_ID, name: 'Changed' },
    ])).rejects.toBeInstanceOf(CanvasCommandConflictError)

    expect(apply).toHaveBeenCalledTimes(1)
    expect(refetch).toHaveBeenCalledTimes(1)
    expect(queue.currentSnapshot().document.version).toBe('12')
    expect(versions).toEqual(['12'])

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    expect(apply.mock.calls[1]?.[1].expectedVersion).toBe('12')
  })

  it('does not let a failed batch block later queued batches', async () => {
    const apply = vi.fn()
      .mockRejectedValueOnce(new ApiError('bad request', 400))
      .mockResolvedValueOnce(advancePatch(1, 2))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000004',
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).rejects.toThrow('bad request')
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { version: '2' },
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
    })

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }], { signal })

    expect(apply).toHaveBeenCalledWith(CANVAS_ID, {
      expectedVersion: '1',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-000000000005',
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
    })

    await expect(queue.enqueue([])).resolves.toBe(initial)
    expect(apply).not.toHaveBeenCalled()
  })

  it('does not regress to a stale query snapshot but accepts equal-version runtime updates', () => {
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(8),
      apply: vi.fn(),
      refetch: vi.fn(),
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
    expect(queue.currentSnapshot().document.version).toBe('8')
    expect(queue.replaceSnapshot(equal)).toBe(equal)
    expect(queue.currentSnapshot().document.title).toBe('runtime update')
  })

  it('recovers a non-continuous command patch through the full snapshot', async () => {
    const apply = vi.fn(async (_canvasId, request) => advancePatch(
      nextVersion(request.expectedVersion),
      nextVersion(nextVersion(request.expectedVersion)),
    ))
    const refetch = vi.fn().mockResolvedValue(snapshot(4))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch,
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000006',
    })

    // 响应 patch base=2 -> version=4：base 与当前版本 1 不连续；
    // 不尝试补 patch window，直接读取权威 Snapshot。
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { version: '4' },
    })
    expect(refetch).toHaveBeenCalledWith(CANVAS_ID, { signal: undefined })
  })

  it('handles versions beyond Number.MAX_SAFE_INTEGER without JS number loss', async () => {
    const huge = '9007199254740992'
    const hugeNext = '9007199254740993'
    const apply = vi.fn(async (_canvasId, request) => (
      advancePatch(request.expectedVersion, nextVersion(request.expectedVersion))
    ))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(huge),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000007',
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { version: hugeNext },
    })
    expect(apply.mock.calls[0]?.[1].expectedVersion).toBe(huge)
    // 过期查询快照（大版本语境）不能回退本地状态。
    expect(queue.replaceSnapshot(snapshot('9007199254740991'))).toBe(queue.currentSnapshot())
    expect(queue.currentSnapshot().document.version).toBe(hugeNext)
  })

})
