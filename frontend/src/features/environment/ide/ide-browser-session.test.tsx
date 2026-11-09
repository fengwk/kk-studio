import { StrictMode } from 'react'
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useIdeBrowserSession } from '@/features/environment/ide/ide-browser-session'
import type { IdeTabLoadResult } from '@/features/environment/ide/ide-tab-state'

const BROWSER_A = 'b1000000-0000-4000-8000-000000000001'

interface DeferredLockRequest {
  name: string
  options: LockOptions
  callback: (lock: Lock | null) => Promise<unknown>
  resolve: () => void
  reject: (reason?: unknown) => void
}

function deferredLocks() {
  const calls: DeferredLockRequest[] = []
  const request = vi.fn((
    name: string,
    options: LockOptions,
    callback: (lock: Lock | null) => Promise<unknown>,
  ) => new Promise<void>((resolve, reject) => {
    calls.push({ name, options, callback, resolve, reject })
  }))
  return {
    calls,
    request,
    manager: { request } as unknown as LockManager,
  }
}

function installLocks(manager: LockManager): void {
  Object.defineProperty(navigator, 'locks', {
    configurable: true,
    value: manager,
  })
}

function availableLock(call: DeferredLockRequest): Lock {
  return { name: call.name } as Lock
}

function initial(sessionSaved = true): IdeTabLoadResult {
  return {
    browserSessionId: BROWSER_A,
    presentation: {
      panelVisible: false,
      selectedEnvironmentId: null,
      openedEnvironmentIds: [],
    },
    recoveryByEnvironment: {},
    source: 'session',
    sessionSaved,
  }
}

const originalLocks = Object.getOwnPropertyDescriptor(navigator, 'locks')

afterEach(() => {
  if (originalLocks == null) {
    Reflect.deleteProperty(navigator, 'locks')
  } else {
    Object.defineProperty(navigator, 'locks', originalLocks)
  }
})

describe('useIdeBrowserSession locks', () => {
  it('does not leak a lock released before the async native callback and serializes StrictMode remount', async () => {
    const locks = deferredLocks()
    installLocks(locks.manager)
    const onClone = vi.fn()
    const onRestore = vi.fn()
    const onSuspend = vi.fn()
    const { result, unmount } = renderHook(
      () => useIdeBrowserSession({ initial: initial(), onClone, onRestore, onSuspend }),
      { wrapper: StrictMode },
    )

    await waitFor(() => expect(locks.calls).toHaveLength(1))
    expect(locks.request).toHaveBeenCalledTimes(1)
    expect(result.current.status).toBe('acquiring')

    const firstCallback = locks.calls[0]!.callback(availableLock(locks.calls[0]!))
    await firstCallback
    locks.calls[0]!.resolve()

    await waitFor(() => expect(locks.calls).toHaveLength(2))
    const secondCallback = locks.calls[1]!.callback(availableLock(locks.calls[1]!))
    locks.calls[1]!.resolve()

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.browserSessionId).toBe(BROWSER_A)
    expect(onClone).not.toHaveBeenCalled()
    expect(onRestore).toHaveBeenCalled()

    unmount()
    await secondCallback
  })

  it('preserves the real sessionSaved flag across pagehide/pageshow', async () => {
    const locks = deferredLocks()
    installLocks(locks.manager)
    const onClone = vi.fn()
    const onRestore = vi.fn()
    const onSuspend = vi.fn()
    const { result, unmount } = renderHook(() => useIdeBrowserSession({
      initial: initial(false),
      onClone,
      onRestore,
      onSuspend,
    }))

    await waitFor(() => expect(locks.calls).toHaveLength(1))
    const firstCallback = locks.calls[0]!.callback(availableLock(locks.calls[0]!))
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.sessionSaved).toBe(false)

    act(() => {
      window.dispatchEvent(new Event('pagehide'))
    })
    expect(result.current.status).toBe('suspended')
    await firstCallback
    locks.calls[0]!.resolve()

    act(() => {
      window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
    })
    await waitFor(() => expect(locks.calls).toHaveLength(2))
    const secondCallback = locks.calls[1]!.callback(availableLock(locks.calls[1]!))
    locks.calls[1]!.resolve()

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(result.current.sessionSaved).toBe(false)
    expect(onSuspend).toHaveBeenCalled()
    expect(onRestore).toHaveBeenCalled()
    expect(onClone).not.toHaveBeenCalled()

    unmount()
    await secondCallback
  })
})
