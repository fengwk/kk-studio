import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import App from '@/app/App'
import { AppProviders } from '@/app/providers'
import { CanvasPage } from '@/features/canvas/CanvasPage'
import { canvasExtension } from '@/features/canvas/extensions/canvas-extension'
import { AppShell } from '@/platform/shell/AppShell'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/studio-service', () => ({
  listCanvases: vi.fn(async () => ([
    {
      id: '1001',
      title: '竞品研究与产品方案',
      revision: '0',
      homeViewportJson: '{}',
    },
  ])),
  createCanvas: vi.fn(async (title: string) => ({
    id: '1002',
    title: title || '未命名画布',
    revision: '0',
    homeViewportJson: '{}',
  })),
}))

vi.mock('@xyflow/react', () => {
  return {
    ReactFlowProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
    ReactFlow: ({ children, nodeTypes }: { children: ReactNode; nodeTypes?: Record<string, unknown> }) => {
      // Keep a handle so tests can assert nodeTypes identity stays stable across rerenders.
      ;(globalThis as { __canvasNodeTypes?: unknown }).__canvasNodeTypes = nodeTypes
      return <div data-testid="react-flow">{children}</div>
    },
    Background: () => <div data-testid="rf-background" />,
    BackgroundVariant: { Dots: 'dots' },
    MiniMap: () => <div data-testid="rf-minimap" />,
    SelectionMode: { Partial: 'partial' },
    useReactFlow: () => ({
      fitView: vi.fn(async () => undefined),
      setViewport: vi.fn(async () => undefined),
      getViewport: () => ({ x: 80, y: 20, zoom: 0.6 }),
      zoomTo: vi.fn(async () => undefined),
    }),
  }
})

describe('Canvas feature vertical slice', () => {
  it('registers builtin.canvas page contribution without navigation pollution', () => {
    // Canvas must not publish into AI NavigationSlot via extension.navigation.
    expect(canvasExtension.id).toBe('builtin.canvas')
    expect(canvasExtension.pages?.[0]?.path).toBe('canvas')
    expect(canvasExtension.navigation).toBeUndefined()
  })

  it('shows create card and real canvas cards, then enters the editor', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: /把想法、资料和结果/ })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /从模板快速开始/ })).not.toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '创建新画布' })).toBeInTheDocument()
    const card = await screen.findByRole('button', { name: /竞品研究/ })
    expect(card).toBeInTheDocument()

    await user.click(card)
    expect(screen.getByLabelText(/无限画布/)).toBeInTheDocument()
    expect(screen.getByText('已保存')).toBeInTheDocument()
  })

  it('switches library and editor UI live between Chinese and English', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )

    expect(await screen.findByRole('heading', { name: /把想法、资料和结果/ })).toBeInTheDocument()
    await act(async () => {
      setLocale('en-US')
    })
    expect(await screen.findByRole('heading', { name: /Put ideas, references, and results/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create a new canvas' })).toBeInTheDocument()

    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))
    expect(await screen.findByLabelText(/Infinite canvas/)).toBeInTheDocument()
    expect(screen.getByText('Saved')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Share' })).toBeInTheDocument()

    await act(async () => {
      setLocale('zh-CN')
    })
    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()
    expect(screen.getByText('已保存')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '分享' })).toBeInTheDocument()
  })

  it('switches an interactive Agent run control and status live', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )
    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))

    await act(async () => {
      setLocale('en-US')
    })
    const prompt = screen.getByLabelText('Describe a task for Agent')
    await user.type(prompt, 'Continue organizing the matrix')
    await user.click(screen.getByRole('button', { name: 'Send to Agent' }))
    expect((await screen.findAllByRole('button', { name: 'Pause' })).length).toBeGreaterThan(0)
    expect(screen.getByText(/Running ·/)).toBeInTheDocument()

    await act(async () => {
      setLocale('zh-CN')
    })
    expect((await screen.findAllByRole('button', { name: '暂停' })).length).toBeGreaterThan(0)
    expect(screen.getByText(/运行中 ·/)).toBeInTheDocument()
  })

  it('supports add menu keyboard navigation and fixed generator creation', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )
    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))

    const addButton = screen.getByRole('button', { name: '添加内容' })
    await user.click(addButton)
    const menu = screen.getByRole('menu', { name: '添加内容' })
    expect(within(menu).getAllByRole('menuitem')).toHaveLength(5)

    await user.keyboard('{ArrowDown}{ArrowDown}{Home}{End}{Escape}')
    expect(menu).not.toBeVisible()

    await user.click(addButton)
    await user.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: /图片生成/ }))
    expect(screen.getByRole('status')).toHaveTextContent('已创建图片生成节点')
    expect(screen.getByLabelText(/提交图片生成/)).toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
  })

  it('runs agent controls from dock and resets the demo', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )
    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))

    const prompt = screen.getByLabelText('向 Agent 描述任务')
    await user.type(prompt, '继续整理矩阵')
    await user.click(screen.getByRole('button', { name: '发送给 Agent' }))
    const thread = screen.getByLabelText('Agent 消息与运行状态')
    expect(thread).toBeVisible()
    expect(within(thread).getByText('继续整理矩阵')).toBeInTheDocument()

    const pause = await screen.findAllByRole('button', { name: '暂停' })
    await user.click(pause[0])
    expect((await screen.findAllByRole('button', { name: '继续' })).length).toBeGreaterThan(0)

    await user.click(screen.getByRole('button', { name: '重置' }))
    expect(screen.getByRole('status')).toHaveTextContent('演示和 Agent 消息已重置')
  })

  it('closes modal help and focuses the Agent Dock on Ctrl-K', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )
    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))
    await user.click(screen.getByRole('button', { name: '查看快捷操作' }))
    await waitFor(() => expect(document.getElementById('helpDialog')).toHaveAttribute('open'))

    await user.keyboard('{Control>}k{/Control}')
    await waitFor(() => {
      expect(document.getElementById('helpDialog')).not.toHaveAttribute('open')
      expect(screen.getByLabelText('向 Agent 描述任务')).toHaveFocus()
    })
  })

  it('uses a unified AppShell visual language on both AI and Canvas routes', () => {
    const ai = render(
      <MemoryRouter initialEntries={['/chats']}>
        <AppShell>
          <div>ai</div>
        </AppShell>
      </MemoryRouter>,
    )
    expect(ai.container.querySelector('.app-frame')).toBeTruthy()
    expect(ai.container.querySelector('.app-frame-ai')).toBeNull()
    expect(ai.container.querySelector('.app-frame-canvas')).toBeNull()
    expect(ai.container.querySelector('.brand-mark img')).toBeTruthy()
    expect(ai.getByRole('link', { name: '智能 AI' })).toHaveClass('active')
    expect(ai.getByRole('link', { name: '画布 Canvas' })).not.toHaveClass('active')
    expect(ai.getByRole('link', { name: '工具 Tools' })).not.toHaveClass('active')
    expect(ai.container.querySelector('.avatar svg')).toBeTruthy()
    ai.unmount()

    const canvas = render(
      <MemoryRouter initialEntries={['/canvas']}>
        <AppShell>
          <div>canvas</div>
        </AppShell>
      </MemoryRouter>,
    )
    expect(canvas.container.querySelector('.app-frame')).toBeTruthy()
    expect(canvas.container.querySelector('.app-frame-canvas')).toBeNull()
    expect(canvas.container.querySelector('.brand-mark img')).toBeTruthy()
    expect(canvas.getByRole('link', { name: '画布 Canvas' })).toHaveClass('active')
    expect(canvas.getByRole('link', { name: '智能 AI' })).not.toHaveClass('active')
    expect(canvas.container.querySelector('.avatar svg')).toBeTruthy()
  })

  it('renders /canvas through the application host and highlights the shell nav', async () => {
    window.history.replaceState({}, '', '/canvas')
    render(
      <AppProviders>
        <App />
      </AppProviders>,
    )
    expect(await screen.findByRole('heading', { name: /把想法、资料和结果/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '画布 Canvas' })).toHaveClass('active')
    expect(screen.getByRole('link', { name: '智能 AI' })).not.toHaveClass('active')
  })

  it('returns to Canvas Library when brand is clicked while editor is open on /canvas', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/canvas']}>
        <AppShell>
          <CanvasPage />
        </AppShell>
      </MemoryRouter>,
    )
    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))
    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()

    // Same-route SPA brand navigation must not keep the user stuck in editor.
    await user.click(screen.getByRole('link', { name: 'KK Studio' }))
    expect(await screen.findByRole('heading', { name: /把想法、资料和结果/ })).toBeInTheDocument()
  })
})
