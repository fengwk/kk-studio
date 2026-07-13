/* eslint-disable react-refresh/only-export-components */
import { createContext, useContext, type PropsWithChildren } from 'react'
import { ExtensionHost } from '@/platform/extensions/ExtensionHost'

const ExtensionHostContext = createContext<ExtensionHost | null>(null)

export function ExtensionHostProvider({ host, children }: PropsWithChildren<{ host: ExtensionHost }>) {
  return <ExtensionHostContext.Provider value={host}>{children}</ExtensionHostContext.Provider>
}

export function useExtensionHost(): ExtensionHost {
  const host = useContext(ExtensionHostContext)
  if (!host) {
    throw new Error('ExtensionHostProvider is required')
  }
  return host
}
