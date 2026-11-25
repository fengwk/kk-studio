import userEvent from '@testing-library/user-event'
import { act, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Bot, Grid2X2, Inbox, Settings } from 'lucide-react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { applicationContributions } from '@/app/application-contributions'
import { AppShell } from '@/platform/shell/AppShell'
import type { PageContribution } from '@/platform/extensions/types'
import type { PrimaryNavItem } from '@/platform/shell/types'
import { ApplicationContributionsProvider } from '@/platform/extensions/ApplicationContributionsContext'
import { ThreadComposer } from '@/features/thread/runtime/thread-panel/ThreadComposer'
import { interactionService } from '@/shared/api/interaction-service'
import { ApplicationEventProvider } from '@/shared/app-events'
import { FakeWebSocketHarness } from '@/test-support/fake-websocket'
import { setLocale } from '@/shared/i18n'

const FIXTURE_NAV_ITEMS: readonly PrimaryNavItem[] = [
  {
    id: 'ai',
    groupId: 'ai',
    to: '/chats',
    labelKey: 'platform.nav.ai',
    ariaKey: 'platform.nav.aiAria',
    icon: Bot,
  },
  {
    id: 'interactions',
    groupId: 'interactions',
    to: '/interactions',
    labelKey: 'ai.nav.interactions',
    ariaKey: 'ai.nav.interactionsAria',
    icon: Inbox,
  },
  {
    id: 'canvas',
    groupId: 'canvas',
    to: '/canvas',
    labelKey: 'platform.nav.canvas',
    ariaKey: 'platform.nav.canvasAria',
    icon: Grid2X2,
  },
  {
    id: 'settings',
    groupId: 'settings',
    to: '/settings',
    labelKey: 'platform.nav.settings',
    ariaKey: 'platform.nav.settingsAria',
    icon: Settings,
  },
]

function renderShell(
  initialEntry: string,
  children: ReactNode = <div>Content</div>,
  options?: {
    navItems?: readonly PrimaryNavItem[]
    queryClient?: QueryClient
    pages?: readonly PageContribution[]
  },
) {
  const queryClient = options?.queryClient ?? new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const navItems = options?.navItems ?? FIXTURE_NAV_ITEMS
  const sockets = new FakeWebSocketHarness()
  const view = render(
    <QueryClientProvider client={queryClient}>
      <ApplicationContributionsProvider contributions={applicationContributions}>
        <ApplicationEventProvider url="ws://test/ws/events" socketFactory={sockets.factory}>
          <MemoryRouter initialEntries={[initialEntry]}>
            <AppShell navItems={navItems} pages={options?.pages}>
              {children}
            </AppShell>
          </MemoryRouter>
        </ApplicationEventProvider>
      </ApplicationContributionsProvider>
    </QueryClientProvider>,
  )
  return { ...view, sockets }
}

describe('AppShell chat immersive routes', () => {
  it.each([
    '/chats/chat-1',
    '/chats/chat-1/',
  ])('hides the global topbar and adds the immersive class for %s', (path) => {
    renderShell(path, <div>Chat workspace</div>)

    expect(screen.queryByRole('banner')).not.toBeInTheDocument()
    expect(document.querySelector('.app-frame')).toHaveClass('workspace-immersive')
    expect(screen.getByText('Chat workspace')).toBeInTheDocument()
  })

  it.each([
    '/chats',
    '/chats/',
    '/chats/chat-1/extra',
    '/chats/chat-1/7',
  ])('keeps the global topbar outside a valid chat workspace route: %s', (path) => {
    renderShell(path, <div>Chat route</div>)

    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'KK Studio' })).toBeInTheDocument()
    expect(document.querySelector('.app-frame')).not.toHaveClass('workspace-immersive')
  })
})

describe('AppShell canvas immersive routes', () => {
  it('hides the global topbar and adds the immersive class for a valid /canvas/:canvasId editor route', () => {
    renderShell('/canvas/8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f', <div>Editor</div>)

    expect(screen.queryByRole('banner')).not.toBeInTheDocument()
    expect(document.querySelector('.app-frame')).toHaveClass('workspace-immersive')
    expect(screen.getByText('Editor')).toBeInTheDocument()
  })

  it.each([
    '/canvas',
    '/canvas/7',
    '/canvas/not-a-uuid',
    '/canvas/8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f/extra',
    '/canvas/8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f/7',
  ])(
    'keeps the global topbar outside a valid editor route: %s',
    (path) => {
      renderShell(path, <div>Canvas route</div>)

      expect(screen.getByRole('banner')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'KK Studio' })).toBeInTheDocument()
      expect(document.querySelector('.app-frame')).not.toHaveClass('workspace-immersive')
    },
  )
})

describe('AppShell settings navigation', () => {
  it('renders the settings link with only the current-language label for desktop and mobile panels', () => {
    renderShell('/chats', <div>Content</div>)
    const settingsLink = screen.getByRole('link', { name: '设置' })
    expect(settingsLink).toHaveAttribute('href', '/settings')
    // 主导航只渲染当前语言的标签，不再附带英文副标。
    expect(settingsLink).toHaveTextContent(/^设置$/)
    // 与 AI/Canvas/Tools 同级，移动端汉堡面板复用同一组链接。
    expect(settingsLink.closest('nav')).toBe(screen.getByRole('navigation'))
  })

  it('keeps the global topbar and activates only Settings on /settings', () => {
    renderShell('/settings', <div>Settings content</div>)
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(document.querySelector('.app-frame')).not.toHaveClass('workspace-immersive')
    // settings 激活时 AI/Canvas 一律不误激活。
    expect(screen.getByRole('link', { name: '设置' })).toHaveClass('active')
    expect(screen.getByRole('link', { name: '智能' })).not.toHaveClass('active')
    expect(screen.getByRole('link', { name: '画布' })).not.toHaveClass('active')
    expect(screen.getByRole('link', { name: '待处理事项' })).not.toHaveClass('active')
  })

  it('does not mis-activate Settings on the AI route', () => {
    renderShell('/chats', <div>Content</div>)
    expect(screen.getByRole('link', { name: '设置' })).not.toHaveClass('active')
    expect(screen.getByRole('link', { name: '智能' })).toHaveClass('active')
  })
})

describe('AppShell nav Escape priority guards', () => {
  it('closes the nav on Escape outside modals and editable targets', async () => {
    const user = userEvent.setup()
    renderShell('/chats', <div>Content</div>)
    const toggle = screen.getByRole('button', { name: '打开导航' })
    await user.click(toggle)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

    await user.keyboard('{Escape}')
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'false')
    expect(document.activeElement).toBe(toggle)
  })

  it('does not close the nav while a modal overlay owns Escape', async () => {
    const user = userEvent.setup()
    renderShell('/chats', <div>Content</div>)
    const toggle = screen.getByRole('button', { name: '打开导航' })
    await user.click(toggle)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

    const modalAction = document.createElement('button')
    modalAction.type = 'button'
    modalAction.textContent = 'Modal action'
    const backdrop = document.createElement('div')
    backdrop.className = 'modal-backdrop'
    backdrop.append(modalAction)
    document.body.append(backdrop)
    modalAction.focus()
    try {
      await user.keyboard('{Escape}')
      // Modal 拥有优先 Escape 语义，导航保持打开。
      expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')
    } finally {
      backdrop.remove()
    }
  })

  it('does not close the nav while typing in an editable target', async () => {
    const user = userEvent.setup()
    renderShell('/chats', <div>Content</div>)
    const toggle = screen.getByRole('button', { name: '打开导航' })
    await user.click(toggle)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

    const input = document.createElement('input')
    document.body.append(input)
    input.focus()
    try {
      await user.keyboard('{Escape}')
      expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')
    } finally {
      input.remove()
    }
  })

  it('keeps the nav open when a higher-priority handler already consumed Escape', async () => {
    const user = userEvent.setup()
    renderShell('/chats', <div>Content</div>)
    const consume = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
      }
    }
    document.addEventListener('keydown', consume)
    try {
      const toggle = screen.getByRole('button', { name: '打开导航' })
      await user.click(toggle)
      expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

      await user.keyboard('{Escape}')
      expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')
    } finally {
      document.removeEventListener('keydown', consume)
    }
  })

  it('consumes Escape on close so a mounted ThreadComposer cannot steal focus afterwards', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()
    renderShell(
      '/chats',
      <ThreadComposer
        parts={[]}
        pending={false}
        disabled={false}
        onPartsChange={() => undefined}
        onSubmit={() => undefined}
        onCommand={() => undefined}
        focusOnEscape
      />,
    )
    const toggle = screen.getByRole('button', { name: '打开导航' })
    await user.click(toggle)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

    await user.keyboard('{Escape}')
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'false')
    expect(document.activeElement).toBe(toggle)
    await new Promise((resolve) => setTimeout(resolve, 60))
    expect(document.activeElement).toBe(toggle)
  })

  it('closes only the inner listbox on the first Escape and keeps the nav; a second Escape closes the nav', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()
    renderShell('/chats', <div>Content</div>)
    const toggle = screen.getByRole('button', { name: '打开导航' })
    await user.click(toggle)
    const trigger = screen.getAllByRole('button', { name: '语言: 中文' })[0]!
    await user.click(trigger)
    expect(screen.getByRole('listbox', { name: '语言' })).toBeInTheDocument()

    // 第一次 Escape：焦点在内层 listbox，只由 LocaleSelector 消费并保留导航。
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox', { name: '语言' })).not.toBeInTheDocument()
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

    // 第二次 Escape：内层已关闭，导航关闭并把焦点还给 toggle。
    await user.keyboard('{Escape}')
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'false')
    expect(document.activeElement).toBe(toggle)
  })
})

describe('AppShell locale selector', () => {
  it('keeps both responsive dropdowns synchronized and supports keyboard controls', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()

    renderShell('/chats', <div>Content</div>)

    const chineseTriggers = screen.getAllByRole('button', { name: '语言: 中文' })
    expect(chineseTriggers).toHaveLength(2)
    expect(chineseTriggers.map((trigger) => trigger.querySelector('.ui-select-value')?.textContent)).toEqual([
      '中文',
      '中文',
    ])
    expect(chineseTriggers.every((trigger) => trigger.getAttribute('aria-expanded') === 'false')).toBe(true)

    await user.click(chineseTriggers[0]!)

    expect(screen.getByRole('listbox', { name: '语言' })).toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(2)
    expect(screen.getByRole('option', { name: 'English' })).toHaveAttribute(
      'aria-selected',
      'false',
    )
    expect(chineseTriggers[0]).toHaveAttribute('aria-expanded', 'true')
    expect(chineseTriggers[1]).toHaveAttribute('aria-expanded', 'false')

    await user.keyboard('{ArrowUp}')
    await user.keyboard('{Enter}')

    expect(document.documentElement.lang).toBe('en-US')
    const englishTriggers = screen.getAllByRole('button', { name: 'Language: English' })
    expect(englishTriggers).toHaveLength(2)
    expect(englishTriggers.map((trigger) => trigger.querySelector('.ui-select-value')?.textContent)).toEqual([
      'English',
      'English',
    ])
    expect(localStorage.getItem('kk-studio.locale')).toBe('en-US')
    expect(screen.getByRole('link', { name: 'AI' })).toBeInTheDocument()
    // en-US 同样只渲染当前语言标签，不再叠加中文副标。
    expect(screen.getByRole('link', { name: 'Settings' })).toHaveTextContent(/^Settings$/)

    await user.click(englishTriggers[0]!)
    expect(screen.getByRole('listbox', { name: 'Language' })).toBeInTheDocument()
    await user.click(screen.getByText('Content'))
    expect(screen.queryByRole('listbox', { name: 'Language' })).not.toBeInTheDocument()

    await user.click(englishTriggers[1]!)
    expect(screen.getByRole('listbox', { name: 'Language' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('listbox', { name: 'Language' })).not.toBeInTheDocument()
    expect(document.activeElement).toBe(englishTriggers[1])
  })
})

describe('AppShell interactions pending badge and navigation behaviors', () => {
  // 每个用例前还原 spy：spyOn 会复用同一 mock 实例，若不还原，上一个用例的调用次数与一次性返回值
  // 会污染下一个用例的精确调用次数断言（这里不放松断言，只隔离用例状态）。
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the server-provided true pending total instead of the page length', async () => {
    // 测试意图：全局角标必须读服务端给出的真实待处理总数，不能用首页长度假装全局计数。
    vi.spyOn(interactionService, 'listInteractions').mockResolvedValueOnce({
      items: [
        {
          type: 'INPUT',
          toolInvocationId: 'int-1',
          threadId: 'th-1',
          rootThreadId: 'th-1',
          sessionId: 'sess-1',
          owner: { kind: 'CHAT', id: 'c-1', title: null, link: '/chats/c-1' },
          toolCallId: 'call-1',
          toolName: 'ask_user',
          argumentsJson: '{}',
          createdAt: 1767225600000,
        },
      ],
      nextCursor: null,
      total: 7,
      freshnessAt: null,
    })

    renderShell('/chats')
    await waitFor(() => {
      const badge = screen.getByLabelText('7 项待处理')
      expect(badge).toBeInTheDocument()
      expect(badge).toHaveTextContent('7')
    })
  })

  it('refetches the pending badge on a committed pending change event', async () => {
    // 测试意图：pending 变化只由服务端事件提示回读，因此角标不需要轮询也能及时更新。
    const listSpy = vi
      .spyOn(interactionService, 'listInteractions')
      .mockResolvedValueOnce({ items: [], nextCursor: null, total: 0, freshnessAt: null })
      .mockResolvedValueOnce({ items: [], nextCursor: null, total: 3, freshnessAt: null })

    const { sockets } = renderShell('/chats')
    await waitFor(() => expect(listSpy).toHaveBeenCalledTimes(1))
    expect(screen.queryByLabelText('3 项待处理')).not.toBeInTheDocument()
    const socket = sockets.openLatest()

    act(() => {
      socket.emitServer({
        type: 'event',
        resource: { kind: 'interactions' },
        name: 'changed',
        data: { rootThreadId: '11111111-2222-4333-8444-555555555555' },
      })
    })

    await waitFor(() => {
      expect(screen.getByLabelText('3 项待处理')).toBeInTheDocument()
    })
    expect(listSpy).toHaveBeenCalledTimes(2)
  })

  it('closes navigation drawer on brand link click and nav item click', async () => {
    const user = userEvent.setup()
    renderShell('/chats')

    const toggle = screen.getByRole('button', { name: '打开导航' })
    await user.click(toggle)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

    const brand = screen.getByRole('link', { name: 'KK Studio' })
    await user.click(brand)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'false')

    await user.click(toggle)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

    const aiLink = screen.getByRole('link', { name: '智能' })
    await user.click(aiLink)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'false')
  })

  it('handles root path / and unmatched routes gracefully', () => {
    renderShell('/')
    expect(screen.getByRole('link', { name: '智能' })).toHaveClass('active')

    // Empty pages fallback route matching
    renderShell('/unknown-nonexistent-path', <div>404</div>, { pages: [] })
    expect(screen.getByText('404')).toBeInTheDocument()
  })
})
