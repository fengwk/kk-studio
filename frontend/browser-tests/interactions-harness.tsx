import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router'
import '@/styles.css'
import { ApplicationEventProvider } from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'
import { InteractionsPage } from '@/app/pages/InteractionsPage'

setLocale('zh-CN')

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
})

/**
 * 只渲染真实待处理页与现行 styles：数据全部由 Playwright 拦截 /api 提供，
 * 不依赖后端、模型或任何主机服务。
 * 待处理列表的权威事实由服务端 changed 推送驱动，因此必须挂载真实 ApplicationEventProvider
 * （单例 WebSocket，连接失败只走真实退避）；不得替换为 no-op context。
 */
export function InteractionsHarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/browser-tests/interactions-harness.html" element={<InteractionsPage />} />
          </Routes>
        </BrowserRouter>
      </ApplicationEventProvider>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<InteractionsHarnessApp />)
}
