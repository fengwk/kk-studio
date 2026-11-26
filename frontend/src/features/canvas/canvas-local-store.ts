/**
 * Canvas 本地持久化公共基座 (IndexedDB)
 *
 * - 本地状态按用户、画布视图与编辑会话隔离，不同用途各自使用独立 object store，
 *   绝不把“待确认操作”塞进草稿记录后再靠可选字段猜测。
 * - 写入必须等待 `transaction.oncomplete` 才算真正落盘；`onabort`/`onerror` 一律上报，
 *   绝不依据 `request.onsuccess` 提前宣称已持久化。
 * - IndexedDB 不可用时绝不假装已落盘：内存回退可用于当前标签页会话，但必须向调用方告警。
 */

import { canvasViewKey } from './canvas-view-key'

export interface CanvasLocalStoreOptions {
  userId: string
  editingSessionId?: string
  viewSlot: number
  idbFactory?: IDBFactory
}

/** IndexedDB 在当前环境不可用；本地状态只能在当前标签页会话内保留。文案由渲染层按 locale 生成。 */
export class CanvasStorageUnavailableError extends Error {
  constructor(
    message = 'IndexedDB is unavailable; drafts stay in this tab session only',
  ) {
    super(message)
    this.name = 'CanvasStorageUnavailableError'
  }
}

export function toStorageError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error))
}

export function resolveIdbFactory(options?: CanvasLocalStoreOptions): IDBFactory | null {
  if (options?.idbFactory) {
    return options.idbFactory
  }
  if (typeof window !== 'undefined' && window.indexedDB) {
    return window.indexedDB
  }
  return null
}

export function buildSessionKey(userId: string, canvasId: string, editingSessionId: string, viewSlot: number): string {
  return `${userId}:${canvasViewKey(canvasId, viewSlot)}:${editingSessionId}`
}

export type CanvasStorageErrorListener = (error: Error) => void

const storageErrorListeners = new Set<CanvasStorageErrorListener>()

/** 订阅本地持久化异常（落盘失败 / IndexedDB 不可用），用于向用户显示告警。 */
export function onCanvasStorageError(listener: CanvasStorageErrorListener): () => void {
  storageErrorListeners.add(listener)
  return () => {
    storageErrorListeners.delete(listener)
  }
}

export function notifyCanvasStorageError(error: Error): void {
  for (const listener of storageErrorListeners) {
    try {
      listener(error)
    } catch {
      // 单个监听器异常不能阻断其他监听器。
    }
  }
}

/**
 * 打开（必要时升级）本地数据库。升级回调在 versionchange 事务内创建 object store 与索引。
 */
export function openCanvasDatabase(
  factory: IDBFactory,
  databaseName: string,
  version: number,
  upgrade: (db: IDBDatabase, transaction: IDBTransaction | null, oldVersion: number) => void,
): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    try {
      const request = factory.open(databaseName, version)

      request.onupgradeneeded = (event) => {
        const transaction = (event.target as IDBOpenDBRequest | null)?.transaction ?? null
        upgrade(request.result, transaction, event.oldVersion)
      }

      request.onsuccess = () => {
        resolve(request.result)
      }

      request.onerror = () => {
        const error = request.error ?? new Error(`Failed to open ${databaseName}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
    } catch (error) {
      const storageError = toStorageError(error)
      notifyCanvasStorageError(storageError)
      reject(storageError)
    }
  })
}
