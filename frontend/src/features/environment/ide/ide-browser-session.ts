import { useCallback, useEffect, useRef, useState } from 'react'
import { createUuid } from '@/shared/lib/uuid'
import {
  saveIdeSession,
  type IdeTabLoadResult,
  type IdeTabStorage,
} from '@/features/environment/ide/ide-tab-state'

export type IdeBrowserSessionStatus =
  | 'acquiring'
  | 'ready'
  | 'cloned'
  | 'suspended'
  | 'conflict'
  | 'unsupported'
  | 'unavailable'

export interface IdeBrowserSessionState {
  browserSessionId: string
  status: IdeBrowserSessionStatus
  /** 克隆 tab 的新身份是否成功写入 sessionStorage。 */
  sessionSaved: boolean
}

interface BrowserSessionLock {
  acquired: Promise<boolean>
  release: () => void
}

/**
 * 对 browserSessionId 做同源独占。Web Locks 不可用或无法取得锁时显式报告，
 * 不退回共享身份、心跳或本地时间接管。
 */
function acquireBrowserSessionLock(browserSessionId: string): BrowserSessionLock {
  const locks = navigator.locks
  if (locks == null) {
    return { acquired: Promise.resolve(false), release: () => {} }
  }
  let release: () => void = () => {}
  let settled = false
  let settle: (acquired: boolean) => void = () => {}
  const acquired = new Promise<boolean>((resolve) => {
    settle = (value) => {
      if (!settled) {
        settled = true
        resolve(value)
      }
    }
  })
  const name = `kk-studio.ide-session:${browserSessionId}`
  void locks.request(name, { ifAvailable: true }, async (lock) => {
    if (lock == null) {
      settle(false)
      return
    }
    settle(true)
    await new Promise<void>((resolve) => {
      release = resolve
    })
  }).catch(() => {
    settle(false)
  })
  return {
    acquired,
    release: () => {
      release()
      settle(false)
    },
  }
}

interface UseIdeBrowserSessionOptions {
  initial: IdeTabLoadResult
  storage?: IdeTabStorage
  /** 克隆 tab 丢弃复制来的运行身份；只保留展示偏好。 */
  onClone: (browserSessionId: string) => void
  /** 正常页面恢复（含 BFCache）后重新确认权威绑定。 */
  onRestore: () => void
  /** pagehide 立即撤回使用证明，不作为服务端恢复正确性的前提。 */
  onSuspend: () => void
}

/**
 * 一个浏览器 tab 的 Chat IDE 会话身份。
 *
 * <p>刷新复用 sessionStorage 中的 browserSessionId；复制 tab 若发现同一会话已被
 * 活页面持有，就生成新会话并清空复制来的运行身份。锁是会话互斥，不是网络在线判据。
 */
export function useIdeBrowserSession({
  initial,
  storage,
  onClone,
  onRestore,
  onSuspend,
}: UseIdeBrowserSessionOptions): IdeBrowserSessionState {
  const [state, setState] = useState<IdeBrowserSessionState>(() => ({
    browserSessionId: initial.browserSessionId,
    status: 'acquiring',
    sessionSaved: initial.sessionSaved,
  }))
  const currentLockRef = useRef<BrowserSessionLock | null>(null)
  const generationRef = useRef(0)
  const callbacksRef = useRef({ onClone, onRestore, onSuspend })
  const initialRef = useRef(initial)
  const storageRef = useRef(storage)
  const mountedRef = useRef(false)
  const sessionRef = useRef(initial.browserSessionId)

  useEffect(() => {
    callbacksRef.current = { onClone, onRestore, onSuspend }
    initialRef.current = initial
    storageRef.current = storage
  })

  const acquire = useCallback((browserSessionId: string, clone: boolean, sessionSaved = true) => {
    const start = (candidateId: string, candidateClone: boolean, candidateSaved: boolean) => {
      const generation = generationRef.current + 1
      generationRef.current = generation
      currentLockRef.current?.release()
      const lock = acquireBrowserSessionLock(candidateId)
      currentLockRef.current = lock
      void lock.acquired.then((acquired) => {
        if (!mountedRef.current || generationRef.current !== generation) {
          lock.release()
          return
        }
        if (!acquired) {
          if (candidateClone) {
            setState({
              browserSessionId: candidateId,
              status: 'unavailable',
              sessionSaved: candidateSaved,
            })
            return
          }
          // 复制 tab 继承同一 sessionStorage；原页面仍持有锁时生成独立会话。
          const clonedId = createUuid()
          const saved = saveIdeSession(
            clonedId,
            initialRef.current.presentation,
            {},
            storageRef.current,
          )
          start(clonedId, true, saved)
          return
        }
        sessionRef.current = candidateId
        setState({
          browserSessionId: candidateId,
          status: candidateClone ? 'cloned' : 'ready',
          sessionSaved: candidateSaved,
        })
        if (candidateClone) {
          callbacksRef.current.onClone(candidateId)
        } else {
          callbacksRef.current.onRestore()
        }
      })
    }
    start(browserSessionId, clone, sessionSaved)
  }, [])

  useEffect(() => {
    mountedRef.current = true
    if (navigator.locks == null) {
      setState({
        browserSessionId: sessionRef.current,
        status: 'unsupported',
        sessionSaved: false,
      })
    } else {
      acquire(sessionRef.current, false)
    }
    return () => {
      mountedRef.current = false
      generationRef.current += 1
      currentLockRef.current?.release()
      currentLockRef.current = null
    }
  }, [acquire])

  useEffect(() => {
    const handlePageHide = () => {
      callbacksRef.current.onSuspend()
      const generation = generationRef.current + 1
      generationRef.current = generation
      currentLockRef.current?.release()
      currentLockRef.current = null
      if (mountedRef.current) {
        setState((current) => ({ ...current, status: 'suspended' }))
      }
    }
    const handlePageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) {
        return
      }
      if (navigator.locks == null) {
        setState((current) => ({ ...current, status: 'unsupported' }))
        return
      }
      const browserSessionId = sessionRef.current
      const generation = generationRef.current + 1
      generationRef.current = generation
      const lock = acquireBrowserSessionLock(browserSessionId)
      currentLockRef.current?.release()
      currentLockRef.current = lock
      void lock.acquired.then((acquired) => {
        if (!mountedRef.current || generationRef.current !== generation) {
          lock.release()
          return
        }
        if (!acquired) {
          setState({ browserSessionId, status: 'conflict', sessionSaved: false })
          return
        }
        setState({ browserSessionId, status: 'ready', sessionSaved: true })
        callbacksRef.current.onRestore()
      })
    }
    window.addEventListener('pagehide', handlePageHide)
    window.addEventListener('pageshow', handlePageShow)
    return () => {
      window.removeEventListener('pagehide', handlePageHide)
      window.removeEventListener('pageshow', handlePageShow)
    }
  }, [])

  return state
}
