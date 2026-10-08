import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { ProjectsInvalidationBridge } from '@/features/projects/extensions/projects-extension'
import { ProjectDetailPage } from '@/features/projects/ProjectDetailPage'
import { ProjectsPage } from '@/features/projects/ProjectsPage'
import { IssueDetailModal } from '@/features/projects/components/IssueDetailModal'
import { ThreadLink } from '@/features/ai/runtime/ThreadLink'
import '@/features/ai/runtime/thread-panel/messages/tool-card.css'

setLocale('zh-CN')
const client = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

export function HarnessRoutes() {
  const navigate = useNavigate()
  if (new URLSearchParams(location.search).has('issue')) {
    return <IssueVisualHarness />
  }
  return <Routes>
    <Route path="/browser-tests/project-workflow-harness.html" element={
      <ProjectDetailPage projectId="a0000000-0000-0000-0000-000000000001"
        onBack={() => navigate('/browser-tests/project-workflow-harness.html/list')} />
    } />
    <Route path="/browser-tests/project-workflow-harness.html/list" element={<ProjectsPage />} />
  </Routes>
}

function IssueVisualHarness() {
  const [open, setOpen] = useState(true)
  return <>
    <ThreadLink threadId="offline-task-thread" className="task-tool-thread-link">Task thread</ThreadLink>
    <IssueDetailModal isOpen={open} issueId="a0000000-0000-0000-0000-000000000002"
      projectId="a0000000-0000-0000-0000-000000000001" onClose={() => setOpen(false)} />
  </>
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
