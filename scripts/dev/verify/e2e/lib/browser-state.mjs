import { sleep } from './http.mjs'

/** 只读核验绑定 Thread 的 IndexedDB 草稿，不改 schema 或写入记录。 */

const THREAD_DRAFT_DB = 'kkstudio.thread-drafts'
const THREAD_DRAFT_STORE = 'threadDrafts'
const THREAD_DRAFT_RECORD_KEYS = 'appliedStopRequestIds,goalText,parts,threadId'
const THREAD_DRAFT_TEXT_PART_KEYS = 'partId,text,type'

/**
 * 严格断言某个 Thread 的草稿记录。
 *
 * `expectedText === null` 表示 composer parts 为空（`parts: []`），记录本身与
 * `goalText` / `appliedStopRequestIds` 元数据保留；非 null 时要求恰好一个 text part
 * 且文本逐字相等。记录字段、part 字段与回执 id 唯一性都按真实 schema 严格校验，
 * 拒绝缺字段、多字段或非法 part 的污染记录。
 */
export async function expectThreadDraft(page, threadId, expectedText, { timeout = 10_000 } = {}) {
  const expected = expectedText
  const id = String(threadId)
  const deadline = Date.now() + timeout
  let snapshot
  for (;;) {
    snapshot = await readThreadDraft(page, id)
    if (matchThreadDraft(snapshot.record, id, expected)) {
      return
    }
    if (Date.now() >= deadline) {
      break
    }
    await sleep(100)
  }
  throw new Error(
    `IndexedDB thread draft mismatch for ${id}: expected `
      + `${expected == null ? 'empty parts' : JSON.stringify(expected)}, got ${JSON.stringify(snapshot)}`,
  )
}

/**
 * 纯匹配器：只判断读取到的记录是否精确满足期望，便于在 Node 中确定性验证。
 *
 * 清空草稿仍须存在合法记录；数据库不可用或读取失败不能冒充成功清空。
 */
export function matchThreadDraft(record, threadId, expectedText) {
  const expected = expectedText
  if (record == null || typeof record !== 'object' || Array.isArray(record)) {
    return false
  }
  if (Object.keys(record).sort().join(',') !== THREAD_DRAFT_RECORD_KEYS) {
    return false
  }
  if (record.threadId !== String(threadId)) {
    return false
  }
  if (!Array.isArray(record.parts)) {
    return false
  }
  if (record.goalText !== null && typeof record.goalText !== 'string') {
    return false
  }
  const appliedIds = record.appliedStopRequestIds
  if (!Array.isArray(appliedIds)) {
    return false
  }
  if (!appliedIds.every((id) => typeof id === 'string' && id.trim().length > 0)) {
    return false
  }
  if (new Set(appliedIds).size !== appliedIds.length) {
    return false
  }
  if (expected === null) {
    return record.parts.length === 0
  }
  if (record.parts.length !== 1) {
    return false
  }
  const part = record.parts[0]
  if (part == null || typeof part !== 'object' || Array.isArray(part)) {
    return false
  }
  if (Object.keys(part).sort().join(',') !== THREAD_DRAFT_TEXT_PART_KEYS) {
    return false
  }
  return (
    part.type === 'text'
    && typeof part.partId === 'string'
    && part.partId.trim().length > 0
    && typeof part.text === 'string'
    && part.text === expected
  )
}

/**
 * 浏览器侧只读读取：原生 IndexedDB，事务结束后关闭连接，绝不打开不存在的 DB（避免
 * 以只读校验为名创建空库）。返回 `{ exists, record }`；库不存在时 `record` 为 null。
 */
export async function readThreadDraft(page, threadId) {
  return page.evaluate(
    async ({ dbName, storeName, threadId }) => {
      const databases = await indexedDB.databases()
      if (!databases.some((entry) => entry.name === dbName)) {
        return { exists: false, record: null }
      }
      const db = await new Promise((resolve, reject) => {
        const request = indexedDB.open(dbName, 1)
        // 枚举后被删除的数据库也不得由断言重新创建。
        request.onupgradeneeded = () => request.transaction.abort()
        request.onsuccess = () => resolve(request.result)
        request.onerror = () => reject(request.error)
      })
      try {
        const record = await new Promise((resolve, reject) => {
          const tx = db.transaction(storeName, 'readonly')
          const request = tx.objectStore(storeName).get(threadId)
          tx.oncomplete = () => resolve(request.result ?? null)
          request.onerror = () => reject(request.error)
          tx.onerror = () => reject(tx.error)
          tx.onabort = () => reject(tx.error)
        })
        return { exists: true, record }
      } finally {
        db.close()
      }
    },
    { dbName: THREAD_DRAFT_DB, storeName: THREAD_DRAFT_STORE, threadId: String(threadId) },
  )
}
