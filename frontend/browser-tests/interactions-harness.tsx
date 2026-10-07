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
 * 待处理页使用应用事件 Provider；HTTP 数据由 Playwright 拦截提供。
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
