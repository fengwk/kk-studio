import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactFlowProvider, type NodeProps } from '@xyflow/react'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Group, Resource, ResourceNode } from '@/features/canvas/domain'
import { canvasNodeTypes } from '@/features/canvas/nodes/CanvasNodeRenderers'
import type { CanvasFlowNode } from '@/features/canvas/projection'
import type { ResourceFlowNodeData } from '@/features/canvas/types'
import { setLocale } from '@/shared/i18n'

vi.mock('@/shared/api/studio-service', () => ({
  getCanvasResourceOriginalUrl: vi.fn(async () => ({
    method: 'GET',
    url: 'https://s3.example/original',
    headers: {},
    expiresAt: '2026-08-10T00:15:00Z',
  })),
  getCanvasResourcePreviewUrl: vi.fn(async () => ({
    method: 'GET',
    url: 'https://s3.example/preview',
    headers: {},
    expiresAt: '2026-08-10T00:15:00Z',
  })),
}))

const transform = { x: 20, y: 30, width: 320, height: 260 }

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'

function resource(kind: Resource['kind'], overrides: Partial<Resource> = {}): Resource {
  return {
    id: '20',
    canvasId: CANVAS_ID,
    ownerNodeId: '2',
    resourceIndex: 0,
    blobId: kind === 'TEXT' ? null : 'blob-asset',
    name: `${kind.toLowerCase()}.asset`,
    textContent: kind === 'TEXT' ? '# hello' : null,
    kind,
    mediaType: kind === 'IMAGE'
      ? 'image/png'
      : kind === 'VIDEO'
        ? 'video/mp4'
        : kind === 'AUDIO'
          ? 'audio/mpeg'
          : 'text/markdown',
    sizeBytes: Number.MAX_SAFE_INTEGER,
    width: null,
    height: null,
    durationMs: null,
    createdAt: '2026-08-10T00:00:00Z',
    ...overrides,
  }
}

function resourceNode(overrides: Partial<ResourceNode> = {}): ResourceNode {
  return {
    id: '2',
    canvasId: CANVAS_ID,
    name: 'Resource node',
    transform,
    groupId: null,
    resources: [resource('IMAGE', { width: 1024, height: 768 })],
    function: null,
    run: null,
    ...overrides,
  }
}

function renderResourceNode(node: ResourceNode, callbacks = {
  renameNode: vi.fn(),
  editTextNode: vi.fn(),
  deleteNode: vi.fn(),
}) {
  const Component = canvasNodeTypes.resource
  const data: ResourceFlowNodeData = {
    kind: 'resource',
    node,
    model: node.function ? {
      key: node.function.modelKey,
      label: 'Model label',
      outputKind: 'IMAGE',
      referencePolicy: { allowedKinds: ['IMAGE'], maxReferences: 1, maxByKind: {} },
      parameters: [],
      available: true,
      unavailableReason: null,
    } : null,
    callbacks,
  }
  const props = {
    id: node.id,
    type: 'resource',
    data,
    selected: false,
    dragging: false,
    zIndex: 1,
    selectable: true,
    deletable: true,
    draggable: true,
    isConnectable: true,
    positionAbsoluteX: node.transform.x,
    positionAbsoluteY: node.transform.y,
  } as NodeProps<CanvasFlowNode>
  return {
    callbacks,
    ...render(
      <QueryClientProvider client={new QueryClient({
        defaultOptions: { queries: { retry: false } },
      })}>
        <ReactFlowProvider>
          <Component {...props} />
        </ReactFlowProvider>
      </QueryClientProvider>,
    ),
  }
}

describe('Canvas resource/group node renderers', () => {
  it('renders basic cards for IMAGE, VIDEO, AUDIO, and TEXT resources', () => {
    const fixtures = [
      resource('IMAGE', { width: 1024, height: 768 }),
      resource('VIDEO', { durationMs: 65_000 }),
      resource('AUDIO', {}),
      resource('TEXT', { textContent: 'hello world' }),
    ]

    for (const fixture of fixtures) {
      const view = renderResourceNode(resourceNode({
        id: `${fixtures.indexOf(fixture) + 2}`,
        resources: [fixture],
      }))
      expect(view.container.querySelector('.resource-node')).toHaveAttribute('data-resource-kind', fixture.kind)
      if (fixture.kind === 'TEXT') {
        expect(screen.getByText('hello world')).toBeInTheDocument()
      }
      expect(view.container.querySelectorAll('.react-flow__handle')).toHaveLength(1)
      view.unmount()
    }
  })

  it('caps the visible resource list at four', () => {
    const resources = Array.from({ length: 5 }, (_, index) => resource('IMAGE', {
      id: `${20 + index}`,
      name: `image-${index}`,
      width: index === 0 ? 1024 : null,
      height: 768,
    }))
    renderResourceNode(resourceNode({ resources }))

    expect(screen.getAllByRole('button', { name: /查看资源/ })).toHaveLength(4)
    expect(screen.getByText('+1')).toBeInTheDocument()
  })

  it('navigates resources beyond the four visible thumbnails', async () => {
    const user = userEvent.setup()
    const resources = Array.from({ length: 5 }, (_, index) => resource('IMAGE', {
      id: `${20 + index}`,
      name: `image-${index}.png`,
      width: 1024,
      height: 768,
    }))
    renderResourceNode(resourceNode({ resources }))
    const previous = screen.getByRole('button', { name: '上一个资源' })
    const next = screen.getByRole('button', { name: '下一个资源' })
    expect(previous).toBeDisabled()

    await user.click(next)
    await user.click(next)
    await user.click(next)
    await user.click(next)
    expect(screen.getByText('5 / 5')).toBeInTheDocument()
    expect(next).toBeDisabled()

    await user.click(previous)
    expect(screen.getByText('4 / 5')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '查看资源 2' }))
    expect(screen.getByText('2 / 5')).toBeInTheDocument()
  })

  it('renders Function/run state and both reference handles without a generation panel', () => {
    const view = renderResourceNode(resourceNode({
      resources: [],
      function: { modelKey: 'fake-image', configJson: '{}' },
      run: {
        nodeId: '2',
        requestId: 'c9c9c9c9-9999-4999-8999-999999999991',
        status: 'FAILED',
        stage: 'FAILED',
        error: 'boom',
        updatedAt: '2026-08-10T00:00:00Z',
      },
    }))

    expect(screen.getByText('Function 首次成功前暂无资源')).toBeInTheDocument()
    expect(screen.getByText('Model label')).toBeInTheDocument()
    expect(screen.getByText('失败')).toBeInTheDocument()
    expect(view.container.querySelectorAll('.react-flow__handle')).toHaveLength(2)
    expect(view.container.querySelector('.generation-panel')).toBeNull()
  })

  it('renders a cancelled Function with a neutral localized status', () => {
    const view = renderResourceNode(resourceNode({
      resources: [],
      function: { modelKey: 'fake-image', configJson: '{}' },
      run: {
        nodeId: '2',
        requestId: 'c9c9c9c9-9999-4999-8999-999999999992',
        status: 'CANCELLED',
        stage: 'CANCELLED',
        error: null,
        updatedAt: '2026-08-10T00:00:00Z',
      },
    }))
    expect(screen.getByText('已取消')).toHaveClass('run-status', 'cancelled')
    expect(view.container.querySelectorAll('.react-flow__handle')).toHaveLength(2)
  })

  it('renders a ready Function with the content-fit run-status badge class', () => {
    // 徽标样式由 .function-footer .run-status 显式重置全局 .run-status 的固定尺寸。
    const view = renderResourceNode(resourceNode({
      resources: [],
      function: { modelKey: 'fake-image', configJson: '{}' },
      run: null,
    }))
    const badge = view.container.querySelector('.function-footer .run-status')
    expect(badge).not.toBeNull()
    expect(badge).toHaveClass('run-status', 'ready')
    expect(badge).toHaveTextContent('就绪')
  })

  it('renders the generic empty-resource state without a model descriptor', () => {
    renderResourceNode(resourceNode({ resources: [] }))
    expect(screen.getAllByText('✦')).toHaveLength(1)
    expect(screen.getByText('暂无资源')).toBeInTheDocument()
  })

  it('supports rename and text edit callbacks', async () => {
    const user = userEvent.setup()
    const callbacks = {
      renameNode: vi.fn(),
      editTextNode: vi.fn(),
      deleteNode: vi.fn(),
    }
    const node = resourceNode({
      name: 'Note',
      resources: [resource('TEXT', { textContent: 'note' })],
    })
    renderResourceNode(node, callbacks)

    await user.click(screen.getByRole('button', { name: '编辑「Note」的名称' }))
    const input = screen.getByRole('textbox', { name: '节点名称' })
    await user.clear(input)
    await user.type(input, 'Renamed{Enter}')
    expect(callbacks.renameNode).toHaveBeenCalledWith('2', 'Renamed')

    fireEvent.doubleClick(screen.getByText('note'))
    expect(callbacks.editTextNode).toHaveBeenCalledWith(node)
    fireEvent.click(screen.getByRole('button', { name: '编辑 Markdown' }))
    expect(callbacks.editTextNode).toHaveBeenCalledTimes(2)
  })

  it('cancels an in-progress rename on Escape', async () => {
    const user = userEvent.setup()
    const callbacks = {
      renameNode: vi.fn(),
      editTextNode: vi.fn(),
      deleteNode: vi.fn(),
    }
    renderResourceNode(resourceNode({ name: 'Original' }), callbacks)

    await user.click(screen.getByRole('button', { name: '编辑「Original」的名称' }))
    const input = screen.getByRole('textbox', { name: '节点名称' })
    await user.type(input, ' changed')
    fireEvent.keyDown(input, { key: 'Escape' })

    expect(screen.getByText('Original')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '编辑「Original」的名称' })).toBeInTheDocument()
    expect(callbacks.renameNode).not.toHaveBeenCalled()
  })

  it('requires an explicit second confirmation before deleting a node', async () => {
    const user = userEvent.setup()
    const callbacks = {
      renameNode: vi.fn(),
      editTextNode: vi.fn(),
      deleteNode: vi.fn(),
    }
    renderResourceNode(resourceNode({ name: 'Disposable' }), callbacks)

    const deleteAction = screen.getByRole('button', { name: '删除节点「Disposable」' })
    await user.click(deleteAction)
    const dialog = screen.getByRole('alertdialog', { name: '删除「Disposable」？' })
    expect(callbacks.deleteNode).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('button', { name: '取消' }))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    await user.click(deleteAction)
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: '确认删除' }))
    expect(callbacks.deleteNode).toHaveBeenCalledWith('2')
  })

  it('renders a world-coordinate group card', () => {
    const Component = canvasNodeTypes.group
    const group: Group = {
      id: '4',
      canvasId: CANVAS_ID,
      title: 'Group title',
      transform: { x: 0, y: 0, width: 500, height: 400 },
    }
    const props = {
      id: 'group:4',
      type: 'group',
      data: { kind: 'group', group },
      selected: false,
      dragging: false,
      zIndex: -1,
      selectable: true,
      deletable: true,
      draggable: true,
      isConnectable: false,
      positionAbsoluteX: 0,
      positionAbsoluteY: 0,
    } as NodeProps<CanvasFlowNode>

    render(
      <ReactFlowProvider>
        <Component {...props} />
      </ReactFlowProvider>,
    )
    expect(screen.getByText('Group title')).toBeInTheDocument()
  })

  it('fails closed when a renderer receives the other node-data discriminator', () => {
    const ResourceComponent = canvasNodeTypes.resource
    const GroupComponent = canvasNodeTypes.group
    const group: Group = {
      id: '4',
      canvasId: CANVAS_ID,
      title: 'Group title',
      transform: { x: 0, y: 0, width: 500, height: 400 },
    }
    const commonProps = {
      id: 'mismatch',
      selected: false,
      dragging: false,
      zIndex: 0,
      selectable: true,
      deletable: true,
      draggable: true,
      isConnectable: false,
      positionAbsoluteX: 0,
      positionAbsoluteY: 0,
    }
    const resourceView = render(
      <ResourceComponent {...({
        ...commonProps,
        type: 'resource',
        data: { kind: 'group', group },
      } as NodeProps<CanvasFlowNode>)} />,
    )
    expect(resourceView.container).toBeEmptyDOMElement()
    resourceView.unmount()

    const node = resourceNode()
    const groupView = render(
      <GroupComponent {...({
        ...commonProps,
        type: 'group',
        data: {
          kind: 'resource',
          node,
          model: null,
          callbacks: { renameNode: vi.fn(), editTextNode: vi.fn(), deleteNode: vi.fn() },
        },
      } as NodeProps<CanvasFlowNode>)} />,
    )
    expect(groupView.container).toBeEmptyDOMElement()
  })

  it('uses a compact content-first shell for media nodes', () => {
    const view = renderResourceNode(resourceNode({
      name: 'Vision board',
      resources: [resource('IMAGE', { width: 1024, height: 768 })],
    }))
    expect(view.container.querySelector('.resource-node-titlebar')).not.toHaveClass(
      'resource-node-drag-handle',
    )
    expect(view.container.querySelector('.resource-node-content')).not.toBeNull()
    expect(view.container.querySelector('.resource-node-preview')).not.toHaveClass('nodrag')
    expect(screen.getByText('Vision board')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '编辑「Vision board」的名称' })).toHaveClass('nodrag')
    expect(screen.getByRole('button', { name: '删除节点「Vision board」' })).toHaveClass('nodrag')
    expect(view.container.querySelector('.media-original-actions')).toHaveClass('nodrag')
    expect(view.container.querySelector('.resource-node.compact-media-node')).not.toBeNull()
    expect(view.container.querySelector('.resource-node-footer')).toBeNull()
  })

  it('extracts a real Function summary from the prompt config', () => {
    const view = renderResourceNode(resourceNode({
      name: 'Generator',
      resources: [],
      function: {
        modelKey: 'fake-image',
        configJson: JSON.stringify({
          prompt: { segments: [{ type: 'TEXT', text: 'generate a red square' }] },
          parameters: {},
        }),
      },
      run: null,
    }))
    const summary = view.container.querySelector('.resource-node-summary')
    expect(summary).not.toBeNull()
    expect(summary).toHaveTextContent('generate a red square')
  })

  it('localizes visible chrome labels with the active locale', () => {
    setLocale('en-US')
    const imageView = renderResourceNode(resourceNode({
      resources: [resource('IMAGE', { width: 1024, height: 768 })],
    }))
    expect(screen.getByRole('button', { name: 'Edit the name of Resource node' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Delete node Resource node' })).toBeInTheDocument()
    imageView.unmount()

    const textView = renderResourceNode(resourceNode({
      resources: [resource('TEXT', { textContent: 'note' })],
    }))
    expect(screen.getByRole('button', { name: 'Edit Markdown' })).toBeInTheDocument()
    textView.unmount()

    const multiView = renderResourceNode(resourceNode({
      resources: [
        resource('IMAGE', { width: 1024, height: 768 }),
        resource('IMAGE', { width: 512, height: 512 }),
      ],
    }))
    expect(screen.getByRole('button', { name: 'Previous resource' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Next resource' })).toBeEnabled()
    multiView.unmount()

    const emptyView = renderResourceNode(resourceNode({ resources: [] }))
    expect(screen.getByText('No resources yet')).toBeInTheDocument()
    emptyView.unmount()
  })
})
