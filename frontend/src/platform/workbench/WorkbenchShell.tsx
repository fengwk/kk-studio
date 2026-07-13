import { Navigate, Route, Routes, useParams } from 'react-router-dom'
import type { PageContribution, WorkspacePageProps } from '@/platform/extensions/types'
import { useExtensionHost } from '@/platform/extensions/ExtensionHostContext'
import { AppShell } from '@/platform/shell/AppShell'
import { OverlayHost, WorkbenchSlot } from '@/platform/workbench/WorkbenchSlots'

function UnknownContributionFallback() {
  const { workspaceId = '' } = useParams()
  return (
    <section className="screen active">
      <div className="screen-body"><h1>页面不可用</h1><p>该页面扩展不存在或已卸载。</p></div>
      <a href={`/workspaces/${encodeURIComponent(workspaceId)}/sessions`}>返回 Chat</a>
    </section>
  )
}

function RegisteredPage({ page, workspaceId }: { page: PageContribution; workspaceId: string }) {
  const Page = page.component
  return (
    <Page workspaceId={workspaceId}>
      <OverlayHost workspaceId={workspaceId} />
    </Page>
  )
}

function WorkspaceWorkbenchRoutes({ workspaceId }: WorkspacePageProps) {
  const host = useExtensionHost()
  const pages = host.pages.list()
  const defaultPage = pages[0]

  if (!defaultPage) {
    return <UnknownContributionFallback />
  }

  return (
    <Routes>
      <Route index element={<Navigate to={defaultPage.path} replace />} />
      {pages.map((page) => <Route key={page.id} path={page.path} element={<RegisteredPage page={page} workspaceId={workspaceId} />} />)}
      <Route path="*" element={<UnknownContributionFallback />} />
    </Routes>
  )
}

export function WorkbenchShell() {
  const { workspaceId = '' } = useParams()
  return (
    <AppShell workspaceId={workspaceId}>
      <WorkbenchSlot slot="header" workspaceId={workspaceId} />
      <WorkspaceWorkbenchRoutes workspaceId={workspaceId} />
      <WorkbenchSlot slot="status" workspaceId={workspaceId} />
    </AppShell>
  )
}
