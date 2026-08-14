/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useEffect, useState, type PropsWithChildren } from 'react'
import { createApplicationEventUrl, type ApplicationEventSocketFactory } from '@/shared/app-events/connection'
import { ApplicationEventManager } from '@/shared/app-events/manager'

const ApplicationEventContext = createContext<ApplicationEventManager | null>(null)

export interface ApplicationEventProviderProps {
  children: PropsWithChildren['children']
  /** 测试注入；默认使用生产 URL 与原生 WebSocket。 */
  url?: string
  socketFactory?: ApplicationEventSocketFactory
}

/**
 * 应用生命周期事件 Provider：mount 即创建 Manager 并连接，
 * 路由切换不重建连接；unmount 时断开。
 */
export function ApplicationEventProvider({
  children,
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
    manager.connect()
    return () => manager.disconnect()
  }, [manager])
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
