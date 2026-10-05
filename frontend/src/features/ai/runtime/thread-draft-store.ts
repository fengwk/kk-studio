import type { HarnessStoppedThreadReceiptDTO } from '@/shared/api/contracts/ai-runtime'
import {
  hasMessageContent,
  partsKey,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  mergeCancelledGoalTexts,
  prependCancelledMessages,
} from '@/features/ai/runtime/cancelled-message-parts'

/**
 * 绑定 Thread 的本地草稿事实源。
 *
 * 它是每个 Thread 的唯一权威记录：composer 可编辑 parts、Goal 编辑区文本，以及已合并的
 * Stop 回执身份。三者必须在同一个 IndexedDB 事务里更新，才能保证
 *
 * - 同一 Stop 回执从 Stop 响应或 snapshot 重复到达时不会重复回填；
 * - 刷新、多标签或响应丢失后重放时，草稿与「已合并回执」不会各写一半。
 *
 * 读改写都在单 store 的单次 readwrite 事务内完成，因此天然对并发标签页串行化。
 * 平台原生能力足够，不引入任何依赖。
 */
export interface ThreadDraftRecord {
  threadId: string
  /** composer 可编辑 parts（text + durable resource）。 */
  parts: ComposerPart[]
  /** Goal 编辑区未提交文本。 */
  goalText: string | null
  /** 已合并的 Stop 回执身份，按合并顺序。 */
  appliedStopRequestIds: string[]
}

/** 一次 Stop 回执合并的结果；重复回执返回同一份记录，不重复追加。 */
export type StopReceiptMergeResult = ThreadDraftRecord

const DB_NAME = 'kkstudio.thread-drafts'
const DB_VERSION = 1
const STORE_NAME = 'threadDrafts'

// 连接与写入链都按 indexedDB 实现的身份隔离：换了实现就没有共享的连接或排队关系。
let connection: { factory: IDBFactory; database: Promise<IDBDatabase> } | null = null
const writeChains = new WeakMap<IDBFactory, Map<string, Promise<unknown>>>()

function resolveFactory(): IDBFactory | null {
  return typeof globalThis === 'undefined' ? null : globalThis.indexedDB ?? null
}

function openDatabase(factory: IDBFactory): Promise<IDBDatabase> {
  if (connection?.factory === factory) {
    return connection.database
  }
  const database = new Promise<IDBDatabase>((resolve, reject) => {
    const request = factory.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME, { keyPath: 'threadId' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => {
      if (connection?.factory === factory) {
        connection = null
      }
      reject(request.error ?? new Error('Failed to open thread draft database'))
    }
  })
  connection = { factory, database }
  return database
}

/**
 * 同一 Thread 的读改写串行：回执合并与草稿保存共享同一条链，顺序即调用顺序。
 * 连接在入队时固定，迟到的写任务不会落到之后才建立的连接上。
 */
function enqueueWrite<T>(
  threadId: string,
  task: (database: IDBDatabase) => Promise<T>,
): Promise<T> {
  const factory = resolveFactory()
  if (factory == null) {
    return Promise.reject(new Error('IndexedDB is not available'))
  }
  const database = openDatabase(factory)
  const chains = writeChains.get(factory) ?? new Map<string, Promise<unknown>>()
  writeChains.set(factory, chains)
  const previous = chains.get(threadId) ?? Promise.resolve()
  // 任务在链上前一个写完成后才启动，读改写因此严格按调用顺序串行。
  const run = () => database.then((db) => task(db))
  const next = previous.then(run, run)
  chains.set(threadId, next.catch(() => undefined))
  return next
}

/**
 * 读取记录。IndexedDB 不可用时显式抛错，由调用方向用户暴露「草稿不可用」，
 * 绝不以 null 静默充当「没有草稿」。
 */
export async function loadThreadDraft(threadId: string): Promise<ThreadDraftRecord | null> {
  const factory = resolveFactory()
  if (factory == null) {
    throw new Error('IndexedDB is not available')
  }
  const db = await openDatabase(factory)
  return new Promise<ThreadDraftRecord | null>((resolve, reject) => {
    const tx = db.transaction(STORE_NAME, 'readonly')
    const request = tx.objectStore(STORE_NAME).get(threadId)
    request.onsuccess = () => {
      resolve(request.result == null ? null : normalizeRecord(request.result, threadId))
    }
    request.onerror = () => reject(request.error ?? new Error('Failed to read thread draft'))
    tx.onerror = () => reject(tx.error ?? new Error('Failed to read thread draft'))
  })
}

/** 写入 composer 草稿；Goal 文本与已合并回执身份在同一事务内保留。 */
export function saveThreadDraftParts(threadId: string, parts: ComposerPart[]): Promise<void> {
  return enqueueWrite(threadId, async (database) => {
    await updateRecord(database, threadId, (current) => ({ ...current, parts }))
  })
}

/** 写入 Goal 编辑区文本；composer 草稿与已合并回执身份在同一事务内保留。 */
export function saveThreadGoalText(threadId: string, goalText: string | null): Promise<void> {
  return enqueueWrite(threadId, async (database) => {
    await updateRecord(database, threadId, (current) => ({ ...current, goalText }))
  })
}

/**
 * 确定失败后把未消费的消息草稿写回该 Thread 的编辑区。
 *
 * 判定与写入在同一事务、同一条串行链上：只有当记录没有其它内容（或已是同一草稿）时才写入，
 * 因此既不会被同 Thread 更晚的编辑覆盖，也不会覆盖别处更新的草稿。返回是否真的回填。
 */
export function restoreThreadDraftParts(
  threadId: string,
  parts: ComposerPart[],
): Promise<boolean> {
  return enqueueWrite(threadId, async (database) => {
    let restored = false
    await updateRecord(database, threadId, (current) => {
      if (hasMessageContent(current.parts) && partsKey(current.parts) !== partsKey(parts)) {
        return current
      }
      restored = true
      return { ...current, parts }
    })
    return restored
  })
}

/** Goal 编辑区的对应回填；语义与 {@link restoreThreadDraftParts} 一致。 */
export function restoreThreadGoalText(threadId: string, goalText: string): Promise<boolean> {
  return enqueueWrite(threadId, async (database) => {
    let restored = false
    await updateRecord(database, threadId, (current) => {
      const existing = current.goalText ?? ''
      if (existing.length > 0 && existing !== goalText) {
        return current
      }
      restored = true
      return { ...current, goalText }
    })
    return restored
  })
}

/**
 * 原子幂等合并一条 Stop 回执。
 *
 * 持久记录是唯一事实源：回填以记录中的可编辑草稿为基准（正常编辑走同一条串行链，
 * 因此记录里已包含先于本次合并落盘的编辑），绝不接受调用方传入的整份 draft 快照，
 * 否则另一个标签页已经恢复/新增的输入会被过期快照覆盖。
 * 已合并过的回执不重复追加，但仍返回记录，供尚未读到它的标签页同步。
 */
export function applyStopReceipt(
  receipt: HarnessStoppedThreadReceiptDTO,
): Promise<StopReceiptMergeResult> {
  return enqueueWrite(receipt.threadId, async (database) =>
    updateRecord(database, receipt.threadId, (current) => {
      if (current.appliedStopRequestIds.includes(receipt.stopRequestId)) {
        return current
      }
      const cancelledInputs = receipt.cancelledInputs ?? []
      return {
        threadId: receipt.threadId,
        parts: prependCancelledMessages(cancelledInputs, current.parts),
        goalText: mergeCancelledGoalTexts(cancelledInputs, current.goalText),
        appliedStopRequestIds: [...current.appliedStopRequestIds, receipt.stopRequestId],
      }
    }))
}

function updateRecord(
  db: IDBDatabase,
  threadId: string,
  mutate: (current: ThreadDraftRecord) => ThreadDraftRecord,
): Promise<ThreadDraftRecord> {
  return new Promise<ThreadDraftRecord>((resolve, reject) => {
    let result: ThreadDraftRecord | null = null
    const tx = db.transaction(STORE_NAME, 'readwrite')
    const store = tx.objectStore(STORE_NAME)
    const request = store.get(threadId)
    request.onsuccess = () => {
      const next = mutate(normalizeRecord(request.result, threadId))
      result = next
      store.put(storedRecord(next))
    }
    request.onerror = () => reject(request.error ?? new Error('Failed to read thread draft'))
    tx.oncomplete = () => {
      resolve(result ?? normalizeRecord(null, threadId))
    }
    tx.onerror = () => reject(tx.error ?? new Error('Failed to write thread draft'))
    tx.onabort = () => reject(tx.error ?? new Error('Failed to write thread draft'))
  })
}

/** attachment part 依赖页面内上传注册表，不进入持久记录。 */
function storedRecord(record: ThreadDraftRecord): ThreadDraftRecord {
  return {
    ...record,
    parts: record.parts.filter((part) => part.type !== 'attachment'),
  }
}

function normalizeRecord(value: unknown, threadId: string): ThreadDraftRecord {
  const record = isRecord(value) ? value : {}
  return {
    threadId,
    parts: Array.isArray(record.parts) ? record.parts.filter(isPersistablePart) : [],
    goalText: typeof record.goalText === 'string' ? record.goalText : null,
    appliedStopRequestIds: Array.isArray(record.appliedStopRequestIds)
      ? record.appliedStopRequestIds.filter((id): id is string => typeof id === 'string')
      : [],
  }
}

function isPersistablePart(value: unknown): value is ComposerPart {
  if (!isRecord(value) || typeof value.partId !== 'string' || !value.partId) {
    return false
  }
  if (value.type === 'text') {
    return typeof value.text === 'string'
  }
  return value.type === 'resource'
    && typeof value.blobId === 'string'
    && typeof value.name === 'string'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
}
