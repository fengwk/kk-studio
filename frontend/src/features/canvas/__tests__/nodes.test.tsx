import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactFlowProvider, type NodeProps } from '@xyflow/react'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Group, Resource, ResourceNode } from '@/features/canvas/domain'
import { canvasNodeTypes } from '@/features/canvas/nodes/CanvasNodeRenderers'
import type { CanvasFlowNode } from '@/features/canvas/projection'
import type { ResourceFlowNodeData } from '@/features/canvas/types'

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

function resource(kind: Resource['kind'], overrides: Partial<Resource> = {}): Resource {
  return {
    id: '20',
    canvasId: '1',
    kind,
    mediaType: kind === 'IMAGE'
      ? 'image/png'
      : kind === 'VIDEO'
        ? 'video/mp4'
        : kind === 'AUDIO'
          ? 'audio/mpeg'
          : 'text/markdown',
    name: `${kind.toLowerCase()}.asset`,
    size: '9223372036854775807',
    text: kind === 'TEXT' ? '# hello' : null,
    metadata: {},
    createdAt: '2026-08-10T00:00:00Z',
    ...overrides,
  }
}

function resourceNode(overrides: Partial<ResourceNode> = {}): ResourceNode {
  return {
    id: '2',
    canvasId: '1',
    name: 'Resource node',
    transform,
    groupId: null,
    resources: [resource('IMAGE', { metadata: { width: 1024, height: 768 } })],
    function: null,
    run: null,
    ...overrides,
  }
}

function renderResourceNode(node: ResourceNode, callbacks = {
  renameNode: vi.fn(),
  editTextNode: vi.fn(),
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
      resource('IMAGE', { metadata: { width: 1024, height: 768 } }),
      resource('VIDEO', { metadata: { durationMs: 65_000 } }),
      resource('AUDIO', { metadata: {} }),
      resource('TEXT', { text: 'hello world' }),
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

  it('caps the visible resource list at four and keeps bigint sizes as decimal strings', () => {
    const resources = Array.from({ length: 5 }, (_, index) => resource('IMAGE', {
      id: `${20 + index}`,
      name: `image-${index}`,
      size: index === 0 ? '9223372036854775807' : '1024',
    }))
    renderResourceNode(resourceNode({ resources }))

    expect(screen.getAllByRole('button', { name: /查看资源/ })).toHaveLength(4)
    expect(screen.getByText('+1')).toBeInTheDocument()
    expect(screen.getByText(/8796093022207/)).toBeInTheDocument()
  })

  it.each([
    ['12', '12 B'],
    ['1024', '1 KB'],
    ['1536', '1.5 KB'],
    ['invalid', 'invalid'],
  ])('formats resource size %s without lossy number conversion', (size, expected) => {
    const view = renderResourceNode(resourceNode({
      id: `size-${size}`,
      resources: [resource('IMAGE', { id: `resource-${size}`, size })],
    }))
    expect(screen.getByText(expected)).toBeInTheDocument()
    view.unmount()
  })

  it('navigates resources beyond the four visible thumbnails', async () => {
    const user = userEvent.setup()
    const resources = Array.from({ length: 5 }, (_, index) => resource('IMAGE', {
      id: `${20 + index}`,
      name: `image-${index}.png`,
      size: '1024',
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
        requestId: 'request-1',
        status: 'FAILED',
        stage: 'FAILED',
        error: 'boom',
        updatedAt: '2026-08-10T00:00:00Z',
      },
    }))

    expect(screen.getByText('Function 首次成功前暂无资源')).toBeInTheDocument()
    expect(screen.getByText('Model label')).toBeInTheDocument()
    expect(screen.getByText('FAILED')).toBeInTheDocument()
    expect(view.container.querySelectorAll('.react-flow__handle')).toHaveLength(2)
    expect(view.container.querySelector('.generation-panel')).toBeNull()
  })

  it('renders the generic empty-resource state without a model descriptor', () => {
    renderResourceNode(resourceNode({ resources: [] }))
    expect(screen.getAllByText('✦')).toHaveLength(2)
    expect(screen.getByText('暂无资源')).toBeInTheDocument()
  })

  it('supports rename and text edit callbacks', async () => {
    const user = userEvent.setup()
    const callbacks = {
      renameNode: vi.fn(),
      editTextNode: vi.fn(),
    }
    const node = resourceNode({
      name: 'Note',
      resources: [resource('TEXT', { text: 'note' })],
    })
    renderResourceNode(node, callbacks)

    await user.click(screen.getByRole('button', { name: 'Note' }))
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
    }
    renderResourceNode(resourceNode({ name: 'Original' }), callbacks)

    await user.click(screen.getByRole('button', { name: 'Original' }))
    const input = screen.getByRole('textbox', { name: '节点名称' })
    await user.type(input, ' changed')
    fireEvent.keyDown(input, { key: 'Escape' })

    expect(screen.getByRole('button', { name: 'Original' })).toBeInTheDocument()
    expect(callbacks.renameNode).not.toHaveBeenCalled()
  })

  it('renders a world-coordinate group card', () => {
    const Component = canvasNodeTypes.group
    const group: Group = {
      id: '4',
      canvasId: '1',
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
      canvasId: '1',
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
          callbacks: { renameNode: vi.fn(), editTextNode: vi.fn() },
        },
      } as NodeProps<CanvasFlowNode>)} />,
    )
    expect(groupView.container).toBeEmptyDOMElement()
  })
})
