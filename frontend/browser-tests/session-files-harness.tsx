import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import '@/styles.css'
import { SessionFilesDialog } from '@/features/chat/session-files/SessionFilesDialog'
import { setLocale } from '@/shared/i18n'

setLocale('zh-CN')

const SESSION_ID = '11111111-2222-4333-8444-555555555555'
const OWNER_ID = 'a1000000-0000-4000-8000-000000000009'

/**
 * 在真实浏览器中验证会话文件面板：目录浏览、文本窗口分页、图片预览、
 * 下载以及 404/422 错误态。API 由 Playwright 按契约拦截。
 */
export function SessionFilesHarnessApp() {
  const [open, setOpen] = useState(true)
  const [queryClient] = useState(() => new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  }))
  return (
    <QueryClientProvider client={queryClient}>
      <div style={{ padding: 24 }}>
        <button type="button" id="open-session-files" onClick={() => setOpen(true)}>
          打开会话文件
        </button>
      </div>
      {open ? (
        <SessionFilesDialog
          sessionId={SESSION_ID}
          owner={{ kind: 'CHAT', id: OWNER_ID }}
          onClose={() => setOpen(false)}
        />
      ) : null}
    </QueryClientProvider>
  )
}

const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(<SessionFilesHarnessApp />)
}
