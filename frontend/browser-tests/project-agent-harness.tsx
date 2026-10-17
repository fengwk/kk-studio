import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { IdeLifecycleProvider } from '@/features/ai/ide/IdeLifecycleProvider'
import { ProjectDetailPage } from '@/features/projects/ProjectDetailPage'

setLocale('zh-CN')

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
})

const router = createBrowserRouter([{
  path: '/browser-tests/project-agent-harness.html',
  element: (
    <IdeLifecycleProvider>
      <ProjectDetailPage projectId="a0000000-0000-0000-0000-000000000001" />
    </IdeLifecycleProvider>
  ),
}])

export function ProjectAgentHarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <RouterProvider router={router} />
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<ProjectAgentHarnessApp />)
}
