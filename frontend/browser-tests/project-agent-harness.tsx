import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
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

export function ProjectAgentHarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <BrowserRouter>
          <Routes>
            <Route
              path="/browser-tests/project-agent-harness.html"
              element={<ProjectDetailPage projectId="a0000000-0000-0000-0000-000000000001" />}
            />
          </Routes>
        </BrowserRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<ProjectAgentHarnessApp />)
}
