import { useEffect, useRef } from 'react'
// 经 barrel 引入 useApplicationEvents，保持与既有测试的 '@/shared/app-events' mock 一致。
import { useApplicationEvents } from '@/shared/app-events'
import type { ApplicationEventName, ApplicationEventResource } from '@/shared/app-events/protocol'

export interface ResourceSyncOptions {
  /**
   * 读取权威快照（通常是 invalidateQueries / refetch）。订阅建立（首次与每次重连）、
   * resync、服务端 error 一律触发：这三者都表示本地状态可能落后或不可信。
   */
  sync: () => void
  /** 返回 true 的资源事件同样触发 sync（例如 version / changed / 更高 revision）。 */
  syncOn?: (name: ApplicationEventName, data: unknown) => boolean
  /** 订阅建立后在 sync 之后追加的动作（例如请求追帧）。 */
  onSubscribed?: () => void
  /** 不触发 sync 的其余资源事件（例如瞬态 realtime overlay）。 */
  onEvent?: (name: ApplicationEventName, data: unknown) => void
}

/**
 * 「权威快照 + 资源事件」的统一订阅：事件只提示回读，持久事实永远来自快照；
 * overlay / 未确认操作由调用方叠加在快照之上。选项经 ref 读取，因此回调身份变化
 * 不会重订阅，订阅只随 resource（kind + id）与 enabled 变化。
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
    return applicationEvents.subscribe(target, {
      onSubscribed: () => {
        optionsRef.current.sync()
        optionsRef.current.onSubscribed?.()
      },
      onEvent: (name, data) => {
        const current = optionsRef.current
        if (current.syncOn?.(name, data)) {
          current.sync()
        } else {
          current.onEvent?.(name, data)
        }
      },
      onResync: () => optionsRef.current.sync(),
      onError: () => optionsRef.current.sync(),
    })
  }, [applicationEvents, kind, id])
}
