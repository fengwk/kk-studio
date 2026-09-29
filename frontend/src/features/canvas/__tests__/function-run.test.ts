import { describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import * as studioService from '@/shared/api/studio-service'
import {
  clearPendingFunctionRun,
  discardPendingFunctionRun,
  loadPendingFunctionRun,
  patchSnapshotRun,
  savePendingFunctionRun,
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

function cleanupTestStorage(): void {
  window.localStorage.clear()
}

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

  function setupHook(options?: { flushFunctionConfig?: (nodeId: UUIDString) => Promise<void> }) {
    const queryClient = new QueryClient()
    const setToast = vi.fn()
    const flushFunctionConfig = options?.flushFunctionConfig ?? vi.fn(async () => undefined)
    const { result } = renderHook(() => useCanvasFunctionRun({
      canvasId,
      queryClient,
      setToast,
      flushFunctionConfig,
    }))
    return { result, setToast, flushFunctionConfig, queryClient }
  }

  it('发请求前完整冻结 requestDTO (含 requestId) 到 localStorage，成功后清除', async () => {
    cleanupTestStorage()
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
    cleanupTestStorage()
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
    cleanupTestStorage()
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
    cleanupTestStorage()
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

  it('存储异常 fail-closed：localStorage.setItem 失败阻断请求，startCanvasFunctionRun 严格调用 0 次', async () => {
    cleanupTestStorage()
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockClear()

    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => {
      throw new Error('QuotaExceededError: storage is full')
    })

    const { result, setToast } = setupHook()
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    expect(startSpy).toHaveBeenCalledTimes(0)
    expect(setToast).toHaveBeenCalledWith('本地运行状态持久化失败，无法发起生成')
    setItemSpy.mockRestore()
  })

  it('未知重试语义：既有未决 attempt 存在时不冲刷新配置，避免破坏待确认请求', async () => {
    cleanupTestStorage()
    const flushFunctionConfig = vi.fn().mockResolvedValue(undefined)
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun').mockRejectedValueOnce(
      new ApiError('timeout', 408),
    )
    vi.spyOn(studioService, 'getCanvasFunctionRun').mockRejectedValueOnce(new Error('unreachable'))

    const { result } = setupHook({ flushFunctionConfig })

    // 第一次调用：无 existing，必须调用 flushFunctionConfig
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(flushFunctionConfig).toHaveBeenCalledTimes(1)
    expect(startSpy).toHaveBeenCalledTimes(1)

    // 第二次调用（重试已存在的 attempt）：绝不可再调用 flushFunctionConfig！
    startSpy.mockResolvedValueOnce(run('req-retry' as UUIDString, 'SUCCEEDED', 'COMPLETED'))
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(flushFunctionConfig).toHaveBeenCalledTimes(1) // 依然是 1 次，未重复调用
  })

  it('并发连点防御：同一节点并发 start 请求互斥锁生效', async () => {
    cleanupTestStorage()
    let resolveFirst: ((val: CanvasFunctionRunDTO) => void) | null = null
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockReset()
    startSpy.mockImplementation(() => (
      new Promise((res) => {
        resolveFirst = res
      })
    ))

    const { result } = setupHook()
    const p1 = result.current.startFunctionRun(nodeId)
    const p2 = result.current.startFunctionRun(nodeId)
    await Promise.resolve()

    expect(startSpy).toHaveBeenCalledTimes(1)
    resolveFirst?.(run('req-1' as UUIDString, 'SUCCEEDED', 'COMPLETED'))
    await act(async () => {
      await Promise.all([p1, p2])
    })
  })

  it('cancel/resolve 精确按 requestId 清除 pending 记录，不误删其他 attempt', async () => {
    cleanupTestStorage()
    const reqA = 'aaaaaaaa-1111-4000-8000-000000000001' as UUIDString
    const reqB = 'bbbbbbbb-2222-4000-8000-000000000002' as UUIDString

    savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA },
      basisRequestId: null,
      createdAt: Date.now(),
    })

    vi.spyOn(studioService, 'cancelCanvasFunctionRun').mockResolvedValue(run(reqB, 'CANCELLED', 'DONE'))

    const { result } = setupHook()
    // 取消 request B，不应删除存储中的 request A
    await act(async () => {
      await result.current.cancelFunctionRun(nodeId, reqB)
    })
    expect(loadPendingFunctionRun(canvasId, nodeId)?.request.requestId).toBe(reqA)

    // 取消 request A，精确删除
    vi.spyOn(studioService, 'cancelCanvasFunctionRun').mockResolvedValue(run(reqA, 'CANCELLED', 'DONE'))
    await act(async () => {
      await result.current.cancelFunctionRun(nodeId, reqA)
    })
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()
  })

  it('严格 Schema 校验与损坏记录 fail-closed：非法记录必须 throw 且保留 raw，绝不能误判为无 pending', () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`

    // 1. 非规范 UUID 的 requestId -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId,
      nodeId,
      request: { requestId: 'not-a-valid-uuid' },
      basisRequestId: null,
      createdAt: Date.now(),
    }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)
    // 原始数据必须完好保留在 storage 中
    expect(window.localStorage.getItem(storageKey)).not.toBeNull()

    // 2. 非小写 canonical UUID (大写) -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId,
      nodeId,
      request: { requestId: 'A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D' },
      basisRequestId: null,
      createdAt: Date.now(),
    }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 3. request 包含额外注入属性 -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId,
      nodeId,
      request: { requestId: crypto.randomUUID(), extra: 123 },
      basisRequestId: null,
      createdAt: Date.now(),
    }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 4. createdAt 为非法数值 -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId,
      nodeId,
      request: { requestId: crypto.randomUUID() },
      basisRequestId: null,
      createdAt: -1,
    }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 5. canvasId / nodeId 不匹配 -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId: crypto.randomUUID(),
      nodeId,
      request: { requestId: crypto.randomUUID() },
      basisRequestId: null,
      createdAt: Date.now(),
    }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 6. 非法 JSON 语法 -> 必须 throw
    window.localStorage.setItem(storageKey, 'invalid-json{{{')
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/invalid JSON/)

    // 7. 合法规范结构 -> 正确解析
    const validReqId = crypto.randomUUID() as UUIDString
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId,
      nodeId,
      request: { requestId: validReqId },
      basisRequestId: null,
      createdAt: Date.now(),
    }))
    const loaded = loadPendingFunctionRun(canvasId, nodeId)
    expect(loaded?.request.requestId).toBe(validReqId)
  })

  it('corrupt 记录安全防御：记录损坏时 startFunctionRun 严格保证 0 flush 0 POST，clear 不删除损坏项', async () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    window.localStorage.setItem(storageKey, 'corrupt-raw-attempt-data')

    const flushFunctionConfig = vi.fn().mockResolvedValue(undefined)
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockClear()

    const { result, setToast } = setupHook({ flushFunctionConfig })
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    // 严格保证：0 flush、0 POST
    expect(flushFunctionConfig).toHaveBeenCalledTimes(0)
    expect(startSpy).toHaveBeenCalledTimes(0)
    expect(setToast).toHaveBeenCalledWith(expect.stringContaining('未决运行记录损坏'))
    // 原始数据完好保留
    expect(window.localStorage.getItem(storageKey)).toBe('corrupt-raw-attempt-data')

    // 尝试 clearPendingFunctionRun：损坏数据不可被盲目删除
    clearPendingFunctionRun(canvasId, nodeId, crypto.randomUUID())
    expect(window.localStorage.getItem(storageKey)).toBe('corrupt-raw-attempt-data')
  })

  it('getItemerror 异常防御：读取存储抛错时 startFunctionRun 严格保证 0 flush 0 POST', async () => {
    cleanupTestStorage()
    const getItemSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementationOnce(() => {
      throw new Error('DatabaseCorruptError in localStorage backend')
    })

    const flushFunctionConfig = vi.fn().mockResolvedValue(undefined)
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockClear()

    const { result, setToast } = setupHook({ flushFunctionConfig })
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    expect(flushFunctionConfig).toHaveBeenCalledTimes(0)
    expect(startSpy).toHaveBeenCalledTimes(0)
    expect(setToast).toHaveBeenCalledWith(expect.stringContaining('存储读取失败'))
    getItemSpy.mockRestore()
  })

  it('localStorage getter 抛错防御（如 Safari 无痕或 SecurityError）：安全阻断 0 flush 0 POST', async () => {
    cleanupTestStorage()
    const originalLocalStorage = window.localStorage
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('SecurityError: The operation is insecure.')
      },
    })

    try {
      const flushFunctionConfig = vi.fn().mockResolvedValue(undefined)
      const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
      startSpy.mockClear()

      const { result, setToast } = setupHook({ flushFunctionConfig })
      await act(async () => {
        await result.current.startFunctionRun(nodeId)
      })

      expect(flushFunctionConfig).toHaveBeenCalledTimes(0)
      expect(startSpy).toHaveBeenCalledTimes(0)
      expect(setToast).toHaveBeenCalledWith(expect.stringContaining('存储读取失败'))
    } finally {
      Object.defineProperty(window, 'localStorage', {
        configurable: true,
        value: originalLocalStorage,
      })
    }
  })

  it('mismatch readback 防御：写入后读回校验不匹配抛错，阻断 POST', () => {
    cleanupTestStorage()
    const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockReturnValue(undefined)
    const getItemSpy = vi.spyOn(Storage.prototype, 'getItem')
      .mockReturnValueOnce(null) // 第 1 次 getItem：检查 rawExisting 为 null
      .mockReturnValueOnce('mismatched-content') // 第 2 次 getItem：读回校验发现不匹配

    try {
      expect(() => savePendingFunctionRun({
        canvasId,
        nodeId,
        request: { requestId: crypto.randomUUID() as UUIDString },
        basisRequestId: null,
        createdAt: Date.now(),
      })).toThrow(/readback mismatch/)
    } finally {
      setItemSpy.mockRestore()
      getItemSpy.mockRestore()
    }
  })

  it('other attempt 互斥守护：同一节点已存在不同 requestId 的 attempt 时禁止覆盖', () => {
    cleanupTestStorage()
    const reqA = crypto.randomUUID() as UUIDString
    const reqB = crypto.randomUUID() as UUIDString

    savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA },
      basisRequestId: null,
      createdAt: Date.now(),
    })

    // 尝试写入同一个 (canvasId, nodeId) 但 requestId 为 reqB
    expect(() => savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqB },
      basisRequestId: null,
      createdAt: Date.now(),
    })).toThrow(/Cannot overwrite conflicting or modified pending function run attempt/)

    // 原 reqA 数据完好保留
    expect(loadPendingFunctionRun(canvasId, nodeId)?.request.requestId).toBe(reqA)
  })

  it('same body guard 校验：同 requestId 但不同 basisRequestId 或 createdAt 一律拒绝覆盖，完全相同方可幂等保存', () => {
    cleanupTestStorage()
    const reqA = crypto.randomUUID() as UUIDString
    const basisA = crypto.randomUUID() as UUIDString
    const basisB = crypto.randomUUID() as UUIDString
    const timeA = 1000000

    const initialAttempt: studioService.PendingFunctionRunAttempt = {
      canvasId,
      nodeId,
      request: { requestId: reqA },
      basisRequestId: basisA,
      createdAt: timeA,
    }
    savePendingFunctionRun(initialAttempt)

    // 1. 同 requestId 但不同 basisRequestId -> 拒绝
    expect(() => savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA },
      basisRequestId: basisB,
      createdAt: timeA,
    })).toThrow(/Cannot overwrite conflicting or modified pending function run attempt/)

    // 2. 同 requestId 但不同 createdAt -> 拒绝
    expect(() => savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA },
      basisRequestId: basisA,
      createdAt: timeA + 500,
    })).toThrow(/Cannot overwrite conflicting or modified pending function run attempt/)

    // 3. 完全相同内容 -> 允许幂等覆盖保存
    expect(() => savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA },
      basisRequestId: basisA,
      createdAt: timeA,
    })).not.toThrow()
  })

  it('discardPendingFunctionRun 严格校验：stale 确认不删新记录，权限受限抛错', () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const rawA = 'raw-attempt-data-version-A'
    const rawB = 'raw-attempt-data-version-B'
    window.localStorage.setItem(storageKey, rawB)

    // 1. stale 确认（expected 为 rawA，实际存储已是 rawB）-> 返回 false，不予删除
    const deletedStale = discardPendingFunctionRun(canvasId, nodeId, rawA)
    expect(deletedStale).toBe(false)
    expect(window.localStorage.getItem(storageKey)).toBe(rawB)

    // 2. matching 确认（expected 为 rawB）-> 返回 true，成功删除
    const deletedMatch = discardPendingFunctionRun(canvasId, nodeId, rawB)
    expect(deletedMatch).toBe(true)
    expect(window.localStorage.getItem(storageKey)).toBeNull()

    // 3. 存储读取权限抛错 -> discard 抛出异常
    const getItemSpy = vi.spyOn(Storage.prototype, 'getItem').mockImplementationOnce(() => {
      throw new Error('SecurityError: The operation is insecure.')
    })
    expect(() => discardPendingFunctionRun(canvasId, nodeId, rawA)).toThrow(/无法读取浏览器存储/)
    getItemSpy.mockRestore()
  })

  it('坏记录可恢复完整流程：坏记录阻断 POST -> in-flight 禁 discard -> 确认清理坏记录 -> 再次生成成功发起 POST', async () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const corruptRaw = 'corrupt-raw-attempt-payload'
    window.localStorage.setItem(storageKey, corruptRaw)

    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun').mockResolvedValue(
      run('valid-request-id-1234' as UUIDString, 'RUNNING', 'RUNNING'),
    )
    startSpy.mockClear()

    const { result, setToast } = setupHook()

    // 1. 尝试启动生成：坏记录阻断，0 次 POST，写入 localPendingErrors
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(startSpy).toHaveBeenCalledTimes(0)
    expect(setToast).toHaveBeenCalledWith(expect.stringContaining('未决运行记录损坏'))
    expect(result.current.localPendingErrors[nodeId]?.raw).toBe(corruptRaw)

    // 2. in-flight 时禁止 discard
    let resolveFirst: ((val: CanvasFunctionRunDTO) => void) | null = null
    startSpy.mockImplementationOnce(() => new Promise((resolve) => {
      resolveFirst = resolve
    }))
    // 临时清理以允许发一个挂起的请求测试 inFlight
    window.localStorage.removeItem(storageKey)
    let pendingPromise: Promise<void>
    act(() => {
      pendingPromise = result.current.startFunctionRun(nodeId)
    })
    // 等待微任务进入 startCanvasFunctionRun 并挂起
    await Promise.resolve()
    await Promise.resolve()
    expect(result.current.isNodeInFlight(nodeId)).toBe(true)
    // 飞行中 discard 必须返回 false
    expect(result.current.discardPendingRun(nodeId, corruptRaw)).toBe(false)

    // 完成飞行中请求
    resolveFirst!(run('valid-request-id-1234' as UUIDString, 'RUNNING', 'RUNNING'))
    await act(async () => {
      await pendingPromise
    })
    expect(result.current.isNodeInFlight(nodeId)).toBe(false)

    // 3. 重新植入坏记录，测试 stale discard 与 matching discard
    window.localStorage.setItem(storageKey, corruptRaw)
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(result.current.localPendingErrors[nodeId]?.raw).toBe(corruptRaw)

    // stale discard：无法删除
    act(() => {
      const res = result.current.discardPendingRun(nodeId, 'stale-different-raw')
      expect(res).toBe(false)
    })
    expect(window.localStorage.getItem(storageKey)).toBe(corruptRaw)

    // matching discard：确认清理
    act(() => {
      const res = result.current.discardPendingRun(nodeId, corruptRaw)
      expect(res).toBe(true)
    })
    expect(window.localStorage.getItem(storageKey)).toBeNull()
    expect(result.current.localPendingErrors[nodeId]).toBeUndefined()

    // 4. 清理后再启动生成：正常发起 POST！
    startSpy.mockClear()
    startSpy.mockResolvedValueOnce(run('new-req-id' as UUIDString, 'RUNNING', 'RUNNING'))
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(startSpy).toHaveBeenCalledTimes(1)
  })
})
