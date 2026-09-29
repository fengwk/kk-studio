/**
 * Canvas 草稿持久化存储 (IndexedDB)
 *
 * 依据 docs/canvas-project.md §2.4：
 * - IndexedDB 按登录身份、画布和编辑会话保存已落盘草稿；待确认操作另有独立 store，
 *   草稿记录只承载编辑内容本身，绝不与冻结请求混存。
 * - 多标签页（编辑会话）互不覆盖：不同 tab 拥有独立的 editingSessionId。
 * - 刷新、路由切换、重连后先从存储恢复草稿，再对照权威快照。
 * - 落盘失败必须通知调用方并显示告警；未完成落盘的输入绝不宣称已持久化。
 * - 写入必须等待 transaction.oncomplete 才算真正持久化落盘，绝不可仅依据 request.onsuccess 提前 resolve。
 */

import {
  buildSessionKey,
  CanvasStorageUnavailableError,
  getCurrentUserId,
  notifyCanvasStorageError,
  openCanvasDatabase,
  resolveIdbFactory,
  toStorageError,
  type CanvasLocalStoreOptions,
} from '@/features/canvas/canvas-local-store'
import { getEditingSessionId } from '@/features/canvas/canvas-editing-session'
import { removeDraftField, type CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import type { CanvasDraftAck } from '@/features/canvas/canvas-operation-storage'

const DB_NAME = 'kkstudio.canvas.drafts'
const DB_VERSION = 2
const STORE_NAME = 'drafts'
const SESSION_INDEX = 'sessionKey'

export type CanvasDraftStorageOptions = CanvasLocalStoreOptions

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
  updatedAt: number
}

function buildRecordKey(userId: string, canvasId: string, editingSessionId: string, nodeId: string): string {
  return `${userId}:${canvasId}:${editingSessionId}:${nodeId}`
}

function resolveScope(canvasId: string, options?: CanvasDraftStorageOptions) {
  const userId = options?.userId ?? getCurrentUserId()
  const editingSessionId = options?.editingSessionId ?? getEditingSessionId()
  return {
    userId,
    editingSessionId,
    sessionKey: buildSessionKey(userId, canvasId, editingSessionId),
  }
}

function upgradeDrafts(db: IDBDatabase, transaction: IDBTransaction | null): void {
  let store: IDBObjectStore
  if (!db.objectStoreNames.contains(STORE_NAME)) {
    store = db.createObjectStore(STORE_NAME, { keyPath: 'id' })
  } else {
    store = (transaction?.objectStore(STORE_NAME)) as IDBObjectStore
  }

  if (!store.indexNames.contains(SESSION_INDEX)) {
    store.createIndex(SESSION_INDEX, 'sessionKey', { unique: false })
  }
  if (!store.indexNames.contains('canvasId')) {
    store.createIndex('canvasId', 'canvasId', { unique: false })
  }
  if (!store.indexNames.contains('userId')) {
    store.createIndex('userId', 'userId', { unique: false })
  }
}

/** 内存降级回退（用于 IndexedDB 不可用的环境），仅服务当前标签页会话。 */
const memoryFallbackStore = new Map<string, CanvasDraftRecord>()
let unavailableNotified = false

/** 仅供测试：清空内存回退与“不可用”告警标记。 */
export function resetDraftStorageFallback(): void {
  memoryFallbackStore.clear()
  unavailableNotified = false
}

function noticeUnavailable(): void {
  if (unavailableNotified) {
    return
  }
  unavailableNotified = true
  notifyCanvasStorageError(new CanvasStorageUnavailableError())
}

/**
 * 加载特定用户、画布和编辑会话下的所有本地草稿。
 */
export async function loadCanvasDrafts(
  canvasId: string,
  options?: CanvasDraftStorageOptions,
): Promise<Record<string, CanvasNodeDraft>> {
  const scope = resolveScope(canvasId, options)
  const factory = resolveIdbFactory(options)

  if (!factory) {
    const result: Record<string, CanvasNodeDraft> = {}
    for (const record of memoryFallbackStore.values()) {
      if (record.sessionKey === scope.sessionKey) {
        result[record.nodeId] = record.draft
      }
    }
    return result
  }

  const db = await openCanvasDatabase(factory, DB_NAME, DB_VERSION, upgradeDrafts)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const request = tx.objectStore(STORE_NAME).index(SESSION_INDEX).getAll(scope.sessionKey)

      request.onsuccess = () => {
        const records = (request.result ?? []) as CanvasDraftRecord[]
        const drafts: Record<string, CanvasNodeDraft> = {}
        for (const record of records) {
          drafts[record.nodeId] = record.draft
        }
        resolve(drafts)
      }
      request.onerror = () => {
        const error = request.error ?? new Error('Failed to load drafts from IndexedDB')
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.onerror = () => {
        const error = tx.error ?? new Error('Transaction error while loading drafts')
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
  const scope = resolveScope(canvasId, options)
  const record: CanvasDraftRecord = {
    id: buildRecordKey(scope.userId, canvasId, scope.editingSessionId, nodeId),
    userId: scope.userId,
    canvasId,
    editingSessionId: scope.editingSessionId,
    nodeId,
    sessionKey: scope.sessionKey,
    draft,
    updatedAt: draft.updatedAt || Date.now(),
  }

  const factory = resolveIdbFactory(options)
  if (!factory) {
    memoryFallbackStore.set(record.id, record)
    noticeUnavailable()
    return
  }

  const db = await openCanvasDatabase(factory, DB_NAME, DB_VERSION, upgradeDrafts)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const putReq = tx.objectStore(STORE_NAME).put(record)

      putReq.onerror = () => {
        const error = putReq.error ?? new Error(`Failed to put draft for node ${nodeId}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.oncomplete = () => {
        resolve()
      }
      tx.onabort = () => {
        const error = tx.error ?? new Error(`Transaction aborted while persisting draft for node ${nodeId}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.onerror = () => {
        const error = tx.error ?? new Error(`Transaction failed while persisting draft for node ${nodeId}`)
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

/**
 * 删除单个节点的草稿。
 * 必须等待 tx.oncomplete。
 */
export async function deleteCanvasDraft(
  canvasId: string,
  nodeId: string,
  options?: CanvasDraftStorageOptions,
): Promise<void> {
  const scope = resolveScope(canvasId, options)
  const id = buildRecordKey(scope.userId, canvasId, scope.editingSessionId, nodeId)

  const factory = resolveIdbFactory(options)
  if (!factory) {
    memoryFallbackStore.delete(id)
    return
  }

  const db = await openCanvasDatabase(factory, DB_NAME, DB_VERSION, upgradeDrafts)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const deleteReq = tx.objectStore(STORE_NAME).delete(id)

      deleteReq.onerror = () => {
        const error = deleteReq.error ?? new Error(`Failed to delete draft for node ${nodeId}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.oncomplete = () => {
        resolve()
      }
      tx.onabort = () => {
        const error = tx.error ?? new Error(`Transaction aborted while deleting draft for node ${nodeId}`)
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.onerror = () => {
        const error = tx.error ?? new Error(`Transaction failed while deleting draft for node ${nodeId}`)
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

/**
 * 清除当前会话、画布下的全部草稿。
 */
export async function clearCanvasDrafts(
  canvasId: string,
  options?: CanvasDraftStorageOptions,
): Promise<void> {
  const scope = resolveScope(canvasId, options)

  const factory = resolveIdbFactory(options)
  if (!factory) {
    for (const [key, record] of memoryFallbackStore.entries()) {
      if (record.sessionKey === scope.sessionKey) {
        memoryFallbackStore.delete(key)
      }
    }
    return
  }

  const db = await openCanvasDatabase(factory, DB_NAME, DB_VERSION, upgradeDrafts)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)
      const request = store.index(SESSION_INDEX).getAll(scope.sessionKey)

      request.onsuccess = () => {
        const records = (request.result ?? []) as CanvasDraftRecord[]
        for (const record of records) {
          store.delete(record.id)
        }
      }
      request.onerror = () => {
        const error = request.error ?? new Error('Failed to query session drafts for clearing')
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.oncomplete = () => {
        resolve()
      }
      tx.onabort = () => {
        const error = tx.error ?? new Error('Transaction aborted while clearing session drafts')
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.onerror = () => {
        const error = tx.error ?? new Error('Transaction failed while clearing session drafts')
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

/**
 * 依据 CanvasDraftAck 列表，在持久层精确清除匹配 generation 的草稿字段。
 * 必须在单个 readwrite transaction 中执行，严格等待 transaction.oncomplete 才算持久落盘完成。
 * 绝不可先删除 operation 后清草稿；如果 transaction abort/error，Promise 立即 reject。
 */
export async function ackCanvasDrafts(
  canvasId: string,
  acks: CanvasDraftAck[],
  options?: CanvasDraftStorageOptions,
): Promise<void> {
  if (acks.length === 0) {
    return
  }
  const scope = resolveScope(canvasId, options)
  const factory = resolveIdbFactory(options)

  if (!factory) {
    for (const ack of acks) {
      const key = buildRecordKey(scope.userId, canvasId, scope.editingSessionId, ack.nodeId)
      const record = memoryFallbackStore.get(key)
      if (record && record.draft) {
        const field = ack.field === 'group' ? 'groupId' : ack.field
        const next = removeDraftField(record.draft, field, ack.generation)
        if (next) {
          memoryFallbackStore.set(key, { ...record, draft: next, updatedAt: Date.now() })
        } else {
          memoryFallbackStore.delete(key)
        }
      }
    }
    return
  }

  const db = await openCanvasDatabase(factory, DB_NAME, DB_VERSION, upgradeDrafts)
  return new Promise((resolve, reject) => {
    try {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const store = tx.objectStore(STORE_NAME)

      for (const ack of acks) {
        const key = buildRecordKey(scope.userId, canvasId, scope.editingSessionId, ack.nodeId)
        const getReq = store.get(key)
        getReq.onsuccess = () => {
          const record = getReq.result as CanvasDraftRecord | undefined
          if (!record || !record.draft) {
            return
          }
          const field = ack.field === 'group' ? 'groupId' : ack.field
          const next = removeDraftField(record.draft, field, ack.generation)
          if (next) {
            store.put({ ...record, draft: next, updatedAt: Date.now() })
          } else {
            store.delete(key)
          }
        }
        getReq.onerror = () => {
          // Transaction will abort on error
        }
      }

      tx.oncomplete = () => {
        resolve()
      }
      tx.onabort = () => {
        const error = tx.error ?? new Error('Transaction aborted while applying draft ACKs')
        notifyCanvasStorageError(error)
        reject(error)
      }
      tx.onerror = () => {
        const error = tx.error ?? new Error('Transaction failed while applying draft ACKs')
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
