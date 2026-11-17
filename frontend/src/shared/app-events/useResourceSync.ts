import { useEffect, useRef } from 'react'
// 经 barrel 引入 useApplicationEvents，保持与既有测试的 '@/shared/app-events' mock 一致。
import { useApplicationEvents } from '@/shared/app-events'
import type { ApplicationEventName, ApplicationEventResource } from '@/shared/app-events/protocol'

export interface ResourceSyncOptions {
  /**
   * 读取或请求权威快照。默认在订阅建立（首次与每次重连）、resync、服务端 error 时调用；
   * 仅执行树可选择由 WebSocket refresh 帧同步。
   */
  sync: () => void | Promise<unknown>
  /** 默认 true：订阅建立后同步；服务端 ack 仍会通知 onSubscribed。 */
  syncOnSubscribed?: boolean
  /** 返回 true 的资源事件同样触发 sync（例如 version / changed / 更高 revision）。 */
  syncOn?: (name: ApplicationEventName, data: unknown) => boolean
  /** 订阅建立后追加的动作。 */
  onSubscribed?: () => void
  /** 服务端资源错误处理；未指定时沿用既有行为，触发 sync。 */
  onError?: (code: string, message: string) => void
  /** sync 返回 Promise 失败时接收错误；不传时记录错误，避免未处理 rejection。 */
  onSyncError?: (error: unknown) => void
  /** 不触发 sync 的其余资源事件（例如瞬态 realtime overlay）。 */
  onEvent?: (name: ApplicationEventName, data: unknown) => void
}

/**
 * 「权威快照 + 资源事件」的统一订阅：事件只提示回读或同步，持久事实永远来自权威快照；
 * overlay / 未确认操作由调用方叠加在快照之上。选项经 ref 读取，因此回调身份变化
 * 不会重订阅，订阅只随 resource（kind + id）变化。
 */
export function useResourceSync(
  resource: ApplicationEventResource | null,
  options: ResourceSyncOptions,
): void {
  const applicationEvents = useApplicationEvents()
  const optionsRef = useRef(options)
  useEffect(() => {
    optionsRef.current = options
  })
  const kind = resource?.kind
  const id = resource != null && 'id' in resource ? resource.id : undefined

  useEffect(() => {
    if (kind == null) {
      return undefined
    }
    const target = (id == null ? { kind } : { kind, id }) as ApplicationEventResource
    const runSync = () => runResourceSync(optionsRef.current)
    return applicationEvents.subscribe(target, {
      onSubscribed: () => {
        const current = optionsRef.current
        if (current.syncOnSubscribed !== false) {
          runResourceSync(current)
        }
        current.onSubscribed?.()
      },
      onEvent: (name, data) => {
        const current = optionsRef.current
        if (current.syncOn?.(name, data)) {
          runResourceSync(current)
        } else {
          current.onEvent?.(name, data)
        }
      },
      onResync: runSync,
      onError: (code, message) => {
        const current = optionsRef.current
        if (current.onError != null) {
          current.onError(code, message)
        } else {
          runResourceSync(current)
        }
      },
    })
  }, [applicationEvents, kind, id])
}

function runResourceSync(options: ResourceSyncOptions): void {
  const reportError = (error: unknown) => {
    if (options.onSyncError != null) {
      try {
        options.onSyncError(error)
        return
      } catch (callbackError) {
        console.error('application resource sync error handler failed', callbackError)
      }
    }
    console.error('application resource sync failed', error)
  }
  try {
    const result = options.sync()
    if (result != null) {
      void result.catch(reportError)
    }
  } catch (error) {
    reportError(error)
  }
}
