import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CanvasGenerationPanel } from '@/features/canvas/CanvasGenerationPanel'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
import type { CanvasGenerationPanelAnchor } from '@/features/canvas/CanvasGenerationPanel'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import type { CanvasFunctionConfig } from '@/features/canvas/types'
import type {
  CanvasFunctionDefinitionDTO,
  UUIDString,
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

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
const NODE_SINGLE = '00000000-0000-4000-8000-000000000002' as UUIDString
const NODE_VIDEO = '00000000-0000-4000-8000-000000000003' as UUIDString
const NODE_SECOND = '00000000-0000-4000-8000-000000000004' as UUIDString
const NODE_TARGET = '00000000-0000-4000-8000-000000000009' as UUIDString

const models: CanvasFunctionDefinitionDTO[] = [{
  name: 'image-a',
  description: 'Image A',
  outputs: [{ kind: 'IMAGE', name: null }],
  argsSchema: {
    type: 'object',
    required: ['ratio'],
    properties: {
      prompt: { type: 'string', description: 'Prompt' },
      ratio: {
        type: 'string',
        title: '比例',
        default: '1:1',
        enum: ['1:1', '16:9'],
      },
      references: {
        type: 'array',
        items: { type: 'resourceReference' },
      },
    },
  },
  referencePolicy: {
    allowedKinds: ['IMAGE'],
    maxReferences: 2,
    maxByKind: { IMAGE: 2 },
  },
  available: true,
  unavailableReason: null,
}, {
  name: 'image-b',
  description: 'Image B',
  outputs: [{ kind: 'IMAGE', name: null }],
  argsSchema: {
    type: 'object',
    required: ['duration'],
    properties: {
      prompt: { type: 'string', description: 'Prompt' },
      duration: {
        type: 'integer',
        title: '时长',
        default: 5,
        minimum: 4,
        maximum: 15,
      },
      references: {
        type: 'array',
        items: { type: 'resourceReference' },
      },
    },
  },
  referencePolicy: {
    allowedKinds: ['VIDEO'],
    maxReferences: 1,
    maxByKind: { VIDEO: 1 },
  },
  available: true,
  unavailableReason: null,
}]

/** 共享下拉没有原生 value：当前选中值以 data-value 暴露。 */
function expectSelectValue(label: string, value: string) {
  // 展开的 listbox 与触发器共用相同的无障碍名称，因此按 role 精确定位触发器。
  expect(screen.getByRole('button', { name: label })).toHaveAttribute('data-value', value)
}

/** 共享下拉的交互是「点触发器展开 → 点选项」，与原生 select 的 selectOptions 不同。 */
async function chooseOption(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
  option: string,
) {
  await user.click(screen.getByLabelText(label))
  await user.click(screen.getByRole('option', { name: option }))
}

function changeSelect(label: string, option: string) {
  fireEvent.click(screen.getByLabelText(label))
  fireEvent.click(screen.getByRole('option', { name: option }))
}

function resourceNode(
  id: UUIDString,
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
      id: `${id}-r0` as UUIDString,
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
    id: NODE_TARGET,
    canvasId: CANVAS_ID,
    name: 'Generator',
    transform: { x: 0, y: 0, width: 320, height: 260 },
    groupId: null,
    resources: [resourceNode(NODE_SINGLE, 'old-output', 'IMAGE').resources[0]!],
    function: {
      name: modelKey,
      args: modelKey === 'image-a'
        ? {
          prompt: 'frontback',
          ratio: '1:1',
          references: [],
        }
        : {
          prompt: 'frontback',
          duration: 5,
          references: [],
        },
    },
    run,
  }
  const snapshot: CanvasSnapshot = {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      revision: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    resourceNodes: [
      resourceNode(NODE_SINGLE, 'single', 'IMAGE'),
      resourceNode(NODE_VIDEO, 'video', 'VIDEO'),
      resourceNode(NODE_SECOND, 'second', 'IMAGE'),
      target,
    ],
    groups: [],
    references: [],
  }
  return { snapshot, target }
}

function renderPanel(
  run: ResourceNode['run'] = null,
  availableModels: CanvasFunctionDefinitionDTO[] = models,
  rawAnchor?: CanvasGenerationPanelAnchor | { targetNodeId: string },
) {
  const scheduleFunctionConfig = vi.fn()
  const flushFunctionConfig = vi.fn(async () => undefined)
  const setToast = vi.fn()
  const setSelection = vi.fn()
  const discardPendingRun = vi.fn(() => true)
  const isNodeInFlight = vi.fn(() => false)
  const runtime = {
    models: availableModels,
    scheduleFunctionConfig,
    flushFunctionConfig,
    setToast,
    setSelection,
    discardPendingRun,
    isNodeInFlight,
    localPendingErrors: {},
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
    discardPendingRun,
    isNodeInFlight,
  }
}

describe('Canvas generic generation panel', () => {
  it('toggles independent references and displays attached chips', async () => {
    // 验证 UI 简化：引用作为附件芯片，点击 candidate 切换挂载，删除按钮移除指定引用
    const user = userEvent.setup()
    const view = renderPanel()
    const input = screen.getByRole('textbox', { name: '提示词' })
    expect(input).toHaveValue('frontback')

    // 点击 candidate 插入引用
    await user.click(screen.getByRole('button', { name: '插入参考 @single_0' }))
    const latest1 = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig
    expect(latest1.references).toEqual([
      { type: 'resource', nodeId: NODE_SINGLE, index: 0 },
    ])
    expect(screen.getByRole('button', { name: '删除 @single_0' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '插入参考 @single_0' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )

    // 点击另一个 candidate
    await user.click(screen.getByRole('button', { name: '插入参考 @second_0' }))
    const latest2 = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig
    expect(latest2.references).toEqual([
      { type: 'resource', nodeId: NODE_SINGLE, index: 0 },
      { type: 'resource', nodeId: NODE_SECOND, index: 0 },
    ])

    // 点击删除按钮移除指定引用
    await user.click(screen.getByRole('button', { name: '删除 @single_0' }))
    const latest3 = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig
    expect(latest3.references).toEqual([
      { type: 'resource', nodeId: NODE_SECOND, index: 0 },
    ])
    expect(screen.queryByRole('button', { name: '删除 @single_0' })).not.toBeInTheDocument()
  })

  it('types verbatim prompt and updates parameters without cursor splicing', async () => {
    // 验证用户原始文本逐字符保留（包括已有 @ 和 HTML 类似标签），参数表单扁平更新
    const user = userEvent.setup()
    const view = renderPanel()
    const input = screen.getByRole('textbox', { name: '提示词' })
    await user.clear(input)
    await user.type(input, 'raw prompt with @user and <tag>')
    const latestPrompt = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig
    expect(latestPrompt.prompt).toBe('raw prompt with @user and <tag>')

    await chooseOption(user, '比例', '16:9')
    const latest = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig
    expect(latest.parameters.ratio).toBe('16:9')
  })

  it('initializes when models arrive late and accepts a genuine external config change', async () => {
    // Late registry data and a later server edit both rehydrate from authoritative config.
    const view = renderPanel(null, [])
    expect(screen.queryByLabelText('Function 生成面板')).not.toBeInTheDocument()

    view.runtime.models = models
    view.rerenderPanel(view.snapshot, view.target)
    expect(await screen.findByRole('textbox', { name: '提示词' })).toHaveValue('frontback')
    expectSelectValue('比例', '1:1')

    view.rerenderPanel(view.snapshot, {
      ...view.target,
      function: {
        name: 'image-b',
        args: {
          prompt: 'server replacement',
          duration: 9,
          references: [],
        },
      },
    })
    expect(await screen.findByRole('textbox', { name: '提示词' })).toHaveValue(
      'server replacement',
    )
    expectSelectValue('模型', 'image-b')
    expect(screen.getByLabelText('时长')).toHaveValue('9')
  })

  it('keeps a newer focused draft when an earlier debounced config is acknowledged', async () => {
    // The first server echo is a source version acknowledgement, not permission to overwrite later typing.
    const user = userEvent.setup()
    const view = renderPanel()
    const input = screen.getByRole('textbox', { name: '提示词' })
    await user.clear(input)
    await user.type(input, 'first')
    const acknowledged = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig
    await user.type(input, ' second')
    expect(input).toHaveFocus()

    view.rerenderPanel(view.snapshot, {
      ...view.target,
      function: {
        name: 'image-a',
        args: {
          prompt: acknowledged.prompt,
          ratio: acknowledged.parameters.ratio,
          references: acknowledged.references,
        },
      },
    })
    expect(screen.getByRole('textbox', { name: '提示词' })).toHaveValue('first second')
    expect(screen.getByRole('textbox', { name: '提示词' })).toHaveFocus()
  })

  it('switches models with fresh defaults and filters references by the new policy', async () => {
    // A prior IMAGE ref is deliberately incompatible with Image B, while its INTEGER default is retained.
    const user = userEvent.setup()
    const view = renderPanel()
    await user.click(screen.getByRole('button', { name: '插入参考 @single_0' }))
    await chooseOption(user, '模型', 'Image B')
    const latest = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig
    expect(view.scheduleFunctionConfig.mock.calls.at(-1)?.[1]).toBe('image-b')
    expect(latest.parameters).toEqual({ duration: 5 })
    expect(latest.references.some((ref) => ref.nodeId === NODE_SINGLE)).toBe(false)
    expect(screen.getByLabelText('时长')).toHaveValue('5')
    const scheduledCount = view.scheduleFunctionConfig.mock.calls.length
    fireEvent.change(screen.getByLabelText('时长'), { target: { value: '20' } })
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduledCount)
    fireEvent.change(screen.getByLabelText('时长'), { target: { value: '8' } })
    expect((view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig)
      .parameters.duration).toBe(8)
  })

  it('keeps run actions out of the workbench while exposing run status and failures', () => {
    // Run/cancel is a right-click action; the workbench only edits configuration and shows status.
    const ready = renderPanel()
    expect(screen.queryByRole('button', { name: '开始生成' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '取消' })).not.toBeInTheDocument()
    ready.unmount()
    expect(ready.flushFunctionConfig).toHaveBeenCalledWith(NODE_TARGET)

    const running = renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991' as UUIDString,
      status: 'RUNNING',
      stage: 'GENERATING',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    expect(screen.queryByRole('button', { name: '取消' })).not.toBeInTheDocument()
    expect(screen.getByText('GENERATING')).toBeInTheDocument()
    running.unmount()

    renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999992' as UUIDString,
      status: 'FAILED',
      stage: 'FAILED',
      error: 'provider failed',
      updatedAt: '2026-08-10T00:00:01Z',
    })
    expect(screen.getByRole('alert')).toHaveTextContent('provider failed')
  })

  it('shows the generation header with the real model label and the true run status', () => {
    const view = renderPanel()
    const header = view.container.querySelector('.generation-panel-head')
    expect(header).not.toBeNull()
    expect(within(header as HTMLElement).getByText('Image A')).toBeInTheDocument()
    expect(within(header as HTMLElement).getByText('就绪')).toBeInTheDocument()
    expect(within(header as HTMLElement).getByText('Function')).toHaveClass('generation-node-kicker')
    view.unmount()

    const ready = renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999990' as UUIDString,
      status: 'READY',
      stage: 'QUEUED',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    expect(within(ready.container.querySelector('.generation-panel-head') as HTMLElement)
      .getByText('就绪')).toHaveClass('generation-node-status', 'ready')
    expect(screen.getByLabelText('模型')).toBeDisabled()
    ready.unmount()

    const running = renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991' as UUIDString,
      status: 'RUNNING',
      stage: 'GENERATING',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    expect(within(running.container.querySelector('.generation-panel-head') as HTMLElement)
      .getByText('运行中')).toHaveClass('generation-node-status', 'running')
    running.unmount()

    const failed = renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999992' as UUIDString,
      status: 'FAILED',
      stage: 'FAILED',
      error: 'boom',
      updatedAt: '2026-08-10T00:00:01Z',
    })
    expect(within(failed.container.querySelector('.generation-panel-head') as HTMLElement)
      .getByText('失败')).toHaveClass('generation-node-status', 'failed')
    failed.unmount()

    const cancelled = renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999993' as UUIDString,
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
    const user = userEvent.setup()
    const view = renderPanel()
    await user.click(screen.getByRole('button', { name: '关闭面板' }))
    expect(view.setSelection).toHaveBeenCalledWith([])
    view.unmount()
    expect(view.flushFunctionConfig).toHaveBeenCalledWith(NODE_TARGET)
  })

  it('anchors the panel below the node with compact desktop geometry', () => {
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
      expect(screen.getByLabelText('提示词')).toHaveValue('frontback')
      expect(view.scheduleFunctionConfig).not.toHaveBeenCalled()
    } finally {
      rectSpy.mockRestore()
    }
  })

  it('disables model, enum, and number controls while a run is in progress', async () => {
    const view = renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991' as UUIDString,
      status: 'RUNNING',
      stage: 'GENERATING',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    expect(screen.getByLabelText('模型')).toBeDisabled()
    expect(screen.getByLabelText('比例')).toBeDisabled()
    expect(view.scheduleFunctionConfig).not.toHaveBeenCalled()
    view.unmount()
    expect(view.flushFunctionConfig).toHaveBeenCalledWith(NODE_TARGET)
  })

  it('shows the succeeded status and prefers a distinct stage label in the footer', () => {
    const view = renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991' as UUIDString,
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
    const view = renderPanel({
      nodeId: NODE_TARGET,
      requestId: 'c9c9c9c9-9999-4999-8999-999999999991' as UUIDString,
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
    const user = userEvent.setup()
    const limited: CanvasFunctionDefinitionDTO[] = [{
      ...models[0]!,
      referencePolicy: {
        ...models[0]!.referencePolicy!,
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

  it('shows no reference row when no reference candidates exist', () => {
    // 模型只接受 AUDIO 而快照中只有 IMAGE/VIDEO 时，candidates 为空，不渲染候选栏
    const emptyModels: CanvasFunctionDefinitionDTO[] = [{
      ...models[0]!,
      referencePolicy: {
        allowedKinds: ['AUDIO'],
        maxReferences: 2,
        maxByKind: { AUDIO: 2 },
      },
    }]
    renderPanel(null, emptyModels)
    expect(screen.queryByLabelText('可用参考资源')).not.toBeInTheDocument()
  })

  it('disables an unavailable model option and keeps the current model', async () => {
    const user = userEvent.setup()
    const unavailable: CanvasFunctionDefinitionDTO[] = [{
      ...models[0]!,
      available: false,
      unavailableReason: 'quota exceeded',
    }]
    const view = renderPanel(null, unavailable)
    await user.click(screen.getByLabelText('模型'))
    const option = screen.getByRole('option', { name: 'Image A（quota exceeded）' })
    expect(option).toBeDisabled()
    fireEvent.click(option)
    expect(view.scheduleFunctionConfig).not.toHaveBeenCalled()
    expectSelectValue('模型', 'image-a')
  })

  it('rejects non-integer and below-min number edits for INTEGER parameters', async () => {
    const view = renderPanel(null, models)
    changeSelect('模型', 'Image B')
    const numberInput = screen.getByLabelText('时长')
    expect(numberInput).toHaveValue('5')
    const scheduled = view.scheduleFunctionConfig.mock.calls.length

    fireEvent.change(numberInput, { target: { value: '3.5' } })
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduled)
    fireEvent.change(numberInput, { target: { value: '3' } })
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduled)

    fireEvent.change(numberInput, { target: { value: '8' } })
    expect((view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig)
      .parameters.duration).toBe(8)
    view.unmount()
  })

  it('I11 参数 JSON 双向同步与非法输入防御：打开时同步最新值，表单修改联动，非法 JSON 绝不覆盖表单', async () => {
    const view = renderPanel(null, models)

    // 1. 展开参数 JSON：初始化展示当前参数
    const toggleBtn = screen.getByRole('button', { name: '编辑参数 JSON' })
    fireEvent.click(toggleBtn)

    const textarea = screen.getByRole('textbox', { name: '参数 JSON' })
    expect(JSON.parse((textarea as HTMLTextAreaElement).value)).toEqual({ ratio: '1:1' })

    // 2. 表单联动：当未脏态修改 JSON 时，表单控件（比例选择器）修改会联动同步更新 JSON 文本
    changeSelect('比例', '16:9')
    expect(JSON.parse((textarea as HTMLTextAreaElement).value)).toEqual({ ratio: '16:9' })

    // 3. 用户在 JSON 编辑器中输入合法 JSON：更新表单与调度
    const scheduledCount = view.scheduleFunctionConfig.mock.calls.length
    fireEvent.change(textarea, { target: { value: '{\n  "ratio": "1:1"\n}' } })
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduledCount + 1)
    expect((view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig)
      .parameters.ratio).toBe('1:1')

    // 4. 用户输入非法 JSON（语法错误）：提示错误，绝不调用 scheduleFunctionConfig 破坏已有有效值
    const beforeInvalidCalls = view.scheduleFunctionConfig.mock.calls.length
    fireEvent.change(textarea, { target: { value: '{\n  "ratio": "1:1"' } })
    expect(screen.getByRole('alert')).toHaveTextContent('JSON 语法错误')
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(beforeInvalidCalls)

    // 5. 用户输入非对象 JSON（如数组）：提示错误，不覆盖有效值
    fireEvent.change(textarea, { target: { value: '[1, 2, 3]' } })
    expect(screen.getByRole('alert')).toHaveTextContent('参数必须为 JSON 对象')
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(beforeInvalidCalls)

    view.unmount()
  })

  it('I11: 合法到非法部分输入保留草稿、外部 config 更新不覆盖 dirty、收起重开重置权威/修正输入有效', async () => {
    const view = renderPanel(null, models)

    // 1. 展开参数 JSON
    const toggleBtn = screen.getByRole('button', { name: '编辑参数 JSON' })
    fireEvent.click(toggleBtn)

    const textarea = screen.getByRole('textbox', { name: '参数 JSON' }) as HTMLTextAreaElement
    expect(JSON.parse(textarea.value)).toEqual({ ratio: '1:1' })

    // 2. 从合法输入修改为非法部分输入
    const partialInput = '{\n  "ratio": "16:9",\n  "incomplete": '
    fireEvent.change(textarea, { target: { value: partialInput } })

    expect(textarea.value).toBe(partialInput)
    expect(screen.getByRole('alert')).toHaveTextContent('JSON 语法错误')
    const scheduledCalls = view.scheduleFunctionConfig.mock.calls.length

    // 3. 外部 config 更新或组件受外部状态重渲染时，由于处于 dirty 状态，绝不冲掉文本框里的用户输入草稿
    const externalUpdatedTarget: ResourceNode = {
      ...view.target,
      function: {
        ...view.target.function!,
        args: {
          prompt: 'external change',
          ratio: '4:3',
          external: 'yes',
          references: [],
        },
      },
    }
    view.rerenderPanel(view.snapshot, externalUpdatedTarget)

    expect(textarea.value).toBe(partialInput)
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduledCalls)

    // 4. 用户修正为合法输入：错误提示消失，并立即有效调度提交
    const correctedInput = '{\n  "ratio": "16:9",\n  "steps": 25\n}'
    fireEvent.change(textarea, { target: { value: correctedInput } })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduledCalls + 1)
    expect(
      (view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig)
        .parameters,
    ).toEqual({ ratio: '16:9', steps: 25 })

    // 5. 再次输入非法内容后，点击“收起参数 JSON”并重新打开：重置为权威 parameters
    fireEvent.change(textarea, { target: { value: 'invalid-json' } })
    expect(screen.getByRole('alert')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '收起参数 JSON' }))
    expect(screen.queryByRole('textbox', { name: '参数 JSON' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '编辑参数 JSON' }))
    const reopenedTextarea = screen.getByRole('textbox', { name: '参数 JSON' }) as HTMLTextAreaElement
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(JSON.parse(reopenedTextarea.value)).toEqual({ ratio: '16:9', steps: 25 })

    view.unmount()
  })

  it('enters raw JSON mode with error alert when prompt or references shape is unsupported, and edits full args losslessly', async () => {
    // 测试意图：当节点 args 中 prompt 为非字符串（如旧版 prompt.segments 或复杂对象）时，
    // 面板绝不能静默覆盖为 ''，必须切换至完整 JSON 编辑模式并显示提示，保证编辑保存无损。
    const rawTarget: ResourceNode = {
      ...fixture().target,
      function: {
        name: 'image-a',
        args: {
          prompt: { segments: [{ type: 'TEXT', text: 'custom object prompt' }] },
          ratio: '1:1',
          references: [],
        },
      },
    }
    const view = renderPanel()
    view.rerenderPanel(view.snapshot, rawTarget)

    // 验证：显示错误提示，提示已进入完整 JSON 模式
    expect(screen.getByRole('alert')).toHaveTextContent('入参 prompt 包含非字符串结构，已进入完整 JSON 模式以防止数据丢失。')
    // 文本域展示完整原始 JSON，未被清空
    const textarea = screen.getByRole('textbox', { name: '参数 JSON' }) as HTMLTextAreaElement
    expect(JSON.parse(textarea.value)).toEqual(rawTarget.function!.args)

    // 用户在完整 JSON 文本域中修改入参并保存
    const scheduledCalls = view.scheduleFunctionConfig.mock.calls.length
    fireEvent.change(textarea, {
      target: {
        value: JSON.stringify({
          prompt: 'back to text',
          ratio: '16:9',
          references: [],
        }),
      },
    })
    expect(view.scheduleFunctionConfig).toHaveBeenCalledTimes(scheduledCalls + 1)
    const submitted = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfig
    expect(submitted.rawArgs).toEqual({
      prompt: 'back to text',
      ratio: '16:9',
      references: [],
    })
    view.unmount()
  })

  it('I06: 未决记录异常展示、确认放弃弹窗与状态恢复', async () => {
    const corruptRaw = 'corrupt-raw-json-data'
    const targetId = NODE_TARGET
    const storageKey = `kkstudio.canvas.pending-run:${CANVAS_ID}:${targetId}`
    window.localStorage.setItem(storageKey, corruptRaw)

    const view = renderPanel()

    const errorBanner = await screen.findByTestId('local-pending-error')
    expect(errorBanner).toHaveTextContent('未决运行记录异常')
    const discardBtn = screen.getByTestId('discard-pending-btn')
    expect(discardBtn).toBeInTheDocument()

    fireEvent.click(discardBtn)
    expect(screen.getByRole('alertdialog', { name: '确认放弃未决记录' })).toBeInTheDocument()
    expect(screen.getByText('这不会撤销服务端运行，核实后再继续')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByTestId('discard-pending-btn')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('discard-pending-btn'))
    const confirmBtn = screen.getByTestId('confirm-discard-btn')
    fireEvent.click(confirmBtn)
    expect(view.discardPendingRun).toHaveBeenCalledWith(targetId, corruptRaw)
    expect(screen.queryByTestId('local-pending-error')).not.toBeInTheDocument()

    window.localStorage.removeItem(storageKey)
    view.unmount()
  })
})
