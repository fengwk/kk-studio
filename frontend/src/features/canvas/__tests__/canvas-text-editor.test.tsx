import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import { CanvasTextEditor } from '@/features/canvas/CanvasTextEditor'
import { projectCanvasSnapshot } from '@/features/canvas/domain'
import { projectNodes } from '@/features/canvas/projection'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import type { CanvasTextEditorState } from '@/features/canvas/types'
import type { CanvasSnapshotDTO } from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_ID = '9f1e6d2a-3b4c-4d5e-8f6a-7b8c9d0e1f2a'
const RESOURCE_ID = 'd5e6f7a8-9b0c-4d1e-8f2a-3b4c5d6e7f8a'
const EDITOR_WIDTH = 400
const EDITOR_GAP = 10
const STAGE_MARGIN = 12

function snapshotDTO(
  transform: { x: number; y: number; width: number; height: number } = {
    x: 20,
    y: 30,
    width: 320,
    height: 260,
  },
): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: '0',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [{
      id: NODE_ID,
      canvasId: CANVAS_ID,
      name: 'Note',
      transform,
      groupId: null,
      resources: [{
        id: RESOURCE_ID,
        canvasId: CANVAS_ID,
        ownerNodeId: NODE_ID,
        resourceIndex: 0,
        blobId: null,
        name: 'note.md',
        textContent: 'hello',
        kind: 'TEXT',
        mediaType: 'text/markdown',
        sizeBytes: 5,
        width: null,
        height: null,
        durationMs: null,
        createdAt: '2026-08-10T00:00:00Z',
      }],
      function: null,
      run: null,
    }],
    groups: [],
    links: [],
  }
}

function renderTextEditor(options: {
  textEditor?: CanvasTextEditorState | null
  snapshot?: CanvasSnapshotDTO | null
  viewport?: { x: number; y: number; zoom: number }
} = {}) {
  const closeTextEditor = vi.fn()
  const setTextEditorDraft = vi.fn()
  const saveTextEditor = vi.fn()
  const state = {
    view: 'editor' as const,
    canvasId: CANVAS_ID,
    selectedIds: [],
    selectedLinks: [],
    positionDrafts: {},
    viewport: options.viewport ?? { x: 0, y: 0, zoom: 1 },
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    threadOpen: false,
    uploadProgress: {},
    commandPending: false,
    conflictMessage: null,
    textEditor: options.textEditor ?? null,
  }
  const runtime = {
    state,
    snapshot: options.snapshot ?? null,
    models: [],
    nodeCallbacks: { editTextNode: vi.fn() },
    stageMetrics: { width: 960, height: 640, dockTop: 520 },
    closeTextEditor,
    setTextEditorDraft,
    saveTextEditor,
  } as unknown as CanvasController
  const view = render(
    <CanvasRuntimeContext.Provider value={runtime}>
      <CanvasTextEditor />
    </CanvasRuntimeContext.Provider>,
  )
  return {
    view,
    closeTextEditor,
    setTextEditorDraft,
    saveTextEditor,
    panel: () => view.container.querySelector('.canvas-text-editor') as HTMLElement,
    rerenderWith: (patch: Partial<typeof state>) => {
      view.rerender(
        <CanvasRuntimeContext.Provider
          value={{ ...runtime, state: { ...state, ...patch } } as CanvasController}
        >
          <CanvasTextEditor />
        </CanvasRuntimeContext.Provider>,
      )
    },
  }
}

describe('CanvasTextEditor panels', () => {
  it('renders nothing while no text editor is open', () => {
    const { view } = renderTextEditor()
    expect(view.container.querySelector('.canvas-text-editor')).not.toBeInTheDocument()
  })

  it('renders create mode with defaults and no stage anchor', () => {
    const { panel } = renderTextEditor({
      textEditor: { mode: 'create', nodeId: null, name: '文本', markdown: '# 新文本' },
    })
    expect(screen.getByLabelText('创建 Markdown 文本')).toBeInTheDocument()
    expect(screen.getByText('新建文本')).toBeInTheDocument()
    expect(panel()).toHaveClass('canvas-text-editor', 'create')
    expect(panel()).not.toHaveAttribute('style')
    expect(screen.getByLabelText('名称')).toHaveValue('文本')
    expect(screen.getByLabelText('Markdown 内容')).toHaveValue('# 新文本')
    expect(screen.getByRole('button', { name: '保存' })).toBeEnabled()
  })

  it('anchors edit mode below the projected flow node', () => {
    const snapshot = snapshotDTO()
    const { panel } = renderTextEditor({
      textEditor: { mode: 'edit', nodeId: NODE_ID, name: 'Note', markdown: 'hello' },
      snapshot,
    })
    expect(screen.getByLabelText('编辑 Markdown 文本「Note」')).toBeInTheDocument()
    expect(screen.getByText('编辑文本')).toBeInTheDocument()
    expect(panel()).not.toHaveClass('create')

    // 期望值由与组件相同的投影函数推导，验证面板使用投影后位置与 gap/clamp 常量。
    const projected = projectCanvasSnapshot(snapshot)
    const flowNode = projectNodes(projected, [], [], { editTextNode: vi.fn() }, {})
      .find((node) => node.id === NODE_ID)
    expect(flowNode).toBeDefined()
    const height = Number(flowNode?.measured?.height ?? flowNode?.style?.height ?? 260)
    expect(panel()).toHaveStyle({
      left: `${Math.max(STAGE_MARGIN, Math.min(20, 960 - EDITOR_WIDTH - STAGE_MARGIN))}px`,
      top: `${Math.max(STAGE_MARGIN, 30 + height + EDITOR_GAP)}px`,
    })
  })

  it('clamps the anchor into the stage bounds', () => {
    const { panel: far } = renderTextEditor({
      textEditor: { mode: 'edit', nodeId: NODE_ID, name: 'Note', markdown: 'hello' },
      snapshot: snapshotDTO({ x: 2000, y: -2000, width: 320, height: 260 }),
    })
    expect(far()).toHaveStyle({
      left: `${960 - EDITOR_WIDTH - STAGE_MARGIN}px`,
      top: `${STAGE_MARGIN}px`,
    })

    const { panel: near } = renderTextEditor({
      textEditor: { mode: 'edit', nodeId: NODE_ID, name: 'Note', markdown: 'hello' },
      snapshot: snapshotDTO({ x: -2000, y: -2000, width: 320, height: 260 }),
    })
    expect(near()).toHaveStyle({
      left: `${STAGE_MARGIN}px`,
      top: `${STAGE_MARGIN}px`,
    })
  })

  it('renders edit mode without an anchor when the flow node is missing', () => {
    const { panel } = renderTextEditor({
      textEditor: { mode: 'edit', nodeId: 'ghost-id', name: 'Note', markdown: 'hello' },
      snapshot: snapshotDTO(),
    })
    expect(screen.getByLabelText('编辑 Markdown 文本「Note」')).toBeInTheDocument()
    expect(panel()).not.toHaveAttribute('style')
  })

  it('disables save until both name and markdown are non-blank', async () => {
    const user = userEvent.setup()
    const { rerenderWith, saveTextEditor } = renderTextEditor({
      textEditor: { mode: 'edit', nodeId: NODE_ID, name: 'Note', markdown: '' },
      snapshot: snapshotDTO(),
    })
    const save = () => screen.getByRole('button', { name: '保存' })
    expect(save()).toBeDisabled()

    rerenderWith({
      textEditor: { mode: 'edit', nodeId: NODE_ID, name: '   ', markdown: 'hello' },
    })
    expect(save()).toBeDisabled()

    rerenderWith({
      textEditor: { mode: 'edit', nodeId: NODE_ID, name: 'Note', markdown: 'hello' },
    })
    expect(save()).toBeEnabled()
    await user.click(save())
    expect(saveTextEditor).toHaveBeenCalledTimes(1)
  })

  it('reports the markdown character count live', () => {
    const { rerenderWith } = renderTextEditor({
      textEditor: { mode: 'create', nodeId: null, name: '文本', markdown: 'hello' },
    })
    expect(screen.getByText('5 字')).toBeInTheDocument()

    rerenderWith({
      textEditor: { mode: 'create', nodeId: null, name: '文本', markdown: '' },
    })
    expect(screen.getByText('0 字')).toBeInTheDocument()
  })

  it('streams drafts and closes through cancel or the close button', async () => {
    const user = userEvent.setup()
    const { closeTextEditor, setTextEditorDraft } = renderTextEditor({
      textEditor: { mode: 'create', nodeId: null, name: '文本', markdown: '# 新文本' },
    })
    fireEvent.change(screen.getByLabelText('名称'), { target: { value: 'New' } })
    expect(setTextEditorDraft).toHaveBeenCalledWith({ name: 'New' })
    fireEvent.change(screen.getByLabelText('Markdown 内容'), { target: { value: 'body' } })
    expect(setTextEditorDraft).toHaveBeenCalledWith({ markdown: 'body' })

    await user.click(screen.getByRole('button', { name: '取消' }))
    expect(closeTextEditor).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '关闭文本编辑' }))
    expect(closeTextEditor).toHaveBeenCalledTimes(2)
  })
})
