import { describe, expect, it } from 'vitest'
import { patchSnapshotRun } from '@/features/canvas/function-run'
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
