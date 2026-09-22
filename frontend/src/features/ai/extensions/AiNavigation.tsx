import { Link, useLocation } from 'react-router'
import { useOptionalExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import { aiExtension } from '@/features/ai/extensions/ai-extension.definition'
import { useI18n } from '@/shared/i18n'

export function AiNavigation() {
  const host = useOptionalExtensionHostSnapshot()
  const location = useLocation()
  const { t } = useI18n()

  const pages = host ? host.pages.list() : (aiExtension.pages ?? [])
  const items = pages
    .filter((page) => page.navGroup === 'ai' && page.navItem)
    .sort((a, b) => (b.navItem?.order ?? 0) - (a.navItem?.order ?? 0))

  return (
    <nav className="subnav" aria-label={t('platform.aiResources')}>
      {items.map((item) => {
        const href = `/${item.path}`
        const active =
          location.pathname === href
          || (href !== '/' && location.pathname.startsWith(`${href}/`))
        return (
          <Link key={item.id} className={active ? 'active' : undefined} to={href}>
            {item.navItem?.labelKey ? t(item.navItem.labelKey) : item.navItem?.label}
          </Link>
        )
      })}
    </nav>
  )
}
