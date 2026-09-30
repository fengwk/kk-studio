import { describe, expect, it, vi } from 'vitest'
import { createMockIDBFactory } from './mock-idb'
import {
  buildPendingOperation,
  createCanvasOperationStore,
  deleteCanvasOperation,
  loadCanvasOperations,
  resetCanvasOperationStorage,
  saveCanvasOperation,
  type CanvasPendingOperation,
} from '@/features/canvas/canvas-operation-storage'
import {
  CanvasStorageUnavailableError,
  onCanvasStorageError,
} from '@/features/canvas/canvas-local-store'
import {
  handlePageshow,
  resetEditingSessionForTests,
} from '@/features/canvas/canvas-editing-session'
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
      sequence: 1,
      createdAt: 300,
    })
    const earlier = buildPendingOperation({
      canvasId: CANVAS_ID,
      userId: 'user-1',
      editingSessionId: 'tab-1',
      idempotencyKey: 'key-earlier',
      commands: [renameCommand('earlier')],
      sequence: 1,
      createdAt: 100,
    })

    await saveCanvasOperation(later, scoped)
    await saveCanvasOperation(earlier, scoped)

    const loaded = await loadCanvasOperations(CANVAS_ID, scoped)
    expect(loaded.map((item) => item.createdAt)).toEqual([100, 300])
    expect(loaded[0]?.ack).toEqual([])
  })

  it('reload 现存带有多余旧字段（如 baselineRevision）的持久记录仍可取回命令并保留身份', async () => {
    const idbFactory = createMockIDBFactory()
    const scoped = { idbFactory, userId: 'user-1', editingSessionId: 'tab-1' }
    // 模拟既有数据库中存储了带有旧多余字段的历史记录
    const legacyRecord = {
      id: `user-1:${CANVAS_ID}:tab-1:legacy-key`,
      userId: 'user-1',
      canvasId: CANVAS_ID,
      editingSessionId: 'tab-1',
      sessionKey: `user-1:${CANVAS_ID}:tab-1`,
      idempotencyKey: 'legacy-key',
      commands: [renameCommand('legacy-node')],
      baselineRevision: '42', // 历史多余字段
      unknownExtraField: 'ignored-value',
      ack: [{ nodeId: NODE_ID, field: 'name', generation: 1 }],
      sequence: 1,
      createdAt: 1_700_000_000_000,
    } as unknown as CanvasPendingOperation
    await saveCanvasOperation(legacyRecord, scoped)

    const loaded = await loadCanvasOperations(CANVAS_ID, scoped)
    expect(loaded).toHaveLength(1)
    expect(loaded[0]?.idempotencyKey).toBe('legacy-key')
    expect(loaded[0]?.commands).toEqual([renameCommand('legacy-node')])
    expect(loaded[0]?.ack).toEqual([{ nodeId: NODE_ID, field: 'name', generation: 1 }])
    expect(loaded[0]?.sequence).toBe(1)
  })

  it('I10: sessionReloading 期间 operation store 各入口（save/remove/list）全部 fail-closed 拒写，且不污染已持久化的旧 session 记录', async () => {
    // 测试意图：验证当 bfcache 导致 sessionReloading 为 true 时，待确认操作存储的所有入口（save/remove/list）全部拒绝抛出 CanvasStorageUnavailableError，且旧 session 已存记录完好无损未被篡改
    resetEditingSessionForTests()
    const idbFactory = createMockIDBFactory()
    const validOp = operation(1, 'key-persisted-1', 'initial-state', 'tab-1')

    // 1. 在正常状态下预先写入一条合法记录
    await saveCanvasOperation(validOp, { idbFactory, userId: 'user-1', editingSessionId: 'tab-1' })
    const initialList = await loadCanvasOperations(CANVAS_ID, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })
    expect(initialList).toHaveLength(1)
    expect(initialList[0].idempotencyKey).toBe('key-persisted-1')

    // 2. 在正常状态下创建 store 实例
    const store = createCanvasOperationStore(CANVAS_ID, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })

    // 3. 模拟 bfcache 冲突触发 sessionReloading = true
    const OWNER_STORAGE_KEY = 'kkstudio.canvas.editingSessionOwner'
    const SESSION_STORAGE_KEY = 'kkstudio.canvas.editingSessionId'
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, 'tab-1')
    window.localStorage.setItem(
      OWNER_STORAGE_KEY,
      JSON.stringify({ 'tab-1': { pageId: 'another-live-page', at: Date.now() } }),
    )
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...window.location, reload: vi.fn() },
    })
    handlePageshow({ persisted: true })

    // 4. 测试已建 store 调用 save/remove/list 均拒绝
    const newOp = operation(2, 'key-persisted-2', 'polluting-state', 'tab-1')

    await expect(store.save(newOp)).rejects.toThrow(CanvasStorageUnavailableError)
    await expect(store.remove(validOp.id)).rejects.toThrow(CanvasStorageUnavailableError)
    await expect(store.list()).rejects.toThrow(CanvasStorageUnavailableError)

    // 5. 测试 sessionReloading 期间新建 store 亦直接拒创
    expect(() =>
      createCanvasOperationStore(CANVAS_ID, {
        idbFactory,
        userId: 'user-1',
        editingSessionId: 'tab-1',
      }),
    ).toThrow(CanvasStorageUnavailableError)

    // 6. 测试独立函数 saveCanvasOperation / deleteCanvasOperation / loadCanvasOperations 亦全部 fail-closed
    await expect(saveCanvasOperation(newOp, { idbFactory, userId: 'user-1', editingSessionId: 'tab-1' }))
      .rejects.toThrow(CanvasStorageUnavailableError)
    await expect(deleteCanvasOperation(validOp.id, { idbFactory, userId: 'user-1', editingSessionId: 'tab-1' }))
      .rejects.toThrow(CanvasStorageUnavailableError)
    await expect(loadCanvasOperations(CANVAS_ID, { idbFactory, userId: 'user-1', editingSessionId: 'tab-1' }))
      .rejects.toThrow(CanvasStorageUnavailableError)

    // 7. 恢复会话后验证旧记录未被污染或误删除
    resetEditingSessionForTests()
    const verifyList = await loadCanvasOperations(CANVAS_ID, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })
    expect(verifyList).toHaveLength(1)
    expect(verifyList[0].idempotencyKey).toBe('key-persisted-1')
    expect(verifyList[0].commands).toEqual([renameCommand('initial-state')])
  })
})
