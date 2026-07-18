import { Link, Navigate, Route, Routes } from 'react-router-dom'
import type { PageContribution } from '@/platform/extensions/types'
import { useExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import { AppShell } from '@/platform/shell/AppShell'
import { OverlayHost, WorkbenchSlot } from '@/platform/workbench/WorkbenchSlots'

function UnknownContributionFallback() {
  return (
    <section className="screen active">
      <div className="screen-body"><h1>页面不可用</h1><p>该页面扩展不存在或已卸载。</p></div>
      <Link to="/threads">返回 Chat</Link>
    </section>
  )
}

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

export function WorkbenchShell() {
  return (
    <AppShell>
      <WorkbenchSlot slot="header" />
      <StudioRoutes />
      <WorkbenchSlot slot="status" />
    </AppShell>
  )
}
