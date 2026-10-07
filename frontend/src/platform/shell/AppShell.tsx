import { Menu, UserRound, X } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, matchRoutes, useLocation, type Location } from 'react-router'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useOptionalExtensionHostSnapshot } from '@/platform/extensions/ExtensionHostContext'
import type { PageContribution } from '@/platform/extensions/types'
import type { AppShellProps, PrimaryNavItem } from '@/platform/shell/types'
import { interactionService } from '@/shared/api/interaction-service'
import { useApplicationEvents } from '@/shared/app-events'
import { hasBlockingModal, isEditableKeyboardTarget } from '@/shared/ui/blocking-overlay'
import { useI18n } from '@/shared/i18n'
import { LocaleSelector } from '@/shared/i18n/LocaleSelector'
import { queryKeys } from '@/shared/lib/query-keys'

const PENDING_INTERACTIONS_LIMIT = 50

/**
 * 全局待处理角标：读服务端给出的真实 total，绝不用首页长度或本地累加假装全局计数。
 *
 * 待处理事实变化只由服务端事件提示（含真实执行根），因此这里没有轮询：changed 只回读角标查询，
 * resync/subscribed（首订与每次重连重订阅）同样回读，关闭断线期间的状态窗口。
 */
function usePendingInteractionsCount(): number {
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const { data } = useQuery({
    queryKey: queryKeys.interactions.list(null, null, PENDING_INTERACTIONS_LIMIT),
    queryFn: () => interactionService.listInteractions(null, null, PENDING_INTERACTIONS_LIMIT),
    staleTime: 5000,
  })
  useEffect(() => {
    const invalidate = () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all })
    }
    return applicationEvents.subscribe(
      { kind: 'interactions' },
      {
        onSubscribed: invalidate,
        onEvent: (name) => {
          if (name === 'changed') {
            invalidate()
          }
        },
        onResync: invalidate,
      },
    )
  }, [applicationEvents, queryClient])
  return data?.total ?? 0
}

/** 焦点位于已打开的内层交互作用域（listbox/menu）时返回 true：其 Escape 语义由内层消费。 */
function isInsideOpenMenuTarget(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('[role="listbox"], [role="menu"]') != null
}

interface ResolvedLayout {
  activeGroupId: string
  immersive: boolean
}

function resolveRouteLayout(
  pages: readonly PageContribution[],
  navItems: readonly PrimaryNavItem[],
  location: Location,
): ResolvedLayout {
  if (location.pathname === '/' || location.pathname === '') {
    return { activeGroupId: navItems[0]?.groupId ?? 'ai', immersive: false }
  }

  if (pages.length > 0) {
    const routes = pages.map((page) => ({
      path: page.path.startsWith('/') ? page.path : `/${page.path}`,
      page,
    }))

    const matches = matchRoutes(routes, location)
    const matchedRoute = matches?.[0]

    if (matchedRoute) {
      const page = matchedRoute.route.page
      const params = matchedRoute.params
      const activeGroupId = page.navGroup ?? ''

      const isWorkspace =
        typeof page.workspace === 'function'
          ? page.workspace(params)
          : Boolean(page.workspace)

      // 所有工作区共用同一沉浸壳：隐藏全局顶栏并让 stage 占满可见区。
      // 各业务（对话/画布）的专属几何由各自 feature 局部样式表达，不在这里分叉皮肤。
      return { activeGroupId, immersive: isWorkspace }
    }
  }

  // 路由未匹配（或缺少 pages 时的回退）：按 navItems 路径前缀回退识别分组，不开启沉浸
  for (const item of navItems) {
    if (
      location.pathname === item.to
      || (item.to !== '/' && location.pathname.startsWith(`${item.to}/`))
    ) {
      return { activeGroupId: item.groupId, immersive: false }
    }
  }

  return { activeGroupId: '', immersive: false }
}

export function AppShell({
  children,
  pages: explicitPages,
  navItems,
}: AppShellProps) {
  const location = useLocation()
  const host = useOptionalExtensionHostSnapshot()
  const { t } = useI18n()
  const pendingInteractionsCount = usePendingInteractionsCount()

  const { activeGroupId, immersive } = useMemo(() => {
    const pages = explicitPages ?? host?.pages.list() ?? []
    return resolveRouteLayout(pages, navItems, location)
  }, [explicitPages, host, navItems, location])

  const activeNavItem = navItems.find((item) => item.groupId === activeGroupId)
  const homePath = activeNavItem?.to ?? navItems[0]?.to ?? '/chats'

  const [navOpen, setNavOpen] = useState(false)
  const navToggleRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    setNavOpen(false)
  }, [location.pathname])

  useEffect(() => {
    if (!navOpen) {
      return
    }
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) {
        return
      }
      // 优先级守卫：Modal/Lightbox/alertdialog 拥有自己的 Escape 语义，
      // 输入控件中的 Escape 交给输入自身；导航都不抢。
      if (hasBlockingModal()) {
        return
      }
      if (isEditableKeyboardTarget(event.target)) {
        return
      }
      // 焦点位于已打开的内层 listbox/menu（如 LocaleSelector）时，第一次
      // Escape 只由内层交互消费（关闭自身并保留导航），第二次才关闭导航。
      if (isInsideOpenMenuTarget(event.target)) {
        return
      }
      // 关闭时消费事件（至少 preventDefault），确保低优先级的 ThreadComposer
      // window handler 依赖 defaultPrevented 让路，不会随后把焦点抢走。
      event.preventDefault()
      setNavOpen(false)
      navToggleRef.current?.focus()
    }
    document.addEventListener('keydown', closeOnEscape)
    return () => document.removeEventListener('keydown', closeOnEscape)
  }, [navOpen])

  return (
    <div
      className={`app-frame${immersive ? ' workspace-immersive' : ''}`}
      data-nav-open={navOpen ? 'true' : 'false'}
    >
      {!immersive ? (
        <header className="topbar">
          <div className="topbar-left">
            <Link
              to={homePath}
              className="brand"
              aria-label="KK Studio"
              onClick={() => setNavOpen(false)}
            >
              <span className="brand-mark" aria-hidden="true">
                <img src="/favicon.svg?v=11" alt="" width={23} height={23} draggable={false} />
              </span>
              <span>KK Studio</span>
            </Link>
          </div>
          <button
            ref={navToggleRef}
            type="button"
            className="nav-toggle"
            aria-label={navOpen ? t('platform.nav.close') : t('platform.nav.open')}
            aria-expanded={navOpen}
            aria-controls="primary-navigation"
            onClick={() => setNavOpen((open) => !open)}
          >
            {navOpen ? <X aria-hidden="true" /> : <Menu aria-hidden="true" />}
          </button>
          <div className="topbar-center">
            <nav id="primary-navigation" className="topnav" aria-label={t('platform.primaryNavigation')}>
              {navItems.map((item) => {
                const Icon = item.icon
                const active = item.groupId === activeGroupId
                return (
                  <Link
                    key={item.id}
                    className={active ? 'active' : undefined}
                    to={item.to}
                    aria-label={t(item.ariaKey)}
                    onClick={() => setNavOpen(false)}
                  >
                    <Icon aria-hidden="true" />
                    <span>{t(item.labelKey)}</span>
                    <small aria-hidden="true">{item.shortLabel}</small>
                    {item.id === 'interactions' && pendingInteractionsCount > 0 ? (
                      <span className="nav-pending-badge" aria-label={`${pendingInteractionsCount} pending`}>
                        {pendingInteractionsCount}
                      </span>
                    ) : null}
                  </Link>
                )
              })}
              <LocaleSelector />
              {/* 移动端收起后并入汉堡面板；桌面由 .topbar-right 展示 */}
              <div
                className="topnav-user"
                title={t('platform.workspace')}
                aria-label={t('platform.workspace')}
              >
                <div className="avatar" aria-hidden="true">
                  <UserRound aria-hidden="true" />
                </div>
                <span>{t('platform.workspace')}</span>
              </div>
            </nav>
          </div>
          <div className="topbar-right">
            <LocaleSelector />
            <div
              className="avatar"
              title={t('platform.workspace')}
              aria-label={t('platform.workspace')}
            >
              <UserRound aria-hidden="true" />
            </div>
          </div>
        </header>
      ) : null}
      <main className="stage">{children}</main>
    </div>
  )
}
