/**
 * Canvas 草稿持久化存储 (IndexedDB)
 *
 * 依据 docs/canvas-project.md §2.4：
 * - IndexedDB 按登录身份、画布和编辑会话保存已落盘草稿及待确认操作。
 * - 多标签页（编辑会话）互不覆盖：不同 tab 拥有独立的 editingSessionId。
 * - 刷新、路由切换、重连后先从存储恢复草稿，再对照权威快照。
 * - 落盘失败必须通知调用方并显示告警；未完成落盘的输入绝不宣称已持久化。
 * - 写入必须等待 transaction.oncomplete 才算真正持久化落盘，绝不可仅依据 request.onsuccess 提前 resolve。
 */

import type { CanvasCommandDTO } from '@/shared/api/contracts/studio'
import type { CanvasNodeDraft } from './canvas-drafts'

const DB_NAME = 'kkstudio.canvas.drafts'
const DB_VERSION = 2
const STORE_NAME = 'drafts'

const EDITING_SESSION_STORAGE_KEY = 'kkstudio.canvas.editingSessionId'

export const DEFAULT_ANONYMOUS_USER = 'anonymous'

export function getCurrentUserId(): string {
  return DEFAULT_ANONYMOUS_USER
}

export type StorageErrorListener = (error: Error) => void
const storageErrorListeners = new Set<StorageErrorListener>()

export function onDraftStorageError(listener: StorageErrorListener): () => void {
  storageErrorListeners.add(listener)
  return () => {
    storageErrorListeners.delete(listener)
  }
}

function notifyStorageError(error: Error): void {
  for (const listener of storageErrorListeners) {
    try {
      listener(error)
    } catch {
      // 避免监听器本身报错阻断
    }
  }
}

/**
 * 获取当前标签页/客户端的编辑会话标识 (editingSessionId)。
 * 利用 sessionStorage 在标签页独立但页面刷新/路由切换时保留的特性，
 * 保证多标签页互不覆盖，单标签页刷新/重连后可稳定恢复。
 */
export function getEditingSessionId(): string {
  if (typeof window === 'undefined' || !window.sessionStorage) {
    return 'default-session'
  }
  try {
    let sessionId = window.sessionStorage.getItem(EDITING_SESSION_STORAGE_KEY)
    if (!sessionId) {
      sessionId = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
        ? crypto.randomUUID()
        : `session-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
      window.sessionStorage.setItem(EDITING_SESSION_STORAGE_KEY, sessionId)
    }
    return sessionId
  } catch {
    return 'default-session'
  }
}

export interface CanvasDraftRecord {
  /** 唯一主键：`${userId}:${canvasId}:${editingSessionId}:${nodeId}` */
  id: string
  userId: string
  canvasId: string
  editingSessionId: string
  nodeId: string
  /** 对应 `sessionKey` 复合检索索引：`${userId}:${canvasId}:${editingSessionId}` */
  sessionKey: string
  draft: CanvasNodeDraft
  /** 冻结在途请求与基线，保证多标签/离线恢复时精确原样重放 */
  requestId?: string
  commands?: CanvasCommandDTO[]
  expectedBaseline?: Record<string, unknown>
  updatedAt: number
}

export interface CanvasDraftStorageOptions {
  userId?: string
  editingSessionId?: string
  idbFactory?: IDBFactory
  requestId?: string
  commands?: CanvasCommandDTO[]
  expectedBaseline?: Record<string, unknown>
}

function resolveFactory(options?: CanvasDraftStorageOptions): IDBFactory | null {
  if (options?.idbFactory) {
    return options.idbFactory
  }
  if (typeof window !== 'undefined' && window.indexedDB) {
    return window.indexedDB
  }
  return null
}

function buildRecordKey(userId: string, canvasId: string, editingSessionId: string, nodeId: string): string {
  return `${userId}:${canvasId}:${editingSessionId}:${nodeId}`
}

function buildSessionKey(userId: string, canvasId: string, editingSessionId: string): string {
  return `${userId}:${canvasId}:${editingSessionId}`
}

function openDatabase(factory: IDBFactory): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    try {
      const request = factory.open(DB_NAME, DB_VERSION)

      request.onupgradeneeded = (event) => {
        const db = request.result
        let store: IDBObjectStore
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          store = db.createObjectStore(STORE_NAME, { keyPath: 'id' })
        } else {
          store = (event.target as IDBOpenDBRequest).transaction!.objectStore(STORE_NAME)
        }

        if (!store.indexNames.contains('sessionKey')) {
          store.createIndex('sessionKey', 'sessionKey', { unique: false })
        }
        if (!store.indexNames.contains('canvasId')) {
          store.createIndex('canvasId', 'canvasId', { unique: false })
        }
        if (!store.indexNames.contains('userId')) {
          store.createIndex('userId', 'userId', { unique: false })
        }
      }

      request.onsuccess = () => resolve(request.result)
      request.onerror = () => {
        const err = request.error ?? new Error('Failed to open Canvas Drafts database')
        notifyStorageError(err)
        reject(err)
      }
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err))
      notifyStorageError(error)
      reject(error)
    }
  })
}

// 内存降级回退（用于不支持 IndexedDB 的环境）
const memoryFallbackStore = new Map<string, CanvasDraftRecord>()

export function resetDraftStorageFallback(): void {
  memoryFallbackStore.clear()
}

/**
 * 加载特定用户、画布和编辑会话下的所有本地草稿。
 */
export async function loadCanvasDrafts(
  canvasId: string,
  options?: CanvasDraftStorageOptions,
): Promise<Record<string, CanvasNodeDraft>> {
  const userId = options?.userId ?? getCurrentUserId()
  const editingSessionId = options?.editingSessionId ?? getEditingSessionId()
  const targetSessionKey = buildSessionKey(userId, canvasId, editingSessionId)
  const factory = resolveFactory(options)

  if (!factory) {
    const result: Record<string, CanvasNodeDraft> = {}
    for (const record of memoryFallbackStore.values()) {
      if (record.sessionKey === targetSessionKey) {
        result[record.nodeId] = record.draft
      }
    }
    return result
  }

  const db = await openDatabase(factory)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const store = tx.objectStore(STORE_NAME)
      const index = store.index('sessionKey')
      const request = index.getAll(targetSessionKey)

      request.onsuccess = () => {
        const records = (request.result ?? []) as CanvasDraftRecord[]
        const drafts: Record<string, CanvasNodeDraft> = {}
        for (const record of records) {
          drafts[record.nodeId] = record.draft
        }
        resolve(drafts)
      }

      request.onerror = () => {
        const err = request.error ?? new Error('Failed to load drafts from IndexedDB')
        notifyStorageError(err)
        reject(err)
      }

      tx.onerror = () => {
        const err = tx.error ?? new Error('Transaction error while loading drafts')
        notifyStorageError(err)
        reject(err)
      }
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err))
      notifyStorageError(error)
      reject(error)
    }
  })
}

/**
 * 持久化单个节点的草稿。
 * 严格遵循真实落盘原则：必须等待 tx.oncomplete 完成，任何 abort 或 error 均 reject，不向调用方谎报已持久化。
 */
export async function saveCanvasDraft(
  canvasId: string,
  nodeId: string,
  draft: CanvasNodeDraft,
  options?: CanvasDraftStorageOptions,
): Promise<void> {
  const userId = options?.userId ?? getCurrentUserId()
  const editingSessionId = options?.editingSessionId ?? getEditingSessionId()
  const id = buildRecordKey(userId, canvasId, editingSessionId, nodeId)
  const sessionKey = buildSessionKey(userId, canvasId, editingSessionId)

  const record: CanvasDraftRecord = {
    id,
    userId,
    canvasId,
    editingSessionId,
    nodeId,
    sessionKey,
    draft,
    requestId: options?.requestId ?? draft.requestId,
    commands: options?.commands ?? draft.commands,
    expectedBaseline: options?.expectedBaseline ?? (draft.baseline as Record<string, unknown> | undefined),
    updatedAt: draft.updatedAt || Date.now(),
  }

  const factory = resolveFactory(options)
  if (!factory) {
    memoryFallbackStore.set(id, record)
    return
  }

  const db = await openDatabase(factory)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      const putReq = store.put(record)

      putReq.onerror = () => {
        const err = putReq.error ?? new Error(`Failed to put draft for node ${nodeId}`)
        notifyStorageError(err)
        reject(err)
      }

      tx.oncomplete = () => {
        resolve()
      }

      tx.onabort = () => {
        const err = tx.error ?? new Error(`Transaction aborted while persisting draft for node ${nodeId}`)
        notifyStorageError(err)
        reject(err)
      }

      tx.onerror = () => {
        const err = tx.error ?? new Error(`Transaction failed while persisting draft for node ${nodeId}`)
        notifyStorageError(err)
        reject(err)
      }
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err))
      notifyStorageError(error)
      reject(error)
    }
  })
}

/**
 * 删除单个节点的草稿。
 * 必须等待 tx.oncomplete。
 */
export async function deleteCanvasDraft(
  canvasId: string,
  nodeId: string,
  options?: CanvasDraftStorageOptions,
): Promise<void> {
  const userId = options?.userId ?? getCurrentUserId()
  const editingSessionId = options?.editingSessionId ?? getEditingSessionId()
  const id = buildRecordKey(userId, canvasId, editingSessionId, nodeId)

  const factory = resolveFactory(options)
  if (!factory) {
    memoryFallbackStore.delete(id)
    return
  }

  const db = await openDatabase(factory)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      const delReq = store.delete(id)

      delReq.onerror = () => {
        const err = delReq.error ?? new Error(`Failed to delete draft for node ${nodeId}`)
        notifyStorageError(err)
        reject(err)
      }

      tx.oncomplete = () => {
        resolve()
      }

      tx.onabort = () => {
        const err = tx.error ?? new Error(`Transaction aborted while deleting draft for node ${nodeId}`)
        notifyStorageError(err)
        reject(err)
      }

      tx.onerror = () => {
        const err = tx.error ?? new Error(`Transaction failed while deleting draft for node ${nodeId}`)
        notifyStorageError(err)
        reject(err)
      }
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err))
      notifyStorageError(error)
      reject(error)
    }
  })
}

/**
 * 清除当前会话、画布下的全部草稿。
 */
export async function clearCanvasDrafts(
  canvasId: string,
  options?: CanvasDraftStorageOptions,
): Promise<void> {
  const userId = options?.userId ?? getCurrentUserId()
  const editingSessionId = options?.editingSessionId ?? getEditingSessionId()
  const targetSessionKey = buildSessionKey(userId, canvasId, editingSessionId)

  const factory = resolveFactory(options)
  if (!factory) {
    for (const [key, record] of memoryFallbackStore.entries()) {
      if (record.sessionKey === targetSessionKey) {
        memoryFallbackStore.delete(key)
      }
    }
    return
  }

  const db = await openDatabase(factory)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      const index = store.index('sessionKey')
      const request = index.getAll(targetSessionKey)

      request.onsuccess = () => {
        const records = (request.result ?? []) as CanvasDraftRecord[]
        for (const record of records) {
          store.delete(record.id)
        }
      }

      request.onerror = () => {
        const err = request.error ?? new Error('Failed to query session drafts for clearing')
        notifyStorageError(err)
        reject(err)
      }

      tx.oncomplete = () => {
        resolve()
      }

      tx.onabort = () => {
        const err = tx.error ?? new Error('Transaction aborted while clearing session drafts')
        notifyStorageError(err)
        reject(err)
      }

      tx.onerror = () => {
        const err = tx.error ?? new Error('Transaction failed while clearing session drafts')
        notifyStorageError(err)
        reject(err)
      }
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err))
      notifyStorageError(error)
      reject(error)
    }
  })
}
