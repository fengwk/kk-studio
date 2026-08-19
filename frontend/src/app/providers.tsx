import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router'
import { useState, type PropsWithChildren } from 'react'
import { createApplicationExtensionHost } from '@/app/extension-host'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { ApplicationEventProvider } from '@/shared/app-events'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

export function AppProviders({ children }: PropsWithChildren) {
  const [extensionHost] = useState(createApplicationExtensionHost)
  return (
    <QueryClientProvider client={queryClient}>
      <ExtensionHostProvider host={extensionHost}>
        <BrowserPreferencesProvider>
          <ApplicationEventProvider>
            <BrowserRouter>{children}</BrowserRouter>
          </ApplicationEventProvider>
        </BrowserPreferencesProvider>
      </ExtensionHostProvider>
    </QueryClientProvider>
  )
}
