import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { useEffect } from 'react'
import { BrowserRouter, Route, Routes, useNavigate } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { AgentPane } from '@/features/ai/runtime/AgentPane'
import { ThreadWorkspacePage } from '@/features/ai/thread/ThreadWorkspacePage'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'

setLocale('zh-CN')

export const HARNESS_ROOT_THREAD_ID = 'a1000000-0000-4000-8000-0000000000a1'
export const HARNESS_CHILD_THREAD_ID = 'a1000000-0000-4000-8000-0000000000a2'

const HARNESS_AGENT: AgentDefinitionDTO = {
  name: 'assistant',
  description: '通用助手',
  systemPrompt: null,
  model: 'minimax/VeryLongModelName',
  variant: 'default',
  environmentId: null,
  config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
  version: '1',
  createTime: '2026-10-01T00:00:00Z',
  updateTime: '2026-10-01T00:00:00Z',
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
})

/**
 * Pane 控制面真实浏览器基座。
 *
 * 挂载真实 `AgentPane`（根 Composer、活跃子代理树、根交互汇聚）与真实
 * `ThreadWorkspacePage`（子代理深链），后端全部由 Playwright `page.route` 提供，
 * 不启动后端、模型或任何主机服务。
 *
 * `?scenario=` 选择被验证的入口：
 * - `pane-root`（默认）：绑定执行根的 pane；
 * - `pane-child`：绑定子代理目标的 pane（不得出现 Composer）；
 * - `thread-child`：子代理独立地址 `/threads/{child}`。
 */
export function PaneControlHarnessApp() {
  const scenario = new URLSearchParams(window.location.search).get('scenario') ?? 'pane-root'
  if (scenario === 'thread-child') {
    return (
      <QueryClientProvider client={queryClient}>
        <ApplicationEventProvider>
          <BrowserRouter>
            <Routes>
              <Route path="/threads/:threadId" element={<ThreadWorkspacePage />} />
              <Route path="*" element={<ChildThreadDeepLinkEntry />} />
            </Routes>
          </BrowserRouter>
        </ApplicationEventProvider>
      </QueryClientProvider>
    )
  }
  const threadId = scenario === 'pane-child'
    ? HARNESS_CHILD_THREAD_ID
    : HARNESS_ROOT_THREAD_ID
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <BrowserRouter>
          <Routes>
            <Route
              path="*"
              element={(
                <div className="pane-harness-frame" data-testid="pane-harness-frame">
                  <AgentPane
                    owner={{ type: 'CHAT', chatId: 'chat-pane-harness' }}
                    paneId="pane-harness"
                    focused
                    agents={[HARNESS_AGENT]}
                    initialTarget={{ kind: 'BOUND_THREAD', threadId }}
                  />
                </div>
              )}
            />
          </Routes>
          <span data-testid="route-marker" />
        </BrowserRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

/** 基座加载后跳到子代理的独立地址，等价于用户直接打开该深链。 */
function ChildThreadDeepLinkEntry() {
  const navigate = useNavigate()
  useEffect(() => {
    navigate(`/threads/${HARNESS_CHILD_THREAD_ID}`, { replace: true })
  }, [navigate])
  return null
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<PaneControlHarnessApp />)
}
