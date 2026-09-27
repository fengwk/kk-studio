/**
 * Canvas 本地持久化公共基座 (IndexedDB)
 *
 * 依据 docs/canvas-project.md §2.4：
 * - 本地状态按“登录身份 + 画布 + 编辑会话”隔离，不同用途各自使用独立 object store，
 *   绝不把“待确认操作”塞进草稿记录后再靠可选字段猜测。
 * - 写入必须等待 `transaction.oncomplete` 才算真正落盘；`onabort`/`onerror` 一律上报，
 *   绝不依据 `request.onsuccess` 提前宣称已持久化。
 * - IndexedDB 不可用时绝不假装已落盘：内存回退可用于当前标签页会话，但必须向调用方告警。
 */

/** 未接入登录态前的稳定匿名身份。 */
export const DEFAULT_ANONYMOUS_USER = 'anonymous'

export interface CanvasLocalStoreOptions {
  userId?: string
  editingSessionId?: string
  idbFactory?: IDBFactory
}

export function getCurrentUserId(): string {
  return DEFAULT_ANONYMOUS_USER
}

/** IndexedDB 在当前环境不可用；本地状态只能在当前标签页会话内保留。 */
export class CanvasStorageUnavailableError extends Error {
  constructor(
    message = '本机持久化不可用（IndexedDB 被禁用或缺失）；本地状态仅保留在当前标签页会话，刷新或关闭标签页将丢失。',
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

export function buildSessionKey(userId: string, canvasId: string, editingSessionId: string): string {
  return `${userId}:${canvasId}:${editingSessionId}`
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
