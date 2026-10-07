import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { MemoryRouter, Route, Routes } from 'react-router'
import '@/styles.css'
import { setLocale } from '@/shared/i18n'
import { ApplicationEventProvider } from '@/shared/app-events'
import { ChatWorkspacePage } from '@/features/ai/chat/ChatWorkspacePage'

setLocale('zh-CN')

export const HARNESS_CHAT_ID = 'chat-branch-1'
export const HARNESS_THREAD_ID = 'a2000000-0000-4000-8000-0000000000a1'
export const HARNESS_SESSION_ID = 'b2000000-0000-4000-8000-0000000000b1'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: false },
    mutations: { retry: false },
  },
})

/**
 * 新建分支真实浏览器基座。
 *
 * 挂载真实 `ChatWorkspacePage`（1..9 目标路由、顶栏面包屑、历史树与回合 footer 入口），
 * 后端全部由 Playwright `page.route` 提供；不启动后端、模型或任何主机服务。
 *
 * `?scenario=` 选择入口：
 * - `tree`（默认）：历史树 `/history` 分叉；
 * - `footer`：回合 footer（TURN_END）图标分叉；
 * - `debug`：Debug 主视图下隐藏底部 chrome 并在退出后原样恢复草稿。
 */
export function ChatBranchHarnessApp() {
  const scenario = new URLSearchParams(window.location.search).get('scenario') ?? 'tree'
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <MemoryRouter initialEntries={[`/chats/${HARNESS_CHAT_ID}`]}>
          <div data-testid="scenario" data-scenario={scenario}>
            <Routes>
              <Route path="/chats/:chatId" element={<ChatWorkspacePage />} />
            </Routes>
          </div>
        </MemoryRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootElement = document.getElementById('root')
if (rootElement) {
  createRoot(rootElement).render(<ChatBranchHarnessApp />)
}
