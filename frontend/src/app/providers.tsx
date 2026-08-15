import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router'
import { useState, type PropsWithChildren } from 'react'
import { createApplicationExtensionHost } from '@/app/extension-host'
import { ApplicationSettingsProvider } from '@/features/settings/application-settings'
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
        <ApplicationSettingsProvider>
          <ApplicationEventProvider>
            <BrowserRouter>{children}</BrowserRouter>
          </ApplicationEventProvider>
        </ApplicationSettingsProvider>
      </ExtensionHostProvider>
    </QueryClientProvider>
  )
}
