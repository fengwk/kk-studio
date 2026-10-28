import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { PropsWithChildren } from 'react'
import { applicationContributions } from '@/app/application-contributions'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { ApplicationEventProvider } from '@/shared/app-events'
import { ApplicationContributionsProvider } from '@/platform/extensions/ApplicationContributionsContext'
import { IdePanelProvider } from '@/features/environment/ide/IdePanelProvider'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationContributionsProvider contributions={applicationContributions}>
        <BrowserPreferencesProvider>
          <ApplicationEventProvider>
            <IdePanelProvider>{children}</IdePanelProvider>
          </ApplicationEventProvider>
        </BrowserPreferencesProvider>
      </ApplicationContributionsProvider>
    </QueryClientProvider>
  )
}
