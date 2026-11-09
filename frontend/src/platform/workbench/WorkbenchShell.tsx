import type { ReactNode } from 'react'
import { Link, matchPath, Navigate, Route, Routes, useLocation } from 'react-router'
import type { PageContribution } from '@/platform/extensions/types'
import { useApplicationContributions } from '@/platform/extensions/ApplicationContributionsContext'
import { ChatIdeWorkspace } from '@/features/environment/ide/ChatIdeWorkspace'
import { AppShell } from '@/platform/shell/AppShell'
import type { PrimaryNavItem } from '@/platform/shell/types'
import { OverlayHost } from '@/platform/workbench/WorkbenchSlots'
import { useI18n } from '@/shared/i18n'

function UnknownContributionFallback() {
  const { t } = useI18n()

  return (
    <section className="screen active">
      <div className="screen-body">
        <h1>{t('platform.pageUnavailable')}</h1>
        <p>{t('platform.pageExtensionMissing')}</p>
      </div>
      <Link to="/chats">{t('platform.backToChat')}</Link>
    </section>
  )
}

/**
 * Registered Page 必须在自己的 runtime provider 内部渲染 OverlayHost：
 * AI 的 create-chat / resource-editor / delete-resource dialog contribution 通过
 * `useOptionalChatRuntime` / `useOptionalCatalogRuntime` 读取页面级控制器，
 * 只有位于页面 Provider 子树内才能拿到值；挂到 WorkbenchShell 根部会永远读到 null，
 * 模态因此永不挂载。页面组件（AiConsoleFrame 等）负责把 children 渲染在自身子树内。
 */
function RegisteredPage({ page }: { page: PageContribution }) {
  const Page = page.component
  return (
    <Page>
      <OverlayHost />
    </Page>
  )
}

function StudioRoutes() {
  const { pages } = useApplicationContributions()
  const defaultPage = pages[0]

  if (!defaultPage) {
    return <UnknownContributionFallback />
  }

  return (
    <Routes>
      <Route index element={<Navigate to={defaultPage.path} replace />} />
      {pages.map((page) => <Route key={page.id} path={page.path} element={<RegisteredPage page={page} />} />)}
      <Route path="*" element={<UnknownContributionFallback />} />
    </Routes>
  )
}

/**
 * 显式传入 children 的自定义页面（如 `/interactions`）不在 registered page 组合链上，
 * 由 shell 在根部提供唯一 OverlayHost；registered page 路径已由页面自身挂载，
 * 两条路径各自只挂载一次，不重复渲染。
 *
 * Chat IDE 宿主在路由组合层常驻，只由明确的 `/chats/:chatId` 路由匹配激活；
 * 非 Chat 页面直接渲染独立全宽内容，完全不被 IDE shell 包装。
 */
interface WorkbenchShellProps {
  navItems: readonly PrimaryNavItem[]
  children?: ReactNode
}

export function WorkbenchShell({
  navItems,
  children,
}: WorkbenchShellProps) {
  const location = useLocation()
  const chatDetail = matchPath({ path: '/chats/:chatId', end: true }, location.pathname) != null
  const content = children ?? <StudioRoutes />

  return (
    <AppShell navItems={navItems}>
      <ChatIdeWorkspace active={chatDetail}>
        {chatDetail ? content : null}
      </ChatIdeWorkspace>
      {chatDetail ? null : content}
      {children ? <OverlayHost /> : null}
    </AppShell>
  )
}
