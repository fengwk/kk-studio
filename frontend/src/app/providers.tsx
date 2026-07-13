import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import { useState, type PropsWithChildren } from 'react'
import { createApplicationExtensionHost } from '@/app/extension-host'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

export function AppProviders({ children }: PropsWithChildren) {
  const [extensionHost] = useState(createApplicationExtensionHost)
  return (
    <QueryClientProvider client={queryClient}>
      <ExtensionHostProvider host={extensionHost}>
        <BrowserRouter>{children}</BrowserRouter>
      </ExtensionHostProvider>
    </QueryClientProvider>
  )
}
