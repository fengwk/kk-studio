import { useEffect, useRef, useState, type ReactNode, type Ref } from 'react'
import { PanelLeftClose, PanelLeftOpen, Settings } from 'lucide-react'
import { useI18n } from '@/shared/i18n'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Tabs } from '@/shared/ui/controls/Tabs'

export const DEFAULT_SIDEBAR_WIDTH = 264
const minWidth = 200
const maxWidth = 480

function clampWidth(width: number) { return Math.max(minWidth, Math.min(maxWidth, width)) }

type WorkspaceSidebarProps = {
  fullscreen: boolean
  storage: { key: string; initialWidth: number; expanded: ReadonlySet<string> }
  drawer: { open: boolean; close: () => void; closeRef: Ref<HTMLButtonElement> }
  spaceMenu: ReactNode
  inbox: ReactNode
  panels: { directory: ReactNode; status: ReactNode; schedules: ReactNode }
  needsAttention: boolean
  environments: ReactNode
  account: () => ReactNode
  goToSettings: () => void
}

export function WorkspaceSidebar({ fullscreen, storage: { key, initialWidth, expanded },
  drawer: { open: drawerOpen, close: closeDrawer, closeRef: drawerCloseRef }, spaceMenu, inbox,
  panels, needsAttention, environments, account, goToSettings }: WorkspaceSidebarProps) {
  const { t } = useI18n()
  const [width, setWidth] = useState(() => clampWidth(initialWidth))
  const [collapsed, setCollapsed] = useState(false)
  const [tab, setTab] = useState('directory')
  const resize = useRef<{ x: number; width: number }>(undefined)

  useEffect(() => {
    try { localStorage.setItem(key, JSON.stringify({ width, expanded: [...expanded] })) } catch { /* Keep state in memory. */ }
  }, [expanded, key, width])

  // Keep layout state but unmount sidebar controls and overlays in fullscreen.
  if (fullscreen) return null

  function setSidebarWidth(value: number) { setWidth(clampWidth(value)) }
  const footer = (rail: boolean) => <div className="workspace-account" data-rail={rail || undefined}>
    {account()}
    <IconButton className="workspace-touch" label={t('workspace.settings')} onClick={goToSettings}><Settings /></IconButton>
  </div>
  return <>
    {drawerOpen && <button type="button" className="workspace-drawer-backdrop" tabIndex={-1} aria-label={t('workspace.collapseSidebar')} onClick={closeDrawer} />}
    <aside className="workspace-sidebar" data-collapsed={collapsed || undefined} style={{ width }}>
      <div className="workspace-sidebar-head">
        {spaceMenu}
        {inbox}
        <IconButton className="workspace-desktop-collapse" label={t('workspace.collapseSidebar')} onClick={() => setCollapsed(true)}><PanelLeftClose /></IconButton>
        <IconButton ref={drawerCloseRef} className="workspace-mobile-close workspace-touch" label={t('workspace.collapseSidebar')} onClick={closeDrawer}><PanelLeftClose /></IconButton>
      </div>
      <Tabs tabs={[
        { id: 'directory', label: t('workspace.directory') },
        { id: 'status', label: <span className="workspace-status-tab">{t('workspace.status')}{needsAttention && <i />}</span> },
        { id: 'schedules', label: t('workspace.schedules') },
      ]} activeId={tab} onChange={setTab} ariaLabel={t('workspace.sidebar')} className="workspace-tabs" panelClassName="workspace-tab-panel">
        {tab === 'directory' ? panels.directory : tab === 'status' ? panels.status : panels.schedules}
      </Tabs>
      {environments && <div className="workspace-environments">{environments}</div>}
      {footer(false)}
      <div className="workspace-side-resize" role="separator" data-ui-focus="row" aria-label={t('workspace.resizeSidebar')}
        aria-orientation="vertical" aria-valuemin={minWidth} aria-valuemax={maxWidth} aria-valuenow={width} tabIndex={0}
        onPointerDown={event => { if (event.button === 0) { resize.current = { x: event.clientX, width }; event.currentTarget.setPointerCapture(event.pointerId) } }}
        onPointerMove={event => { if (resize.current) setSidebarWidth(resize.current.width + event.clientX - resize.current.x) }}
        onLostPointerCapture={() => { resize.current = undefined }} onDoubleClick={() => setSidebarWidth(DEFAULT_SIDEBAR_WIDTH)}
        onKeyDown={event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); setSidebarWidth(width + (event.key === 'ArrowLeft' ? -16 : 16)) } }} />
    </aside>
    {collapsed && <aside className="workspace-rail">
      <div className="workspace-rail-top">
        <IconButton label={t('workspace.expandSidebar')} onClick={() => setCollapsed(false)}><PanelLeftOpen /></IconButton>
        {inbox}
      </div>
      {footer(true)}
    </aside>}
  </>
}
