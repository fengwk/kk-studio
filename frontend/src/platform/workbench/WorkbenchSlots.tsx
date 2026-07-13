import { Link, useLocation } from 'react-router-dom'
import { useExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import type { WorkspacePageProps, WorkbenchSlotName } from '@/platform/extensions/types'

export function NavigationSlot({ workspaceId }: WorkspacePageProps) {
  const host = useExtensionHostSnapshot()
  const location = useLocation()
  const workspacePrefix = `/workspaces/${encodeURIComponent(workspaceId)}/`

  return (
    <nav className="subnav" aria-label="AI resources">
      {host.navigation.list().map((item) => {
        const href = `${workspacePrefix}${item.path}`
        return (
          <Link key={item.id} className={location.pathname === href ? 'active' : undefined} to={href}>
            {item.label}
          </Link>
        )
      })}
    </nav>
  )
}

export function WorkbenchSlot({ slot, workspaceId }: WorkspacePageProps & { slot: WorkbenchSlotName }) {
  const host = useExtensionHostSnapshot()
  const panels = host.panels.list().filter((panel) => panel.slot === slot)
  const widgets = host.widgets.list().filter((widget) => widget.slot === slot)
  const inspectors = slot === 'inspector' ? host.inspectors.list() : []
  const statuses = slot === 'status' ? host.statuses.list() : []
  const contributions = [...panels, ...widgets, ...inspectors, ...statuses]

  return (
    <>
      {contributions.map((contribution) => {
        const Component = contribution.component
        return <Component key={contribution.id} workspaceId={workspaceId} />
      })}
    </>
  )
}

export function OverlayHost({ workspaceId }: WorkspacePageProps) {
  const host = useExtensionHostSnapshot()
  const contributions = [...host.dialogs.list(), ...host.overlays.list()]

  return (
    <>
      {contributions.map((contribution) => {
        const Component = contribution.component
        return <Component key={contribution.id} workspaceId={workspaceId} />
      })}
    </>
  )
}
