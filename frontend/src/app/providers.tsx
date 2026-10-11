import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState, type PropsWithChildren } from 'react'
import { createApplicationExtensionHost } from '@/app/extension-host'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { ApplicationEventProvider } from '@/shared/app-events'
import { ExtensionHostProvider } from '@/platform/extensions/ExtensionHostContext'
import { IdeLifecycleProvider } from '@/features/ai/ide/IdeLifecycleProvider'

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
            <IdeLifecycleProvider>{children}</IdeLifecycleProvider>
          </ApplicationEventProvider>
        </BrowserPreferencesProvider>
      </ExtensionHostProvider>
    </QueryClientProvider>
  )
}
