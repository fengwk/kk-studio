/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useSyncExternalStore, type PropsWithChildren } from 'react'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'

const ExtensionHostContext = createContext<ExtensionHost | null>(null)
const EMPTY_HOST_SNAPSHOT = Object.freeze({ version: 0 })
const emptySubscribe = () => () => undefined
const getEmptySnapshot = () => EMPTY_HOST_SNAPSHOT

export function ExtensionHostProvider({ host, children }: PropsWithChildren<{ host: ExtensionHost }>) {
  return <ExtensionHostContext.Provider value={host}>{children}</ExtensionHostContext.Provider>
}

function useExtensionHost(): ExtensionHost {
  const host = useContext(ExtensionHostContext)
  if (!host) {
    throw new Error('ExtensionHostProvider is required')
  }
  return host
}

export function useExtensionHostSnapshot(): ExtensionHost {
  const host = useExtensionHost()
  useSyncExternalStore(host.subscribe, host.getSnapshot, host.getSnapshot)
  return host
}

/** 允许可复用消息组件在测试或非 Workbench 宿主中回退到默认 renderer。 */
export function useOptionalExtensionHostSnapshot(): ExtensionHost | null {
  const host = useContext(ExtensionHostContext)
  useSyncExternalStore(
    host?.subscribe ?? emptySubscribe,
    host?.getSnapshot ?? getEmptySnapshot,
    host?.getSnapshot ?? getEmptySnapshot,
  )
  return host
}
