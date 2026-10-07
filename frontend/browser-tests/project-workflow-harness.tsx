import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { ProjectsInvalidationBridge } from '@/features/projects/extensions/projects-extension'
import { ProjectDetailPage } from '@/features/projects/ProjectDetailPage'
import { ProjectsPage } from '@/features/projects/ProjectsPage'

setLocale('zh-CN')
const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

export function HarnessRoutes() {
  const navigate = useNavigate()
  return <Routes>
    <Route path="/browser-tests/project-workflow-harness.html" element={
      <ProjectDetailPage projectId="a0000000-0000-0000-0000-000000000001"
        onBack={() => navigate('/browser-tests/project-workflow-harness.html/list')} />
    } />
    <Route path="/browser-tests/project-workflow-harness.html/list" element={<ProjectsPage />} />
  </Routes>
}

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}>
    <ApplicationEventProvider>
      <BrowserRouter>
        <ProjectsInvalidationBridge />
        <HarnessRoutes />
      </BrowserRouter>
    </ApplicationEventProvider>
  </QueryClientProvider>,
)
