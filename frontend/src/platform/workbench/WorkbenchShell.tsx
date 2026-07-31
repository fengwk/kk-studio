import { Link, Navigate, Route, Routes } from 'react-router'
import type { PageContribution } from '@/platform/extensions/types'
import { useExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import { AppShell } from '@/platform/shell/AppShell'
import { OverlayHost, WorkbenchSlot } from '@/platform/workbench/WorkbenchSlots'
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
