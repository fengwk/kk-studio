import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { createBrowserRouter, RouterProvider, Route, Routes, useNavigate } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { ProjectsInvalidationBridge } from '@/features/projects/extensions/projects-extension'
import { ProjectDetailPage } from '@/features/projects/ProjectDetailPage'
import { ProjectsPage } from '@/features/projects/ProjectsPage'
import { IssueDetailModal } from '@/features/projects/components/IssueDetailModal'
import { ThreadLink } from '@/features/thread/runtime/panes/ThreadLink'
import type { ProjectWorkflowDTO } from '@/shared/api/generated/contracts'
import '@/features/thread/runtime/thread-panel/messages/tool-card.css'

// 语言由查询串决定：默认 zh-CN，`locale=en-US` 用于校验英文界面文案。
const locale = new URLSearchParams(location.search).get('locale') === 'en-US' ? 'en-US' : 'zh-CN'
setLocale(locale)
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

/**
 * 详情弹窗的阶段名称来自工作流：夹具按当前语言给出各阶段名称，弹窗只按工作流名称展示阶段。
 * 阶段不给出 next，避免夹具额外渲染流转动作，干扰详情头部与页签断言。
 */
const detailWorkflow: ProjectWorkflowDTO = {
  states: [
    { state: 'INIT', name: locale === 'en-US' ? 'Not started' : '待开始', next: [], agent: null, environment: null, instructions: null, maxRuns: null },
    { state: 'WORK', name: locale === 'en-US' ? 'In progress' : '处理中', next: [], agent: null, environment: null, instructions: null, maxRuns: null },
    { state: 'DONE', name: locale === 'en-US' ? 'Done' : '完成', next: [], agent: null, environment: null, instructions: null, maxRuns: null },
    { state: 'BLOCKED', name: locale === 'en-US' ? 'Blocked' : '业务阻塞', next: [], agent: null, environment: null, instructions: null, maxRuns: null },
  ],
}

function IssueVisualHarness() {
  const [open, setOpen] = useState(true)
  return <>
    <ThreadLink threadId="offline-task-thread" className="task-tool-thread-link">Task thread</ThreadLink>
    <IssueDetailModal isOpen={open} issueId="a0000000-0000-0000-0000-000000000002"
      projectId="a0000000-0000-0000-0000-000000000001" workflow={detailWorkflow}
      onClose={() => setOpen(false)} />
  </>
}

const router = createBrowserRouter([{
  path: '*',
  element: (
    <>
      <ProjectsInvalidationBridge />
      <HarnessRoutes />
    </>
  ),
}])

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={client}>
    <ApplicationEventProvider>
      <RouterProvider router={router} />
    </ApplicationEventProvider>
  </QueryClientProvider>,
)
