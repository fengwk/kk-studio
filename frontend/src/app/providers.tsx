import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { PropsWithChildren } from 'react'
import { applicationContributions } from '@/app/application-contributions'
import { AccountProvider } from '@/features/account/AccountProvider'
import { ApplicationContributionsProvider } from '@/platform/extensions/ApplicationContributionsContext'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

export function AppProviders({ children }: PropsWithChildren) {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationContributionsProvider contributions={applicationContributions}>
        <AccountProvider>{children}</AccountProvider>
      </ApplicationContributionsProvider>
    </QueryClientProvider>
  )
}
