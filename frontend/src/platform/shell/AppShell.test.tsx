import userEvent from '@testing-library/user-event'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AppShell } from '@/platform/shell/AppShell'
import { setLocale } from '@/shared/i18n'

describe('AppShell canvas immersive routes', () => {
  it('hides the global topbar and adds the immersive class for a valid /canvas/:canvasId editor route', () => {
    render(
      <MemoryRouter initialEntries={['/canvas/8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f']}>
        <AppShell>
          <div>Editor</div>
        </AppShell>
      </MemoryRouter>,
    )

    expect(screen.queryByRole('banner')).not.toBeInTheDocument()
    expect(document.querySelector('.app-frame')).toHaveClass('canvas-immersive')
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
      render(
        <MemoryRouter initialEntries={[path]}>
          <AppShell>
            <div>Canvas route</div>
          </AppShell>
        </MemoryRouter>,
      )

      expect(screen.getByRole('banner')).toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'KK Studio' })).toBeInTheDocument()
      expect(document.querySelector('.app-frame')).not.toHaveClass('canvas-immersive')
    },
  )
})

describe('AppShell settings navigation', () => {
  it('renders the Gear link in the top navigation for desktop and mobile panels', () => {
    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>Content</div>
        </AppShell>
      </MemoryRouter>,
    )
    const settingsLink = screen.getByRole('link', { name: '设置 Settings' })
    expect(settingsLink).toHaveAttribute('href', '/settings')
    expect(settingsLink.querySelector('small')?.textContent).toBe('Settings')
    // 与 AI/Canvas/Tools 同级，移动端汉堡面板复用同一组链接。
    expect(settingsLink.closest('nav')).toBe(screen.getByRole('navigation'))
  })

  it('keeps the global topbar and activates only Settings on /settings', () => {
    render(
      <MemoryRouter initialEntries={['/settings']}>
        <AppShell>
          <div>Settings content</div>
        </AppShell>
      </MemoryRouter>,
    )
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(document.querySelector('.app-frame')).not.toHaveClass('chat-immersive')
    expect(document.querySelector('.app-frame')).not.toHaveClass('canvas-immersive')
    // settings 激活时 AI/Canvas/Tools 一律不误激活。
    expect(screen.getByRole('link', { name: '设置 Settings' })).toHaveClass('active')
    expect(screen.getByRole('link', { name: '智能 AI' })).not.toHaveClass('active')
    expect(screen.getByRole('link', { name: '画布 Canvas' })).not.toHaveClass('active')
    expect(screen.getByRole('link', { name: '工具 Tools' })).not.toHaveClass('active')
  })

  it('does not mis-activate Settings on AI, Canvas or Tools routes', () => {
    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>Content</div>
        </AppShell>
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: '设置 Settings' })).not.toHaveClass('active')
    expect(screen.getByRole('link', { name: '智能 AI' })).toHaveClass('active')
  })
})

describe('AppShell nav Escape priority guards', () => {
  it('closes the nav on Escape outside modals and editable targets', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>Content</div>
        </AppShell>
      </MemoryRouter>,
    )
    const toggle = screen.getByRole('button', { name: '打开导航' })
    await user.click(toggle)
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'true')

    await user.keyboard('{Escape}')
    expect(document.querySelector('.app-frame')).toHaveAttribute('data-nav-open', 'false')
    expect(document.activeElement).toBe(toggle)
  })

  it('does not close the nav while a modal overlay owns Escape', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>Content</div>
        </AppShell>
      </MemoryRouter>,
    )
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
    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>Content</div>
        </AppShell>
      </MemoryRouter>,
    )
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
})

describe('AppShell locale selector', () => {
  it('keeps both responsive dropdowns synchronized and supports keyboard controls', async () => {
    setLocale('zh-CN')
    const user = userEvent.setup()

    render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>Content</div>
        </AppShell>
      </MemoryRouter>,
    )

    const chineseTriggers = screen.getAllByRole('button', { name: '语言: 中文' })
    expect(chineseTriggers).toHaveLength(2)
    expect(chineseTriggers.map((trigger) => trigger.querySelector('.locale-selector-current')?.textContent)).toEqual([
      '中文',
      '中文',
    ])
    expect(chineseTriggers.every((trigger) => trigger.getAttribute('aria-expanded') === 'false')).toBe(true)

    await user.click(chineseTriggers[0]!)

    expect(screen.getByRole('listbox', { name: '语言' })).toBeInTheDocument()
    expect(screen.getAllByRole('option')).toHaveLength(2)
    expect(screen.getByRole('option', { name: 'English', exact: true })).toHaveAttribute(
      'aria-selected',
      'false',
    )
    expect(chineseTriggers[0]).toHaveAttribute('aria-expanded', 'true')
    expect(chineseTriggers[1]).toHaveAttribute('aria-expanded', 'false')

    // 选中的「中文」选项在打开时聚焦；ArrowUp 移到 English，Enter 选中它。
    await user.keyboard('{ArrowUp}')
    await user.keyboard('{Enter}')

    expect(document.documentElement.lang).toBe('en-US')
    const englishTriggers = screen.getAllByRole('button', { name: 'Language: English' })
    expect(englishTriggers).toHaveLength(2)
    expect(englishTriggers.map((trigger) => trigger.querySelector('.locale-selector-current')?.textContent)).toEqual([
      'English',
      'English',
    ])
    expect(localStorage.getItem('kk-studio.locale')).toBe('en-US')
    expect(screen.getByRole('link', { name: 'AI' })).toBeInTheDocument()

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
