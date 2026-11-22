import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { createBrowserRouter, RouterProvider, useNavigate } from 'react-router'
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

/**
 * 视图由查询串选择：`?issue` 打开详情弹窗，`?list` 打开项目列表，其余为项目详情。
 *
 * <p>浏览器测试跑在 MPA 静态预览上，只按 HTML 文件提供入口；`/list` 这类子路径会被当作缺失文件，
 * 所以列表入口与返回列表都用查询串表达，刷新或直接打开都能落到同一个 harness。
 */
export function HarnessRoutes() {
  const navigate = useNavigate()
  const params = new URLSearchParams(location.search)
  if (params.has('issue')) {
    return <IssueVisualHarness />
  }
  if (params.has('list')) {
    return <ProjectsPage />
  }
  return <ProjectDetailPage projectId="a0000000-0000-0000-0000-000000000001"
    onBack={() => navigate({ search: '?list' })} />
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
