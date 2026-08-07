import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CanvasPage } from '@/features/canvas/CanvasPage'
import { extractPositionUpdates } from '@/features/canvas/node-position-changes'
import { canvasNodeTypes } from '@/features/canvas/nodes/CanvasNodeRenderers'
import { canvasReducer, createInitialCanvasState } from '@/features/canvas/reducer'

vi.mock('@/shared/api/studio-service', () => ({
  listCanvases: vi.fn(async () => ([
    {
      id: '1001',
      title: '竞品研究与产品方案',
      revision: '0',
      homeViewportJson: '{}',
    },
  ])),
  createCanvas: vi.fn(async () => ({
    id: '1002',
    title: '未命名画布',
    revision: '0',
    homeViewportJson: '{}',
  })),
}))

/**
 * 故意不 mock @xyflow/react。
 * 用于捕获 Maximum update depth / #002 / missing handles #008。
 */
describe('Canvas React Flow smoke (real package)', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('mounts the editor with 8 edge paths and without RF error storms', async () => {
    const user = userEvent.setup()
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})

    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )

    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))
    const stage = await screen.findByLabelText(/无限画布/)
    expect(stage).toBeInTheDocument()

    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(stage.querySelector('.react-flow')).toBeTruthy()
    expect(canvasNodeTypes).toBe(canvasNodeTypes)

    await waitFor(() => {
      const edgePaths = stage.querySelectorAll('.react-flow__edge-path')
      // 初始 demo 文档有 8 条 links；handle 存在时它们必须全部投影出来。
      expect(edgePaths.length).toBe(8)
    })

    const joined = [...errorSpy.mock.calls, ...warnSpy.mock.calls]
      .map((args) => args.map(String).join(' '))
      .join('\n')
    expect(joined).not.toMatch(/Maximum update depth exceeded/i)
    expect(joined).not.toMatch(/error #002/i)
    expect(joined).not.toMatch(/error #008/i)
    expect(joined).not.toMatch(/error #015|not initialized/i)
    expect(joined).not.toMatch(/Couldn't create edge for source handle/i)
    expect(joined).not.toMatch(/nodeTypes or edgeTypes has changed/i)

    errorSpy.mockRestore()
    warnSpy.mockRestore()
  })

  it('keeps permanent hand tool after Space pan activation key usage', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )
    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))
    await screen.findByLabelText(/无限画布/)

    await user.keyboard('h')
    expect(screen.getByRole('status')).toHaveTextContent(/手形工具/)
    await user.keyboard('[Space>]')
    await user.keyboard('[/Space]')
    expect(screen.getByLabelText(/无限画布/)).toHaveClass('hand-tool')
  })

  it('applies RF-like position changes into domain coordinates for controlled drag', () => {
    // Stage onNodesChange 的 smoke 级契约，避免在 jsdom 中做不稳定的指针模拟。
    const updates = extractPositionUpdates([
      { type: 'position', id: 'web', position: { x: 150, y: 200 } },
      { type: 'position', id: 'image', position: { x: 320, y: 210 } },
    ])
    const next = canvasReducer(createInitialCanvasState(), { type: 'move-nodes', updates })
    expect(next.nodes.find((node) => node.id === 'web')).toMatchObject({ x: 150, y: 200 })
    expect(next.nodes.find((node) => node.id === 'image')).toMatchObject({ x: 320, y: 210 })
  })

  it('focuses generation prompt after add-menu generator create (not Dock +)', async () => {
    const user = userEvent.setup()
    render(
      <MemoryRouter>
        <CanvasPage />
      </MemoryRouter>,
    )
    await user.click(await screen.findByRole('button', { name: /竞品研究/ }))
    await user.click(screen.getByRole('button', { name: '添加内容' }))
    await user.click(screen.getByRole('menuitem', { name: /文本生成/ }))
    await waitFor(() => {
      const prompt = document.getElementById('generationPrompt')
      expect(prompt).toBeTruthy()
      expect(document.activeElement).toBe(prompt)
    })

    await user.click(screen.getByRole('button', { name: '关闭生成操作台' }))
    await waitFor(() => {
      expect(document.activeElement).toHaveClass('node-generator')
    })
  })
})
