import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CanvasGenerationPanel } from '@/features/canvas/CanvasGenerationPanel'
import {
  CanvasRuntimeContext,
} from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasSnapshot, ResourceNode } from '@/features/canvas/domain'
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

function resourceNode(
  id: `${bigint}`,
  name: string,
  kind: 'IMAGE' | 'VIDEO',
): ResourceNode {
  return {
    id,
    canvasId: '1',
    name,
    transform: { x: 0, y: 0, width: 320, height: 260 },
    groupId: null,
    resources: [{
      id: `${id}0` as `${bigint}`,
      canvasId: '1',
      kind,
      mediaType: kind === 'IMAGE' ? 'image/png' : 'video/mp4',
      name,
      size: '3',
      text: null,
      metadata: {},
      createdAt: '2026-08-10T00:00:00Z',
    }],
    function: null,
    run: null,
  }
}

function fixture(run: ResourceNode['run'] = null) {
  const target: ResourceNode = {
    id: '9',
    canvasId: '1',
    name: 'Generator',
    transform: { x: 0, y: 0, width: 320, height: 260 },
    groupId: null,
    resources: [resourceNode('2', 'old-output', 'IMAGE').resources[0]!],
    function: {
      modelKey: 'image-a',
      configJson: JSON.stringify({
        prompt: { segments: [{ type: 'TEXT', text: 'frontback' }] },
        parameters: { ratio: '1:1' },
      }),
    },
    run,
  }
  const snapshot: CanvasSnapshot = {
    document: {
      id: '1',
      title: 'Board',
      graphRevision: '0',
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
      { canvasId: '1', sourceNodeId: '2', targetNodeId: '9' },
      { canvasId: '1', sourceNodeId: '3', targetNodeId: '9' },
      { canvasId: '1', sourceNodeId: '4', targetNodeId: '9' },
    ],
  }
  return { snapshot, target }
}

function renderPanel(
  run: ResourceNode['run'] = null,
  availableModels: CanvasFunctionModelDTO[] = models,
) {
  const scheduleFunctionConfig = vi.fn()
  const flushFunctionConfig = vi.fn(async () => undefined)
  const startFunctionRun = vi.fn(async () => undefined)
  const cancelFunctionRun = vi.fn(async () => undefined)
  const setToast = vi.fn()
  const runtime = {
    models: availableModels,
    scheduleFunctionConfig,
    flushFunctionConfig,
    startFunctionRun,
    cancelFunctionRun,
    setToast,
  } as unknown as CanvasController
  const { snapshot, target } = fixture(run)
  const tree = (currentSnapshot: CanvasSnapshot, currentNode: ResourceNode) => (
    <QueryClientProvider client={new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })}>
      <CanvasRuntimeContext.Provider value={runtime}>
        <CanvasGenerationPanel snapshot={currentSnapshot} node={currentNode} />
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
    startFunctionRun,
    cancelFunctionRun,
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
    await user.click(screen.getByRole('button', { name: '插入参考 @single' }))
    expect(screen.getByRole('textbox', { name: '提示词片段 3' })).toHaveFocus()
    await user.click(screen.getByRole('button', { name: '插入参考 @single' }))
    await user.click(screen.getByRole('button', { name: '插入参考 @second' }))

    const latest = view.scheduleFunctionConfig.mock.calls.at(-1)?.[2] as CanvasFunctionConfigDTO
    expect(latest.prompt.segments.filter((segment) => segment.type === 'REFERENCE')).toEqual([
      { type: 'REFERENCE', nodeId: '2', index: 0 },
      { type: 'REFERENCE', nodeId: '2', index: 0 },
      { type: 'REFERENCE', nodeId: '4', index: 0 },
    ])
    expect(screen.getAllByRole('button', { name: '删除 @single' })).toHaveLength(2)
    expect(screen.getByRole('button', { name: '插入参考 @single' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(screen.getByRole('button', { name: '插入参考 @second' })).toHaveAttribute(
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
    await user.click(screen.getByRole('option', { name: '@single' }))
    const suffix = screen.getByRole('textbox', { name: '提示词片段 3' })
    suffix.focus()
    suffix.setSelectionRange(0, 0)
    fireEvent.keyDown(suffix, { key: 'Backspace' })
    expect(screen.queryByRole('button', { name: '删除 @single' })).not.toBeInTheDocument()

    const prefix = screen.getByRole('textbox', { name: '提示词片段 1' })
    prefix.focus()
    prefix.setSelectionRange(prefix.value.length, prefix.value.length)
    fireEvent.select(prefix)
    await user.click(screen.getByRole('button', { name: '插入参考 @single' }))
    fireEvent.keyDown(screen.getByRole('button', { name: '删除 @single' }), { key: 'Backspace' })
    expect(screen.queryByRole('button', { name: '删除 @single' })).not.toBeInTheDocument()

    prefix.focus()
    prefix.setSelectionRange(prefix.value.length, prefix.value.length)
    fireEvent.select(prefix)
    await user.click(screen.getByRole('button', { name: '插入参考 @single' }))
    prefix.focus()
    prefix.setSelectionRange(prefix.value.length, prefix.value.length)
    fireEvent.keyDown(prefix, { key: 'Delete' })
    expect(screen.queryByRole('button', { name: '删除 @single' })).not.toBeInTheDocument()
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
    // The first server echo is a source revision acknowledgement, not permission to overwrite later typing.
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
    await user.click(screen.getByRole('button', { name: '插入参考 @single' }))
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

  it('flushes before submit through the runtime and exposes cancel/failure without hiding old output', async () => {
    // The fake runtime isolates paid work while proving submit/cancel and terminal failure UI wiring.
    const user = userEvent.setup()
    const ready = renderPanel()
    await user.click(screen.getByRole('button', { name: '开始生成' }))
    expect(ready.startFunctionRun).toHaveBeenCalledWith('9')
    ready.unmount()
    expect(ready.flushFunctionConfig).toHaveBeenCalledWith('9')

    const running = renderPanel({
      nodeId: '9',
      requestId: 'request-1',
      status: 'RUNNING',
      stage: 'GENERATING',
      error: null,
      updatedAt: '2026-08-10T00:00:00Z',
    })
    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(running.cancelFunctionRun).toHaveBeenCalledWith('9', 'request-1')
    running.unmount()

    renderPanel({
      nodeId: '9',
      requestId: 'request-2',
      status: 'FAILED',
      stage: 'FAILED',
      error: 'provider failed',
      updatedAt: '2026-08-10T00:00:01Z',
    })
    expect(await screen.findByRole('alert')).toHaveTextContent('provider failed')
  })
})
