import { describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/shared/api/client'
import * as studioService from '@/shared/api/studio-service'
import {
  clearPendingFunctionRun,
  discardPendingFunctionRun,
  inspectLocalPendingRun,
  loadPendingFunctionRun,
  patchSnapshotRun,
  savePendingFunctionRun,
  useCanvasFunctionRun,
  type PendingFunctionRunAttempt,
} from '@/features/canvas/function-run'
import type {
  CanvasFunctionRunDTO,
  CanvasFunctionRunRequestDTO,
  CanvasFunctionVersionDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const NODE_FN = 'aaaaaaaa-0000-4000-8000-000000000003'
const REQUEST_A = 'eeeeeeee-0000-4000-8000-000000000001'
const REQUEST_B = 'eeeeeeee-0000-4000-8000-000000000002'
const REQUEST_C = 'eeeeeeee-0000-4000-8000-000000000003'

/** 目录版本夹具：同 epoch 下定义指纹变化，代表「函数定义已推进」。 */
const FUNCTION_VERSION_A: CanvasFunctionVersionDTO = {
  catalogEpoch: '1',
  definitionFingerprint: 'a'.repeat(64),
}
const FUNCTION_VERSION_B: CanvasFunctionVersionDTO = {
  catalogEpoch: '1',
  definitionFingerprint: 'b'.repeat(64),
}

function cleanupTestStorage(): void {
  window.localStorage.clear()
}

function snapshot(run: CanvasFunctionRunDTO | null): CanvasSnapshotDTO {
  return {
    document: {
      id: CANVAS_ID,
      title: 'Board',
      revision: '4',
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
        name: 'fake-image',
        args: {},
      },
      run,
    }],
    groups: [],
    references: [],
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
    expect(next?.document.revision).toBe('4')
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

  function setupHook(options?: {
    flushFunctionConfig?: (nodeId: UUIDString) => Promise<void>
    resolveFunctionVersion?: (nodeId: UUIDString) => CanvasFunctionVersionDTO | null
  }) {
    const queryClient = new QueryClient()
    const setToast = vi.fn()
    const flushFunctionConfig = options?.flushFunctionConfig ?? vi.fn(async () => undefined)
    const resolveFunctionVersion = options?.resolveFunctionVersion ?? (() => FUNCTION_VERSION_A)
    const { result } = renderHook(() => useCanvasFunctionRun({
      canvasId,
      queryClient,
      setToast,
      flushFunctionConfig,
      resolveFunctionVersion,
    }))
    return { result, setToast, flushFunctionConfig, resolveFunctionVersion, queryClient }
  }

  it('发请求前完整冻结 requestDTO (含 requestId 与点击时目录版本) 到 localStorage，成功后清除', async () => {
    cleanupTestStorage()
    let persistedBeforeSend: ReturnType<typeof loadPendingFunctionRun> = null

    vi.spyOn(studioService, 'startCanvasFunctionRun').mockImplementation(async (_cid, _nid, req) => {
      persistedBeforeSend = loadPendingFunctionRun(canvasId, nodeId)
      expect(persistedBeforeSend?.request).toEqual({
        requestId: req.requestId,
        expectedVersion: FUNCTION_VERSION_A,
      })
      return run(req.requestId, 'RUNNING', 'QUEUED')
    })

    const { result } = setupHook()
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    expect(persistedBeforeSend).not.toBeNull()
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()
  })

  it('错误分类：403 Forbidden 属于不可重试终态，清除 pending 记录', async () => {
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
  })

  it('409 Conflict：清 pending、提示手动刷新函数目录，零自动重提且零对账 GET', async () => {
    cleanupTestStorage()
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun').mockRejectedValueOnce(
      new ApiError('conflict', 409, 'CANVAS_CONFLICT'),
    )
    startSpy.mockClear()
    const getRunSpy = vi.spyOn(studioService, 'getCanvasFunctionRun')
    getRunSpy.mockClear()

    const { result, setToast } = setupHook()
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    // 409 可能来自目录版本围栏，也可能是运行状态冲突：文案不得武断断言「目录已变化」，
    // 但必须要求刷新目录/状态后由用户重新确认。
    const toast = setToast.mock.calls.map(([message]) => String(message)).join('\n')
    expect(toast).toContain('刷新函数目录后重新确认运行')
    expect(toast).not.toContain('目录已变化')
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()
    // 只发了一次请求（无自动重提），也没有走 UNKNOWN 对账
    expect(startSpy).toHaveBeenCalledTimes(1)
    expect(getRunSpy).not.toHaveBeenCalled()
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
    const calls: CanvasFunctionRunRequestDTO[] = []

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
    expect(setToast).toHaveBeenCalledWith('无法保存运行记录，未启动生成')
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
    let resolveFirst!: (val: CanvasFunctionRunDTO) => void
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockReset()
    startSpy.mockImplementation(() => (
      new Promise<CanvasFunctionRunDTO>((res) => {
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

  it('cancel 精确按 requestId 清除 pending（只发 requestId，不受目录版本变化影响），不误删其他 attempt', async () => {
    cleanupTestStorage()
    const reqA = 'aaaaaaaa-1111-4000-8000-000000000001' as UUIDString
    const reqB = 'bbbbbbbb-2222-4000-8000-000000000002' as UUIDString

    savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA, expectedVersion: FUNCTION_VERSION_A },
      basisRequestId: null,
      createdAt: Date.now(),
    })

    const cancelSpy = vi.spyOn(studioService, 'cancelCanvasFunctionRun')
      .mockResolvedValue(run(reqB, 'CANCELLED', 'DONE'))

    // 目录已推进到新版本：cancel 只按 requestId 收敛，不携带任何版本。
    const { result } = setupHook({ resolveFunctionVersion: () => FUNCTION_VERSION_B })
    // 取消 request B，不应删除存储中的 request A
    await act(async () => {
      await result.current.cancelFunctionRun(nodeId, reqB)
    })
    expect(cancelSpy).toHaveBeenCalledWith(canvasId, nodeId, { requestId: reqB })
    expect(loadPendingFunctionRun(canvasId, nodeId)?.request.requestId).toBe(reqA)

    // 取消 request A，精确删除
    cancelSpy.mockResolvedValue(run(reqA, 'CANCELLED', 'DONE'))
    await act(async () => {
      await result.current.cancelFunctionRun(nodeId, reqA)
    })
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()
  })

  it('严格 Schema 校验：非法记录必须 throw（含旧缓存缺 expectedVersion 与坏版本），合法记录才可解析', () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const validRequest = { requestId: crypto.randomUUID(), expectedVersion: FUNCTION_VERSION_A }
    const write = (request: unknown, createdAt: number = Date.now()) => {
      window.localStorage.setItem(storageKey, JSON.stringify({
        canvasId,
        nodeId,
        request,
        basisRequestId: null,
        createdAt,
      }))
    }

    // 1. 非规范 UUID 的 requestId -> 必须 throw
    write({ ...validRequest, requestId: 'not-a-valid-uuid' })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)
    // 原始字节完好保留（是否丢弃由调用方按策略决定）
    expect(window.localStorage.getItem(storageKey)).not.toBeNull()

    // 2. 非小写 canonical UUID (大写) -> 必须 throw
    write({ ...validRequest, requestId: 'A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D' })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 3. request 含额外注入属性 -> 必须 throw
    write({ ...validRequest, extra: 123 })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 4. 旧缓存缺少 expectedVersion -> 必须 throw（绝不被当作可重放请求）
    write({ requestId: crypto.randomUUID() })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 5. expectedVersion.catalogEpoch 前导零 -> 必须 throw
    write({ ...validRequest, expectedVersion: { ...FUNCTION_VERSION_A, catalogEpoch: '01' } })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 6. expectedVersion.catalogEpoch 超出 Java long -> 必须 throw
    write({ ...validRequest, expectedVersion: { ...FUNCTION_VERSION_A, catalogEpoch: '9223372036854775808' } })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 7. expectedVersion.catalogEpoch 为 number（绝不 coerce 成文本）-> 必须 throw
    write({ ...validRequest, expectedVersion: { ...FUNCTION_VERSION_A, catalogEpoch: 1 } })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 8. expectedVersion.definitionFingerprint 大写/长度不足 -> 必须 throw
    write({ ...validRequest, expectedVersion: { ...FUNCTION_VERSION_A, definitionFingerprint: 'A'.repeat(64) } })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 9. expectedVersion 含未知字段 -> 必须 throw
    write({
      ...validRequest,
      expectedVersion: { ...FUNCTION_VERSION_A, definitionName: 'fake-image' },
    })
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 10. createdAt 为非法数值 -> 必须 throw
    write(validRequest, -1)
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 11. canvasId / nodeId 不匹配 -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId: crypto.randomUUID(),
      nodeId,
      request: validRequest,
      basisRequestId: null,
      createdAt: Date.now(),
    }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 12. 非法 JSON 语法 -> 必须 throw
    window.localStorage.setItem(storageKey, 'invalid-json{{{')
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/invalid JSON/)

    // 13. 合法规范结构（含超 Number.MAX_SAFE_INTEGER 的 epoch）-> 原值解析
    const validReqId = crypto.randomUUID() as UUIDString
    const bigEpoch = '9223372036854775807'
    write({ requestId: validReqId, expectedVersion: { ...FUNCTION_VERSION_A, catalogEpoch: bigEpoch } })
    const loaded = loadPendingFunctionRun(canvasId, nodeId)
    expect(loaded?.request.requestId).toBe(validReqId)
    expect(loaded?.request.expectedVersion).toEqual({
      catalogEpoch: bigEpoch,
      definitionFingerprint: FUNCTION_VERSION_A.definitionFingerprint,
    })
  })

  it('坏缓存明确丢弃：损坏记录/旧缓存使本次点击 0 flush 0 POST，并清掉该记录，下一次显式点击才新建请求', async () => {
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

    // 本次点击严格 0 flush、0 POST，并给出明确提示
    expect(flushFunctionConfig).toHaveBeenCalledTimes(0)
    expect(startSpy).toHaveBeenCalledTimes(0)
    expect(setToast).toHaveBeenCalledWith(expect.stringContaining('已丢弃该记录'))
    // 坏缓存已被明确丢弃（不补版本、不同次点击自动启动）
    expect(window.localStorage.getItem(storageKey)).toBeNull()

    // 下一次显式点击：目录可解析，创建全新请求（新 requestId + 当前版本）
    startSpy.mockResolvedValue(run(REQUEST_A, 'RUNNING', 'QUEUED'))
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })
    expect(startSpy).toHaveBeenCalledTimes(1)
    expect(flushFunctionConfig).toHaveBeenCalledTimes(1)
  })

  it('旧缓存（有 requestId 但缺 expectedVersion）同样丢弃且本次点击 0 POST', async () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const legacyRequestId = crypto.randomUUID() as UUIDString
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId,
      nodeId,
      request: { requestId: legacyRequestId },
      basisRequestId: null,
      createdAt: Date.now(),
    }))

    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockClear()
    const { result, setToast } = setupHook()
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    expect(startSpy).toHaveBeenCalledTimes(0)
    expect(window.localStorage.getItem(storageKey)).toBeNull()
    expect(setToast).toHaveBeenCalledWith(expect.stringContaining('已丢弃该记录'))
  })

  it('严格存储对象校验：外层缺字段或含额外字段的未决记录必须被拒（strict schema）', () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const request = { requestId: crypto.randomUUID(), expectedVersion: FUNCTION_VERSION_A }
    const validOuter = {
      canvasId,
      nodeId,
      request,
      basisRequestId: null,
      createdAt: Date.now(),
    }

    // 合法 5 字段 -> 可解析
    window.localStorage.setItem(storageKey, JSON.stringify(validOuter))
    expect(loadPendingFunctionRun(canvasId, nodeId)?.request).toEqual(request)

    // 外层额外注入字段 -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({ ...validOuter, injected: 1 }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 外层缺少 basisRequestId -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId,
      nodeId,
      request,
      createdAt: Date.now(),
    }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 外层缺少 createdAt -> 必须 throw
    window.localStorage.setItem(storageKey, JSON.stringify({
      canvasId,
      nodeId,
      request,
      basisRequestId: null,
    }))
    expect(() => loadPendingFunctionRun(canvasId, nodeId)).toThrow(/schema validation failed/)

    // 保存侧同样拒绝多余字段
    expect(() => savePendingFunctionRun({
      ...validOuter,
      request,
      injected: 1,
    } as unknown as PendingFunctionRunAttempt)).toThrow(/Invalid function run attempt payload/)
  })

  it('坏缓存已被其它记录替换：保留新记录、本次 0 POST，并如实提示未能清理', async () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const corruptRaw = 'corrupt-raw-attempt-payload'
    window.localStorage.setItem(storageKey, corruptRaw)

    // 第 1 次 getItem 是 load 读到的坏记录；第 2 次 getItem（discard 的确认读）已是另一标签页写入的新记录
    const getItemSpy = vi.spyOn(Storage.prototype, 'getItem')
      .mockReturnValueOnce(corruptRaw)
      .mockReturnValueOnce('other-tab-replacement-record')

    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockClear()

    try {
      const { result, setToast } = setupHook()
      await act(async () => {
        await result.current.startFunctionRun(nodeId)
      })

      expect(startSpy).toHaveBeenCalledTimes(0)
      // 不盲删：另一份记录必须原样保留
      expect(window.localStorage.getItem(storageKey)).toBe(corruptRaw)
      expect(result.current.localPendingErrors[nodeId]?.raw).toBe(corruptRaw)
      expect(setToast).toHaveBeenCalledWith(expect.stringContaining('未能安全清理'))
    } finally {
      getItemSpy.mockRestore()
    }
  })

  it('坏缓存清理失败（removeItem 抛错）：保留原记录、本次 0 POST，并如实提示未能清理', async () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const corruptRaw = 'corrupt-raw-attempt-payload'
    window.localStorage.setItem(storageKey, corruptRaw)

    const removeSpy = vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => {
      throw new Error('SecurityError: The operation is insecure.')
    })
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockClear()

    try {
      const { result, setToast } = setupHook()
      await act(async () => {
        await result.current.startFunctionRun(nodeId)
      })

      expect(startSpy).toHaveBeenCalledTimes(0)
      expect(window.localStorage.getItem(storageKey)).toBe(corruptRaw)
      expect(result.current.localPendingErrors[nodeId]?.raw).toBe(corruptRaw)
      expect(setToast).toHaveBeenCalledWith(expect.stringContaining('未能安全清理'))
    } finally {
      removeSpy.mockRestore()
    }
  })

  it('clearPendingFunctionRun 只清除精确匹配 requestId 的记录，不盲目删除未知内容', () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const storedRequestId = crypto.randomUUID() as UUIDString

    savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: storedRequestId, expectedVersion: FUNCTION_VERSION_A },
      basisRequestId: null,
      createdAt: Date.now(),
    })
    // 不同 requestId：保留
    clearPendingFunctionRun(canvasId, nodeId, crypto.randomUUID())
    expect(loadPendingFunctionRun(canvasId, nodeId)?.request.requestId).toBe(storedRequestId)
    // 精确匹配：删除
    clearPendingFunctionRun(canvasId, nodeId, storedRequestId)
    expect(window.localStorage.getItem(storageKey)).toBeNull()

    // 损坏内容不可被盲目删除
    window.localStorage.setItem(storageKey, 'corrupt-raw-attempt-data')
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
        request: { requestId: crypto.randomUUID() as UUIDString, expectedVersion: FUNCTION_VERSION_A },
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
      request: { requestId: reqA, expectedVersion: FUNCTION_VERSION_A },
      basisRequestId: null,
      createdAt: Date.now(),
    })

    // 尝试写入同一个 (canvasId, nodeId) 但 requestId 为 reqB
    expect(() => savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqB, expectedVersion: FUNCTION_VERSION_A },
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

    const initialAttempt: PendingFunctionRunAttempt = {
      canvasId,
      nodeId,
      request: { requestId: reqA, expectedVersion: FUNCTION_VERSION_A },
      basisRequestId: basisA,
      createdAt: timeA,
    }
    savePendingFunctionRun(initialAttempt)

    // 1. 同 requestId 但不同 basisRequestId -> 拒绝
    expect(() => savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA, expectedVersion: FUNCTION_VERSION_A },
      basisRequestId: basisB,
      createdAt: timeA,
    })).toThrow(/Cannot overwrite conflicting or modified pending function run attempt/)

    // 2. 同 requestId 但不同 createdAt -> 拒绝
    expect(() => savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA, expectedVersion: FUNCTION_VERSION_A },
      basisRequestId: basisA,
      createdAt: timeA + 500,
    })).toThrow(/Cannot overwrite conflicting or modified pending function run attempt/)

    // 3. 完全相同内容 -> 允许幂等覆盖保存
    expect(() => savePendingFunctionRun({
      canvasId,
      nodeId,
      request: { requestId: reqA, expectedVersion: FUNCTION_VERSION_A },
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

  it('坏记录可恢复完整流程：面板可读坏记录并在确认后手动清理 -> in-flight 禁 discard -> 再次生成成功发起 POST', async () => {
    cleanupTestStorage()
    const storageKey = `kkstudio.canvas.pending-run:${canvasId}:${nodeId}`
    const corruptRaw = 'corrupt-raw-attempt-payload'
    window.localStorage.setItem(storageKey, corruptRaw)

    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun').mockResolvedValue(
      run('valid-request-id-1234' as UUIDString, 'RUNNING', 'RUNNING'),
    )
    startSpy.mockClear()

    const { result } = setupHook()

    // 1. 面板（未点击）即可读出坏记录，供用户手动确认清理
    const inspected = inspectLocalPendingRun(canvasId, nodeId)
    expect(inspected.error).toBeTruthy()
    expect(inspected.raw).toBe(corruptRaw)

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

  it('点击后 flush 阻塞期间目录换版：仍发送点击前捕获的版本，且不重取定义补旧请求', async () => {
    cleanupTestStorage()
    let releaseFlush: (() => void) | null = null
    const flushFunctionConfig = vi.fn(() => new Promise<void>((resolve) => {
      releaseFlush = resolve
    }))
    const sent: CanvasFunctionRunRequestDTO[] = []
    vi.spyOn(studioService, 'startCanvasFunctionRun').mockImplementation(async (_cid, _nid, req) => {
      sent.push(req)
      return run(req.requestId, 'RUNNING', 'QUEUED')
    })

    // 目录版本在 flush 期间前进：点击时读到 A，点击后回调改为返回 B
    let catalogVersion = FUNCTION_VERSION_A
    const { result, resolveFunctionVersion } = setupHook({
      flushFunctionConfig,
      resolveFunctionVersion: () => catalogVersion,
    })

    let pendingPromise: Promise<void>
    act(() => {
      pendingPromise = result.current.startFunctionRun(nodeId)
    })
    await Promise.resolve()
    // flush 已被调用并阻塞
    expect(flushFunctionConfig).toHaveBeenCalledTimes(1)
    expect(resolveFunctionVersion).toBeDefined()
    catalogVersion = FUNCTION_VERSION_B

    await act(async () => {
      releaseFlush?.()
      await pendingPromise
    })

    expect(sent).toHaveLength(1)
    expect(sent[0]?.expectedVersion).toEqual(FUNCTION_VERSION_A)
  })

  it('点击后 flush 阻塞期间同一 version 对象被原地改写：仍发送点击前的两个字段', async () => {
    cleanupTestStorage()
    let releaseFlush: (() => void) | null = null
    const flushFunctionConfig = vi.fn(() => new Promise<void>((resolve) => {
      releaseFlush = resolve
    }))
    const sent: CanvasFunctionRunRequestDTO[] = []
    const persisted: Array<CanvasFunctionRunRequestDTO | null> = []
    vi.spyOn(studioService, 'startCanvasFunctionRun').mockImplementation(async (_cid, _nid, req) => {
      sent.push(req)
      persisted.push(loadPendingFunctionRun(canvasId, nodeId)?.request ?? null)
      return run(req.requestId, 'RUNNING', 'QUEUED')
    })

    // 目录查询缓存里的同一个 version 对象在 flush 期间被原地改写（不是换数组/换对象）
    const liveVersion = { ...FUNCTION_VERSION_A }
    const { result } = setupHook({
      flushFunctionConfig,
      resolveFunctionVersion: () => liveVersion,
    })

    let pendingPromise: Promise<void>
    act(() => {
      pendingPromise = result.current.startFunctionRun(nodeId)
    })
    await Promise.resolve()
    expect(flushFunctionConfig).toHaveBeenCalledTimes(1)
    liveVersion.catalogEpoch = FUNCTION_VERSION_B.catalogEpoch
    liveVersion.definitionFingerprint = FUNCTION_VERSION_B.definitionFingerprint

    await act(async () => {
      releaseFlush?.()
      await pendingPromise
    })

    expect(sent).toHaveLength(1)
    expect(sent[0]?.expectedVersion).toEqual(FUNCTION_VERSION_A)
    expect(sent[0]?.expectedVersion).not.toBe(liveVersion)
    // 持久化的 pending 请求同样必须是点击时的副本，而不是被改写的目录对象
    expect(persisted[0]?.expectedVersion).toEqual(FUNCTION_VERSION_A)
    expect(persisted[0]?.expectedVersion).not.toBe(liveVersion)
  })

  it('未知结果 reload 精确重放：原样沿用持久化的 requestId 与旧 expectedVersion，不改请求', async () => {
    cleanupTestStorage()
    const frozenRequest: CanvasFunctionRunRequestDTO = {
      requestId: 'cafebabe-0000-4000-8000-000000000001' as UUIDString,
      expectedVersion: FUNCTION_VERSION_A,
    }
    savePendingFunctionRun({
      canvasId,
      nodeId,
      request: frozenRequest,
      basisRequestId: null,
      createdAt: Date.now(),
    })

    const flushFunctionConfig = vi.fn(async () => undefined)
    const sent: CanvasFunctionRunRequestDTO[] = []
    vi.spyOn(studioService, 'startCanvasFunctionRun').mockImplementation(async (_cid, _nid, req) => {
      sent.push(req)
      return run(req.requestId, 'RUNNING', 'QUEUED')
    })

    // 目录已推进到 B：重放绝不能用新版本替换旧请求
    const { result } = setupHook({
      flushFunctionConfig,
      resolveFunctionVersion: () => FUNCTION_VERSION_B,
    })
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    expect(sent).toEqual([frozenRequest])
    // 重放既不 flush 配置，也不遗留 pending
    expect(flushFunctionConfig).not.toHaveBeenCalled()
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()
  })

  it('缺定义（无法解析目录版本）时 0 flush 0 POST', async () => {
    cleanupTestStorage()
    const flushFunctionConfig = vi.fn(async () => undefined)
    const startSpy = vi.spyOn(studioService, 'startCanvasFunctionRun')
    startSpy.mockClear()

    const { result, setToast } = setupHook({
      flushFunctionConfig,
      resolveFunctionVersion: () => null,
    })
    await act(async () => {
      await result.current.startFunctionRun(nodeId)
    })

    expect(flushFunctionConfig).toHaveBeenCalledTimes(0)
    expect(startSpy).toHaveBeenCalledTimes(0)
    expect(loadPendingFunctionRun(canvasId, nodeId)).toBeNull()
    expect(setToast).toHaveBeenCalledWith(expect.stringContaining('目录版本'))
  })
})
