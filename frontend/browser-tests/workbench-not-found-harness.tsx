/* eslint-disable react-refresh/only-export-components */
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Bot } from 'lucide-react'
import '@/styles.css'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'
import { ApplicationContributionsProvider } from '@/platform/extensions/ApplicationContributionsContext'
import type { ApplicationContributions, PageContribution } from '@/platform/extensions/types'
import type { PrimaryNavItem } from '@/platform/shell/types'
import { ApplicationEventProvider } from '@/shared/app-events'
import { setLocale } from '@/shared/i18n'

// 语言由查询串决定：默认 zh-CN，`locale=en-US` 用于校验英文界面文案。
setLocale(new URLSearchParams(location.search).get('locale') === 'en-US' ? 'en-US' : 'zh-CN')

/** 已注册页面的占位实现：本夹具只关心未匹配路由的 404 回退。 */
function ChatsPage() {
  return (
    <section className="screen active">
      <div className="screen-body">
        <h1>Chats</h1>
      </div>
    </section>
  )
}

const PAGES: readonly PageContribution[] = [
  {
    id: 'ai',
    path: '/chats',
    component: ChatsPage,
    navGroup: 'ai',
    navItem: { label: 'AI', labelKey: 'platform.nav.ai' },
  },
]

const NAV_ITEMS: readonly PrimaryNavItem[] = [
  {
    id: 'ai',
    groupId: 'ai',
    to: '/chats',
    labelKey: 'platform.nav.ai',
    ariaKey: 'platform.nav.aiAria',
    icon: Bot,
  },
]

const CONTRIBUTIONS: ApplicationContributions = {
  pages: PAGES,
  dialogs: [],
  overlays: [],
  toolRenderers: new Map(),
}

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
})

/**
 * WorkbenchShell 的未匹配路由回退夹具：挂载真实 AppShell + StudioRoutes，
 * 用最小 contributions 驱动 `*` 路由，只验证 404 空态在真实壳层里的布局。
 */
const rootEl = document.getElementById('root')
if (rootEl) {
  createRoot(rootEl).render(
    <QueryClientProvider client={queryClient}>
      <ApplicationEventProvider>
        <ApplicationContributionsProvider contributions={CONTRIBUTIONS}>
          <MemoryRouter initialEntries={['/missing-page']}>
            <WorkbenchShell navItems={NAV_ITEMS} />
          </MemoryRouter>
        </ApplicationContributionsProvider>
      </ApplicationEventProvider>
    </QueryClientProvider>,
  )
}
