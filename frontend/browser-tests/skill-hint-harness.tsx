import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, Route, RouterProvider, Routes } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { AgentPane } from '@/features/thread/runtime/AgentPane'
import type { AgentDefinitionDTO, EnvironmentDTO } from '@/shared/api/generated/contracts'

setLocale(new URLSearchParams(window.location.search).get('locale') === 'en-US' ? 'en-US' : 'zh-CN')

export const HARNESS_ROOT_THREAD_ID = 'a1000000-0000-4000-8000-0000000000b1'

/** 环境注册表：NAS 缺少部分技能的当前版本，builder 已安装全部技能。 */
const HARNESS_ENVIRONMENTS: EnvironmentDTO[] = [{
  id: 'a2000000-0000-4000-8000-0000000000e1',
  name: 'NAS',
  status: 'READY',
  statusExpiresAt: null,
  ready: true,
  lastSeenAt: 1790812800000,
  capabilities: [],
  userName: null,
  homeDirectory: null,
  version: '1',
  createdAt: 1790812800000,
  updatedAt: 1790812800000,
}, {
  id: 'a2000000-0000-4000-8000-0000000000e2',
  name: 'builder',
  status: 'READY',
  statusExpiresAt: null,
  ready: true,
  lastSeenAt: 1790812800000,
  capabilities: [],
  userName: null,
  homeDirectory: null,
  version: '1',
  createdAt: 1790812800000,
  updatedAt: 1790812800000,
}]

const HARNESS_AGENT: AgentDefinitionDTO = {
  name: 'assistant',
  description: '带技能的助手',
  systemPrompt: null,
  type: 'BUILTIN',
  execution: { driver: 'builtin', settings: { modelName: 'VeryLongModelName', variant: 'default' } },
  config: {
    tools: [],
    skills: [
      { packageName: 'dev-tools', name: 'dev' },
      { packageName: 'docs-tools', name: 'docs' },
      { packageName: 'ops', name: 'deploy' },
      { packageName: 'release-tools', name: 'migrate' },
    ],
    subagents: [],
  },
  version: '1',
  createTime: 1790812800,
  updateTime: 1790812800,
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
 * 技能可用性提示真实浏览器基座。
 *
 * 挂载真实 `AgentPane`（执行根 Composer、环境选择与环境推送订阅），后端全部由
 * Playwright `page.route` 提供，不启动后端、模型或任何主机服务。
 * 覆盖 jsdom 无法证明的行为：提示与 Composer 同处 pane 输入区、宽度不溢出、
 * 切换环境与安装完成推送后提示消失。
 */
function SkillHintRoutes() {
  return (
    <Routes>
      <Route
        path="*"
        element={(
          <div className="skill-hint-harness-frame" data-testid="skill-hint-harness-frame">
            <AgentPane
              owner={{ kind: 'CHAT', id: 'chat-skill-hint-harness' }}
              paneId="pane-skill-hint"
              focused
              agents={[HARNESS_AGENT]}
              environments={HARNESS_ENVIRONMENTS}
              initialTarget={{ kind: 'BOUND_THREAD', threadId: HARNESS_ROOT_THREAD_ID }}
            />
          </div>
        )}
      />
    </Routes>
  )
}

const router = createBrowserRouter([{
  path: '*',
  element: <SkillHintRoutes />,
}])

export function SkillHintHarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <RouterProvider router={router} />
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<SkillHintHarnessApp />)
}
