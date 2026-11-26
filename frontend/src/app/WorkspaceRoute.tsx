import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Navigate, useLocation, useNavigate, useParams } from 'react-router'
import { AccountMenu } from '@/features/account/AccountMenu'
import { useAccount } from '@/features/account/account-context'
import { CanvasWorkbench } from '@/features/canvas/CanvasWorkbench'
import { EnvironmentList } from '@/features/environment/ide/EnvironmentList'
import { IdePaneCloseContext } from '@/features/environment/ide/ide-pane-close'
import { IdeWorkspaceProvider } from '@/features/environment/ide/IdeWorkspaceProvider'
import { VsCodePaneRenderer } from '@/features/environment/ide/VsCodePaneRenderer'
import { DraftPaneRenderer, ThreadPaneRenderer } from '@/features/thread/workspace'
import { WorkspaceShell, type WorkspaceShellProps } from '@/features/workspace/WorkspaceShell'
import type { PaneRendererProps, PaneRenderers, PaneTarget } from '@/features/workspace/pane-target'
import { workspaceKeys } from '@/features/workspace/queries'
import { createSpace, listSpaces } from '@/features/workspace/workspace-api'
import { useI18n } from '@/shared/i18n'
import { createUuid } from '@/shared/lib/uuid'
import { Button } from '@/shared/ui/controls/Button'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { NameDialog } from '@/shared/ui/overlays/NameDialog'

function CanvasPane({ target }: PaneRendererProps<Extract<PaneTarget, { kind: 'canvas' }>>) {
  return <CanvasWorkbench canvasId={target.canvasId} viewSlot={target.viewSlot} />
}
const renderers = { canvas: CanvasPane, vscode: VsCodePaneRenderer, thread: ThreadPaneRenderer, draft: DraftPaneRenderer } satisfies PaneRenderers
const account = (hasUnsentContent: boolean) => <AccountMenu hasUnsentContent={hasUnsentContent} />
const pathToSpace = (id: string) => `/chat-spaces/${id}`

function IdePaneScope({ children, guards, remove }: { children: ReactNode; guards: Map<string, () => Promise<boolean>>; remove: (id: string) => void }) {
  const register = useCallback((id: string, guard: () => Promise<boolean>) => {
    guards.set(id, guard)
    return () => { if (guards.get(id) === guard) guards.delete(id) }
  }, [guards])
  return <IdePaneCloseContext.Provider value={{ register, remove }}>{children}</IdePaneCloseContext.Provider>
}

function WorkspacePage(props: Omit<WorkspaceShellProps, 'renderers' | 'account' | 'environments' | 'closeView' | 'wrapPanes'>) {
  const [guards] = useState(() => new Map<string, () => Promise<boolean>>())
  const closeView = (id: string) => guards.get(id)?.() ?? Promise.resolve(false)
  return <IdeWorkspaceProvider>
    <WorkspaceShell {...props} renderers={renderers} account={account} environments={EnvironmentList} closeView={closeView}
      wrapPanes={(content, remove) => <IdePaneScope guards={guards} remove={remove}>{content}</IdePaneScope>} />
  </IdeWorkspaceProvider>
}

export function WorkspaceRoute() {
  const { user } = useAccount()
  const { t } = useI18n()
  const { spaceId } = useParams()
  const location = useLocation()
  const navigate = useNavigate()
  const query = useQueryClient()
  const spaces = useQuery({ queryKey: workspaceKeys.spaces(), queryFn: ({ signal }) => listSpaces(signal), enabled: Boolean(user) })
  const current = spaces.data?.find(space => space.id === spaceId)
  const storageKey = `workspace-last-active:${user?.id}`
  const idempotencyKey = useRef<string>(undefined)
  const [create, setCreate] = useState(false)
  useEffect(() => {
    if (user && current && !current.archivedAt) {
      try { localStorage.setItem(storageKey, current.id) } catch { /* Storage may be unavailable. */ }
    }
  }, [current, storageKey, user])
  if (!user) return null
  if (!spaces.data) return <StateBlock title={t(spaces.isError ? 'workspace.unavailable' : 'workspace.loading')} tone={spaces.isError ? 'danger' : undefined} />
  if (spaceId && !current) return <StateBlock tone="danger" title={t('workspace.unavailable')} />
  if (!current) {
    const active = spaces.data.filter(space => !space.archivedAt)
    let recent: string | null = null
    try { recent = localStorage.getItem(storageKey) } catch { /* Storage may be unavailable. */ }
    const next = active.find(space => space.id === recent) ?? active[0]
    if (next) return <Navigate to={pathToSpace(next.id)} replace />
    return <><StateBlock title={t('workspace.noSpace')} action={<Button onClick={() => { idempotencyKey.current = createUuid(); setCreate(true) }}>{t('workspace.newSpace')}</Button>} />
      {create && <NameDialog title={t('workspace.newSpace')} onCancel={() => setCreate(false)} onConfirm={async title => {
        try {
          const space = await createSpace({ title, rootFolderName: t('workspace.defaultFolder') }, idempotencyKey.current!)
          await query.invalidateQueries({ queryKey: workspaceKeys.spaces(), exact: true })
          setCreate(false)
          void navigate(pathToSpace(space.id), { replace: true })
        } catch (error) { throw new Error(t('shared.requestFailed'), { cause: error }) }
      }} />}
    </>
  }
  return <WorkspacePage key={`${user.id}:${current.id}`} userId={user.id} space={current} spaces={spaces.data}
    selectSpace={id => { void navigate(pathToSpace(id)) }}
    openInteraction={(id, threadId) => { void navigate({ pathname: pathToSpace(id), search: new URLSearchParams({ thread: threadId }).toString() }) }}
    goToSettings={() => { void navigate('/settings/preferences', { state: { from: `${location.pathname}${location.search}${location.hash}` } }) }}
    link={{ search: location.search, consume: () => { void navigate({ pathname: location.pathname, hash: location.hash }, { replace: true }) } }} />
}
