import { describe, expect, it, vi } from 'vitest'
import {
  buildSessionKey,
  onCanvasStorageError,
  openCanvasDatabase,
  resolveIdbFactory,
} from '@/features/canvas/canvas-local-store'
import { createMockIDBFactory } from './mock-idb'

describe('Canvas 本地持久化公共基座', () => {
  it('解析 IDBFactory：优先显式传入，其次 window.indexedDB，缺失时为 null', () => {
    const explicit = createMockIDBFactory()
    expect(resolveIdbFactory({ idbFactory: explicit })).toBe(explicit)

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

  it('会话键由登录身份、画布与编辑会话组成', () => {
    expect(buildSessionKey('user-1', 'canvas-1', 'tab-1')).toBe('user-1:canvas-1:tab-1')
  })
})
