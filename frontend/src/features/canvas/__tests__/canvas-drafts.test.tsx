import { describe, expect, it, vi } from 'vitest'
import {
  hasDraftContent,
  isNodeDirty,
  overlayNodeWithDraft,
  removeDraftField,
  type CanvasNodeDraft,
} from '@/features/canvas/canvas-drafts'
import {
  clearCanvasDrafts,
  deleteCanvasDraft,
  loadCanvasDrafts,
  saveCanvasDraft,
} from '@/features/canvas/canvas-draft-storage'
import { onCanvasStorageError } from '@/features/canvas/canvas-local-store'
import { createMockIDBFactory } from './mock-idb'
import type { ResourceNode } from '@/features/canvas/domain'
import type { UUIDString } from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' as UUIDString
const NODE_A = 'aaaaaaaa-1111-4111-8111-111111111111' as UUIDString
const NODE_B = 'aaaaaaaa-2222-4222-8222-222222222222' as UUIDString

function sampleNode(id: UUIDString): ResourceNode {
  return {
    id,
    canvasId: CANVAS_ID,
    name: 'Sample Node',
    transform: { x: 10, y: 20, width: 320, height: 260 },
    groupId: null,
    resources: [{
      id: 'res-1' as UUIDString,
      canvasId: CANVAS_ID,
      ownerNodeId: id,
      resourceIndex: 0,
      blobId: null,
      name: 'text.md',
      textContent: 'original text',
      kind: 'TEXT',
      mediaType: 'text/markdown',
      sizeBytes: 13,
      width: null,
      height: null,
      durationMs: null,
      createdAt: '2026-08-10T00:00:00Z',
    }],
    function: null,
    run: null,
  }
}

describe('Canvas Drafts & IndexedDB Architecture', () => {
  it('saves and loads drafts with editing baseline across sessions', async () => {
    const idbFactory = createMockIDBFactory()
    const draft: CanvasNodeDraft = {
      generation: 1,
      updatedAt: Date.now(),
      text: { name: 'Sample Node', markdown: 'draft text' },
      baseline: { revision: '5', name: 'Sample Node', markdown: 'original text' },
    }

    await saveCanvasDraft(CANVAS_ID, NODE_A, draft, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })

    // 同一编辑会话可以读取到保存的草稿
    const loadedSession1 = await loadCanvasDrafts(CANVAS_ID, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-1',
    })
    expect(loadedSession1[NODE_A]).toBeDefined()
    expect(loadedSession1[NODE_A]?.text?.markdown).toBe('draft text')
    expect(loadedSession1[NODE_A]?.baseline).toEqual({
      revision: '5',
      name: 'Sample Node',
      markdown: 'original text',
    })

    // 不同编辑会话（另一标签页）相互隔离，不相互覆盖
    const loadedSession2 = await loadCanvasDrafts(CANVAS_ID, {
      idbFactory,
      userId: 'user-1',
      editingSessionId: 'tab-2',
    })
    expect(loadedSession2[NODE_A]).toBeUndefined()
  })

  it('notifies storage error listeners when IndexedDB transaction fails', async () => {
    const idbFactory = createMockIDBFactory({ shouldAbortTransaction: true })
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)

    const draft: CanvasNodeDraft = {
      generation: 1,
      updatedAt: Date.now(),
      position: { x: 50, y: 60 },
    }

    await expect(saveCanvasDraft(CANVAS_ID, NODE_A, draft, { idbFactory }))
      .rejects.toThrow()

    expect(onError).toHaveBeenCalled()
    unsubscribe()
  })

  it('protects newer local generation when receiving ACK of an older generation', () => {
    const draftGen1: CanvasNodeDraft = {
      generation: 1,
      updatedAt: 1000,
      text: { markdown: 'edit 1' },
    }

    // 用户在在途请求等待期间键入了新字符，本地递增为 generation 2
    const draftGen2: CanvasNodeDraft = {
      ...draftGen1,
      generation: 2,
      updatedAt: 2000,
      text: { markdown: 'edit 1 + new edit' },
    }

    // 旧请求的 ACK（对应 generation 1）到达，绝不清除较新的输入
    const afterOldAck = removeDraftField(draftGen2, 'text', 1)
    expect(afterOldAck).not.toBeNull()
    expect(afterOldAck?.text?.markdown).toBe('edit 1 + new edit')
    expect(afterOldAck?.generation).toBe(2)

    // 匹配的 ACK（对应 generation 2）到达，正确清除草稿字段
    const afterMatchingAck = removeDraftField(draftGen2, 'text', 2)
    expect(afterMatchingAck).toBeNull()
  })

  it('purely overlays drafts onto server nodes without mutating server snapshot', () => {
    const serverNode = sampleNode(NODE_A)
    const draft: CanvasNodeDraft = {
      generation: 1,
      updatedAt: Date.now(),
      position: { x: 200, y: 300 },
      text: { name: 'Renamed Node', markdown: 'modified text' },
    }

    const projected = overlayNodeWithDraft(serverNode, draft)

    // 投影节点体现草稿变更
    expect(projected.transform.x).toBe(200)
    expect(projected.transform.y).toBe(300)
    expect(projected.name).toBe('Renamed Node')
    expect(projected.resources[0]?.textContent).toBe('modified text')

    // 原服务端节点不可变，完全不受污染
    expect(serverNode.transform.x).toBe(10)
    expect(serverNode.transform.y).toBe(20)
    expect(serverNode.name).toBe('Sample Node')
    expect(serverNode.resources[0]?.textContent).toBe('original text')
  })

  it('manages dirty checks and draft content state', () => {
    const emptyDraft: CanvasNodeDraft = {
      generation: 0,
      updatedAt: Date.now(),
    }
    expect(hasDraftContent(emptyDraft)).toBe(false)
    expect(isNodeDirty(emptyDraft)).toBe(false)

    const dirtyDraft: CanvasNodeDraft = {
      generation: 1,
      updatedAt: Date.now(),
      function: {
        name: 'test-fn',
        args: { prompt: 'generate' },
      },
    }
    expect(hasDraftContent(dirtyDraft)).toBe(true)
    expect(isNodeDirty(dirtyDraft, 'function')).toBe(true)
    expect(isNodeDirty(dirtyDraft, 'text')).toBe(false)
  })

  it('deletes draft and clears session drafts from storage cleanly', async () => {
    const idbFactory = createMockIDBFactory()
    await saveCanvasDraft(CANVAS_ID, NODE_A, { generation: 1, updatedAt: Date.now(), position: { x: 1, y: 1 } }, { idbFactory })
    await saveCanvasDraft(CANVAS_ID, NODE_B, { generation: 1, updatedAt: Date.now(), position: { x: 2, y: 2 } }, { idbFactory })

    let loaded = await loadCanvasDrafts(CANVAS_ID, { idbFactory })
    expect(Object.keys(loaded)).toHaveLength(2)

    await deleteCanvasDraft(CANVAS_ID, NODE_A, { idbFactory })
    loaded = await loadCanvasDrafts(CANVAS_ID, { idbFactory })
    expect(loaded[NODE_A]).toBeUndefined()
    expect(loaded[NODE_B]).toBeDefined()

    await clearCanvasDrafts(CANVAS_ID, { idbFactory })
    loaded = await loadCanvasDrafts(CANVAS_ID, { idbFactory })
    expect(Object.keys(loaded)).toHaveLength(0)
  })

  it('IndexedDB 不可用时草稿回退到内存并明确告警，且删除 / 清空仍然一致', async () => {
    Object.defineProperty(window, 'indexedDB', { configurable: true, writable: true, value: undefined })
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)

    await saveCanvasDraft(CANVAS_ID, NODE_A, { generation: 1, updatedAt: Date.now(), position: { x: 1, y: 1 } })
    await saveCanvasDraft(CANVAS_ID, NODE_B, { generation: 1, updatedAt: Date.now(), position: { x: 2, y: 2 } })

    // 同一会话只告警一次，明确表示未落盘。
    expect(onError).toHaveBeenCalledTimes(1)
    expect((onError.mock.calls[0]?.[0] as Error).name).toBe('CanvasStorageUnavailableError')

    let loaded = await loadCanvasDrafts(CANVAS_ID)
    expect(Object.keys(loaded).sort()).toEqual([NODE_A, NODE_B].sort())

    await deleteCanvasDraft(CANVAS_ID, NODE_A)
    loaded = await loadCanvasDrafts(CANVAS_ID)
    expect(Object.keys(loaded)).toEqual([NODE_B])

    await clearCanvasDrafts(CANVAS_ID)
    loaded = await loadCanvasDrafts(CANVAS_ID)
    expect(Object.keys(loaded)).toHaveLength(0)
    unsubscribe()
  })

  it('打开数据库 / 读取 / 删除失败时 reject 并上报存储异常', async () => {
    const openFailure = createMockIDBFactory({ shouldFailOpen: true })
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)

    await expect(loadCanvasDrafts(CANVAS_ID, { idbFactory: openFailure })).rejects.toThrow()
    await expect(deleteCanvasDraft(CANVAS_ID, NODE_A, { idbFactory: openFailure })).rejects.toThrow()
    await expect(clearCanvasDrafts(CANVAS_ID, { idbFactory: openFailure })).rejects.toThrow()
    expect(onError).toHaveBeenCalled()

    const readFailure = createMockIDBFactory({ shouldFailGetAll: true })
    await expect(loadCanvasDrafts(CANVAS_ID, { idbFactory: readFailure })).rejects.toThrow()

    const deleteFailure = createMockIDBFactory({ shouldFailDelete: true })
    await expect(deleteCanvasDraft(CANVAS_ID, NODE_A, { idbFactory: deleteFailure })).rejects.toThrow()

    const putFailure = createMockIDBFactory({ shouldFailRequest: true })
    await expect(saveCanvasDraft(
      CANVAS_ID,
      NODE_A,
      { generation: 1, updatedAt: Date.now(), position: { x: 1, y: 1 } },
      { idbFactory: putFailure },
    )).rejects.toThrow()
    unsubscribe()
  })

  it('事务 error / abort 与事务创建失败都 reject 并上报，绝不断言已落盘', async () => {
    const onError = vi.fn()
    const unsubscribe = onCanvasStorageError(onError)
    const draft = { generation: 1, updatedAt: Date.now(), position: { x: 1, y: 1 } }

    const txError = createMockIDBFactory({ shouldFailTransaction: true })
    await expect(saveCanvasDraft(CANVAS_ID, NODE_A, draft, { idbFactory: txError })).rejects.toThrow()
    await expect(deleteCanvasDraft(CANVAS_ID, NODE_A, { idbFactory: txError })).rejects.toThrow()
    await expect(clearCanvasDrafts(CANVAS_ID, { idbFactory: txError })).rejects.toThrow()
    // 加载在 request.onsuccess 时已 resolve；随后的 tx.onerror 只负责上报，不应产生未处理拒绝。
    await loadCanvasDrafts(CANVAS_ID, { idbFactory: txError })

    const abortFactory = createMockIDBFactory({ shouldAbortTransaction: true })
    await expect(deleteCanvasDraft(CANVAS_ID, NODE_A, { idbFactory: abortFactory })).rejects.toThrow()
    await expect(clearCanvasDrafts(CANVAS_ID, { idbFactory: abortFactory })).rejects.toThrow()

    const getAllFailure = createMockIDBFactory({ shouldFailGetAll: true })
    await expect(clearCanvasDrafts(CANVAS_ID, { idbFactory: getAllFailure })).rejects.toThrow()

    const createFailure = createMockIDBFactory({ shouldFailTransactionCreate: true })
    await expect(saveCanvasDraft(CANVAS_ID, NODE_A, draft, { idbFactory: createFailure })).rejects.toThrow()
    await expect(deleteCanvasDraft(CANVAS_ID, NODE_A, { idbFactory: createFailure })).rejects.toThrow()
    await expect(clearCanvasDrafts(CANVAS_ID, { idbFactory: createFailure })).rejects.toThrow()
    await expect(loadCanvasDrafts(CANVAS_ID, { idbFactory: createFailure })).rejects.toThrow()

    expect(onError).toHaveBeenCalled()
    // 等待 mock 事务的延迟回调，确保错误分支确实执行。
    await new Promise((resolve) => setTimeout(resolve, 30))
    unsubscribe()
  })
})
