import { Link, useLocation } from 'react-router'
import { useExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import type { WorkbenchSlotName } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

export function NavigationSlot() {
  const host = useExtensionHostSnapshot()
  const location = useLocation()
  const { t } = useI18n()

  return (
    <nav className="subnav" aria-label={t('platform.aiResources')}>
      {host.navigation.list().map((item) => {
        const href = `/${item.path}`
        const active =
          location.pathname === href
          || (href !== '/' && location.pathname.startsWith(`${href}/`))
        return (
          <Link key={item.id} className={active ? 'active' : undefined} to={href}>
            {item.labelKey ? t(item.labelKey) : item.label}
          </Link>
        )
      })}
    </nav>
  )
}

export function WorkbenchSlot({ slot }: { slot: WorkbenchSlotName }) {
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
        return <Component key={contribution.id} />
      })}
    </>
  )
}

export function OverlayHost() {
  const host = useExtensionHostSnapshot()
  const contributions = [...host.dialogs.list(), ...host.overlays.list()]

  return (
    <>
      {contributions.map((contribution) => {
        const Component = contribution.component
        return <Component key={contribution.id} />
      })}
    </>
  )
}
