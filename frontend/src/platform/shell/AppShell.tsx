import { Bot, Grid2X2, Menu, UserRound, Wrench, X } from 'lucide-react'
import { useEffect, useRef, useState, type PropsWithChildren } from 'react'
import { Link, useLocation } from 'react-router'
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
    || pathname.startsWith('/settings')
  )
}

function isToolsRoute(pathname: string) {
  return pathname.startsWith('/comfyui') || pathname.startsWith('/tools')
}

/** Chat 工作区沉浸页：`/chats/:chatId`，不含列表 `/chats`。 */
function isChatWorkspaceRoute(pathname: string) {
  return /^\/chats\/[^/]+/.test(pathname)
}

/** Canvas 编辑器沉浸页：仅合法 `/canvas/:canvasId`（正整数），`/canvas` Library 保留全局顶栏。 */
function isCanvasWorkspaceRoute(pathname: string) {
  return /^\/canvas\/[1-9][0-9]*\/?$/.test(pathname)
}

export function AppShell({ children }: PropsWithChildren) {
  const location = useLocation()
  const { t } = useI18n()
  const canvasMode = location.pathname.startsWith('/canvas')
  const toolsMode = isToolsRoute(location.pathname)
  const chatWorkspaceMode = isChatWorkspaceRoute(location.pathname)
  const canvasWorkspaceMode = isCanvasWorkspaceRoute(location.pathname)
  const immersive = chatWorkspaceMode || canvasWorkspaceMode
  const aiActive = !canvasMode && !toolsMode && isAiRoute(location.pathname)
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
      if (event.key === 'Escape') {
        setNavOpen(false)
        navToggleRef.current?.focus()
      }
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
