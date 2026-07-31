/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, useSyncExternalStore, type PropsWithChildren } from 'react'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'

const ExtensionHostContext = createContext<ExtensionHost | null>(null)

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
