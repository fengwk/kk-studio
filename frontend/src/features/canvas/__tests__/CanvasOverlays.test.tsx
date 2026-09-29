import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CanvasOverlays } from '@/features/canvas/CanvasOverlays'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasController } from '@/features/canvas/useCanvasController'

function createFakeController(overrides?: Partial<CanvasController>): CanvasController {
  return {
    state: {
      view: 'editor',
      canvasId: null,
      selectedIds: [],
      selectedLinks: [],
      positionDrafts: {},
      drafts: {},
      storageError: null,
      viewport: { x: 0, y: 0, zoom: 1 },
      toast: null,
      addMenuOpen: false,
      addMenuIndex: 0,
      uploadProgress: {},
      commandPending: false,
      conflictMessage: null,
      textEditor: null,
    },
    snapshot: null,
    projectedSnapshot: null,
    stageMetrics: { width: 1000, height: 800, dockTop: 100 },
    setStageMetrics: vi.fn(),
    fitViewRef: { current: vi.fn() },
    focusSelectionRef: { current: vi.fn() },
    zoomRef: { current: vi.fn() },
    stageElementRef: { current: null },
    dockAddRef: { current: null },
    closeContextMenuRef: { current: vi.fn() },
    snapshotQuery: {} as never,
    modelsQuery: {} as never,
    models: [],
    nodeCallbacks: { editTextNode: vi.fn() },
    initialFitPending: false,
    completeInitialFit: vi.fn(),
    openEditor: vi.fn(),
    openLibrary: vi.fn(),
    setToast: vi.fn(),
    setViewport: vi.fn(),
    setSelection: vi.fn(),
    moveNodes: vi.fn(),
    commitTransforms: vi.fn(),
    createLink: vi.fn(),
    deleteLink: vi.fn(),
    deleteSelection: vi.fn(),
    createTextNode: vi.fn(),
    setTextEditorDraft: vi.fn(),
    closeTextEditor: vi.fn(),
    saveTextEditor: vi.fn(),
    createFunctionNode: vi.fn(),
    scheduleFunctionConfig: vi.fn(),
    flushFunctionConfig: vi.fn(),
    startFunctionRun: vi.fn(),
    cancelFunctionRun: vi.fn(),
    resolveFunctionRun: vi.fn(),
    createGroup: vi.fn(),
    ungroupGroup: vi.fn(),
    renameGroup: vi.fn(),
    deleteGroup: vi.fn(),
    renameNode: vi.fn(),
    editTextNode: vi.fn(),
    deleteNode: vi.fn(),
    uploadFiles: vi.fn(),
    handleAddAction: vi.fn(),
    toggleAddMenu: vi.fn(),
    closeAddMenu: vi.fn(),
    setAddMenuIndex: vi.fn(),
    dismissDraft: vi.fn(),
    retryDraft: vi.fn(),
    retryRecovery: vi.fn(),
    dismissConflictMessage: vi.fn(),
    saveDraftAsNewNode: vi.fn(),
    restoreDeletedDraftAsNewNode: vi.fn(),
    ...overrides,
  }
}

describe('CanvasOverlays UI recovery entry', () => {
  it('renders retry action when conflictMessage indicates blocked/recoverable queue operations', async () => {
    // 意图：当存在未确认操作未能自动重放或队列受阻时，UI 渲染横幅并提供最小 retry 入口，点击后调用 retryRecovery。
    const user = userEvent.setup()
    const retryRecovery = vi.fn().mockResolvedValue(undefined)
    const dismissConflictMessage = vi.fn()

    const controller = createFakeController({
      retryRecovery,
      dismissConflictMessage,
      state: {
        ...createFakeController().state,
        conflictMessage: '部分未确认操作未能自动重放，请检查网络后重试。',
      },
    })

    render(
      <CanvasRuntimeContext.Provider value={controller}>
        <CanvasOverlays />
      </CanvasRuntimeContext.Provider>,
    )

    expect(screen.getByText('部分未确认操作未能自动重放，请检查网络后重试。')).toBeInTheDocument()
    const retryButton = screen.getByRole('button', { name: '重试' })
    expect(retryButton).toBeInTheDocument()

    await user.click(retryButton)
    expect(retryRecovery).toHaveBeenCalledTimes(1)

    const dismissButton = screen.getByRole('button', { name: '关闭' })
    await user.click(dismissButton)
    expect(dismissConflictMessage).toHaveBeenCalledTimes(1)
  })

  it('does not render retry button for non-recoverable conflict banner', () => {
    // 意图：非队列恢复类的普通冲突信息只提供关闭，不展示恢复重试按钮。
    const controller = createFakeController({
      state: {
        ...createFakeController().state,
        conflictMessage: '画布已在其他位置更新。',
      },
    })

    render(
      <CanvasRuntimeContext.Provider value={controller}>
        <CanvasOverlays />
      </CanvasRuntimeContext.Provider>,
    )

    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument()
  })
})
