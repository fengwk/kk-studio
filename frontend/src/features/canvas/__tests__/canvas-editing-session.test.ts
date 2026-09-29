import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  getEditingSessionId,
  handlePageshow,
  resetEditingSessionForTests,
} from '@/features/canvas/canvas-editing-session'

const SESSION_STORAGE_KEY = 'kkstudio.canvas.editingSessionId'

afterEach(() => {
  vi.restoreAllMocks()
  resetEditingSessionForTests()
})

describe('Canvas 编辑会话身份', () => {
  it('同一标签页内身份稳定，并由 sessionStorage 支撑刷新恢复', () => {
    resetEditingSessionForTests()
    const first = getEditingSessionId()
    const second = getEditingSessionId()

    expect(first).toBe(second)
    expect(first).not.toBe('default-session')
    // 写回 sessionStorage，刷新后同一标签页可恢复同一身份。
    expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBe(first)
  })

  it('sessionStorage 抛异常时使用本页稳定随机值并告警，绝不退化为所有标签共享的固定值', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked')
    })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    resetEditingSessionForTests()

    const first = getEditingSessionId()
    const second = getEditingSessionId()

    expect(first).toBe(second)
    expect(first).not.toBe('default-session')
    expect(first).toMatch(/^page-/)
    expect(warn).toHaveBeenCalled()
  })

  it('复制标签页共享同一 sessionStorage 时强制重新隔离，且新身份可被新页恢复', async () => {
    vi.resetModules()
    const pageA = await import('@/features/canvas/canvas-editing-session')
    const idA = pageA.getEditingSessionId()

    // 第二个页面与第一个页面共享 sessionStorage（标签页复制的典型行为）。
    vi.resetModules()
    const pageB = await import('@/features/canvas/canvas-editing-session')
    const idB = pageB.getEditingSessionId()

    expect(idB).not.toBe(idA)
    expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBe(idB)
  })

  it('刷新时旧页释放所有权，重载后复用同一 editingSessionId 以恢复未确认操作', async () => {
    vi.resetModules()
    const pageA = await import('@/features/canvas/canvas-editing-session')
    const idA = pageA.getEditingSessionId()

    // 刷新：旧页面在 pagehide 中主动释放所有权。
    window.dispatchEvent(new Event('pagehide'))

    vi.resetModules()
    const pageB = await import('@/features/canvas/canvas-editing-session')
    const idB = pageB.getEditingSessionId()

    // 未被误判为“另一标签页”，因此复用同一身份，重载后可恢复原会话的待确认操作。
    expect(idB).toBe(idA)
  })

  it('localStorage 所有权存储不可用时仍能返回可用身份', () => {
    const originalLocalStorage = window.localStorage
    const brokenLocalStorage = {
      getItem: () => {
        throw new Error('localStorage blocked')
      },
      setItem: () => {
        throw new Error('localStorage blocked')
      },
      removeItem: () => undefined,
      clear: () => undefined,
      key: () => null,
      length: 0,
    } as unknown as Storage
    Object.defineProperty(window, 'localStorage', { configurable: true, writable: true, value: brokenLocalStorage })

    try {
      resetEditingSessionForTests()
      const id = getEditingSessionId()
      expect(id).not.toBe('default-session')
      expect(id.length).toBeGreaterThan(0)
    } finally {
      Object.defineProperty(window, 'localStorage', { configurable: true, writable: true, value: originalLocalStorage })
    }
  })

  it('sessionStorage 写入失败时仍返回稳定的本页身份', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('write blocked')
    })
    resetEditingSessionForTests()

    const first = getEditingSessionId()
    expect(first).not.toBe('default-session')

    vi.restoreAllMocks()
    resetEditingSessionForTests()
    const second = getEditingSessionId()
    expect(second).not.toBe('default-session')
  })

  it('crypto.randomUUID 缺失时使用稳定随机回退', () => {
    const originalUuid = crypto.randomUUID
    Object.defineProperty(crypto, 'randomUUID', { configurable: true, writable: true, value: undefined })
    try {
      resetEditingSessionForTests()
      expect(getEditingSessionId()).toMatch(/^id-/)
    } finally {
      Object.defineProperty(crypto, 'randomUUID', { configurable: true, writable: true, value: originalUuid })
    }
  })

  it('I10 bfcache: pageshow 恢复时若 session 已被其他活页面持有，触发安全 reload 且不强夺所有权', () => {
    resetEditingSessionForTests()
    const mySessionId = getEditingSessionId()

    // 模拟进入 bfcache：休眠期间另一标签页以相同的 sessionId（如复制标签页）成为活所有者
    const OWNER_STORAGE_KEY = 'kkstudio.canvas.editingSessionOwner'
    const otherPageOwner = {
      [mySessionId]: {
        pageId: 'other-live-page-id',
        at: Date.now(),
      },
    }
    window.localStorage.setItem(OWNER_STORAGE_KEY, JSON.stringify(otherPageOwner))

    const reloadMock = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...window.location, reload: reloadMock },
    })

    handlePageshow({ persisted: true })

    expect(reloadMock).toHaveBeenCalled()
    expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBeNull()
  })

  it('I10 bfcache: pageshow 恢复时若无活所有者竞争，安全重新 claim 并继续', () => {
    resetEditingSessionForTests()
    const mySessionId = getEditingSessionId()

    // 模拟普通 pageshow 恢复
    const reloadMock = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      writable: true,
      value: { ...window.location, reload: reloadMock },
    })

    handlePageshow({ persisted: true })

    expect(reloadMock).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(SESSION_STORAGE_KEY)).toBe(mySessionId)
  })
})
