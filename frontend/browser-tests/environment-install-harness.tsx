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
 * 真实生产 Provider 栈：EnvironmentsPage 依赖应用生命周期事件（环境状态由服务端推送驱动），
 * 因此必须挂载真实的 ApplicationEventProvider（单例 WebSocket），不能 mock 成 no-op context。
 * 连接失败只走真实退避，不影响页面由 Playwright 拦截的 /api 数据。
 */
createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <ApplicationEventProvider>
      <BrowserRouter>{new URLSearchParams(location.search).has('controls') ? <Controls /> : <EnvironmentsPage />}</BrowserRouter>
    </ApplicationEventProvider>
  </QueryClientProvider>,
)
