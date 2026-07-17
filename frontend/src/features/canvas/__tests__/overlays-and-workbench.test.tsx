import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect, useRef } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { CanvasGenerationWorkbench } from '@/features/canvas/CanvasGenerationWorkbench'
import { CanvasOverlays } from '@/features/canvas/CanvasOverlays'
import { CanvasPage } from '@/features/canvas/CanvasPage'
import { CanvasRuntimeProvider, useCanvasRuntime } from '@/features/canvas/CanvasRuntimeContext'
import { GENERATION_PROFILES } from '@/features/canvas/data'
import { PlusIcon, SendIcon } from '@/features/canvas/icons'

vi.mock('@xyflow/react', async () => {
  const React = await import('react')
  return {
    ReactFlowProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
    ReactFlow: ({ children }: { children: React.ReactNode }) => <div data-testid="react-flow">{children}</div>,
    Background: () => null,
    BackgroundVariant: { Dots: 'dots' },
    MiniMap: () => null,
    SelectionMode: { Partial: 'partial' },
    useReactFlow: () => ({
      fitView: vi.fn(async () => undefined),
      setViewport: vi.fn(async () => undefined),
      getViewport: () => ({ x: 80, y: 20, zoom: 0.6 }),
      zoomTo: vi.fn(async () => undefined),
    }),
  }
})

function WorkbenchHarness() {
  const runtime = useCanvasRuntime()
  const booted = useRef(false)
  useEffect(() => {
    if (booted.current) {
      return
    }
    booted.current = true
    runtime.openEditor()
    runtime.createGenerator('text')
  }, [runtime])
  return (
    <>
      <CanvasGenerationWorkbench />
      <CanvasOverlays />
    </>
  )
}

function OverlaysHarness() {
  const runtime = useCanvasRuntime()
  const booted = useRef(false)
  useEffect(() => {
    if (booted.current) {
      return
    }
    booted.current = true
    runtime.setToast('hello')
    runtime.setResearchOpen(true)
    // Open help after research so dialog ref is mounted.
    queueMicrotask(() => runtime.setHelpOpen(true))
  }, [runtime])
  return <CanvasOverlays />
}

function LibraryHarness() {
  return (
    <MemoryRouter>
      <CanvasPage />
    </MemoryRouter>
  )
}

function OverlayFocusHarness() {
  const runtime = useCanvasRuntime()
  return (
    <>
      <button type="button" onClick={(event) => runtime.setResearchOpen(true, event.currentTarget)}>
        Open research
      </button>
      <button type="button" onClick={(event) => runtime.setHelpOpen(true, event.currentTarget)}>
        Open help
      </button>
      <CanvasOverlays />
    </>
  )
}

describe('canvas overlays and workbench', () => {
  it('renders research tabs, modal help dialog and toast through runtime', async () => {
    const user = userEvent.setup()
    const showModal = vi.spyOn(HTMLDialogElement.prototype, 'showModal')
    render(
      <CanvasRuntimeProvider>
        <OverlaysHarness />
      </CanvasRuntimeProvider>,
    )
    expect(await screen.findByRole('status')).toHaveTextContent('hello')
    await user.click(screen.getByRole('button', { name: '方案基座' }))
    await user.click(screen.getByRole('button', { name: '分期计划' }))
    await user.click(screen.getByRole('button', { name: '关闭说明' }))
    // Help is opened in harness; closing must use dialog close button.
    const closeHelp = screen.getByRole('button', { name: '关闭快捷操作' })
    expect(showModal).toHaveBeenCalled()
    await user.click(closeHelp)
  })

  it('moves focus into overlays and restores their exact openers', async () => {
    const user = userEvent.setup()
    render(
      <CanvasRuntimeProvider>
        <OverlayFocusHarness />
      </CanvasRuntimeProvider>,
    )

    const researchOpener = screen.getByRole('button', { name: 'Open research' })
    await user.click(researchOpener)
    await waitFor(() => expect(screen.getByRole('button', { name: '关闭说明' })).toHaveFocus())
    await user.click(screen.getByRole('button', { name: '关闭说明' }))
    await waitFor(() => expect(researchOpener).toHaveFocus())

    const helpOpener = screen.getByRole('button', { name: 'Open help' })
    await user.click(helpOpener)
    await waitFor(() => expect(screen.getByRole('button', { name: '关闭快捷操作' })).toHaveFocus())
    await user.click(screen.getByRole('button', { name: '关闭快捷操作' }))
    await waitFor(() => expect(helpOpener).toHaveFocus())
  })

  it('supports generator workbench interactions for fixed text type', async () => {
    const user = userEvent.setup()
    render(
      <CanvasRuntimeProvider>
        <WorkbenchHarness />
      </CanvasRuntimeProvider>,
    )
    expect(await screen.findByLabelText('提交文本生成')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '参考改写' }))
    await user.click(screen.getByRole('button', { name: '参考素材三' }))
    await user.click(screen.getByRole('button', { name: /模型/ }))
    await user.click(screen.getByRole('button', { name: '提交文本生成' }))
    expect(screen.getByRole('status')).toHaveTextContent(/文本生成完成/)
  })

  it('covers library template and idea create interactions', async () => {
    const user = userEvent.setup()
    render(<LibraryHarness />)
    await user.click(screen.getByRole('button', { name: /空白画布/ }))
    await user.click(screen.getByRole('button', { name: /从想法创建/ }))
    expect(await screen.findByLabelText(/无限画布/)).toBeInTheDocument()
  })

  it('renders exact plus/send SVG path contracts from the design brief', () => {
    const { container: plus } = render(<PlusIcon />)
    const plusSvg = plus.querySelector('svg')
    expect(plusSvg).toHaveAttribute('viewBox', '0 0 24 24')
    const lines = [...(plusSvg?.querySelectorAll('line') ?? [])]
    expect(lines).toHaveLength(2)
    expect(lines[0]).toHaveAttribute('x1', '12')
    expect(lines[0]).toHaveAttribute('y1', '5')
    expect(lines[0]).toHaveAttribute('x2', '12')
    expect(lines[0]).toHaveAttribute('y2', '19')
    expect(lines[1]).toHaveAttribute('x1', '5')
    expect(lines[1]).toHaveAttribute('y1', '12')
    expect(lines[1]).toHaveAttribute('x2', '19')
    expect(lines[1]).toHaveAttribute('y2', '12')
    expect(plusSvg).toHaveAttribute('stroke-width', '2')

    const { container: send } = render(<SendIcon />)
    const sendSvg = send.querySelector('svg')
    expect(sendSvg).toHaveAttribute('width', '16')
    expect(sendSvg).toHaveAttribute('height', '16')
    expect(sendSvg).toHaveAttribute('viewBox', '0 0 24 24')
    const paths = [...(sendSvg?.querySelectorAll('path') ?? [])]
    expect(paths[0]).toHaveAttribute('d', 'M12 19V5')
    expect(paths[1]).toHaveAttribute('d', 'M5 12L12 5L19 12')
    expect(paths[0]).toHaveAttribute('stroke-width', '2.5')
    expect(paths[0]).toHaveAttribute('stroke', 'currentColor')
  })

  it('exposes generation profile contracts for fixed modes', () => {
    expect(Object.keys(GENERATION_PROFILES)).toEqual(['text', 'image', 'video'])
    expect(GENERATION_PROFILES.text.capabilities.length).toBeGreaterThan(0)
  })
})
