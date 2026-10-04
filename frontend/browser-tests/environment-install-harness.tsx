import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EnvironmentsPage } from '@/features/ai/environment/EnvironmentsPage'
import { setLocale } from '@/shared/i18n'
import '@/styles.css'

setLocale('zh-CN')
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <BrowserRouter><EnvironmentsPage /></BrowserRouter>
  </QueryClientProvider>,
)
