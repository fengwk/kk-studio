/* eslint-disable react-refresh/only-export-components */
/**
 * 全局终端 Provider 与 hooks（TerminalProvider / useTerminal / useOptionalTerminal）。
 *
 * - 借用既有 `useApplicationEvents` 的同一 app-events 连接，不建立第二连接；
 * - Provider 跨路由保持挂载，effect 的 start/stop 支持根 StrictMode；
 * - 通过 `useSyncExternalStore` 订阅控制器稳定快照；
 * - Provider 持有唯一的触发元素引用，多个消费者共享同一 show/hide：`show` 记录
 *   触发元素，只有显式 `hide` 才把焦点还给仍挂载的触发元素。
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type PropsWithChildren,
} from 'react'
import { useApplicationEvents } from '@/shared/app-events'
import { TerminalController, type TerminalWorkspaceSnapshot } from './terminal-controller'

export interface TerminalApi {
  readonly controller: TerminalController
  readonly snapshot: TerminalWorkspaceSnapshot
  show(environmentId?: string): void
  hide(): void
}

const TerminalContext = createContext<TerminalApi | null>(null)

export interface TerminalProviderProps {
  children: PropsWithChildren['children']
}

export function TerminalProvider({ children }: TerminalProviderProps) {
  const events = useApplicationEvents()
  const [controller] = useState(() => new TerminalController({ events }))
  const triggerRef = useRef<HTMLElement | null>(null)
  useEffect(() => {
    controller.start()
    return () => controller.stop()
  }, [controller])
  const snapshot = useSyncExternalStore(
    controller.subscribe,
    controller.getSnapshot,
    controller.getSnapshot,
  )
  const show = useCallback(
    (environmentId?: string) => {
      triggerRef.current =
        typeof document === 'undefined' ? null : (document.activeElement as HTMLElement | null)
      controller.show(environmentId)
    },
    [controller],
  )
  const hide = useCallback(() => {
    controller.hide()
    const trigger = triggerRef.current
    triggerRef.current = null
    // 触发元素若已卸载则不再抢焦点。
    if (trigger !== null && trigger.isConnected && typeof trigger.focus === 'function') {
      trigger.focus()
    }
  }, [controller])
  const value = useMemo<TerminalApi>(
    () => ({ controller, snapshot, show, hide }),
    [controller, snapshot, show, hide],
  )
  return <TerminalContext.Provider value={value}>{children}</TerminalContext.Provider>
}

/** 必须处于 TerminalProvider 内；否则抛出。 */
export function useTerminal(): TerminalApi {
  const api = useContext(TerminalContext)
  if (api === null) {
    throw new Error('TerminalProvider is required')
  }
  return api
}

/** 无 Provider 时返回 null，供嵌入/隔离场景使用。 */
export function useOptionalTerminal(): TerminalApi | null {
  return useContext(TerminalContext)
}
