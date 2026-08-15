import { Bot, Grid2X2, Menu, Settings, UserRound, Wrench, X } from 'lucide-react'
import { useEffect, useRef, useState, type PropsWithChildren } from 'react'
import { Link, useLocation } from 'react-router'
import { isCanonicalUuid } from '@/features/canvas/uuid'
import { hasBlockingModal, isEditableKeyboardTarget } from '@/shared/ui/blocking-overlay'
import { useI18n } from '@/shared/i18n'
import { LocaleSelector } from '@/shared/i18n/LocaleSelector'

function isAiRoute(pathname: string) {
  return (
    pathname === '/'
    || pathname.startsWith('/chats')
    || pathname.startsWith('/agents')
    || pathname.startsWith('/models')
    || pathname.startsWith('/providers')
    || pathname.startsWith('/environments')
  )
}

function isToolsRoute(pathname: string) {
  return pathname.startsWith('/comfyui') || pathname.startsWith('/tools')
}

/** Chat 工作区沉浸页：仅 `/chats/:chatId`（可带尾斜杠），不含列表与更深子路径。 */
function isChatWorkspaceRoute(pathname: string) {
  return /^\/chats\/[^/]+\/?$/.test(pathname)
}

/** 焦点位于已打开的内层交互作用域（listbox/menu）时返回 true：其 Escape 语义由内层消费。 */
function isInsideOpenMenuTarget(target: EventTarget | null): boolean {
  return target instanceof Element && target.closest('[role="listbox"], [role="menu"]') != null
}

/** Canvas 编辑器沉浸页：仅合法 `/canvas/:canvasId`（canonical UUID），`/canvas` Library 保留全局顶栏。 */
function isCanvasWorkspaceRoute(pathname: string) {
  const match = /^\/canvas\/([^/]+)\/?$/.exec(pathname)
  return match != null && isCanonicalUuid(match[1] as string)
}

export function AppShell({ children }: PropsWithChildren) {
  const location = useLocation()
  const { t } = useI18n()
  const canvasMode = location.pathname.startsWith('/canvas')
  const toolsMode = isToolsRoute(location.pathname)
  const settingsMode = location.pathname.startsWith('/settings')
  const chatWorkspaceMode = isChatWorkspaceRoute(location.pathname)
  const canvasWorkspaceMode = isCanvasWorkspaceRoute(location.pathname)
  const immersive = chatWorkspaceMode || canvasWorkspaceMode
  const aiActive = !canvasMode && !toolsMode && !settingsMode && isAiRoute(location.pathname)
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
      className={`app-frame${chatWorkspaceMode ? ' chat-immersive' : ''}${canvasWorkspaceMode ? ' canvas-immersive' : ''}`}
      data-nav-open={navOpen ? 'true' : 'false'}
    >
      {!immersive ? (
        <header className="topbar">
          <div className="topbar-left">
            <Link
              to={canvasMode ? '/canvas' : toolsMode ? '/comfyui' : '/chats'}
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
              <Link
                className={aiActive ? 'active' : undefined}
                to="/chats"
                aria-label={t('platform.nav.aiAria')}
                onClick={() => setNavOpen(false)}
              >
                <Bot aria-hidden="true" />
                <span>{t('platform.nav.ai')}</span>
                <small aria-hidden="true">AI</small>
              </Link>
              <Link
                className={canvasMode ? 'active' : undefined}
                to="/canvas"
                aria-label={t('platform.nav.canvasAria')}
                onClick={() => setNavOpen(false)}
              >
                <Grid2X2 aria-hidden="true" />
                <span>{t('platform.nav.canvas')}</span>
                <small aria-hidden="true">Canvas</small>
              </Link>
              <Link
                className={toolsMode ? 'active' : undefined}
                to="/comfyui"
                aria-label={t('platform.nav.toolsAria')}
                onClick={() => setNavOpen(false)}
              >
                <Wrench aria-hidden="true" />
                <span>{t('platform.nav.tools')}</span>
                <small aria-hidden="true">Tools</small>
              </Link>
              <Link
                className={settingsMode ? 'active' : undefined}
                to="/settings"
                aria-label={t('platform.nav.settingsAria')}
                onClick={() => setNavOpen(false)}
              >
                <Settings aria-hidden="true" />
                <span>{t('platform.nav.settings')}</span>
                <small aria-hidden="true">Settings</small>
              </Link>
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
