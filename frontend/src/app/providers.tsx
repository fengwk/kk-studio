import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { PropsWithChildren } from 'react'
import { applicationContributions } from '@/app/application-contributions'
import { BrowserPreferencesProvider } from '@/features/settings/browser-preferences'
import { ApplicationEventProvider } from '@/shared/app-events'
import { ApplicationContributionsProvider } from '@/platform/extensions/ApplicationContributionsContext'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

/** IDE 不属于全应用根 Provider；Chat 宿主由 WorkbenchShell 的明确路由组合提供。 */
export function AppProviders({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationContributionsProvider contributions={applicationContributions}>
        <BrowserPreferencesProvider>
          <ApplicationEventProvider>
            {children}
          </ApplicationEventProvider>
        </BrowserPreferencesProvider>
      </ApplicationContributionsProvider>
    </QueryClientProvider>
  )
}
