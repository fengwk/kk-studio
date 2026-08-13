import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactFlowProvider, type NodeProps } from '@xyflow/react'
import { fireEvent, render, screen } from '@testing-library/react'
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

function renderResourceNode(node: ResourceNode, callbacks = { editTextNode: vi.fn() }) {
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
  it('renders minimal content-first nodes for IMAGE, VIDEO, AUDIO, and TEXT resources', () => {
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
      if (fixture.kind === 'AUDIO') {
        // 音频节点内嵌紧凑自定义播放器，原生 controls 的 audio 元素在首次播放后才挂载。
        expect(view.container.querySelector('.audio-player')).not.toBeNull()
        expect(view.container.querySelector('audio')).toBeNull()
        expect(view.container.querySelector('.audio-load-button')).toBeNull()
      }
      // 常驻标题栏编辑/删除按钮全部移除，只保留透明标签。
      expect(view.container.querySelector('.resource-node-titlebar')).toBeNull()
      expect(view.container.querySelector('.resource-node-action')).toBeNull()
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
    const view = renderResourceNode(resourceNode({ resources }))
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
    view.unmount()
  })

  it('renders a minimal Function node with the localized kind label and both handles', () => {
    const view = renderResourceNode(resourceNode({
      name: 'Generator',
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

    // 顶部透明标签：图片生成 · Generator；卡片/页脚/摘要全部移除。
    expect(screen.getByText('图片生成 · Generator')).toBeInTheDocument()
    expect(screen.getByText('Function 首次成功前暂无资源')).toBeInTheDocument()
    expect(view.container.querySelectorAll('.react-flow__handle')).toHaveLength(2)
    expect(view.container.querySelector('.resource-node-kind-label')).toHaveClass(
      'resource-node-kind-label',
    )
    expect(view.container.querySelector('.function-footer')).toBeNull()
    expect(view.container.querySelector('.resource-node-summary')).toBeNull()
    expect(view.container.querySelector('.generation-panel')).toBeNull()
    expect(view.container.querySelector('.resource-node.function-node')).not.toBeNull()
  })

  it('renders the Function kind label from the model output kind', () => {
    // 顶部标签跟随模型 outputKind 本地化：图片生成 · {nodeName}。
    const view = renderResourceNode(resourceNode({
      name: 'Clip maker',
      resources: [],
      function: { modelKey: 'fake-image', configJson: '{}' },
      run: null,
    }))
    expect(view.container.querySelector('.resource-node-kind-label')).toHaveTextContent(
      '图片生成 · Clip maker',
    )
  })

  it('renders the generic empty-resource state without a model descriptor', () => {
    renderResourceNode(resourceNode({ resources: [] }))
    expect(screen.getAllByText('✦')).toHaveLength(1)
    expect(screen.getByText('暂无资源')).toBeInTheDocument()
  })

  it('opens the text editor through double-click without any permanent chrome', async () => {
    const callbacks = { editTextNode: vi.fn() }
    const node = resourceNode({
      name: 'Note',
      resources: [resource('TEXT', { textContent: 'note' })],
    })
    renderResourceNode(node, callbacks)

    expect(screen.queryByRole('button', { name: /编辑/ })).not.toBeInTheDocument()
    fireEvent.doubleClick(screen.getByText('note'))
    expect(callbacks.editTextNode).toHaveBeenCalledWith(node)
    expect(callbacks.editTextNode).toHaveBeenCalledTimes(1)
  })

  it('renders a lightweight dashed group boundary with a transparent title', () => {
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
    const shell = document.querySelector('.canvas-group-node') as HTMLElement
    expect(shell).not.toBeNull()
    expect(shell.querySelector('.canvas-group-title')).toHaveTextContent('Group title')
    // 组不是业务卡片：无 header 卡片、无成员内容。
    expect(shell.querySelector('header')).toBeNull()
    expect(shell.querySelector('.resource-node')).toBeNull()
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
          callbacks: { editTextNode: vi.fn() },
        },
      } as NodeProps<CanvasFlowNode>)} />,
    )
    expect(groupView.container).toBeEmptyDOMElement()
  })

  it('uses a compact content-first shell for media and Function nodes', () => {
    const view = renderResourceNode(resourceNode({
      name: 'Vision board',
      resources: [resource('IMAGE', { width: 1024, height: 768 })],
    }))
    expect(view.container.querySelector('.resource-node-titlebar')).toBeNull()
    expect(view.container.querySelector('.resource-node-action')).toBeNull()
    expect(view.container.querySelector('.resource-node.compact-media-node')).not.toBeNull()
    expect(screen.getByText('Vision board')).toBeInTheDocument()
    expect(view.container.querySelector('.media-original-actions')).toBeNull()
    expect(view.container.querySelector('.resource-node-footer')).toBeNull()
  })

  it('localizes visible chrome labels with the active locale', () => {
    setLocale('en-US')
    const functionView = renderResourceNode(resourceNode({
      name: 'Generator',
      resources: [],
      function: { modelKey: 'fake-image', configJson: '{}' },
      run: null,
    }))
    expect(screen.getByText('Image generation · Generator')).toBeInTheDocument()
    expect(screen.queryByText('Edit Markdown')).not.toBeInTheDocument()
    functionView.unmount()

    const textView = renderResourceNode(resourceNode({
      resources: [resource('TEXT', { textContent: 'note' })],
    }))
    expect(screen.getByText('note')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit Markdown' })).not.toBeInTheDocument()
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
