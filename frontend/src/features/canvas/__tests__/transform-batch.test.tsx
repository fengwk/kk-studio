import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  useCanvasTransformBatch,
  type CanvasTransformBatchActions,
} from '@/features/canvas/transform-batch'
import type { CanvasCommandDTO, CanvasSnapshotDTO } from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_A = 'aaaaaaaa-0000-4000-8000-000000000001'
const NODE_B = 'aaaaaaaa-0000-4000-8000-000000000002'
const GROUP_G = 'bbbbbbbb-0000-4000-8000-000000000004'

function snapshot(): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: '0',
      threadId: null,
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [{
      id: NODE_A,
      canvasId: CANVAS_ID,
      name: 'A',
      transform: { x: 0, y: 0, width: 320, height: 260 },
      groupId: null,
      resources: [],
      function: null,
      run: null,
    }, {
      id: NODE_B,
      canvasId: CANVAS_ID,
      name: 'B',
      transform: { x: 60, y: 360, width: 320, height: 260 },
      groupId: GROUP_G,
      resources: [],
      function: null,
      run: null,
    }],
    groups: [{
      id: GROUP_G,
      canvasId: CANVAS_ID,
      title: 'Group',
      // 着色 Body 顶部 = y + 头部 20 + 间隙 6 = 26。
      transform: { x: 0, y: 0, width: 800, height: 700 },
    }],
    links: [],
  }
}

function transform(x: number, y: number) {
  return { x, y, width: 320, height: 260 }
}

interface Harness {
  result: { current: CanvasTransformBatchActions }
  getDrafts: () => Record<string, { x: number; y: number }>
}

function createHarness(
  executeCommands: ReturnType<typeof vi.fn>,
  options?: { useSnapshot?: boolean },
): Harness {
  let positionDrafts: Record<string, { x: number; y: number }> = {}
  const setPositionDrafts = vi.fn((updater) => {
    positionDrafts = updater(positionDrafts)
  })
  const view = renderHook(() => useCanvasTransformBatch({
    snapshot: options?.useSnapshot === false ? undefined : snapshot(),
    executeCommands,
    setPositionDrafts,
  }))
  return {
    result: view.result,
    getDrafts: () => positionDrafts,
  }
}

function firstCommands(executeCommands: ReturnType<typeof vi.fn>): CanvasCommandDTO[] {
  return executeCommands.mock.calls[0]?.[0] as CanvasCommandDTO[]
}

afterEach(() => {
  vi.useRealTimers()
})

describe('useCanvasTransformBatch', () => {
  it('debounces flushes by 180ms and coalesces rapid moves into a single batch', async () => {
    vi.useFakeTimers()
    const executeCommands = vi.fn(async () => undefined)
    const { result } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
      result.current.commitTransforms()
    })
    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(30, 40) }])
      result.current.commitTransforms()
    })
    expect(executeCommands).not.toHaveBeenCalled()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(179)
    })
    expect(executeCommands).not.toHaveBeenCalled()

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1)
    })
    expect(executeCommands).toHaveBeenCalledTimes(1)
    expect(firstCommands(executeCommands)).toEqual([{
      type: 'UPDATE_NODE_TRANSFORMS',
      updates: [{ nodeId: NODE_A, transform: transform(30, 40) }],
    }])
  })

  it('keeps a member inside its group while it still overlaps the group body', async () => {
    const executeCommands = vi.fn(async () => undefined)
    const { result, getDrafts } = createHarness(executeCommands)

    act(() => {
      // x:[760,1080] 与 Body x:[0,800] 仍有正面积交集 → 只发 transform 更新。
      result.current.moveNodes([{ id: NODE_B, kind: 'resource', transform: transform(760, 100) }])
      result.current.flushTransforms()
    })
    await act(async () => {})

    expect(executeCommands).toHaveBeenCalledTimes(1)
    expect(firstCommands(executeCommands)).toEqual([{
      type: 'UPDATE_NODE_TRANSFORMS',
      updates: [{ nodeId: NODE_B, transform: transform(760, 100) }],
    }])
    // 提交成功后清理对应草稿。
    expect(getDrafts()[NODE_B]).toBeUndefined()
  })

  it('ungroups a member only after its dragged bounds fully leave the group body', async () => {
    const executeCommands = vi.fn(async () => undefined)
    const { result } = createHarness(executeCommands)

    act(() => {
      // 左边界恰好贴住 Group 右边界：无正面积交集，必须携带 UNGROUP。
      result.current.moveNodes([{ id: NODE_B, kind: 'resource', transform: transform(800, 100) }])
      result.current.flushTransforms()
    })
    await act(async () => {})

    expect(firstCommands(executeCommands)).toEqual([
      {
        type: 'UPDATE_NODE_TRANSFORMS',
        updates: [{ nodeId: NODE_B, transform: transform(800, 100) }],
      },
      {
        type: 'UNGROUP',
        groupId: GROUP_G,
        memberNodeIds: [NODE_B],
      },
    ])
  })

  it('submits group moves, node transforms and ungroups in one stable batch order', async () => {
    const executeCommands = vi.fn(async () => undefined)
    const { result, getDrafts } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: `group:${GROUP_G}`, kind: 'group', transform: { ...transform(10, 20), width: 800, height: 700 } }])
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(50, 60) }])
      result.current.moveNodes([{ id: NODE_B, kind: 'resource', transform: transform(800, 100) }])
      result.current.flushTransforms()
    })
    await act(async () => {})

    expect(firstCommands(executeCommands)).toEqual([
      { type: 'MOVE_GROUP', groupId: GROUP_G, x: 10, y: 20 },
      {
        type: 'UPDATE_NODE_TRANSFORMS',
        updates: [
          { nodeId: NODE_A, transform: transform(50, 60) },
          { nodeId: NODE_B, transform: transform(800, 100) },
        ],
      },
      {
        type: 'UNGROUP',
        groupId: GROUP_G,
        memberNodeIds: [NODE_B],
      },
    ])
    expect(getDrafts()[`group:${GROUP_G}`]).toBeUndefined()
    expect(getDrafts()[NODE_A]).toBeUndefined()
    expect(getDrafts()[NODE_B]).toBeUndefined()
  })

  it('retains drafts and pending transforms after a failed flush and retries on the next commit', async () => {
    const executeCommands = vi.fn()
      .mockRejectedValueOnce(new Error('save failed'))
      .mockResolvedValue(undefined)
    const { result, getDrafts } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(50, 60) }])
      result.current.flushTransforms()
    })
    await act(async () => {})

    expect(executeCommands).toHaveBeenCalledTimes(1)
    // 失败：草稿与 pending 都保留，等待下次 commit/flush 重试，绝不自动重试。
    expect(getDrafts()[NODE_A]).toEqual({ x: 50, y: 60 })

    act(() => {
      result.current.flushTransforms()
    })
    await act(async () => {})

    expect(executeCommands).toHaveBeenCalledTimes(2)
    expect(firstCommands(executeCommands)).toEqual([{
      type: 'UPDATE_NODE_TRANSFORMS',
      updates: [{ nodeId: NODE_A, transform: transform(50, 60) }],
    }])
    expect(executeCommands.mock.calls[1]?.[0]).toEqual(firstCommands(executeCommands))
    // 重试成功后清理草稿。
    expect(getDrafts()[NODE_A]).toBeUndefined()
  })

  it('honors a commit requested while the current batch is still in flight', async () => {
    vi.useFakeTimers()
    let rejectFirst!: (reason: unknown) => void
    const firstSave = new Promise<never>((_resolve, reject) => {
      rejectFirst = reject
    })
    const executeCommands = vi.fn()
      .mockImplementationOnce(() => firstSave)
      .mockResolvedValue(undefined)
    const { result } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
      result.current.flushTransforms()
    })
    act(() => {
      result.current.moveNodes([{ id: NODE_B, kind: 'resource', transform: transform(100, 100) }])
      result.current.commitTransforms()
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(180)
    })
    expect(executeCommands).toHaveBeenCalledTimes(1)

    await act(async () => {
      rejectFirst(new Error('first batch failed'))
      await firstSave.catch(() => undefined)
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(180)
    })

    expect(executeCommands).toHaveBeenCalledTimes(2)
    const retry = executeCommands.mock.calls[1]?.[0] as CanvasCommandDTO[]
    const update = retry.find((command) => command.type === 'UPDATE_NODE_TRANSFORMS')
    expect(update).toEqual({
      type: 'UPDATE_NODE_TRANSFORMS',
      updates: expect.arrayContaining([
        { nodeId: NODE_A, transform: transform(10, 20) },
        { nodeId: NODE_B, transform: transform(100, 100) },
      ]),
    })
  })

  it('never auto-retries a persistently failing flush', async () => {
    const executeCommands = vi.fn().mockRejectedValue(new Error('persistent failure'))
    const { result } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
      result.current.flushTransforms()
    })
    await act(async () => {})
    expect(executeCommands).toHaveBeenCalledTimes(1)

    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 260))
    })
    expect(executeCommands).toHaveBeenCalledTimes(1)
  })

  it('cleans only drafts matching the submitted positions and drains newer moves', async () => {
    let resolveFirst!: (value: unknown) => void
    const gate = new Promise((resolve) => {
      resolveFirst = resolve
    })
    const executeCommands = vi.fn()
      .mockImplementationOnce(() => gate)
      .mockResolvedValue(undefined)
    const { result, getDrafts } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
      result.current.flushTransforms()
    })
    // 提交在途：用户继续拖动 NODE_A（期间更新的草稿不得被清理）。
    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(30, 40) }])
      result.current.commitTransforms()
    })

    await act(async () => {
      resolveFirst(undefined)
      await gate
    })
    expect(executeCommands).toHaveBeenCalledTimes(1)
    expect(getDrafts()[NODE_A]).toEqual({ x: 30, y: 40 })

    // 成功后会补发在途期间累积的新批次（drain）。
    await waitFor(() => expect(executeCommands).toHaveBeenCalledTimes(2))
    expect(executeCommands.mock.calls[1]?.[0]).toEqual([{
      type: 'UPDATE_NODE_TRANSFORMS',
      updates: [{ nodeId: NODE_A, transform: transform(30, 40) }],
    }])
    await waitFor(() => expect(getDrafts()[NODE_A]).toBeUndefined())
  })

  it('starts a new canvas flush immediately while an old batch never settles', async () => {
    let resolveOld!: (value: unknown) => void
    const oldGate = new Promise((resolve) => {
      resolveOld = resolve
    })
    const executeCommands = vi.fn()
      .mockImplementationOnce(() => oldGate)
      .mockResolvedValue(undefined)
    const { result, getDrafts } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
      result.current.flushTransforms()
    })
    expect(executeCommands).toHaveBeenCalledTimes(1)

    // 切换画布：旧批次仍未返回，新 canvas 的提交必须立即放行。
    act(() => {
      result.current.reset()
    })
    act(() => {
      result.current.moveNodes([{ id: NODE_B, kind: 'resource', transform: transform(30, 40) }])
      result.current.commitTransforms()
    })
    await waitFor(() => expect(executeCommands).toHaveBeenCalledTimes(2))
    expect(executeCommands.mock.calls[1]?.[0]).toEqual([{
      type: 'UPDATE_NODE_TRANSFORMS',
      updates: [{ nodeId: NODE_B, transform: transform(30, 40) }],
    }])
    await waitFor(() => expect(getDrafts()[NODE_B]).toBeUndefined())

    // 旧批次迟到成功：不得影响新 owner（不释放占用、不调度、不提交第三个批次）。
    await act(async () => {
      resolveOld(undefined)
      await oldGate
    })
    expect(executeCommands).toHaveBeenCalledTimes(2)
    expect(getDrafts()[NODE_B]).toBeUndefined()
  })

  it('does not restore a rejected old batch into the new canvas owner', async () => {
    let rejectOld!: (value: unknown) => void
    const oldGate = new Promise((_resolve, reject) => {
      rejectOld = reject
    })
    const executeCommands = vi.fn()
      .mockImplementationOnce(() => oldGate)
      .mockResolvedValue(undefined)
    const { result, getDrafts } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
      result.current.flushTransforms()
    })
    act(() => {
      result.current.reset()
    })
    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(50, 60) }])
      result.current.flushTransforms()
    })
    await act(async () => {})
    expect(executeCommands).toHaveBeenCalledTimes(2)

    // 旧批次迟到失败：跨 epoch 不得恢复 NODE_A 到旧位置，也不得触发第三个命令。
    await act(async () => {
      rejectOld(new Error('late failure'))
    })
    await act(async () => {})
    expect(executeCommands).toHaveBeenCalledTimes(2)
    expect(executeCommands.mock.calls[1]?.[0]).toEqual([{
      type: 'UPDATE_NODE_TRANSFORMS',
      updates: [{ nodeId: NODE_A, transform: transform(50, 60) }],
    }])
    expect(getDrafts()[NODE_A]).toBeUndefined()
  })

  it('does not restore an outdated ungroup over a move-back decision made during flight', async () => {
    let rejectOld!: (value: unknown) => void
    const oldGate = new Promise((_resolve, reject) => {
      rejectOld = reject
    })
    const executeCommands = vi.fn()
      .mockImplementationOnce(() => oldGate)
      .mockResolvedValue(undefined)
    const { result, getDrafts } = createHarness(executeCommands)

    act(() => {
      // 批次 A：拖出组外 → 携带 UNGROUP。
      result.current.moveNodes([{ id: NODE_B, kind: 'resource', transform: transform(800, 100) }])
      result.current.flushTransforms()
    })
    expect(firstCommands(executeCommands)).toEqual([
      {
        type: 'UPDATE_NODE_TRANSFORMS',
        updates: [{ nodeId: NODE_B, transform: transform(800, 100) }],
      },
      {
        type: 'UNGROUP',
        groupId: GROUP_G,
        memberNodeIds: [NODE_B],
      },
    ])

    // 在途期间拖回组内：新决策为「不 ungroup」，草稿保留。
    act(() => {
      result.current.moveNodes([{ id: NODE_B, kind: 'resource', transform: transform(100, 50) }])
      result.current.commitTransforms()
    })
    expect(getDrafts()[NODE_B]).toEqual({ x: 100, y: 50 })

    // 旧批次失败：不得把过期的 UNGROUP 决策覆盖到「拖回组内」的 null 决策上。
    await act(async () => {
      rejectOld(new Error('late failure'))
    })
    await act(async () => {})
    expect(getDrafts()[NODE_B]).toEqual({ x: 100, y: 50 })

    // 重试只含新的 transform，绝不携带旧批次的 UNGROUP。
    act(() => {
      result.current.flushTransforms()
    })
    await act(async () => {})
    expect(executeCommands).toHaveBeenCalledTimes(2)
    expect(executeCommands.mock.calls[1]?.[0]).toEqual([{
      type: 'UPDATE_NODE_TRANSFORMS',
      updates: [{ nodeId: NODE_B, transform: transform(100, 50) }],
    }])
    expect(getDrafts()[NODE_B]).toBeUndefined()
  })

  it('reset cancels the pending debounce and clears pending transforms', async () => {
    vi.useFakeTimers()
    const executeCommands = vi.fn(async () => undefined)
    const { result } = createHarness(executeCommands)

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
      result.current.commitTransforms()
    })
    act(() => {
      result.current.reset()
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500)
    })
    expect(executeCommands).not.toHaveBeenCalled()

    // pending 已清空：后续 commit 不再产生任何命令。
    act(() => {
      result.current.commitTransforms()
    })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500)
    })
    expect(executeCommands).not.toHaveBeenCalled()
  })

  it('clears the scheduled flush on unmount', () => {
    vi.useFakeTimers()
    const executeCommands = vi.fn(async () => undefined)
    const { result, unmount } = renderHook(() => useCanvasTransformBatch({
      snapshot: snapshot(),
      executeCommands,
      setPositionDrafts: () => undefined,
    }))

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
      result.current.commitTransforms()
    })
    unmount()
    act(() => {
      vi.advanceTimersByTime(500)
    })
    expect(executeCommands).not.toHaveBeenCalled()
  })

  it('ignores moves before the snapshot is available', async () => {
    const executeCommands = vi.fn(async () => undefined)
    const { result, getDrafts } = createHarness(executeCommands, { useSnapshot: false })

    act(() => {
      result.current.moveNodes([{ id: NODE_A, kind: 'resource', transform: transform(10, 20) }])
    })
    expect(getDrafts()[NODE_A]).toBeUndefined()
    act(() => {
      result.current.flushTransforms()
    })
    expect(executeCommands).not.toHaveBeenCalled()
  })
})
