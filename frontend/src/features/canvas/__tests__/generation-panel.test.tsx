import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CanvasGenerationPanel } from '@/features/canvas/CanvasGenerationPanel'
import {
  CanvasRuntimeContext,
} from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
import type {
  CanvasGenerationPanelAnchor,
} from '@/features/canvas/CanvasGenerationPanel'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import type {
  CanvasFunctionConfigDTO,
  CanvasFunctionModelDTO,
} from '@/shared/api/contracts/studio'

vi.mock('@/shared/api/studio-service', () => ({
  getCanvasResourceOriginalUrl: vi.fn(),
  getCanvasResourcePreviewUrl: vi.fn(async () => ({
    method: 'GET',
    url: 'https://s3.example/preview',
    headers: {},
    expiresAt: '2026-08-10T00:15:00Z',
  })),
}))

const models: CanvasFunctionModelDTO[] = [{
  key: 'image-a',
  label: 'Image A',
  outputKind: 'IMAGE',
  referencePolicy: {
    allowedKinds: ['IMAGE'],
    maxReferences: 2,
    maxByKind: { IMAGE: 2 },
  },
  parameters: [{
    key: 'ratio',
    label: '比例',
    type: 'ENUM',
    required: true,
    defaultValue: '1:1',
    options: ['1:1', '16:9'],
    min: null,
    max: null,
  }],
  available: true,
  unavailableReason: null,
}, {
  key: 'image-b',
  label: 'Image B',
  outputKind: 'IMAGE',
  referencePolicy: {
    allowedKinds: ['VIDEO'],
    maxReferences: 1,
    maxByKind: { VIDEO: 1 },
  },
  parameters: [{
    key: 'duration',
    label: '时长',
    type: 'INTEGER',
    required: true,
    defaultValue: 5,
    options: [],
    min: 4,
    max: 15,
  }],
  available: true,
  unavailableReason: null,
}]

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

function resourceNode(
  id: string,
  name: string,
  kind: 'IMAGE' | 'VIDEO',
): ResourceNode {
  return {
    id,
    canvasId: CANVAS_ID,
    name,
    transform: { x: 0, y: 0, width: 320, height: 260 },
    groupId: null,
    resources: [{
      id: `${id}-r0`,
      canvasId: CANVAS_ID,
      ownerNodeId: id,
      resourceIndex: 0,
      blobId: 'blob-output',
      name,
      textContent: null,
      kind,
      mediaType: kind === 'IMAGE' ? 'image/png' : 'video/mp4',
      sizeBytes: 3,
      width: null,
      height: null,
      durationMs: null,
      createdAt: '2026-08-10T00:00:00Z',
    }],
    function: null,
    run: null,
  }
}

function fixture(run: ResourceNode['run'] = null, modelKey: 'image-a' | 'image-b' = 'image-a') {
  const target: ResourceNode = {
    id: '9',
    canvasId: CANVAS_ID,
    name: 'Generator',
    transform: { x: 0, y: 0, width: 320, height: 260 },
    groupId: null,
    resources: [resourceNode('2', 'old-output', 'IMAGE').resources[0]!],
    function: {
      modelKey,
      configJson: JSON.stringify(modelKey === 'image-a'
        ? {
          prompt: { segments: [{ type: 'TEXT', text: 'frontback' }] },
          parameters: { ratio: '1:1' },
        }
        : {
          prompt: { segments: [{ type: 'TEXT', text: 'frontback' }] },
          parameters: { duration: 5 },
        }),
    },
    run,
  }
  const snapshot: CanvasSnapshot = {
    document: {
      id: '1',
      title: 'Board',
      version: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    resourceNodes: [
      resourceNode('2', 'single', 'IMAGE'),
      resourceNode('3', 'video', 'VIDEO'),
      resourceNode('4', 'second', 'IMAGE'),
      target,
    ],
    groups: [],
    links: [
      { canvasId: CANVAS_ID, sourceNodeId: '2', targetNodeId: '9' },
      { canvasId: CANVAS_ID, sourceNodeId: '3', targetNodeId: '9' },
      { canvasId: CANVAS_ID, sourceNodeId: '4', targetNodeId: '9' },
    ],
  }
  return { snapshot, target }
}

function renderPanel(
  run: ResourceNode['run'] = null,
  availableModels: CanvasFunctionModelDTO[] = models,
  rawAnchor?: CanvasGenerationPanelAnchor | { targetNodeId: string },
) {
  const scheduleFunctionConfig = vi.fn()
  const flushFunctionConfig = vi.fn(async () => undefined)
  const setToast = vi.fn()
  const setSelection = vi.fn()
  const runtime = {
    models: availableModels,
    scheduleFunctionConfig,
    flushFunctionConfig,
    setToast,
    setSelection,
  } as unknown as CanvasController
  const { snapshot, target } = fixture(run)
  const anchor = rawAnchor && !('targetNodeId' in rawAnchor)
    ? {
      ...rawAnchor,
      node: { ...rawAnchor.node, width: 320, height: 260 },
    }
    : undefined
  const tree = (currentSnapshot: CanvasSnapshot, currentNode: ResourceNode) => (
    <QueryClientProvider client={new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })}>
      <CanvasRuntimeContext.Provider value={runtime}>
        <CanvasGenerationPanel
          snapshot={currentSnapshot}
          node={currentNode}
          anchor={anchor}
        />
      </CanvasRuntimeContext.Provider>
    </QueryClientProvider>
  )
  const view = render(tree(snapshot, target))
  return {
    ...view,
    runtime,
    snapshot,
    target,
    rerenderPanel: (currentSnapshot: CanvasSnapshot, currentNode: ResourceNode) => {
      view.rerender(tree(currentSnapshot, currentNode))
    },
    scheduleFunctionConfig,
    flushFunctionConfig,
    setToast,
    setSelection,
  }
}

describe('Canvas generic generation panel', () => {
  it('inserts structured references at the caret and permits duplicate mention chips', async () => {
    // Sequential clicks prove the cursor advances after each chip while duplicate refs remain legal.
    const user = userEvent.setup()
    const view = renderPanel()
    const input = screen.getByRole('textbox', { name: '提示词片段 1' })
    input.focus()
    input.setSelectionRange(5, 5)
    fireEvent.select(input)
    await user.click(screen.getByRole('button', { name: '插入参考 @single_0' }))
    expect(screen.getByRole('textbox', { name: '提示词片段 3' })).toHaveFocus()
    await user.click(screen.getByRole('button', { name: '插入参考 @single_0' }))
    await user.click(screen.getByRole('button', { name: '插入参考 @second_0' }))

    const latest = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfigDTO
    expect(latest.prompt.segments.filter((segment) => segment.type === 'REFERENCE')).toEqual([
      { type: 'REFERENCE', nodeId: '2', index: 0 },
      { type: 'REFERENCE', nodeId: '2', index: 0 },
      { type: 'REFERENCE', nodeId: '4', index: 0 },
    ])
    expect(screen.getAllByRole('button', { name: '删除 @single_0' })).toHaveLength(2)
    expect(screen.getByRole('button', { name: '插入参考 @single_0' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('button', { name: '插入参考 @second_0' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('opens @ candidates, removes adjacent or focused chips by keyboard, and updates parameters', async () => {
    // Boundary keys provide text-editor semantics without serializing aliases into prompt data.
    const user = userEvent.setup()
    const view = renderPanel()
    const input = screen.getByRole('textbox', { name: '提示词片段 1' })
    await user.click(input)
    await user.type(input, '@')
    expect(screen.getByRole('listbox', { name: '@ 引用候选' })).toBeInTheDocument()
    await user.click(screen.getByRole('option', { name: '@single_0' }))
    const suffix = screen.getByRole('textbox', { name: '提示词片段 3' })
    suffix.focus()
    suffix.setSelectionRange(0, 0)
    fireEvent.keyDown(suffix, { key: 'Backspace' })
    expect(screen.queryByRole('button', { name: '删除 @single_0' })).not.toBeInTheDocument()

    const prefix = screen.getByRole('textbox', { name: '提示词片段 1' })
    prefix.focus()
    prefix.setSelectionRange(prefix.value.length, prefix.value.length)
    fireEvent.select(prefix)
    await user.click(screen.getByRole('button', { name: '插入参考 @single_0' }))
    fireEvent.keyDown(screen.getByRole('button', { name: '删除 @single_0' }), { key: 'Backspace' })
    expect(screen.queryByRole('button', { name: '删除 @single_0' })).not.toBeInTheDocument()

    prefix.focus()
    prefix.setSelectionRange(prefix.value.length, prefix.value.length)
    fireEvent.select(prefix)
    await user.click(screen.getByRole('button', { name: '插入参考 @single_0' }))
    prefix.focus()
    prefix.setSelectionRange(prefix.value.length, prefix.value.length)
    fireEvent.keyDown(prefix, { key: 'Delete' })
    expect(screen.queryByRole('button', { name: '删除 @single_0' })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('比例'), '16:9')
    const latest = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfigDTO
    expect(latest.parameters.ratio).toBe('16:9')
  })

  it('initializes when models arrive late and accepts a genuine external config change', async () => {
    // Late registry data and a later server edit both rehydrate from authoritative config.
    const view = renderPanel(null, [])
    expect(screen.queryByLabelText('Function 生成面板')).not.toBeInTheDocument()

    view.runtime.models = models
    view.rerenderPanel(view.snapshot, view.target)
    expect(await screen.findByRole('textbox', { name: '提示词片段 1' })).toHaveValue('frontback')
    expect(screen.getByLabelText('比例')).toHaveValue('1:1')

    view.rerenderPanel(view.snapshot, {
      ...view.target,
      function: {
        modelKey: 'image-b',
        configJson: JSON.stringify({
          prompt: { segments: [{ type: 'TEXT', text: 'server replacement' }] },
          parameters: { duration: 9 },
        }),
      },
    })
    expect(await screen.findByRole('textbox', { name: '提示词片段 1' })).toHaveValue(
      'server replacement',
    )
    expect(screen.getByLabelText('模型')).toHaveValue('image-b')
    expect(screen.getByLabelText('时长')).toHaveValue(9)
  })

  it('keeps a newer focused draft when an earlier debounced config is acknowledged', async () => {
    // The first server echo is a source version acknowledgement, not permission to overwrite later typing.
    const user = userEvent.setup()
    const view = renderPanel()
    const input = screen.getByRole('textbox', { name: '提示词片段 1' })
    await user.clear(input)
    await user.type(input, 'first')
    const acknowledged = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfigDTO
    await user.type(input, ' second')
    expect(input).toHaveFocus()

    view.rerenderPanel(view.snapshot, {
      ...view.target,
      function: {
        modelKey: 'image-a',
        configJson: JSON.stringify(acknowledged),
      },
    })
    expect(screen.getByRole('textbox', { name: '提示词片段 1' })).toHaveValue('first second')
    expect(screen.getByRole('textbox', { name: '提示词片段 1' })).toHaveFocus()
  })

  it('switches models with fresh defaults and filters references by the new policy', async () => {
    // A prior IMAGE ref is deliberately incompatible with Image B, while its INTEGER default is retained.
    const user = userEvent.setup()
    const view = renderPanel()
    await user.click(screen.getByRole('button', { name: '插入参考 @single_0' }))
    await user.selectOptions(screen.getByLabelText('模型'), 'image-b')
    const latest = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfigDTO
    expect(view.scheduleFunctionConfig.mock.calls.at(-1)?.[1]).toBe('image-b')
    expect(latest.parameters).toEqual({ duration: 5 })
    expect(latest.prompt.segments.some((segment) => (
      segment.type === 'REFERENCE' && segment.nodeId === '2'
    ))).toBe(false)
    expect(screen.getByLabelText('时长')).toHaveValue(5)
    const scheduledCount = view.scheduleFunctionConfig.mock.calls.length
    fireEvent.change(screen.getByLabelText('时长'), { target: { value: '20' } })
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduledCount)
    fireEvent.change(screen.getByLabelText('时长'), { target: { value: '8' } })
    expect((view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfigDTO)
      .parameters.duration).toBe(8)
  })

  it('keeps run actions out of the workbench while exposing run status and failures', () => {
    // Run/cancel is a right-click action; the workbench only edits configuration and shows status.
    const ready = renderPanel()
    expect(screen.queryByRole('button', { name: '开始生成' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '取消' })).not.toBeInTheDocument()
    ready.unmount()
    expect(ready.flushFunctionConfig).toHaveBeenCalledWith('9')

    const running = renderPanel({
      nodeId: '9',
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991',
      status: 'RUNNING',
      stage: 'GENERATING',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    expect(screen.queryByRole('button', { name: '取消' })).not.toBeInTheDocument()
    expect(screen.getByText('GENERATING')).toBeInTheDocument()
    running.unmount()

    renderPanel({
      nodeId: '9',
      requestId: 'c9c9c9c9-9999-4999-8999-999999999992',
      status: 'FAILED',
      stage: 'FAILED',
      error: 'provider failed',
      updatedAt: '2026-08-10T00:00:01Z',
    })
    expect(screen.getByRole('alert')).toHaveTextContent('provider failed')
  })

  it('shows the generation header with the real model label and the true run status', () => {
    // Header 同时展示 registry label、kicker 与真实运行状态。
    const view = renderPanel()
    const header = view.container.querySelector('.generation-panel-head')
    expect(header).not.toBeNull()
    expect(within(header as HTMLElement).getByText('Image A')).toBeInTheDocument()
    expect(within(header as HTMLElement).getByText('就绪')).toBeInTheDocument()
    expect(within(header as HTMLElement).getByText('Function')).toHaveClass('generation-node-kicker')
    view.unmount()

    const running = renderPanel({
      nodeId: '9',
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991',
      status: 'RUNNING',
      stage: 'GENERATING',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    expect(within(running.container.querySelector('.generation-panel-head') as HTMLElement)
      .getByText('运行中')).toHaveClass('generation-node-status', 'running')
    running.unmount()

    const failed = renderPanel({
      nodeId: '9',
      requestId: 'c9c9c9c9-9999-4999-8999-999999999992',
      status: 'FAILED',
      stage: 'FAILED',
      error: 'boom',
      updatedAt: '2026-08-10T00:00:01Z',
    })
    expect(within(failed.container.querySelector('.generation-panel-head') as HTMLElement)
      .getByText('失败')).toHaveClass('generation-node-status', 'failed')
    failed.unmount()

    const cancelled = renderPanel({
      nodeId: '9',
      requestId: 'c9c9c9c9-9999-4999-8999-999999999993',
      status: 'CANCELLED',
      stage: 'CANCELLED',
      error: null,
      updatedAt: '2026-08-10T00:00:02Z',
    })
    expect(within(cancelled.container.querySelector('.generation-panel-head') as HTMLElement)
      .getByText('已取消')).toHaveClass('generation-node-status', 'cancelled')
  })

  it('expands and collapses the panel width through the header action', async () => {
    const user = userEvent.setup()
    const view = renderPanel()
    const panel = view.container.querySelector('.generation-panel')
    expect(panel).not.toHaveClass('expanded')

    const expand = screen.getByRole('button', { name: '展开面板' })
    expect(expand).toHaveAttribute('aria-expanded', 'false')
    await user.click(expand)
    expect(panel).toHaveClass('expanded')
    expect(screen.getByRole('button', { name: '收起面板' })).toHaveAttribute('aria-expanded', 'true')

    await user.click(screen.getByRole('button', { name: '收起面板' }))
    expect(panel).not.toHaveClass('expanded')
    expect(screen.getByRole('button', { name: '展开面板' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('closes the panel by clearing the selection while the unmount flush still runs', async () => {
    // Close is the documented escape hatch: it clears selection (unmounting the panel)
    // and the unmount effect still flushes the latest draft config.
    const user = userEvent.setup()
    const view = renderPanel()
    await user.click(screen.getByRole('button', { name: '关闭面板' }))
    expect(view.setSelection).toHaveBeenCalledWith([])
    view.unmount()
    expect(view.flushFunctionConfig).toHaveBeenCalledWith('9')
  })

  it('anchors the panel below the node with compact desktop geometry', () => {
    // Compact 桌面（<=1600 宽）使用 gap 8/padding 4；节点在视口内时面板贴节点下方。
    const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      width: 560,
      height: 190,
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      bottom: 190,
      right: 560,
      toJSON: () => ({}),
    } as DOMRect)
    try {
      const view = renderPanel(null, models, {
        node: { x: 100, y: 80, width: 320, height: 260 },
        viewport: { x: 0, y: 0, zoom: 1 },
        stage: { width: 1440, height: 900, dockTop: 880 },
      })
      const panel = view.container.querySelector('.generation-panel') as HTMLElement
      expect(panel).toHaveAttribute('data-placement', 'below')
      expect(panel).toHaveStyle({ left: '100px', top: '348px' })
    } finally {
      rectSpy.mockRestore()
    }
  })

  it('anchors the panel with wide-desktop spacing when the stage exceeds 1600px', () => {
    // 宽屏走默认 gap 12/padding 12；顶部位置仍从节点下方投影。
    const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      width: 560,
      height: 190,
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      bottom: 190,
      right: 560,
      toJSON: () => ({}),
    } as DOMRect)
    try {
      const view = renderPanel(null, models, {
        node: { x: 100, y: 80, width: 320, height: 260 },
        viewport: { x: 0, y: 0, zoom: 1 },
        stage: { width: 1920, height: 900, dockTop: 880 },
      })
      const panel = view.container.querySelector('.generation-panel') as HTMLElement
      expect(panel).toHaveAttribute('data-placement', 'below')
      expect(panel).toHaveStyle({ left: '100px', top: '352px' })
    } finally {
      rectSpy.mockRestore()
    }
  })

  it('renders without an anchor while the measuring effect tolerates a missing ResizeObserver', () => {
    // 无 anchor 时面板不写定位样式；ResizeObserver 缺失时测量 effect 直接早退。
    const original = globalThis.ResizeObserver
    // @ts-expect-error 删除全局观察者以覆盖生产代码的 undefined 早退分支。
    delete (globalThis as { ResizeObserver?: unknown }).ResizeObserver
    try {
      const view = renderPanel()
      const panel = view.container.querySelector('.generation-panel') as HTMLElement
      expect(panel).not.toHaveAttribute('style')
      expect(screen.getByLabelText('模型')).toBeInTheDocument()
    } finally {
      ;(globalThis as { ResizeObserver?: unknown }).ResizeObserver = original
    }
  })

  it('skips the panel-size publish when the measured rect is zero-sized', () => {
    // 初始测量为零尺寸时 publish 早退，面板仍正常渲染且不因 setState 崩溃。
    const rectSpy = vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({
      width: 0,
      height: 0,
      x: 0,
      y: 0,
      top: 0,
      left: 0,
      bottom: 0,
      right: 0,
      toJSON: () => ({}),
    } as DOMRect)
    try {
      const view = renderPanel()
      const panel = view.container.querySelector('.generation-panel') as HTMLElement
      expect(panel).toBeInTheDocument()
      expect(screen.getByLabelText('提示词片段 1')).toHaveValue('frontback')
      expect(view.scheduleFunctionConfig).not.toHaveBeenCalled()
    } finally {
      rectSpy.mockRestore()
    }
  })

  it('disables model, enum, and number controls while a run is in progress', async () => {
    // 运行中编辑全部冻结：模型下拉、枚举下拉、数字输入都不可改。
    const view = renderPanel({
      nodeId: '9',
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991',
      status: 'RUNNING',
      stage: 'GENERATING',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    expect(screen.getByLabelText('模型')).toBeDisabled()
    expect(screen.getByLabelText('比例')).toBeDisabled()
    expect(view.scheduleFunctionConfig).not.toHaveBeenCalled()
    view.unmount()
    expect(view.flushFunctionConfig).toHaveBeenCalledWith('9')
  })

  it('shows the succeeded status and prefers a distinct stage label in the footer', () => {
    // status=SUCCEEDED 时 header 用成功文案；stage 与 status 不同则 footer 展示 stage。
    const view = renderPanel({
      nodeId: '9',
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991',
      status: 'SUCCEEDED',
      stage: 'GENERATING',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    const header = view.container.querySelector('.generation-panel-head') as HTMLElement
    expect(within(header).getByText('成功')).toHaveClass('generation-node-status', 'succeeded')
    expect(screen.getByText('GENERATING')).toHaveClass('generation-run-feedback', 'succeeded')
    view.unmount()
  })

  it('falls back to the status label when the run stage equals the status', () => {
    // stage 与 status 相同时 footer 回退到翻译后的状态文案。
    const view = renderPanel({
      nodeId: '9',
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991',
      status: 'SUCCEEDED',
      stage: 'SUCCEEDED',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    const header = view.container.querySelector('.generation-panel-head') as HTMLElement
    expect(within(header).getByText('成功')).toHaveClass('generation-node-status', 'succeeded')
    expect(screen.getAllByText('成功')).toHaveLength(2)
    view.unmount()
  })

  it('toasts the reference limit when another distinct reference exceeds the model limit', async () => {
    // maxReferences=1 时第二个不同参考触发上限提示，且不产生新草稿。
    const user = userEvent.setup()
    const limited: CanvasFunctionModelDTO[] = [{
      ...models[0]!,
      referencePolicy: {
        ...models[0]!.referencePolicy,
        maxReferences: 1,
      },
    }]
    const view = renderPanel(null, limited)
    await user.click(screen.getByRole('button', { name: '插入参考 @single_0' }))
    const scheduled = view.scheduleFunctionConfig.mock.calls.length
    await user.click(screen.getByRole('button', { name: '插入参考 @second_0' }))
    expect(view.setToast).toHaveBeenCalledWith('当前模型的参考资源数量已达到上限。')
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduled)
    view.unmount()
  })

  it('shows an empty candidate menu when no reference candidates exist', async () => {
    // 模型只接受 AUDIO 而本节点只链接 IMAGE/VIDEO 时，@ 菜单显示空态文案。
    const user = userEvent.setup()
    const emptyModels: CanvasFunctionModelDTO[] = [{
      ...models[0]!,
      referencePolicy: {
        allowedKinds: ['AUDIO'],
        maxReferences: 2,
        maxByKind: { AUDIO: 2 },
      },
    }]
    renderPanel(null, emptyModels)
    const input = screen.getByRole('textbox', { name: '提示词片段 1' })
    await user.click(input)
    await user.type(input, '@')
    expect(screen.getByRole('listbox', { name: '@ 引用候选' })).toBeInTheDocument()
    expect(screen.getByText('没有可用引用')).toBeInTheDocument()
    expect(screen.queryAllByRole('option').filter((element) => (
      element.closest('.mention-candidates')
    ))).toHaveLength(0)
  })

  it('disables an unavailable model option and ignores an unknown model selection', async () => {
    // 不可用模型以「label（reason）」展示并禁用；select 收到非法值时静默忽略。
    const unavailable: CanvasFunctionModelDTO[] = [{
      ...models[0]!,
      available: false,
      unavailableReason: 'quota exceeded',
    }]
    const view = renderPanel(null, unavailable)
    const select = screen.getByLabelText('模型')
    expect(within(select).getByRole('option', { name: 'Image A（quota exceeded）' }))
      .toBeDisabled()
    fireEvent.change(select, { target: { value: 'ghost-model' } })
    expect(view.scheduleFunctionConfig).not.toHaveBeenCalled()
    expect(screen.getByLabelText('模型')).toHaveValue('image-a')
  })

  it('rejects non-integer and below-min number edits for INTEGER parameters', async () => {
    // 非整数与低于 min 的值不进入草稿；合法值正常调度。
    const view = renderPanel(null, models)
    const select = screen.getByLabelText('模型')
    fireEvent.change(select, { target: { value: 'image-b' } })
    const numberInput = screen.getByLabelText('时长')
    expect(numberInput).toHaveValue(5)
    const scheduled = view.scheduleFunctionConfig.mock.calls.length

    fireEvent.change(numberInput, { target: { value: '3.5' } })
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduled)
    fireEvent.change(numberInput, { target: { value: '3' } })
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduled)

    fireEvent.change(numberInput, { target: { value: '8' } })
    expect((view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfigDTO)
      .parameters.duration).toBe(8)
    view.unmount()
  })
})
