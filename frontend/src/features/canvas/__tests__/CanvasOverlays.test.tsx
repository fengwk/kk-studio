import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CanvasOverlays } from '@/features/canvas/CanvasOverlays'
import { CanvasRuntimeContext } from '@/features/canvas/CanvasRuntimeContext'
import type { CanvasController } from '@/features/canvas/useCanvasController'
import { setLocale } from '@/shared/i18n'

function createFakeController(overrides?: Partial<CanvasController>): CanvasController {
  return {
    viewId: undefined,
    state: {
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
      draftPersistPending: false,
      conflictKind: null,
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
    discardPendingRun: vi.fn(() => false),
    localPendingErrors: {},
    isNodeInFlight: vi.fn(() => false),
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
    retryDraftPersist: vi.fn(async () => {}),
    ackDurableDrafts: vi.fn(async () => {}),
    retryRecovery: vi.fn(),
    dismissConflictMessage: vi.fn(),
    saveDraftAsNewNode: vi.fn(),
    restoreDeletedDraftAsNewNode: vi.fn(),
    ...overrides,
  }
}

function renderOverlays(controller: CanvasController) {
  return render(
    <CanvasRuntimeContext.Provider value={controller}>
      <CanvasOverlays />
    </CanvasRuntimeContext.Provider>,
  )
}

describe('CanvasOverlays recovery entry', () => {
  it('renders the retry entry for a recoverable conflict even when the message carries no matching wording', async () => {
    // 意图：恢复入口由结构化 conflictKind 决定；消息改成英文/改写后仍必须提供整体重试。
    const user = userEvent.setup()
    const retryRecovery = vi.fn().mockResolvedValue(undefined)
    const dismissConflictMessage = vi.fn()
    const base = createFakeController()
    renderOverlays(createFakeController({
      retryRecovery,
      dismissConflictMessage,
      state: {
        ...base.state,
        conflictKind: 'recoverable',
        conflictMessage: 'Some unconfirmed operations could not be replayed. Check the connection and retry.',
      },
    }))

    expect(screen.getByText('Some unconfirmed operations could not be replayed. Check the connection and retry.'))
      .toBeInTheDocument()
    const retryButton = screen.getByRole('button', { name: '重试' })
    await user.click(retryButton)
    expect(retryRecovery).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '关闭' }))
    expect(dismissConflictMessage).toHaveBeenCalledTimes(1)
  })

  it('hides the retry entry for a terminal conflict even when the message contains recovery wording', () => {
    // 意图：终态冲突（409 等服务端裁决）只做就地处理，不能因文案里出现「重试」而显示整体重试。
    const base = createFakeController()
    renderOverlays(createFakeController({
      state: {
        ...base.state,
        conflictKind: 'terminal',
        conflictMessage: '部分未确认操作未能自动重放，请检查网络后重试。',
      },
    }))

    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '关闭' })).toBeInTheDocument()
  })
})

describe('CanvasOverlays localized text', () => {
  it('renders draft conflict messages from the catalog with the node name interpolated', () => {
    // 意图：冲突文案按原始结构 + 当前 locale 生成，节点名通过占位符插值而不是拼接中文模板。
    setLocale('zh-CN')
    const base = createFakeController()
    renderOverlays(createFakeController({
      state: {
        ...base.state,
        drafts: {
          'node-deleted': {
            generation: 1,
            updatedAt: 1,
            text: { name: '故事板', markdown: 'x' },
            conflict: { kind: 'TARGET_MISSING', type: 'remote_deleted' },
          },
          'node-stale': {
            generation: 1,
            updatedAt: 1,
            conflict: { kind: 'STALE_NODE', remoteValue: {} },
          },
        },
      },
    }))

    expect(screen.getByText('节点「故事板」已在远端被删除，本地保留未保存草稿')).toBeInTheDocument()
    expect(screen.getByText('节点在远端已被修改，请对比后决定。')).toBeInTheDocument()
  })

  it('keeps every overlay hint in English for the English locale', async () => {
    // 意图：英文 locale 下叠层不留中文；冲突消息、存储告警与按钮都来自 canvas catalog。
    const user = userEvent.setup()
    setLocale('en-US')
    const retryDraftPersist = vi.fn(async () => {})
    const base = createFakeController()
    const controller = createFakeController({
      retryDraftPersist,
      state: {
        ...base.state,
        storageError: 'Local storage is unavailable; drafts stay in this tab only and are lost if you refresh or close it.',
        drafts: {
          'node-deleted': {
            generation: 1,
            updatedAt: 1,
            text: { name: 'Storyboard', markdown: 'x' },
            conflict: { kind: 'TARGET_MISSING', type: 'remote_deleted' },
          },
          'node-referenced': {
            generation: 1,
            updatedAt: 1,
            conflict: {
              kind: 'NODE_REFERENCED',
              conflicts: [{ type: 'NODE_REFERENCED', nodeId: 'node-referenced', referencingNodeIds: ['reference-source'] }],
            },
          },
        },
      },
    })
    const view = renderOverlays(controller)

    expect(screen.getByText('Local storage is unavailable; drafts stay in this tab only and are lost if you refresh or close it.'))
      .toBeInTheDocument()
    expect(screen.getByText('The node “Storyboard” was deleted remotely; the unsaved draft is kept locally'))
      .toBeInTheDocument()
    expect(screen.getByText('Other nodes reference this node. Remove those references first'))
      .toHaveAttribute('title', 'Referencing node IDs: reference-source')
    expect(view.container.textContent).not.toMatch(/[\u4e00-\u9fff]/u)

    await user.click(screen.getByRole('button', { name: 'Retry' }))
    expect(retryDraftPersist).toHaveBeenCalledTimes(1)
    // 两个冲突草稿各有一个救援/放弃入口，另一个提供「使用最新版本重试」
    expect(screen.getAllByRole('button', { name: 'Save as a new node' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: 'Discard draft' })).toHaveLength(2)
    expect(screen.getByRole('button', { name: 'Retry with the latest version' })).toBeInTheDocument()
  })

  it('shows the localized storage hint verbatim instead of a raw exception or message template', () => {
    // 意图：存储不可用直接展示已本地化的提示，不再把底层异常文本塞进模板。
    setLocale('zh-CN')
    const base = createFakeController()
    const view = renderOverlays(createFakeController({
      state: {
        ...base.state,
        storageError: '本机 IndexedDB 不可用，草稿仅保留在当前标签页，刷新或关闭将丢失。',
      },
    }))

    expect(screen.getByText('本机 IndexedDB 不可用，草稿仅保留在当前标签页，刷新或关闭将丢失。')).toBeInTheDocument()
    expect(view.container.textContent).not.toContain('⟦missing:')
  })
})
