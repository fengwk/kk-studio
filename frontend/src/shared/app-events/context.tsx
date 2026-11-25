/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useEffect, useState, type PropsWithChildren } from 'react'
import {
  createApplicationEventUrl,
  type ApplicationEventConnectionState,
  type ApplicationEventSocketFactory,
} from '@/shared/app-events/connection'
import { ApplicationEventManager } from '@/shared/app-events/manager'

const ApplicationEventContext = createContext<ApplicationEventManager | null>(null)

interface ApplicationEventProviderProps {
  children: PropsWithChildren['children']
  /** 测试注入；默认使用生产 URL 与原生 WebSocket。 */
  enabled?: boolean
  url?: string
  socketFactory?: ApplicationEventSocketFactory
}

/**
 * 应用生命周期事件 Provider：mount 即创建 Manager 并连接，
 * 路由切换不重建连接；unmount 时断开。
 */
export function ApplicationEventProvider({
  children,
  enabled = true,
  url,
  socketFactory,
}: ApplicationEventProviderProps) {
  const [manager] = useState(
    () =>
      new ApplicationEventManager({
        url: url ?? createApplicationEventUrl(),
        socketFactory,
      }),
  )
  useEffect(() => {
    if (!enabled) return
    manager.connect()
    return () => manager.disconnect()
  }, [enabled, manager])
  return (
    <ApplicationEventContext.Provider value={manager}>{children}</ApplicationEventContext.Provider>
  )
}

export function useApplicationEvents(): ApplicationEventManager {
  const manager = useContext(ApplicationEventContext)
  if (manager == null) {
    throw new Error('ApplicationEventProvider is required')
  }
  return manager
}

/**
 * 当前应用事件连接的状态快照（connecting/ready/backoff/stopped 与结构化原因）。
 * 同一份状态驱动全局连接提示与 IDE 面板横幅，不把"没有 identity"当唯一诊断。
 */
export function useApplicationEventConnectionState(): ApplicationEventConnectionState {
  const manager = useApplicationEvents()
  const [state, setState] = useState<ApplicationEventConnectionState>(() =>
    manager.getConnectionState(),
  )
  useEffect(() => manager.subscribeConnectionState(setState), [manager])
  return state
}
