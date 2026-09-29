import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import {
  CanvasCommandConflictError,
  CanvasCommandQueue,
  CanvasQueueBlockedError,
} from '@/features/canvas/command-queue'
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

    const first = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'one' }])
    const second = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'two' }])
    await expect(first).rejects.toThrow('network down')
    await expect(second).rejects.toThrow('Canvas command queue is blocked')
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

  it('A失败B不发送：A遭遇未知结果保留持久store，后续B已落盘但不发送越过A且返回清晰重试错误', async () => {
    // 意图：验证操作 A 遇到网络 / 5xx 未知错误后，后续已入队操作 B 先持久化，但绝对不能越过 A 发送；
    // 队列进入 blocked 状态，B 的 Promise 返回明确可重试错误且不挂起。
    const store = createFakeOperationStore()
    const apply = vi.fn().mockImplementation(async (_canvasId, request: ApplyCanvasCommandsRequestDTO) => {
      if (request.idempotencyKey === 'aaaaaaaa-0000-4000-8000-0000000000a1') {
        throw new ApiError('network down')
      }
      return advancePatch(2)
    })
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: vi.fn()
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000a1')
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000a2'),
      operationStore: store.store,
    })

    const first = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'init', name: 'opA' }])
    const second = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'opA', name: 'opB' }])

    await expect(first).rejects.toThrow('network down')
    const secondErr = await second.catch((err) => err)
    expect(secondErr).toBeInstanceOf(CanvasQueueBlockedError)
    expect(secondErr.retryable).toBe(true)

    // B 绝对没有发送给服务端（apply 仅针对 A 调用了一次）
    expect(apply).toHaveBeenCalledTimes(1)
    expect(apply).toHaveBeenCalledWith(CANVAS_ID, expect.objectContaining({
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000a1',
    }), { signal: undefined })

    // A 和 B 都已完成落盘保存在 store 中，用于后续恢复
    expect(store.saved.size).toBe(2)
    expect(queue.isBlocked()).toBe(true)
  })

  it('A恢复B顺序：A与B按冻结顺序原key原body重放，恢复成功后清除持久记录并解除阻塞', async () => {
    // 意图：承接 A 失败 B 未发送的场景，验证调用 recover 时，A 和 B 严格按照冻结顺序、原 key、原 body 重放；
    // 全部成功后清除持久 store 并解除 blocked 状态，后续 enqueue 可正常执行。
    const store = createFakeOperationStore()
    let networkFailed = true
    const applyRequests: ApplyCanvasCommandsRequestDTO[] = []
    const apply = vi.fn().mockImplementation(async (_canvasId, request: ApplyCanvasCommandsRequestDTO) => {
      applyRequests.push(request)
      if (networkFailed && request.idempotencyKey === 'aaaaaaaa-0000-4000-8000-0000000000b1') {
        throw new ApiError('server unavailable', 503)
      }
      return advancePatch(applyRequests.length + 1)
    })
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: vi.fn()
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000b1')
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000b2')
        .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000b3'),
      operationStore: store.store,
    })

    const pA = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'init', name: 'A' }])
    const pB = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'A', name: 'B' }])
    await expect(pA).rejects.toThrow('server unavailable')
    await expect(pB).rejects.toBeInstanceOf(CanvasQueueBlockedError)
    expect(queue.isBlocked()).toBe(true)

    // 网络恢复后执行 recover
    networkFailed = false
    applyRequests.length = 0
    const recoverResult = await queue.recover()

    expect(recoverResult.replayed).toBe(2)
    expect(recoverResult.failed).toBe(0)
    expect(recoverResult.conflicted).toBe(0)

    // 严格按冻结顺序原 key 原 body 重放
    expect(applyRequests.map((r) => r.idempotencyKey)).toEqual([
      'aaaaaaaa-0000-4000-8000-0000000000b1',
      'aaaaaaaa-0000-4000-8000-0000000000b2',
    ])
    expect(applyRequests.map((r) => r.commands)).toEqual([
      [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'init', name: 'A' }],
      [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'A', name: 'B' }],
    ])

    // 全部重放成功后持久记录清除，队列恢复正常
    expect(store.saved.size).toBe(0)
    expect(queue.isBlocked()).toBe(false)

    // 恢复后新的 enqueue 能够正常执行
    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    expect(applyRequests).toHaveLength(3)
    expect(applyRequests[2]?.idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-0000000000b3')
  })

  it('恢复首项失败不处理后项：reload重放首个操作遇到未知结果立即中断，禁止发送后项', async () => {
    // 意图：模拟 reload 恢复流程中存在待确认操作 A 和 B；若 A 在恢复时再次遭遇网络未知错误，
    // 必须立即 break 停下，绝不尝试处理和发送后项 B，且保持队列 blocked。
    const store = createFakeOperationStore()
    const opA: CanvasPendingOperation = {
      id: 'user:canvas:session:key-a',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000c1' as UUIDString,
      commands: [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'init', name: 'A' }],
      baselineRevision: '1',
      ack: [],
      sequence: 0,
      createdAt: 1000,
    }
    const opB: CanvasPendingOperation = {
      id: 'user:canvas:session:key-b',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000c2' as UUIDString,
      commands: [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'A', name: 'B' }],
      baselineRevision: '1',
      ack: [],
      sequence: 1,
      createdAt: 2000,
    }
    await store.store.save(opA)
    await store.store.save(opB)

    const apply = vi.fn().mockRejectedValue(new ApiError('network down'))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      operationStore: store.store,
    })

    const result = await queue.recover()
    expect(result.failed).toBe(1)
    expect(result.replayed).toBe(0)

    // apply 只尝试了首项 A，后项 B 绝对未被发送
    expect(apply).toHaveBeenCalledTimes(1)
    expect(apply).toHaveBeenCalledWith(CANVAS_ID, expect.objectContaining({
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000c1',
    }), { signal: undefined })

    // store 中 A 和 B 均被保留
    expect(store.saved.size).toBe(2)
    expect(queue.isBlocked()).toBe(true)
  })

  it('二次recover：并发调用singleflight复用Promise，首次失败不永久缓存且允许再次调用重试', async () => {
    // 意图：验证 recover() 的并发 singleflight 机制以及失败后允许再次调用的契约。
    const store = createFakeOperationStore()
    const op: CanvasPendingOperation = {
      id: 'user:canvas:session:key-d',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000d1' as UUIDString,
      commands: [{ type: 'DELETE_NODE', nodeId: NODE_ID }],
      baselineRevision: '1',
      ack: [],
      sequence: 0,
      createdAt: 1000,
    }
    await store.store.save(op)

    let resolveFirst!: () => void
    const firstGate = new Promise<void>((resolve) => {
      resolveFirst = resolve
    })
    let resolveSecond!: () => void
    const secondGate = new Promise<void>((resolve) => {
      resolveSecond = resolve
    })

    let callCount = 0
    const apply = vi.fn().mockImplementation(async () => {
      callCount++
      if (callCount === 1) {
        await firstGate
        throw new ApiError('server timeout', 504)
      }
      await secondGate
      return advancePatch(2)
    })

    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      operationStore: store.store,
    })

    // 并发调用两次 recover() -> 验证 singleflight 返回相同 Promise
    const p1 = queue.recover()
    const p2 = queue.recover()
    expect(p1).toBe(p2)

    // 释放首次恢复并使其失败
    resolveFirst()
    const r1 = await p1
    expect(r1.failed).toBe(1)
    expect(apply).toHaveBeenCalledTimes(1)
    expect(queue.isBlocked()).toBe(true)

    // 第二次调用 recover() -> 验证首次失败不被永久缓存，允许再次调用
    const p3 = queue.recover()
    expect(p3).not.toBe(p1)

    resolveSecond()
    const r3 = await p3
    expect(r3.replayed).toBe(1)
    expect(r3.failed).toBe(0)
    expect(apply).toHaveBeenCalledTimes(2)
    expect(store.saved.size).toBe(0)
    expect(queue.isBlocked()).toBe(false)
  })

  it('store.list失败不能伪装空成功：底层存储异常直接抛出且保持队列阻塞状态', async () => {
    // 意图：验证 storage.list 失败时，recover() 绝不能吞掉异常并伪装成空成功，必须向外抛出且队列保持阻塞。
    const store = {
      save: vi.fn(async () => undefined),
      remove: vi.fn(async () => undefined),
      list: vi.fn(async () => {
        throw new Error('IndexedDB transaction failed')
      }),
    }
    const apply = vi.fn()
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      operationStore: store,
    })

    await expect(queue.recover()).rejects.toThrow('IndexedDB transaction failed')
    expect(queue.isBlocked()).toBe(true)
    expect(apply).not.toHaveBeenCalled()

    // 阻塞态下新的 enqueue 直接拒绝
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }]))
      .rejects.toBeInstanceOf(CanvasQueueBlockedError)
  })

  it('恢复时明确409与确定4xx可settle并继续处理后续操作', async () => {
    // 意图：验证在 recovery 循环中，若遇到语义 409 冲突或确定 4xx 客户端错误，属于终态错误，
    // 应执行 settle 移除该操作，并允许循环继续处理后续的待确认操作。
    const store = createFakeOperationStore()
    const op409: CanvasPendingOperation = {
      id: 'user:canvas:session:k1',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000e1' as UUIDString,
      commands: [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'conflict' }],
      baselineRevision: '1',
      ack: [],
      sequence: 0,
      createdAt: 1000,
    }
    const op400: CanvasPendingOperation = {
      id: 'user:canvas:session:k2',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000e2' as UUIDString,
      commands: [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'a', name: 'bad' }],
      baselineRevision: '1',
      ack: [],
      sequence: 1,
      createdAt: 2000,
    }
    const opOk: CanvasPendingOperation = {
      id: 'user:canvas:session:k3',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000e3' as UUIDString,
      commands: [{ type: 'DELETE_NODE', nodeId: NODE_ID }],
      baselineRevision: '1',
      ack: [{ nodeId: NODE_ID, field: 'text', generation: 2 }],
      sequence: 2,
      createdAt: 3000,
    }
    await store.store.save(op409)
    await store.store.save(op400)
    await store.store.save(opOk)

    const apply = vi.fn().mockImplementation(async (_canvasId, request: ApplyCanvasCommandsRequestDTO) => {
      if (request.idempotencyKey === 'aaaaaaaa-0000-4000-8000-0000000000e1') {
        throw new ApiError('stale', 409, 'CONFLICT')
      }
      if (request.idempotencyKey === 'aaaaaaaa-0000-4000-8000-0000000000e2') {
        throw new ApiError('bad syntax', 400)
      }
      return advancePatch(5)
    })

    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn().mockResolvedValue(snapshot(4)),
      operationStore: store.store,
    })

    const result = await queue.recover()
    expect(result.conflicted).toBe(1)
    expect(result.failed).toBe(1)
    expect(result.replayed).toBe(1)
    expect(result.ackedDrafts).toEqual([{ nodeId: NODE_ID, field: 'text', generation: 2 }])

    // 全部操作（包括 settle 的 409 和 400，以及成功的 opOk）均已处理并移出 store
    expect(store.saved.size).toBe(0)
    expect(queue.isBlocked()).toBe(false)
  })

  it('blocked未知态下新的enqueue直接拒绝且保持草稿，不写入store不造重复key', async () => {
    // 意图：验证队列受阻后，新的 enqueue 绝不会写入 operation store 或生成新 key，保持草稿不被 ACK；
    // 同时之前在队列受阻前已入队的 B 必须完好持久保留在 store 中。
    const store = createFakeOperationStore()
    const createCommandId = vi.fn()
      .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000f1')
      .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000f2')
      .mockReturnValueOnce('aaaaaaaa-0000-4000-8000-0000000000f3')

    const apply = vi.fn().mockRejectedValue(new ApiError('network down'))
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId,
      operationStore: store.store,
    })

    // 先入队 A 和 B
    const pA = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'init', name: 'A' }])
    const pB = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'A', name: 'B' }])
    await expect(pA).rejects.toThrow('network down')
    await expect(pB).rejects.toBeInstanceOf(CanvasQueueBlockedError)

    expect(queue.isBlocked()).toBe(true)
    // 此时之前入队的 A 和 B 均持久保留在 store 中
    expect(store.saved.size).toBe(2)
    expect(createCommandId).toHaveBeenCalledTimes(2)

    // 在 blocked 状态下调用新的 enqueue
    const pC = queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])
    await expect(pC).rejects.toBeInstanceOf(CanvasQueueBlockedError)

    // createCommandId 没有被再次调用，store 中依然只有 2 条，没有造多余 key
    expect(createCommandId).toHaveBeenCalledTimes(2)
    expect(store.saved.size).toBe(2)
  })

  it('Gate store.list 延迟：旧 A seq 5，recover pending 时 enqueue B，释放 list 且 A 网络失败，断言持久 order B > A 且 reload 不越序', async () => {
    // 意图：验证新实例创建时（seq 初值为 0），在 initial recover 等待 store.list 延迟期间若用户入队 B，
    // B 在入队瞬间完成 body/identity 冻结；当 store.list 释放且旧 A (seq 5) 网络失败后，
    // B 分配到的持久序列号必须严格大于 A (B.sequence > A.sequence = 5)，且 reload 恢复时绝不发生 B 越过 A 越序发送。
    const storeMap = new Map<string, CanvasPendingOperation>()
    const opA: CanvasPendingOperation = {
      id: 'user:canvas:session:key-old-a',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000a5' as UUIDString,
      commands: [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'init', name: 'A' }],
      baselineRevision: '1',
      ack: [],
      sequence: 5,
      createdAt: 1000,
    }
    storeMap.set(opA.id, opA)

    let releaseListGate!: () => void
    const listGate = new Promise<void>((resolve) => {
      releaseListGate = resolve
    })
    let listCalls = 0

    const store = {
      save: vi.fn(async (op: CanvasPendingOperation) => {
        storeMap.set(op.id, op)
      }),
      remove: vi.fn(async (id: string) => {
        storeMap.delete(id)
      }),
      list: vi.fn(async () => {
        listCalls++
        if (listCalls === 1) {
          await listGate
        }
        return [...storeMap.values()]
      }),
    }

    const applyCalls: ApplyCanvasCommandsRequestDTO[] = []
    const apply = vi.fn().mockImplementation(async (_canvasId, request: ApplyCanvasCommandsRequestDTO) => {
      applyCalls.push(request)
      if (request.idempotencyKey === 'aaaaaaaa-0000-4000-8000-0000000000a5') {
        throw new ApiError('network down')
      }
      return advancePatch(applyCalls.length + 1)
    })

    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000b6' as UUIDString,
      operationStore: store,
    })

    // 1. 触发 recover()，此时处于 pending 等待 store.list 的 listGate 释放
    const recPromise = queue.recover()
    await Promise.resolve()
    expect(listCalls).toBe(1)

    // 2. 在 recover 还在 pending 期间，用户入队 B
    const commandsB = [{ type: 'RENAME_NODE' as const, nodeId: NODE_ID, expectedName: 'A', name: 'B' }]
    const bPromise = queue.enqueue(commandsB)
    // 验证入队瞬间深拷贝冻结，外部后续修改不影响已入队命令
    commandsB[0].name = 'tampered'

    // 3. 释放 store.list 延迟门禁
    releaseListGate()

    // 4. 旧 A 遇到网络失败，recover 结束且队列阻塞
    const recResult = await recPromise
    expect(recResult.failed).toBe(1)
    expect(queue.isBlocked()).toBe(true)

    // 5. B 执行持久化并因队列阻塞被拒绝，但保留在 store 中
    await expect(bPromise).rejects.toBeInstanceOf(CanvasQueueBlockedError)
    expect(storeMap.size).toBe(2)

    const savedA = storeMap.get(opA.id)!
    const savedB = [...storeMap.values()].find((op) => op.id !== opA.id)!
    expect(savedA).toBeDefined()
    expect(savedB).toBeDefined()
    expect(savedB.commands).toEqual([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'A', name: 'B' }])

    // 6. 核心断言：持久化 sequence 必须在确保持久化已有序列号初始化后生成，断言持久 order B > A (B.sequence > A.sequence)
    expect(savedB.sequence).toBeGreaterThan(savedA.sequence)
    expect(savedA.sequence).toBe(5)
    expect(savedB.sequence).toBe(6)

    // 7. 模拟 reload 恢复流程：在网络恢复后重建队列实例并执行 recover
    const reloadApplyCalls: ApplyCanvasCommandsRequestDTO[] = []
    const reloadApply = vi.fn().mockImplementation(async (_canvasId, request: ApplyCanvasCommandsRequestDTO) => {
      reloadApplyCalls.push(request)
      return advancePatch(reloadApplyCalls.length + 1)
    })
    const reloadedQueue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: reloadApply,
      refetch: vi.fn(),
      operationStore: store,
    })

    const reloadResult = await reloadedQueue.recover()
    expect(reloadResult.replayed).toBe(2)
    expect(reloadResult.failed).toBe(0)

    // 断言 reload 重放顺序严格为先 A (seq 5) 后 B (seq 6)，绝不越序
    expect(reloadApplyCalls).toHaveLength(2)
    expect(reloadApplyCalls[0]?.idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-0000000000a5')
    expect(reloadApplyCalls[1]?.idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-0000000000b6')
    expect(storeMap.size).toBe(0)
    expect(reloadedQueue.isBlocked()).toBe(false)
  })

  it('A5/C9 列表：A 网络失败中断恢复，新 B sequence 必须先从全部 pending 取 max 确保 B > 9 且 reload 顺序为 A -> C -> B', async () => {
    // 意图：验证 sequence 初始化必须预先从 list 全部 pending 取 max，而不是在重放循环逐个 max；
    // 当 A (seq 5) 发送失败中断恢复而 C (seq 9) 尚未被访问时，新入队的 B 必须获取大于 9 的序号（B.sequence = 10 > 9），
    // 绝不能错误获得 6 排在 C 前面。
    const storeMap = new Map<string, CanvasPendingOperation>()
    const opA: CanvasPendingOperation = {
      id: 'user:canvas:session:key-old-a',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000a5' as UUIDString,
      commands: [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'init', name: 'A' }],
      baselineRevision: '1',
      ack: [],
      sequence: 5,
      createdAt: 1000,
    }
    const opC: CanvasPendingOperation = {
      id: 'user:canvas:session:key-old-c',
      userId: 'user',
      canvasId: CANVAS_ID,
      editingSessionId: 'session',
      sessionKey: 'user:canvas:session',
      idempotencyKey: 'aaaaaaaa-0000-4000-8000-0000000000c9' as UUIDString,
      commands: [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'A', name: 'C' }],
      baselineRevision: '1',
      ack: [],
      sequence: 9,
      createdAt: 2000,
    }
    storeMap.set(opA.id, opA)
    storeMap.set(opC.id, opC)

    let releaseListGate!: () => void
    const listGate = new Promise<void>((resolve) => {
      releaseListGate = resolve
    })
    let listCalls = 0

    const store = {
      save: vi.fn(async (op: CanvasPendingOperation) => {
        storeMap.set(op.id, op)
      }),
      remove: vi.fn(async (id: string) => {
        storeMap.delete(id)
      }),
      list: vi.fn(async () => {
        listCalls++
        if (listCalls === 1) {
          await listGate
        }
        return [...storeMap.values()]
      }),
    }

    const applyCalls: ApplyCanvasCommandsRequestDTO[] = []
    const apply = vi.fn().mockImplementation(async (_canvasId, request: ApplyCanvasCommandsRequestDTO) => {
      applyCalls.push(request)
      if (request.idempotencyKey === 'aaaaaaaa-0000-4000-8000-0000000000a5') {
        throw new ApiError('network down')
      }
      return advancePatch(applyCalls.length + 1)
    })

    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply,
      refetch: vi.fn(),
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-0000000000b0' as UUIDString,
      operationStore: store,
    })

    const recPromise = queue.recover()
    await Promise.resolve()

    // 在 recover 等待 store.list 期间入队 B
    const bPromise = queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'C', name: 'B' }])

    // 释放 list 门禁：返回 [A (seq 5), C (seq 9)]
    releaseListGate()

    // A 失败，C 尚未访问，recover 结束
    const recResult = await recPromise
    expect(recResult.failed).toBe(1)
    expect(queue.isBlocked()).toBe(true)

    // B 落盘并拒绝发送
    await expect(bPromise).rejects.toBeInstanceOf(CanvasQueueBlockedError)
    expect(storeMap.size).toBe(3)

    const savedB = [...storeMap.values()].find((op) => op.idempotencyKey === 'aaaaaaaa-0000-4000-8000-0000000000b0')!
    expect(savedB).toBeDefined()
    // 核心断言：B.sequence 必须严格大于 C 的 sequence 9（不能是 6）
    expect(savedB.sequence).toBeGreaterThan(9)
    expect(savedB.sequence).toBe(10)

    // 模拟 reload 重放，验证三者严格保序 A (5) -> C (9) -> B (10)
    const reloadCalls: ApplyCanvasCommandsRequestDTO[] = []
    const reloadedQueue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: vi.fn().mockImplementation(async (_canvasId, req: ApplyCanvasCommandsRequestDTO) => {
        reloadCalls.push(req)
        return advancePatch(reloadCalls.length + 1)
      }),
      refetch: vi.fn(),
      operationStore: store,
    })

    const reloadResult = await reloadedQueue.recover()
    expect(reloadResult.replayed).toBe(3)
    expect(reloadResult.failed).toBe(0)

    expect(reloadCalls).toHaveLength(3)
    expect(reloadCalls[0]?.idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-0000000000a5')
    expect(reloadCalls[1]?.idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-0000000000c9')
    expect(reloadCalls[2]?.idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-0000000000b0')
    expect(storeMap.size).toBe(0)
  })

  it('list 失败期间绝不分配低 seq 写 store，直接拒绝尚未 freeze 的新入队保持草稿', async () => {
    // 意图：当 initial recover 在 store.list 发生错误时，尚未完成 sequence 初始化；
    // 此时排队的尚未 freeze 的新操作绝不能以错误的初值 seq 0 写入 store，必须直接 reject 并保持草稿。
    const storeMap = new Map<string, CanvasPendingOperation>()
    let releaseListGate!: () => void
    const listGate = new Promise<void>((resolve) => {
      releaseListGate = resolve
    })

    const store = {
      save: vi.fn(async (op: CanvasPendingOperation) => {
        storeMap.set(op.id, op)
      }),
      remove: vi.fn(async () => undefined),
      list: vi.fn(async () => {
        await listGate
        throw new Error('IndexedDB list error')
      }),
    }

    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: vi.fn(),
      refetch: vi.fn(),
      operationStore: store,
    })

    const recPromise = queue.recover()
    await Promise.resolve()

    // 在 store.list 挂起期间入队 B
    const bPromise = queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }])

    // 释放门禁使 store.list 抛出错误
    releaseListGate()

    // recover 抛出错误并进入 blocked
    await expect(recPromise).rejects.toThrow('IndexedDB list error')
    expect(queue.isBlocked()).toBe(true)

    // 排队的 B 被直接拒绝
    await expect(bPromise).rejects.toBeInstanceOf(CanvasQueueBlockedError)

    // 断言：B 绝未写入 store，storeMap 为空，save 未被调用
    expect(store.save).not.toHaveBeenCalled()
    expect(storeMap.size).toBe(0)
  })

  it('I02 崩溃时序保证：settle 必须先 await onDraftAcks 物理落盘成功，才允许从底层 store 移除 operation', async () => {
    const { saved, store } = createFakeOperationStore()
    const callOrder: string[] = []

    const onDraftAcks = vi.fn(async () => {
      callOrder.push('onDraftAcks')
    })
    const originalRemove = store.remove
    store.remove = vi.fn(async (opId: string) => {
      callOrder.push('store.remove')
      await originalRemove(opId)
    })

    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: vi.fn(async () => advancePatch(2)),
      refetch: vi.fn(),
      operationStore: store,
      onDraftAcks,
    })

    const ack = [{ nodeId: NODE_ID, field: 'text' as const, generation: 1 }]
    await queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }], { ack })

    expect(onDraftAcks).toHaveBeenCalledWith(ack)
    expect(callOrder).toEqual(['onDraftAcks', 'store.remove'])
    expect(saved.size).toBe(0)
  })

  it('I02 崩溃时序保证：若 onDraftAcks 发生 IDB abort/error，绝不删除 operation，保留供幂等重放', async () => {
    const { saved, store } = createFakeOperationStore()

    const onDraftAcks = vi.fn(async () => {
      throw new Error('IndexedDB draft ACK transaction aborted')
    })

    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: vi.fn(async () => advancePatch(2)),
      refetch: vi.fn(),
      operationStore: store,
      onDraftAcks,
    })

    const ack = [{ nodeId: NODE_ID, field: 'text' as const, generation: 1 }]
    await expect(queue.enqueue([{ type: 'DELETE_NODE', nodeId: NODE_ID }], { ack }))
      .rejects.toThrow('IndexedDB draft ACK transaction aborted')

    // 关键断言：草稿 ACK 失败时，底层 operation 绝未被删除，仍留在持久化 store 中
    expect(store.remove).not.toHaveBeenCalled()
    expect(saved.size).toBe(1)
  })

  it('I02 终端冲突保护：409 发生时 clearAcks 为 false，绝不调用 onDraftAcks 清理本地冲突草稿', async () => {
    const { store } = createFakeOperationStore()
    const onDraftAcks = vi.fn(async () => undefined)

    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: vi.fn().mockRejectedValue(new ApiError('conflict', 409, 'CANVAS_CONFLICT')),
      refetch: vi.fn().mockResolvedValue(snapshot(2)),
      operationStore: store,
      onDraftAcks,
    })

    const ack = [{ nodeId: NODE_ID, field: 'text' as const, generation: 1 }]
    await expect(queue.enqueue([{ type: 'RENAME_NODE', nodeId: NODE_ID, name: 'New' }], { ack }))
      .rejects.toBeInstanceOf(CanvasCommandConflictError)

    // 关键断言：409 终端失败时不清除草稿 ACK，保留草稿供用户救援
    expect(onDraftAcks).not.toHaveBeenCalled()
  })

  it('I02: 草稿 ACK 成功但 operation delete 失败后，重载 recover 严格以原 key 和原 body 幂等重放', async () => {
    // 测试意图：验证服务端执行成功且草稿 ACK 成功落盘后，若 operation store 删除失败（如持久层故障），残留的操作在重载后由 recover() 按照原 idempotencyKey 与原 commands 再次发起幂等重放，且重放成功后再次触发 store.remove 完成清理
    const { saved, store } = createFakeOperationStore()
    const onDraftAcks = vi.fn(async () => undefined)

    // 第一次删除注入失败
    let removeShouldFail = true
    const originalRemove = store.remove
    store.remove = vi.fn(async (opId: string) => {
      if (removeShouldFail) {
        throw new Error('IndexedDB operation delete failed')
      }
      await originalRemove(opId)
    })

    const initialCommands: CanvasCommandDTO[] = [{ type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'before', name: 'after' }]
    const queue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(1),
      apply: vi.fn(async () => advancePatch(2)),
      refetch: vi.fn(),
      operationStore: store,
      onDraftAcks,
      createCommandId: () => 'aaaaaaaa-0000-4000-8000-000000000099',
    })

    const ack = [{ nodeId: NODE_ID, field: 'text' as const, generation: 1 }]
    // 入队执行：服务端成功，onDraftAcks 成功，但 store.remove 失败
    await queue.enqueue(initialCommands, { ack })

    // 草稿 ACK 已被成功调用并物理落盘
    expect(onDraftAcks).toHaveBeenCalledTimes(1)
    expect(onDraftAcks).toHaveBeenCalledWith(ack)

    // 因 store.remove 失败，残留操作依然保存在 store 中
    expect(saved.size).toBe(1)
    const residualOp = [...saved.values()][0]
    expect(residualOp.idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-000000000099')

    // 模拟重载后：创建新的队列实例并执行 recover
    removeShouldFail = false // 后续存储故障恢复
    const replayedRequests: ApplyCanvasCommandsRequestDTO[] = []
    const recoverApply = vi.fn(async (_canvasId: UUIDString, request: ApplyCanvasCommandsRequestDTO) => {
      replayedRequests.push(request)
      return advancePatch(3)
    })

    const reloadedQueue = new CanvasCommandQueue(CANVAS_ID, {
      initialSnapshot: snapshot(2),
      apply: recoverApply,
      refetch: vi.fn(),
      operationStore: store,
    })

    const recoveryResult = await reloadedQueue.recover()

    // 验证 recover 按照原 idempotencyKey 与原 commands 再次提交重放
    expect(recoveryResult.replayed).toBe(1)
    expect(replayedRequests).toHaveLength(1)
    expect(replayedRequests[0].idempotencyKey).toBe('aaaaaaaa-0000-4000-8000-000000000099')
    expect(replayedRequests[0].commands).toEqual(initialCommands)

    // 重放成功后，store.remove 再次被调用，且最终残留操作被彻底清除
    expect(saved.size).toBe(0)
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
