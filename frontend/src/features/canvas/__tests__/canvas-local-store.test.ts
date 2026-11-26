import { describe, expect, it, vi } from 'vitest'
import {
  buildSessionKey,
  onCanvasStorageError,
  openCanvasDatabase,
  resolveIdbFactory,
} from '@/features/canvas/canvas-local-store'
import { createMockIDBFactory } from '@/test-support/mock-idb'

describe('Canvas 本地持久化公共基座', () => {
  it('解析 IDBFactory：优先显式传入，其次 window.indexedDB，缺失时为 null', () => {
    const explicit = createMockIDBFactory()
    expect(resolveIdbFactory({ viewSlot: 0, idbFactory: explicit })).toBe(explicit)

    const globalFactory = createMockIDBFactory()
    Object.defineProperty(window, 'indexedDB', { configurable: true, writable: true, value: globalFactory })
    expect(resolveIdbFactory()).toBe(globalFactory)

    Object.defineProperty(window, 'indexedDB', { configurable: true, writable: true, value: undefined })
    expect(resolveIdbFactory()).toBeNull()
  })

  it('打开数据库成功时执行升级回调并返回连接', async () => {
    const factory = createMockIDBFactory()
    const upgrade = vi.fn()
    const db = await openCanvasDatabase(factory, 'test-db', 1, upgrade)
    expect(db).toBeTruthy()
    expect(upgrade).toHaveBeenCalled()
  })

  it('打开失败（同步抛异常 / 请求 error）时 reject 并上报存储异常', async () => {
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)

    const throwingFactory = {
      open: () => {
        throw new Error('open threw')
      },
      deleteDatabase: () => {
        throw new Error('not implemented')
      },
    } as unknown as IDBFactory
    await expect(openCanvasDatabase(throwingFactory, 'test-db', 1, () => undefined)).rejects.toThrow('open threw')
    expect(onError).toHaveBeenCalled()

    await expect(openCanvasDatabase(createMockIDBFactory({ shouldFailOpen: true }), 'test-db', 1, () => undefined))
      .rejects.toThrow()
    unsubscribe()
  })

  it('会话键包含登录身份、画布、view slot 与编辑会话，且不保留旧命名空间', () => {
    expect(buildSessionKey('user-1', 'canvas-1', 'tab-1', 0)).toBe('user-1:canvas-1/0:tab-1')
    expect(buildSessionKey('user-1', 'canvas-1', 'tab-1', 1)).toBe('user-1:canvas-1/1:tab-1')
    expect(buildSessionKey('user-1', 'canvas-1', 'tab-1', 0)).not.toBe('user-1:canvas-1:tab-1')
    expect(buildSessionKey('user-1', 'canvas-1', 'tab-1', 0)).not.toBe(buildSessionKey('user-1', 'canvas-1', 'tab-1', 1))
  })
})
