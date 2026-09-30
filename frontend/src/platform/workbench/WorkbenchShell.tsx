import type { ReactNode } from 'react'
import { Link, Navigate, Route, Routes } from 'react-router'
import type { PageContribution } from '@/platform/extensions/types'
import { useExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
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
  const host = useExtensionHostSnapshot()
  const pages = host.pages.list()
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
 */
export function WorkbenchShell({
  navItems,
  children,
}: {
  navItems?: readonly PrimaryNavItem[]
  children?: ReactNode
} = {}) {
  return (
    <AppShell navItems={navItems}>
      {children ?? <StudioRoutes />}
      {children ? <OverlayHost /> : null}
    </AppShell>
  )
}
