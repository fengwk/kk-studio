import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { Select } from '@/shared/ui/console/Select'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EnvironmentsPage } from '@/features/ai/environment/EnvironmentsPage'
import { setLocale } from '@/shared/i18n'
import '@/styles.css'

setLocale('zh-CN')

/** 真实共享控件置于视口底部，覆盖普通/compact 的翻转、命中与 Tab 顺序。 */
export function Controls() {
  const [value, setValue] = useState('linux')
  return (
    <div style={{ height: '100dvh', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', padding: 12, gap: 8 }}>
      <button type="button">Before</button>
      <Select aria-label="Normal" value={value} onChange={setValue}
        options={['linux', 'macos', 'windows'].map(value => ({ value, label: value }))} />
      <Select aria-label="Compact" compact value={value} onChange={setValue}
        options={['linux', 'macos', 'windows'].map(value => ({ value, label: value }))} />
      <button type="button">After</button>
    </div>
  )
}

createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <BrowserRouter>{new URLSearchParams(location.search).has('controls') ? <Controls /> : <EnvironmentsPage />}</BrowserRouter>
  </QueryClientProvider>,
)
