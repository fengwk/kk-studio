import { describe, expect, it, vi } from 'vitest'
import { createMockIDBFactory } from './mock-idb'
import {
  buildPendingOperation,
  deleteCanvasOperation,
  loadCanvasOperations,
  resetCanvasOperationStorage,
  saveCanvasOperation,
  type CanvasPendingOperation,
} from '@/features/canvas/canvas-operation-storage'
import { onCanvasStorageError } from '@/features/canvas/canvas-local-store'
import type { CanvasCommandDTO, UUIDString } from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
const NODE_ID = 'c9e3b7f1-2a4d-4e6f-8a9b-0c1d2e3f4a5b' as UUIDString

function renameCommand(name: string): CanvasCommandDTO {
  return { type: 'RENAME_NODE', nodeId: NODE_ID, expectedName: 'before', name }
}

function operation(
  sequence: number,
  idempotencyKey: string,
  name: string,
  editingSessionId = 'tab-1',
): CanvasPendingOperation {
  return buildPendingOperation({
    canvasId: CANVAS_ID,
    userId: 'user-1',
    editingSessionId,
    idempotencyKey,
    commands: [renameCommand(name)],
    baselineRevision: '7',
    ack: [{ nodeId: NODE_ID, field: 'text', generation: sequence }],
    sequence,
    createdAt: 1_700_000_000_000 + sequence,
  })
}

describe('Canvas 待确认操作持久化 (operation store)', () => {
  it('按冻结顺序持久化与加载，并与草稿记录完全分离', async () => {
    const idbFactory = createMockIDBFactory()
    await saveCanvasOperation(operation(2, 'key-2', 'second'), {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })
    await saveCanvasOperation(operation(1, 'key-1', 'first'), {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })

    const loaded = await loadCanvasOperations(CANVAS_ID, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })

    // 与写入顺序无关，严格按 sequence 升序恢复，保证重放顺序确定。
    expect(loaded.map((item) => item.idempotencyKey)).toEqual(['key-1', 'key-2'])
    expect(loaded[0]?.commands).toEqual([renameCommand('first')])
    expect(loaded[0]?.baselineRevision).toBe('7')
    expect(loaded[0]?.ack).toEqual([{ nodeId: NODE_ID, field: 'text', generation: 1 }])
  })

  it('删除单个待确认操作后不再被恢复', async () => {
    const idbFactory = createMockIDBFactory()
    const saved = operation(1, 'key-1', 'first')
    await saveCanvasOperation(saved, { idbFactory, userId: 'user-1', editingSessionId: 'tab-1' })
    await deleteCanvasOperation(saved.id, { idbFactory, userId: 'user-1', editingSessionId: 'tab-1' })

    const loaded = await loadCanvasOperations(CANVAS_ID, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })
    expect(loaded).toHaveLength(0)
  })

  it('不同编辑会话（标签页）严格隔离待确认操作', async () => {
    const idbFactory = createMockIDBFactory()
    await saveCanvasOperation(operation(1, 'key-1', 'first', 'tab-1'), {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })

    const otherTab = await loadCanvasOperations(CANVAS_ID, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-2',
    })
    expect(otherTab).toHaveLength(0)
  })

  it('事务 abort 时 reject 并上报存储异常，绝不假装落盘成功', async () => {
    const idbFactory = createMockIDBFactory({ shouldAbortTransaction: true })
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)

    await expect(saveCanvasOperation(operation(1, 'key-1', 'first'), {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })).rejects.toThrow()

    expect(onError).toHaveBeenCalled()
    unsubscribe()
  })

  it('IndexedDB 不可用时明确 reject 并告警，绝不让调用方误以为已落盘', async () => {
    Object.defineProperty(window, 'indexedDB', { configurable: true, writable: true, value: undefined })
    resetCanvasOperationStorage()
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)

    await expect(saveCanvasOperation(operation(1, 'key-1', 'first'), {
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })).rejects.toMatchObject({ name: 'CanvasStorageUnavailableError' })

    // 同一会话内只告警一次，避免刷屏。
    await expect(loadCanvasOperations(CANVAS_ID, {
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })).rejects.toMatchObject({ name: 'CanvasStorageUnavailableError' })

    expect(onError).toHaveBeenCalledTimes(1)
    expect((onError.mock.calls[0]?.[0] as Error).name).toBe('CanvasStorageUnavailableError')
    unsubscribe()
  })

  it('打开 / 读取 / 删除失败时 reject 并上报，删除在不可用时为幂等空操作', async () => {
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)

    const openFailure = createMockIDBFactory({ shouldFailOpen: true })
    await expect(saveCanvasOperation(operation(1, 'key-1', 'first'), {
      idbFactory: openFailure,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })).rejects.toThrow()
    await expect(loadCanvasOperations(CANVAS_ID, {
      idbFactory: openFailure,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })).rejects.toThrow()
    expect(onError).toHaveBeenCalled()

    const readFailure = createMockIDBFactory({ shouldFailGetAll: true })
    await expect(loadCanvasOperations(CANVAS_ID, {
      idbFactory: readFailure,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })).rejects.toThrow()

    const deleteFailure = createMockIDBFactory({ shouldFailDelete: true })
    await expect(deleteCanvasOperation('missing-id', {
      idbFactory: deleteFailure,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })).rejects.toThrow()

    // 不可用时没有可落盘内容，删除是幂等空操作。
    Object.defineProperty(window, 'indexedDB', { configurable: true, writable: true, value: undefined })
    await expect(deleteCanvasOperation('missing-id', {
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })).resolves.toBeUndefined()
    unsubscribe()
  })

  it('事务 error / abort、事务创建失败与提交失败都 reject 并上报', async () => {
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)
    const scoped = { userId: 'user-1', editingSessionId: 'tab-1' }

    const txError = createMockIDBFactory({ shouldFailTransaction: true })
    await expect(saveCanvasOperation(operation(1, 'key-1', 'first'), { ...scoped, idbFactory: txError }))
      .rejects.toThrow()
    await expect(deleteCanvasOperation('op-1', { ...scoped, idbFactory: txError })).rejects.toThrow()
    await loadCanvasOperations(CANVAS_ID, { ...scoped, idbFactory: txError })

    const abortFactory = createMockIDBFactory({ shouldAbortTransaction: true })
    await expect(deleteCanvasOperation('op-1', { ...scoped, idbFactory: abortFactory })).rejects.toThrow()

    const createFailure = createMockIDBFactory({ shouldFailTransactionCreate: true })
    await expect(saveCanvasOperation(operation(1, 'key-1', 'first'), { ...scoped, idbFactory: createFailure }))
      .rejects.toThrow()
    await expect(deleteCanvasOperation('op-1', { ...scoped, idbFactory: createFailure })).rejects.toThrow()
    await expect(loadCanvasOperations(CANVAS_ID, { ...scoped, idbFactory: createFailure })).rejects.toThrow()

    const putFailure = createMockIDBFactory({ shouldFailRequest: true })
    await expect(saveCanvasOperation(operation(1, 'key-1', 'first'), { ...scoped, idbFactory: putFailure }))
      .rejects.toThrow()

    expect(onError).toHaveBeenCalled()
    await new Promise((resolve) => setTimeout(resolve, 30))
    unsubscribe()
  })

  it('sequence 相同时按 createdAt 稳定排序，未显式指定 ack 时为空数组', async () => {
    const idbFactory = createMockIDBFactory()
    const scoped = { idbFactory, userId: 'user-1', editingSessionId: 'tab-1' }

    const later = buildPendingOperation({
      canvasId: CANVAS_ID,
      userId: 'user-1',
      editingSessionId: 'tab-1',
      idempotencyKey: 'key-later',
      commands: [renameCommand('later')],
      baselineRevision: '7',
      sequence: 1,
      createdAt: 300,
    })
    const earlier = buildPendingOperation({
      canvasId: CANVAS_ID,
      userId: 'user-1',
      editingSessionId: 'tab-1',
      idempotencyKey: 'key-earlier',
      commands: [renameCommand('earlier')],
      baselineRevision: '7',
      sequence: 1,
      createdAt: 100,
    })

    await saveCanvasOperation(later, scoped)
    await saveCanvasOperation(earlier, scoped)

    const loaded = await loadCanvasOperations(CANVAS_ID, scoped)
    expect(loaded.map((item) => item.createdAt)).toEqual([100, 300])
    expect(loaded[0]?.ack).toEqual([])
  })
})
