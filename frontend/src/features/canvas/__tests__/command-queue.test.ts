import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import { CanvasCommandConflictError, CanvasCommandQueue } from '@/features/canvas/command-queue'
import type { CanvasPendingOperation } from '@/features/canvas/canvas-operation-storage'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasNodePatchDTO,
  CanvasPatchDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
const NODE_ID = 'c9e3b7f1-2a4d-4e6f-8a9b-0c1d2e3f4a5b' as UUIDString

/** 测试内版本前进：bigint-safe，支持超过 Number.MAX_SAFE_INTEGER 的用例。 */
function nextRevision(revision: string): string {
  return String(BigInt(revision) + 1n)
}

function snapshot(revision: number | string): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      revision: String(revision),
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [],
    groups: [],
    references: [],
  }
}

function advancePatch(
  to: number | string,
  nodes: CanvasNodePatchDTO[] = [{ op: 'REMOVE', nodeId: NODE_ID }],
): CanvasPatchDTO {
  return {
    revision: String(to),
    groups: [],
    nodes,
  }
}

describe('CanvasCommandQueue', () => {
  it('serializes batches and applies the returned patch to the latest revision', async () => {
    let currentRevision = '7'
    const calls: string[] = []
    const apply = vi.fn(async (_canvasId, _request) => {
      calls.push(currentRevision)
      currentRevision = nextRevision(currentRevision)
      return advancePatch(currentRevision)
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

    await expect(first).resolves.toMatchObject({ document: { revision: '8' } })
    await expect(second).resolves.toMatchObject({ document: { revision: '9' } })
    expect(calls).toEqual(['7', '8'])
    expect(apply.mock.calls[0]?.[1].idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-000000000001')
    expect(apply.mock.calls[1]?.[1].idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-000000000002')
  })

  it('refetches on 409, adopts the snapshot, and never replays semantic commands', async () => {
    const apply = vi.fn()
      .mockRejectedValueOnce(new ApiError('stale', 409, 'CONFLICT'))
      .mockResolvedValueOnce(advancePatch(13))
    const refetch = vi.fn().mockResolvedValue(snapshot(12))
    const revisions: string[] = []
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(10),
      apply,
      refetch,
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000003',
      onSnapshot: (value) => revisions.push(value.document.revision),
    })

    await expect(queue.enqueue([
      { type: 'RENAME_NODE', nodeId: NODE_ID, name: 'Changed' },
    ])).rejects.toBeInstanceOf(CanvasCommandConflictError)

    expect(apply).toHaveBeenCalledTimes(1)
    expect(refetch).toHaveBeenCalledTimes(1)
    expect(queue.currentSnapshot().document.revision).toBe('12')
    expect(revisions).toEqual(['12'])

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    expect(apply).toHaveBeenCalledTimes(2)
  })

  it('does not let a failed batch block later queued batches', async () => {
    const apply = vi.fn()
      .mockRejectedValueOnce(new ApiError('bad request', 400))
      .mockResolvedValueOnce(advancePatch(2))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000004',
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).rejects.toThrow('bad request')
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { revision: '2' },
    })
  })

  it('uses UUID command ids by default and forwards AbortSignal', async () => {
    const signal = new AbortController().signal
    const randomUUID = vi.spyOn(crypto, 'randomUUID')
      .mockReturnValue('aaaaaaaa-0000-4000-8000-000000000005')
    const apply = vi.fn().mockResolvedValue(advancePatch(2))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
    })

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }], { signal })

    expect(apply).toHaveBeenCalledWith(CANVAS_ID, {
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
    expect(queue.currentSnapshot().document.revision).toBe('8')
    expect(queue.replaceSnapshot(equal)).toBe(equal)
    expect(queue.currentSnapshot().document.title).toBe('runtime update')
  })

  it('recovers empty ACK patch through the full snapshot', async () => {
    const apply = vi.fn(async () => advancePatch(2, []))
    const refetch = vi.fn().mockResolvedValue(snapshot(4))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch,
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000006',
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { revision: '4' },
    })
    expect(refetch).toHaveBeenCalledWith(CANVAS_ID, { signal: undefined })
  })

  it('handles revisions beyond Number.MAX_SAFE_INTEGER without JS number loss', async () => {
    const huge = '9007199254740992'
    const hugeNext = '9007199254740993'
    const apply = vi.fn(async () => advancePatch(hugeNext))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(huge),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000007',
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { revision: hugeNext },
    })
    // 过期查询快照（大版本语境）不能回退本地状态。
    expect(queue.replaceSnapshot(snapshot('9007199254740991'))).toBe(queue.currentSnapshot())
    expect(queue.currentSnapshot().document.revision).toBe(hugeNext)
  })

  it('入队即深拷贝并冻结命令体与 id，之后修改入参不影响已落盘 / 已发送请求', async () => {
    const store = createFakeOperationStore()
    const apply = vi.fn(async () => advancePatch(2))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000f1',
      operationStore: store.store,
    })

    const commands = [{ type: 'RENAME_NODE' as const, nodeId: NODE_ID, expectedName: 'a', name: 'original' }]
    const pending = queue.enqueue(commands)
    commands[0].name = 'mutated'
    commands.push({ type: 'DELETE_NODE', nodeId: NODE_ID })

    await pending

    const persisted = store.history[0]
    expect(persisted?.idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-0000000000f1')
    expect(persisted?.commands).toEqual([
      { type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'original' },
    ])
    expect(Object.isFrozen(persisted?.commands[0])).toBe(true)
    expect(persisted?.baselineRevision).toBe('1')
    expect(apply).toHaveBeenCalledWith(CANVAS_ID, {
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000f1',
      commands: [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'original' }],
    }, { signal: undefined })
  })

  it('发送前必须先完成落盘事务，落盘失败则绝不发送', async () => {
    let releaseSave!: () => void
    const gate = new Promise<void>((resolve) => {
      releaseSave = resolve
    })
    const save = vi.fn(() => gate)
    const store = {
      save,
      remove: vi.fn(async () => undefined),
      list: vi.fn(async () => []),
    }
    const apply = vi.fn(async () => advancePatch(2))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000f2',
      operationStore: store,
    })

    const pending = queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    await Promise.resolve()
    expect(save).toHaveBeenCalledTimes(1)
    expect(apply).not.toHaveBeenCalled()

    releaseSave()
    await pending
    expect(apply).toHaveBeenCalledTimes(1)

    const failingStore = {
      save: vi.fn(async () => {
        throw new Error('disk full')
      }),
      remove: vi.fn(async () => undefined),
      list: vi.fn(async () => []),
    }
    const failingApply = vi.fn()
    const failingQueue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: failingApply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000f3',
      operationStore: failingStore,
    })

    await expect(failingQueue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }]))
      .rejects.toThrow('disk full')
    expect(failingApply).not.toHaveBeenCalled()
  })

  it('网络失败保留冻结操作，重载后按原顺序、原 key、原 body 精确重放', async () => {
    const store = createFakeOperationStore()
    const failingApply = vi.fn(async () => {
      throw new ApiError('network down')
    })
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: failingApply,
      refetch: vi.fn(),
      createCommandId: vi.fn()
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000g1')
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000g2'),
      operationStore: store.store,
    })

    await expect(queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'one' }]))
      .rejects.toThrow('network down')
    await expect(queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'two' }]))
      .rejects.toThrow('network down')
    expect(store.saved.size).toBe(2)

    // 模拟重载：新的队列实例共享同一持久化 operation store。
    const replayed: ApplyCanvasCommandsRequestDTO[] = []
    const recoverApply = vi.fn(async (_canvasId: UUIDString, request: ApplyCanvasCommandsRequestDTO) => {
      replayed.push(request)
      return advancePatch(1 + replayed.length)
    })
    const reloaded = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: recoverApply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000g3',
      operationStore: store.store,
    })

    const result = await reloaded.recover()
    expect(result.replayed).toBe(2)
    expect(result.conflicted).toBe(0)
    expect(replayed.map((request) => request.idempotencyKey)).toEqual([
      'aaaaaaaa-0000-4000-8000-0000000000g1',
      'aaaaaaaa-0000-4000-8000-0000000000g2',
    ])
    expect(replayed.map((request) => request.commands)).toEqual([
      [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'one' }],
      [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'two' }],
    ])
    // 重放成功后清除持久记录，避免重复提交。
    expect(store.saved.size).toBe(0)
  })

  it('语义 409 是终态：清除该操作且绝不盲重放', async () => {
    const store = createFakeOperationStore()
    const apply = vi.fn(async () => {
      throw new ApiError('conflict', 409, 'CANVAS_CONFLICT', { conflicts: [] })
    })
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn().mockResolvedValue(snapshot(9)),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000h1',
      operationStore: store.store,
    })

    await expect(queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'b' }]))
      .rejects.toBeInstanceOf(CanvasCommandConflictError)
    expect(store.saved.size).toBe(0)

    const result = await queue.recover()
    expect(result.replayed).toBe(0)
    expect(result.conflicted).toBe(0)
    expect(apply).toHaveBeenCalledTimes(1)
  })

  it('5xx 与未知错误视为瞬时失败，保留操作等待重放', async () => {
    const store = createFakeOperationStore()
    const apply = vi.fn(async () => {
      throw new ApiError('server error', 503)
    })
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000h2',
      operationStore: store.store,
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).rejects.toThrow('server error')
    expect(store.saved.size).toBe(1)
    expect(store.store.remove).not.toHaveBeenCalled()
  })

  it('ACK 只清除与本次 operation 精确匹配的持久记录', async () => {
    const store = createFakeOperationStore()
    const apply = vi.fn()
      .mockResolvedValueOnce(advancePatch(2))
      .mockRejectedValueOnce(new ApiError('network down'))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: vi.fn()
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000i1')
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000i2'),
      operationStore: store.store,
    })

    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).rejects.toThrow('network down')

    // 成功那笔被清除，失败那笔仍在待重放集合中。
    expect([...store.saved.keys()]).toEqual([
      expect.stringContaining('aaaaaaaa-0000-4000-8000-0000000000i2'),
    ])
  })

  it('revision 缺口必须重取权威快照，绝不用增量 patch 假装完整', async () => {
    const refetch = vi.fn().mockResolvedValue(snapshot(9))
    const apply = vi.fn(async () => advancePatch(9))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(5),
      apply,
      refetch,
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000j1',
      operationStore: noopStore(),
    })

    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])).resolves.toMatchObject({
      document: { revision: '9' },
    })
    expect(refetch).toHaveBeenCalledTimes(1)

    // 连续 revision 直接应用增量 patch，无需 refetch。
    const applyNext = vi.fn(async () => advancePatch(11))
    const contiguous = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(10),
      apply: applyNext,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000j2',
      operationStore: noopStore(),
    })
    await contiguous.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    expect(contiguous.currentSnapshot().document.revision).toBe('11')
  })
})

function noopStore() {
  return {
    save: vi.fn(async () => undefined),
    remove: vi.fn(async () => undefined),
    list: vi.fn(async () => []),
  }
}

function createFakeOperationStore() {
  const saved = new Map<string, CanvasPendingOperation>()
  const history: CanvasPendingOperation[] = []
  const store = {
    save: vi.fn(async (operation: CanvasPendingOperation) => {
      saved.set(operation.id, operation)
      history.push(operation)
    }),
    remove: vi.fn(async (operationId: string) => {
      saved.delete(operationId)
    }),
    list: vi.fn(async () => [...saved.values()]),
  }
  return { saved, history, store }
}
