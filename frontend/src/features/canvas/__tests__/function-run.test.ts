import { describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import * as studioService from '@/shared/api/studio-service'
import {
  clearPendingFunctionRun,
  loadPendingFunctionRun,
  patchSnapshotRun,
  useCanvasFunctionRun,
} from '@/features/canvas/function-run'
import type {
  CanvasFunctionRunDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_FN = 'aaaaaaaa-0000-4000-8000-000000000003'
const REQUEST_A = 'eeeeeeee-0000-4000-8000-000000000001'
const REQUEST_B = 'eeeeeeee-0000-4000-8000-000000000002'
const REQUEST_C = 'eeeeeeee-0000-4000-8000-000000000003'

function snapshot(run: CanvasFunctionRunDTO | null): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      version: '4',
      createdAt: '2026-08-10T00:00:00Z',
      updatedAt: '2026-08-10T00:00:00Z',
    },
    nodes: [{
      id: NODE_FN,
      canvasId: CANVAS_ID,
      name: 'Function',
      transform: { x: 0, y: 0, width: 320, height: 260 },
      groupId: null,
      resources: [],
      function: {
        modelKey: 'fake-image',
        configJson: '{}',
      },
      run,
    }],
    groups: [],
    links: [],
  }
}

function run(requestId: UUIDString, status: CanvasFunctionRunDTO['status'], stage: string): CanvasFunctionRunDTO {
  return {
    nodeId: NODE_FN,
    requestId,
    status,
    stage,
    error: null,
    updatedAt: '2026-08-10T00:00:01Z',
  }
}

describe('patchSnapshotRun basis-CAS', () => {
  it('projects the fresh start response when the node has no run yet', () => {
    // 无 run 初始 start：basis=null，缓存仍无 run → 投影。
    const next = patchSnapshotRun(snapshot(null), run(REQUEST_B, 'RUNNING', 'QUEUED'), null)

    expect(next?.nodes[0]?.run).toEqual(run(REQUEST_B, 'RUNNING', 'QUEUED'))
    expect(next?.document.version).toBe('4')
  })

  it('replaces a terminal run A with a fresh start B response (basis was A)', () => {
    // 终态 A 后再 start B：basis=A，缓存仍是 A → 合法新响应 B 可即时投影。
    const next = patchSnapshotRun(
      snapshot(run(REQUEST_A, 'SUCCEEDED', 'SUCCEEDED')),
      run(REQUEST_B, 'RUNNING', 'QUEUED'),
      REQUEST_A,
    )

    expect(next?.nodes[0]?.run).toMatchObject({ requestId: REQUEST_B, status: 'RUNNING' })
  })

  it('advances the same request run', () => {
    // basis=A，缓存仍为 A，A 的后续 stage 可前进。
    const next = patchSnapshotRun(
      snapshot(run(REQUEST_A, 'RUNNING', 'QUEUED')),
      run(REQUEST_A, 'RUNNING', 'CHECKPOINTED'),
      REQUEST_A,
    )

    expect(next?.nodes[0]?.run?.stage).toBe('CHECKPOINTED')
  })

  it('projects the cancel A response while the cache still holds run A', () => {
    // cancel 以被取消的 requestId 为 basis：缓存仍为 A → A 的 CANCELLED 可投影。
    const next = patchSnapshotRun(
      snapshot(run(REQUEST_A, 'RUNNING', 'QUEUED')),
      run(REQUEST_A, 'CANCELLED', 'CANCELLED'),
      REQUEST_A,
    )

    expect(next?.nodes[0]?.run?.status).toBe('CANCELLED')
  })

  it('ignores an in-flight start B response once an authoritative C run arrived', () => {
    // B 在途期间权威 C 到达（basis 是旧的 A 或 null）：C ≠ basis、C ≠ B → B 必须被忽略（原引用不变）。
    const authoritativeC = snapshot(run(REQUEST_C, 'RUNNING', 'QUEUED'))
    const viaOldBasis = patchSnapshotRun(authoritativeC, run(REQUEST_B, 'RUNNING', 'QUEUED'), REQUEST_A)
    const viaNullBasis = patchSnapshotRun(authoritativeC, run(REQUEST_B, 'RUNNING', 'QUEUED'), null)

    expect(viaOldBasis).toBe(authoritativeC)
    expect(viaNullBasis).toBe(authoritativeC)
    expect(authoritativeC.nodes[0]?.run).toMatchObject({ requestId: REQUEST_C, status: 'RUNNING' })
  })

  it('ignores an old cancel A response that cannot overwrite the current run B', () => {
    // 旧 cancel A 迟到达：缓存已是 B，basis=A → 忽略，B 保持权威。
    const next = patchSnapshotRun(
      snapshot(run(REQUEST_B, 'RUNNING', 'QUEUED')),
      run(REQUEST_A, 'CANCELLED', 'CANCELLED'),
      REQUEST_A,
    )

    expect(next?.nodes[0]?.run).toMatchObject({ requestId: REQUEST_B, status: 'RUNNING' })
  })

  it('keeps the snapshot unchanged for identical run facts', () => {
    const current = snapshot(run(REQUEST_A, 'RUNNING', 'QUEUED'))
    const next = patchSnapshotRun(current, run(REQUEST_A, 'RUNNING', 'QUEUED'), REQUEST_A)

    expect(next).toBe(current)
  })

  it('ignores runs for nodes outside the snapshot and empty snapshots', () => {
    const current = snapshot(null)
    const missingNode = patchSnapshotRun(
      current,
      { ...run(REQUEST_A, 'RUNNING', 'QUEUED'), nodeId: 'aaaaaaaa-0000-4000-8000-000000000099' },
      null,
    )

    expect(missingNode).toBe(current)
    expect(patchSnapshotRun(undefined, run(REQUEST_A, 'RUNNING', 'QUEUED'), null)).toBeUndefined()
  })
})

describe('I06: Function Run 幂等 Attempt 全量持久化与重试对账', () => {
  const canvasId = CANVAS_ID as UUIDString
  const nodeId = NODE_FN as UUIDString

  function setupHook() {
    const queryClient = new QueryClient()
    const setToast = vi.fn()
    const flushFunctionConfig = vi.fn(async () => undefined)
    const { result } = renderHook(() => useCanvasFunctionRun({
      canvasId,
      queryClient,
      setToast,
      flushFunctionConfig,
    }))
    return { result, setToast, flushFunctionConfig, queryClient }
  }

  it('发请求前完整冻结 requestDTO (含 requestId) 到 localStorage，成功后清除', async () => {
    clearPendingFunctionRun(canvasId, nodeId)
    let persistedBeforeSend: ReturnType<typeof loadPendingFunctionRun> = null

    vi.spyOn(studioService, 'startCanvasFunctionRun').mockImplementation(async (_cid, _nid, req) => {
      persistedBeforeSend = loadPendingFunctionRun(canvasId, nodeId)
      expect(persistedBeforeSend?.request.requestId).toBe(req.requestId)
      return run(req.requestId, 'RUNNING', 'QUEUED')
    })

    const { result } = setupHook()
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    expect(persistedBeforeSend).not.toBeNull()
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()
  })

  it('错误分类：403 Forbidden 与 409 Conflict 属于不可重试终态，清除 pending 记录', async () => {
    // 403 Forbidden
    clearPendingFunctionRun(canvasId, nodeId)
    vi.spyOn(studioService, 'startCanvasFunctionRun').mockRejectedValueOnce(
      new ApiError('forbidden', 403, 'FORBIDDEN'),
    )
    const { result, setToast } = setupHook()
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(setToast).toHaveBeenCalledWith('forbidden')
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()

    // 409 Conflict
    vi.spyOn(studioService, 'startCanvasFunctionRun').mockRejectedValueOnce(
      new ApiError('conflict', 409, 'CANVAS_CONFLICT'),
    )
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(setToast).toHaveBeenCalledWith('conflict')
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()
  })

  it('错误分类：408 请求超时 与 429 限流属于非终态可重试，保留原 attempt 供复用', async () => {
    clearPendingFunctionRun(canvasId, nodeId)
    vi.spyOn(studioService, 'startCanvasFunctionRun').mockRejectedValueOnce(
      new ApiError('timeout', 408, 'REQUEST_TIMEOUT'),
    )
    vi.spyOn(studioService, 'getCanvasFunctionRun').mockRejectedValueOnce(
      new ApiError('not found', 404),
    )

    const { result } = setupHook()
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    const attemptAfter408 = loadPendingFunctionRun(canvasId, nodeId)
    expect(attemptAfter408).not.toBeNull()

    // 429 Too Many Requests
    vi.spyOn(studioService, 'startCanvasFunctionRun').mockRejectedValueOnce(
      new ApiError('rate limited', 429, 'TOO_MANY_REQUESTS'),
    )
    vi.spyOn(studioService, 'getCanvasFunctionRun').mockRejectedValueOnce(
      new ApiError('not found', 404),
    )
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(loadPendingFunctionRun(canvasId, nodeId)?.request.requestId)
      .toBe(attemptAfter408?.request.requestId)
  })

  it('未知错误/网络中断重试：重试必须采用原冻结 requestId 与 requestDTO，绝不生成新 key', async () => {
    clearPendingFunctionRun(canvasId, nodeId)
    const calls: studioService.CanvasFunctionRunRequestDTO[] = []

    vi.spyOn(studioService, 'startCanvasFunctionRun').mockImplementation(async (_cid, _nid, req) => {
      calls.push(req)
      throw new Error('Network error / connection dropped')
    })
    vi.spyOn(studioService, 'getCanvasFunctionRun').mockRejectedValue(
      new Error('Reconciliation unreachable'),
    )

    const { result } = setupHook()

    // 第一次尝试：失败
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(calls).toHaveLength(1)
    const firstRequestId = calls[0]?.requestId
    expect(firstRequestId).toBeTruthy()

    // 第二次重试（模拟用户点击重试或页面重入重试）：必须原样复用 firstRequestId
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(calls).toHaveLength(2)
    expect(calls[1]?.requestId).toBe(firstRequestId)
    expect(loadPendingFunctionRun(canvasId, nodeId)?.request.requestId).toBe(firstRequestId)
  })
})
