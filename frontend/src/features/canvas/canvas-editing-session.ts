/**
 * Canvas 编辑会话身份 (editingSessionId)
 *
 * 依据 docs/canvas-project.md §2.4：
 * - 本地状态按“登录身份 + 画布 + 编辑会话”隔离，多标签页绝不相互覆盖。
 * - 同一标签页的刷新 / 路由切换必须复用同一 editingSessionId，才能恢复未确认操作；
 *   因此身份主流存放在 sessionStorage（标签页独立、刷新保留）。
 * - 复制标签页会连同 sessionStorage 一起复制，复制出的页面绝不能与原页面共享身份：
 *   通过“同源活动页面所有权租约”识别并强制重新隔离。
 * - sessionStorage 访问可能抛异常（隐私模式 / 存储被禁用）；此时绝不退化到所有标签页
 *   共享的固定值，而是使用本 document 稳定的随机值并告警。
 */

const SESSION_STORAGE_KEY = 'kkstudio.canvas.editingSessionId'
const OWNER_STORAGE_KEY = 'kkstudio.canvas.editingSessionOwner'
/** 页面所有权租约：超过该时长未续约，视为原标签页已关闭。 */
const OWNER_LEASE_MS = 8000
const HEARTBEAT_MS = OWNER_LEASE_MS / 2

interface SessionOwner {
  pageId: string
  at: number
}

/** 本 document 的稳定随机标识：不持久化，用于区分同源的不同页面实例。 */
const PAGE_INSTANCE_ID = createRandomId()

let cachedSessionId: string | null = null
let ownedSessionId: string | null = null
let heartbeatTimer: ReturnType<typeof setInterval> | null = null
let sessionStorageUnavailable = false
let unloadReleaseRegistered = false
let sessionReloading = false

export function isSessionReloading(): boolean {
  return sessionReloading
}

/**
 * 返回当前编辑会话标识：同一标签页内稳定，多标签页 / 复制标签页相互隔离。
 */
export function getEditingSessionId(): string {
  if (cachedSessionId) {
    return cachedSessionId
  }
  cachedSessionId = resolveSessionId()
  ownedSessionId = cachedSessionId
  startHeartbeat(cachedSessionId)
  registerUnloadRelease()
  return cachedSessionId
}

/** 仅供测试：清空进程内缓存、心跳与已写入的会话 / 所有权记录。 */
export function resetEditingSessionForTests(): void {
  cachedSessionId = null
  ownedSessionId = null
  sessionStorageUnavailable = false
  sessionReloading = false
  stopHeartbeat()
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.sessionStorage.removeItem(SESSION_STORAGE_KEY)
  } catch {
    // sessionStorage 不可用则无需清理。
  }
  try {
    window.localStorage.removeItem(OWNER_STORAGE_KEY)
  } catch {
    // localStorage 不可用则无需清理。
  }
}

function resolveSessionId(): string {
  const stored = readSessionStorage()
  if (stored) {
    if (isOwnedByAnotherLivePage(stored)) {
      const isolated = createRandomId()
      console.warn(
        `[canvas] 编辑会话 ${stored} 已被同源另一标签页持有（疑似标签页复制）；`
        + `本页改用隔离会话 ${isolated}，避免多标签互相覆盖。`,
      )
      writeSessionStorage(isolated)
      claimOwnership(isolated)
      return isolated
    }
    claimOwnership(stored)
    return stored
  }

  const fresh = sessionStorageUnavailable ? `page-${PAGE_INSTANCE_ID}` : createRandomId()
  if (sessionStorageUnavailable) {
    console.warn(
      '[canvas] sessionStorage 不可用；本页使用稳定随机编辑会话，'
      + '仅保证当前标签页内隔离与恢复，跨刷新无法恢复未确认操作。',
    )
  }
  writeSessionStorage(fresh)
  claimOwnership(fresh)
  return fresh
}

function readSessionStorage(): string | null {
  if (typeof window === 'undefined') {
    return null
  }
  try {
    return window.sessionStorage.getItem(SESSION_STORAGE_KEY)
  } catch {
    sessionStorageUnavailable = true
    return null
  }
}

function writeSessionStorage(sessionId: string): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.sessionStorage.setItem(SESSION_STORAGE_KEY, sessionId)
  } catch {
    sessionStorageUnavailable = true
  }
}

function readOwners(): Record<string, SessionOwner> {
  if (typeof window === 'undefined') {
    return {}
  }
  try {
    const raw = window.localStorage.getItem(OWNER_STORAGE_KEY)
    if (!raw) {
      return {}
    }
    const parsed = JSON.parse(raw) as Record<string, SessionOwner>
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

function writeOwners(owners: Record<string, SessionOwner>): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.setItem(OWNER_STORAGE_KEY, JSON.stringify(owners))
  } catch {
    // 所有权记录不可写时退化为无跨标签检测，不影响本页功能。
  }
}

function isOwnedByAnotherLivePage(sessionId: string): boolean {
  const owner = readOwners()[sessionId]
  if (!owner) {
    return false
  }
  if (owner.pageId === PAGE_INSTANCE_ID) {
    return false
  }
  return Date.now() - owner.at < OWNER_LEASE_MS
}

function claimOwnership(sessionId: string): void {
  const owners = readOwners()
  const now = Date.now()
  for (const [key, owner] of Object.entries(owners)) {
    if (now - owner.at >= OWNER_LEASE_MS) {
      delete owners[key]
    }
  }
  owners[sessionId] = { pageId: PAGE_INSTANCE_ID, at: now }
  writeOwners(owners)
}

function startHeartbeat(sessionId: string): void {
  if (heartbeatTimer !== null || typeof setInterval !== 'function') {
    return
  }
  heartbeatTimer = setInterval(() => {
    claimOwnership(sessionId)
  }, HEARTBEAT_MS)
  if (typeof heartbeatTimer === 'object' && typeof (heartbeatTimer as { unref?: () => void }).unref === 'function') {
    ;(heartbeatTimer as unknown as { unref: () => void }).unref()
  }
}

function stopHeartbeat(): void {
  if (heartbeatTimer !== null) {
    clearInterval(heartbeatTimer)
    heartbeatTimer = null
  }
}

/**
 * 页面卸载 / 刷新时主动释放本页所有权：
 * - 刷新时旧 page 已消失，若不释放，新 page 会把同一 sessionStorage 误判为“另一标签页”而重新隔离，
 *   导致未确认操作无法恢复；
 * - 复制标签页不会触发原页面的 pagehide，原页所有权仍在，新页面因此被正确识别为复制标签页。
 */
function registerUnloadRelease(): void {
  if (unloadReleaseRegistered || typeof window === 'undefined') {
    return
  }
  unloadReleaseRegistered = true
  const release = () => {
    releaseOwnership()
  }
  window.addEventListener('pagehide', release)
  window.addEventListener('beforeunload', release)
  window.addEventListener('pageshow', handlePageshow)
}

/**
 * bfcache 页面恢复时重新校验会话所有权。
 * 严格仅当 event.persisted 为 true (来自 bfcache) 时才触发冲突处置；
 * 普通 pageshow (persisted 为 false 或缺失) 不强制 reload / rotate session。
 * 若会话已被其他活页面认领，标记重载并刷新页面，避免跨页面覆盖。
 */
export function handlePageshow(event?: Event | { persisted?: boolean }): void {
  const isBfCache = Boolean(event && 'persisted' in event && event.persisted)
  if (!isBfCache) {
    return
  }

  const targetId = cachedSessionId ?? readSessionStorage()
  if (!targetId) {
    getEditingSessionId()
    return
  }

  if (isOwnedByAnotherLivePage(targetId)) {
    sessionReloading = true
    cachedSessionId = null
    ownedSessionId = null
    stopHeartbeat()
    try {
      window.sessionStorage.removeItem(SESSION_STORAGE_KEY)
    } catch {
      // Ignore
    }
    if (typeof window !== 'undefined' && window.location && typeof window.location.reload === 'function') {
      window.location.reload()
    }
    return
  }

  cachedSessionId = targetId
  ownedSessionId = targetId
  claimOwnership(targetId)
  startHeartbeat(targetId)
}

function releaseOwnership(): void {
  stopHeartbeat()
  if (!ownedSessionId) {
    return
  }
  const owners = readOwners()
  const owner = owners[ownedSessionId]
  if (owner && owner.pageId === PAGE_INSTANCE_ID) {
    delete owners[ownedSessionId]
    writeOwners(owners)
  }
}

function createRandomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `id-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}
