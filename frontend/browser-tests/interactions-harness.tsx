import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Route, Routes } from 'react-router'
import '@/styles.css'
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
 */
export function InteractionsHarnessApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/browser-tests/interactions-harness.html" element={<InteractionsPage />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<InteractionsHarnessApp />)
}
