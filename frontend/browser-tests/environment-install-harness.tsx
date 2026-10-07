import { createRoot } from 'react-dom/client'
import { useState } from 'react'
import { Select } from '@/shared/ui/controls/Select'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { EnvironmentsPage } from '@/features/ai/environment/EnvironmentsPage'
import { ApplicationEventProvider } from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'
import '@/styles.css'

setLocale('zh-CN')

/** 真实共享控件置于视口底部，覆盖普通/compact 的翻转、命中与 Tab 顺序。 */
export function Controls() {
  const [value, setValue] = useState('linux')
  const boundaries = new URLSearchParams(location.search).has('tab-boundaries')
  return (
    <div style={{ height: '100dvh', display: 'flex', flexDirection: 'column', justifyContent: 'flex-end', padding: 12, gap: 8 }}>
      <button type="button">Before</button>
      {boundaries && <>
        <details><summary>Before details</summary><input aria-label="Closed before" /></details>
        <button type="button" disabled>Disabled before</button>
      </>}
      <Select aria-label="Normal" value={value} onChange={setValue}
        options={['linux', 'macos', 'windows'].map(value => ({ value, label: value }))} />
      {boundaries && <>
        <details><summary>Between details</summary><input aria-label="Closed between" /></details>
        <button type="button" disabled>Disabled between</button>
      </>}
      <Select aria-label="Compact" compact value={value} onChange={setValue}
        options={['linux', 'macos', 'windows'].map(value => ({ value, label: value }))} />
      {boundaries && <>
        <details><summary>After details</summary><input aria-label="Closed after" /></details>
        <button type="button" disabled>Disabled after</button>
      </>}
      <button type="button">After</button>
    </div>
  )
}

/**
 * 环境页使用应用事件 Provider；HTTP 数据由 Playwright 拦截提供。
 */
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <ApplicationEventProvider>
      <BrowserRouter>{new URLSearchParams(location.search).has('controls') ? <Controls /> : <EnvironmentsPage />}</BrowserRouter>
    </ApplicationEventProvider>
  </QueryClientProvider>,
)
