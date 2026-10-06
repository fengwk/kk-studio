/* eslint-disable react-refresh/only-export-components */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { AgentPane } from '@/features/ai/runtime/AgentPane'

setLocale('zh-CN')

/**
 * 「下一次请求预览」标题点击回归的浏览器基座。
 *
 * 挂载真实 AgentPane（useAgentPaneController + 真实 ThreadComposer + 真实 Debug 视图），
 * 后端全部由 Playwright `page.route` mock 拦截；不启动真实模型或线上服务。
 * 视图初始即 BOUND_THREAD，使 Debug 视图的预览按钮与 Composer 底栏同时可见。
 * 不传 owner：既有 Thread 的输入、Goal、设置、预览与 Stop 都不需要产品容器。
 */
export const PREVIEW_HARNESS_THREAD_ID = 'f0000000-0000-0000-0000-00000000f001'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
})

function DebugPreviewHarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <MemoryRouter>
          <div className="preview-harness-frame" data-testid="preview-harness-frame">
            <AgentPane
              paneId={PREVIEW_HARNESS_THREAD_ID}
              focused
              initialTarget={{ kind: 'BOUND_THREAD', threadId: PREVIEW_HARNESS_THREAD_ID }}
              agents={[
                {
                  name: 'assistant',
                  description: '通用助手',
                  systemPrompt: 'You are a precise coding assistant.',
                  model: 'minimax-m2.7',
                  variant: 'default',
                  config: {
                    inheritParentEnvironment: true,
                    tools: ['read', 'bash'],
                    skills: [],
                    subagents: [],
                  },
                  version: '1',
                  createTime: '2026-10-01T00:00:00Z',
                  updateTime: '2026-10-01T00:00:00Z',
                },
                ...['coder', 'broken-agent'].map((name) => ({
                  name,
                  description: name,
                  model: name === 'coder' ? 'anthropic/Claude' : 'missing/model',
                  variant: 'fast',
                  config: { inheritParentEnvironment: true, tools: [], skills: [], subagents: [] },
                  version: '1',
                })),
              ]}
            />
          </div>
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<DebugPreviewHarnessApp />)
}
