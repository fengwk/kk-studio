import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ComponentType, type ReactNode } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { PanelLeftClose, PanelLeftOpen, Settings } from 'lucide-react'
import { ApiError, apiErrorDetail } from '@/shared/api/client'
import type { ChatSpaceDTO, WorkspaceTreeDTO } from '@/shared/api/generated/contracts'
import { useI18n } from '@/shared/i18n'
import { createUuid } from '@/shared/lib/uuid'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import { NameDialog } from '@/shared/ui/overlays/NameDialog'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { newDraft } from './pane-bindings'
import type { PaneRenderers, PaneTarget } from './pane-target'
import { PaneLayout } from './panes'
import { workspaceKeys } from './queries'
import { indexRows, treeRows, type TreeDrop, type TreeRow } from './resource-tree'
import { SpaceDefaults } from './SpaceDefaults'
import { SpaceMenu } from './SpaceMenu'
import { useWorkspaceRealtime } from './useWorkspaceRealtime'
import { useWorkspacePanes, type OpenTarget } from './useWorkspacePanes'
import { WorkspaceInbox } from './WorkspaceInbox'
import { WorkspaceSchedules } from './WorkspaceSchedules'
import { WorkspaceStatus, WorkspaceTree } from './WorkspaceTree'
import { createCanvas, createFolder, createSpace, deleteTreeRow, getSpaceTree, getThreadPlacement, moveTreeRow, patchSpace, readCanvasRevision, renameTreeRow, type TreeDeletion } from './workspace-api'
import './workspace-shell.css'

const workspaceErrors = {
  VERSION_CONFLICT: 'workspace.versionConflict', FOLDER_NAME_EXISTS: 'workspace.nameExists',
  CHAT_SPACE_TITLE_EXISTS: 'workspace.nameExists', THREAD_NAME_EXISTS: 'workspace.nameExists',
  LAST_ACTIVE_CHAT_SPACE: 'workspace.lastSpace', FOLDER_NOT_EMPTY: 'workspace.folderNotEmpty',
  LAST_ROOT_FOLDER: 'workspace.lastRoot', RESOURCE_IN_USE: 'workspace.inUse',
  EXECUTION_NOT_SETTLED: 'workspace.notSettled',
} as const

export type WorkspaceShellProps = {
  userId: string
  space: ChatSpaceDTO
  spaces: ChatSpaceDTO[]
  selectSpace: (id: string) => void
  openInteraction: (spaceId: string, threadId: string) => void
  goToSettings: () => void
  renderers: Partial<PaneRenderers>
  account: (unsent: boolean) => ReactNode
  environments?: ComponentType<{ open: OpenTarget; findOpenView: (environmentId: string) => string | undefined }>
  closeView: (viewId: string) => Promise<boolean>
  wrapPanes: (content: ReactNode, remove: (viewId: string) => void) => ReactNode
  link: { search: string; consume: () => void }
}

export function WorkspaceShell(props: WorkspaceShellProps) {
  const { t } = useI18n()
  useWorkspaceRealtime(props.space.id)
  const tree = useQuery({ queryKey: workspaceKeys.tree(props.space.id), queryFn: ({ signal }) => getSpaceTree(props.space.id, signal) })
  if (!tree.data) return <StateBlock title={t(tree.isError ? 'workspace.unavailable' : 'workspace.loading')} tone={tree.isError ? 'danger' : undefined} />
  return <WorkspaceBody {...props} tree={tree.data} />
}

function loadSidebar(key: string): { width: number; expanded?: string[] } {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? 'null') as { width?: unknown; expanded?: unknown } | null
    if (value && typeof value.width === 'number' && Array.isArray(value.expanded) && value.expanded.every(id => typeof id === 'string')) {
      return { width: Math.max(200, Math.min(480, value.width)), expanded: value.expanded }
    }
  } catch { /* Local storage may be unavailable. */ }
  return { width: 264 }
}

function WorkspaceBody({ userId, space, spaces, tree, selectSpace, openInteraction, goToSettings, renderers, account, environments: Environments,
  closeView, wrapPanes, link }: WorkspaceShellProps & { tree: WorkspaceTreeDTO }) {
  const { t } = useI18n()
  const query = useQueryClient()
  const roots = useMemo(() => treeRows(tree), [tree])
  const index = useMemo(() => indexRows(roots), [roots])
  const key = `workspace-sidebar:${JSON.stringify([userId, space.id])}`
  const [stored] = useState(() => loadSidebar(key))
  const [expanded, expand] = useState(() => new Set(stored.expanded ?? roots.map(row => row.id)))
  const [width, setWidth] = useState(stored.width)
  const [collapsed, setCollapsed] = useState(false)
  const [drawer, setDrawer] = useState(false)
  const [tab, setTab] = useState('directory')
  const [notice, setNotice] = useState<{ title: string; detail?: string | null }>()
  const report = useCallback((title: string) => setNotice({ title }), [])
  const [creation, setCreation] = useState<{ kind: 'canvas' | 'space'; parentId?: string; key: string }>()
  const [deletion, setDeletion] = useState<{ row: TreeRow; request: TreeDeletion; busy?: boolean; error?: string }>()
  const [defaults, setDefaults] = useState(false)
  const resize = useRef<{ x: number; width: number }>(undefined)
  const narrowTrigger = useRef<HTMLButtonElement>(null)
  const drawerClose = useRef<HTMLButtonElement>(null)
  const restoreDrawerFocus = useRef(false)
  const consumed = useRef('')
  const panes = useWorkspacePanes({ userId, space, rows: index, renderers, closeView, report, openSpaceSettings: () => setDefaults(true) })
  const fullscreen = panes.layoutProps.fullscreen
  const openPane = panes.open
  const errorText = (error: unknown) => {
    if (error instanceof ApiError) {
      const reason = error.reason ?? error.code
      if (reason && Object.hasOwn(workspaceErrors, reason)) {
        const title = t(workspaceErrors[reason as keyof typeof workspaceErrors])
        const detail = reason === 'RESOURCE_IN_USE' ? apiErrorDetail(error) : null
        return detail ? `${title} ${detail}` : title
      }
    }
    return t('shared.requestFailed')
  }
  const refresh = (isSpace: boolean) => query.invalidateQueries({ queryKey: isSpace ? workspaceKeys.spaces() : workspaceKeys.tree(space.id), exact: true })
  async function write<T>(run: () => Promise<T>, isSpace = false) {
    try {
      const result = await run()
      await refresh(isSpace)
      return result
    } catch (error) {
      if (error instanceof ApiError && (error.reason === 'VERSION_CONFLICT' || error.code === 'VERSION_CONFLICT')) await refresh(isSpace)
      throw new Error(errorText(error), { cause: error })
    }
  }
  function setSidebarWidth(value: number) { setWidth(Math.max(200, Math.min(480, value))) }
  useEffect(() => {
    try { localStorage.setItem(key, JSON.stringify({ width, expanded: [...expanded] })) } catch { /* Keep state in memory. */ }
  }, [expanded, key, width])
  useLayoutEffect(() => {
    if (drawer) drawerClose.current?.focus()
    else if (restoreDrawerFocus.current) {
      restoreDrawerFocus.current = false
      narrowTrigger.current?.focus()
    }
  }, [drawer])

  const params = new URLSearchParams(link.search)
  const threadId = params.get('thread'), canvasId = params.get('canvas')
  const threadLink = useQuery({
    queryKey: [...workspaceKeys.tree(space.id), 'placement', threadId],
    queryFn: ({ signal }) => getThreadPlacement(space.id, threadId!, signal),
    enabled: Boolean(threadId && !canvasId), staleTime: 0,
  })
  useEffect(() => {
    if (!threadId && !canvasId) { consumed.current = ''; return }
    if (consumed.current === link.search || (threadId && !canvasId && threadLink.isFetching)) return
    consumed.current = link.search
    const target: PaneTarget | undefined = threadId && !canvasId && threadLink.data && !threadLink.isError
      ? { kind: 'thread', threadId }
      : canvasId && !threadId && index.get(canvasId)?.kind === 'canvas' ? { kind: 'canvas', canvasId } : undefined
    if (target) openPane(target)
    else report(t('workspace.unavailable'))
    link.consume()
  }, [canvasId, index, link, openPane, report, t, threadId, threadLink.data, threadLink.isError, threadLink.isFetching])

  function closeDrawer() { restoreDrawerFocus.current = true; setDrawer(false) }
  function open(target: PaneTarget, anchor?: HTMLElement | null) {
    // The picker or opened pane owns focus; the trigger is only the picker's return target.
    if (drawer) setDrawer(false)
    panes.open(target, drawer ? narrowTrigger.current : anchor)
  }
  function openRow(row: TreeRow, anchor: HTMLElement) {
    if (row.kind === 'thread') open({ kind: 'thread', threadId: row.id }, anchor)
    if (row.kind === 'canvas') open({ kind: 'canvas', canvasId: row.id }, anchor)
  }
  const toggle = useCallback((id: string, force?: boolean) => expand(old => {
    const next = new Set(old)
    if (force ?? !old.has(id)) next.add(id)
    else next.delete(id)
    return next
  }), [])
  function create(kind: 'canvas' | 'session' | 'space', parentId?: string) {
    if (kind === 'session') { if (parentId) toggle(parentId, true); open(newDraft(parentId)); return }
    setCreation({ kind, parentId, key: createUuid() })
  }
  function move(row: TreeRow, drop: TreeDrop) {
    void write(() => moveTreeRow(space.id, row, drop, tree.treeVersion)).then(() => {
      if (drop.parentId) toggle(drop.parentId, true)
    }).catch((error: Error) => report(error.message))
  }
  function remove(row: TreeRow) {
    const expectedTreeVersion = tree.treeVersion
    const { id, kind } = row
    if (kind === 'canvas') {
      void readCanvasRevision(id).then(expectedRevision => setDeletion({ row, request: { kind, id, expectedTreeVersion, expectedRevision } }))
        .catch(error => report(errorText(error)))
    } else if (kind === 'thread') {
      const parent = row.parentId && index.get(row.parentId)
      setDeletion({ row, request: parent && parent.children.length === 1
        ? { kind: 'session', id: parent.id, expectedTreeVersion }
        : { kind, id, expectedTreeVersion, expectedVersion: row.thread.version } })
    } else setDeletion({ row, request: { kind, id, expectedTreeVersion } })
  }
  const footer = (rail: boolean) => <div className="workspace-account" data-rail={rail || undefined}>
    {account(panes.hasUnsentContent)}
    <IconButton className="workspace-touch" label={t('workspace.settings')} onClick={goToSettings}><Settings /></IconButton>
  </div>
  return <div className="workspace-shell" data-fullscreen={fullscreen || undefined} data-drawer={drawer || undefined}
    onKeyDown={event => {
      if (
        event.key === 'Escape' &&
        drawer &&
        !event.defaultPrevented &&
        event.target instanceof Node &&
        event.currentTarget.contains(event.target)
      ) {
        event.stopPropagation()
        closeDrawer()
      }
    }}>
    {!fullscreen && <>
      {drawer && <button type="button" className="workspace-drawer-backdrop" tabIndex={-1} aria-label={t('workspace.collapseSidebar')} onClick={closeDrawer} />}
      <aside className="workspace-sidebar" data-collapsed={collapsed || undefined} style={{ width }}>
        <div className="workspace-sidebar-head">
          <SpaceMenu current={space} spaces={spaces} select={selectSpace} create={() => create('space')}
            settings={() => setDefaults(true)} rename={async (item, title) => { await write(() => patchSpace(item.id, { title, expectedVersion: item.version }), true) }}
            archive={(item, archived) => void write(() => patchSpace(item.id, { archived, expectedVersion: item.version }), true)
              .then(() => {
                if (!archived) selectSpace(item.id)
                else { const next = spaces.find(candidate => !candidate.archivedAt && candidate.id !== item.id); if (next) selectSpace(next.id) }
              }).catch((error: Error) => report(error.message))} />
          <WorkspaceInbox userId={userId} onOpen={(spaceId, threadId) => { if (drawer) closeDrawer(); openInteraction(spaceId, threadId) }} />
          <IconButton className="workspace-desktop-collapse" label={t('workspace.collapseSidebar')} onClick={() => setCollapsed(true)}><PanelLeftClose /></IconButton>
          <IconButton ref={drawerClose} className="workspace-mobile-close workspace-touch" label={t('workspace.collapseSidebar')} onClick={closeDrawer}><PanelLeftClose /></IconButton>
        </div>
        <Tabs tabs={[
          { id: 'directory', label: t('workspace.directory') },
          { id: 'status', label: <span className="workspace-status-tab">{t('workspace.status')}{[...index.values()].some(row => row.kind === 'thread' && row.thread.state === 'NEEDS_YOU') && <i />}</span> },
          { id: 'schedules', label: t('workspace.schedules') },
        ]} activeId={tab} onChange={setTab} ariaLabel={t('workspace.sidebar')} className="workspace-tabs" panelClassName="workspace-tab-panel">
          {tab === 'directory' ? <WorkspaceTree roots={roots} index={index} expanded={expanded} toggle={toggle} open={openRow} create={create}
            createDirectory={async (name, idempotencyKey, parentId) => {
              const result = await write(() => createFolder(space.id, { parentId, name, expectedTreeVersion: tree.treeVersion }, idempotencyKey))
              return result.id
            }}
            rename={async (row, name) => { await write<unknown>(() => renameTreeRow(space.id, row, name)) }} remove={remove} move={move} />
            : tab === 'status' ? <WorkspaceStatus index={index} open={openRow} />
            : <WorkspaceSchedules space={space} tree={tree} open={open} report={report} />}
        </Tabs>
        {Environments && <div className="workspace-environments"><Environments open={open} findOpenView={panes.findOpenView} /></div>}
        {footer(false)}
        <div className="workspace-side-resize" role="separator" data-ui-focus="row" aria-label={t('workspace.resizeSidebar')}
          aria-orientation="vertical" aria-valuemin={200} aria-valuemax={480} aria-valuenow={width} tabIndex={0}
          onPointerDown={event => { if (event.button === 0) { resize.current = { x: event.clientX, width }; event.currentTarget.setPointerCapture(event.pointerId) } }}
          onPointerMove={event => { if (resize.current) setSidebarWidth(resize.current.width + event.clientX - resize.current.x) }}
          onLostPointerCapture={() => { resize.current = undefined }} onDoubleClick={() => setSidebarWidth(264)}
          onKeyDown={event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); setSidebarWidth(width + (event.key === 'ArrowLeft' ? -16 : 16)) } }} />
      </aside>
      {collapsed && <aside className="workspace-rail">
        <div className="workspace-rail-top">
          <IconButton label={t('workspace.expandSidebar')} onClick={() => setCollapsed(false)}><PanelLeftOpen /></IconButton>
          <WorkspaceInbox userId={userId} onOpen={(spaceId, threadId) => { if (drawer) closeDrawer(); openInteraction(spaceId, threadId) }} />
        </div>
        {footer(true)}
      </aside>}
    </>}
    <main className="workspace-main" inert={drawer || undefined}>
      {wrapPanes(<PaneLayout {...panes.layoutProps} activeHeaderLeading={!fullscreen && <div className="workspace-mobile-bar">
        <IconButton ref={narrowTrigger} className="workspace-touch" label={t('workspace.expandSidebar')} onClick={() => setDrawer(true)}><PanelLeftOpen /></IconButton>
      </div>} />, panes.removeView)}
    </main>
    {panes.confirmation}
    {notice && <Dialog title={notice.title} onClose={() => setNotice(undefined)}>
      {notice.detail && <p className="modal-body">{notice.detail}</p>}
    </Dialog>}
    {creation && <NameDialog title={t(creation.kind === 'space' ? 'workspace.newSpace' : 'workspace.newCanvas')}
      onCancel={() => setCreation(undefined)} onConfirm={async name => {
        if (creation.kind === 'space') {
          const next = await write(() => createSpace({ title: name, rootFolderName: t('workspace.defaultFolder'),
            defaultAgentName: space.defaultAgentName, defaultEnvironmentId: space.defaultEnvironmentId, defaultYoloEnabled: space.defaultYoloEnabled }, creation.key), true)
          setCreation(undefined); selectSpace(next.id)
        } else if (creation.parentId) {
          const folderId = creation.parentId
          const canvas = await write(() => createCanvas(space.id, { folderId, title: name, expectedTreeVersion: tree.treeVersion }, creation.key))
          toggle(folderId, true); setCreation(undefined); open({ kind: 'canvas', canvasId: canvas.id })
        }
      }} />}
    <ConfirmActionModal pending={Boolean(deletion?.busy)} error={deletion?.error} onClose={() => setDeletion(undefined)} modal={deletion ? {
      title: t('workspace.deleteItem', { name: deletion.row.name }), description: t('workspace.deletePrompt'), tone: 'danger',
      onConfirm: () => {
        setDeletion({ ...deletion, busy: true })
        void write(() => deleteTreeRow(space.id, deletion.request)).then(() => setDeletion(undefined))
          .catch((error: Error) => {
            const cause = error.cause
            if (cause instanceof ApiError && (cause.reason ?? cause.code) === 'RESOURCE_IN_USE') {
              setDeletion(undefined)
              setNotice({ title: t('workspace.inUse'), detail: apiErrorDetail(cause) })
            } else setDeletion({ ...deletion, error: error.message })
          })
      },
    } : null} />
    {defaults && <SpaceDefaults space={space} onClose={() => setDefaults(false)} save={body => write(() => patchSpace(space.id, body), true)} />}
  </div>
}
