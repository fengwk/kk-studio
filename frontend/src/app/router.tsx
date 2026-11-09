import { lazy, Suspense, type PropsWithChildren } from 'react'
import { matchPath, Navigate, Route, Routes, useLocation } from 'react-router'
import { PRIMARY_NAV_ITEMS } from '@/app/navigation'
import { WorkbenchShell } from '@/platform/workbench/WorkbenchShell'
import { OverlayHost } from '@/platform/workbench/WorkbenchSlots'
import { ChatIdeWorkspace } from '@/features/environment/ide/ChatIdeWorkspace'
import { useI18n } from '@/shared/i18n'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'

const InteractionsPage = lazy(async () => {
  const module = await import('@/app/pages/InteractionsPage')
  return { default: module.InteractionsPage }
})

/** Chat 持久宿主属于应用组合层；非 Chat 页面不经过 IDE 布局。 */
function ChatIdeRouteHost({ children }: PropsWithChildren) {
  const { pathname } = useLocation()
  const active = matchPath({ path: '/chats/:chatId', end: true }, pathname) != null
  return (
    <>
      <ChatIdeWorkspace active={active}>{active ? children : null}</ChatIdeWorkspace>
      {active ? null : children}
    </>
  )
}

export function AppRouter() {
  const { t } = useI18n()

  return (
    <WorkbenchShell navItems={PRIMARY_NAV_ITEMS} renderContent={(registeredContent) => (
      <ChatIdeRouteHost>
        <Routes>
          <Route path="/" element={<Navigate to="/chats" replace />} />
          <Route path="/interactions" element={(
            <>
              <Suspense fallback={<StateBlock title={t('ai.common.loadingResources')} />}>
                <InteractionsPage />
              </Suspense>
              <OverlayHost />
            </>
          )} />
          <Route path="/*" element={registeredContent} />
        </Routes>
      </ChatIdeRouteHost>
    )} />
  )
}
