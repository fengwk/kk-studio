const STORAGE_PREFIX = 'kkstudio.ai.pending-stop.v1:'

/**
 * 一次含混 Stop 的精确重试身份。相同 Thread/basis 下复用原 body；权威 basis 移动后清理。
 */
export interface PendingStopOperation {
  stopRequestId: string
  expectedVersion: string
  requestHeadEntryId: string
  basisVersion: string
}

export function pendingStopStorageKey(threadId: string): string {
  return `${STORAGE_PREFIX}${threadId}`
}

export function storePendingStop(
  threadId: string,
  operation: PendingStopOperation,
  storage: Storage = localStorage,
): void {
  if (!threadId || !validOperation(operation)) {
    return
  }
  try {
    storage.setItem(pendingStopStorageKey(threadId), JSON.stringify(operation))
  } catch {
    clearPendingStop(threadId, storage)
  }
}

export function loadPendingStop(
  threadId: string,
  storage: Storage = localStorage,
): PendingStopOperation | null {
  if (!threadId) {
    return null
  }
  const key = pendingStopStorageKey(threadId)
  try {
    const raw = storage.getItem(key)
    if (raw == null) {
      return null
    }
    const parsed: unknown = JSON.parse(raw)
    if (
      !validOperation(parsed)
      || Object.keys(parsed).length !== 4
    ) {
      storage.removeItem(key)
      return null
    }
    return { ...parsed }
  } catch {
    try {
      storage.removeItem(key)
    } catch {
      // localStorage 可能被浏览器策略整体禁用。
    }
    return null
  }
}

export function clearPendingStop(
  threadId: string,
  storage: Storage = localStorage,
): void {
  if (!threadId) {
    return
  }
  try {
    storage.removeItem(pendingStopStorageKey(threadId))
  } catch {
    // localStorage 清理是 best-effort。
  }
}

function validOperation(value: unknown): value is PendingStopOperation {
  if (!isRecord(value)) {
    return false
  }
  return (
    nonBlank(value.stopRequestId)
    && nonBlank(value.expectedVersion)
    && nonBlank(value.requestHeadEntryId)
    && nonBlank(value.basisVersion)
  )
}

function nonBlank(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
}
