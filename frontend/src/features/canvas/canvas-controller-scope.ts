import { useMemo, useRef, type Dispatch, type MutableRefObject, type SetStateAction } from 'react'
import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import type { CanvasCommandQueue } from '@/features/canvas/command-queue'
import type { CanvasLocalState } from '@/features/canvas/types'
import type { CanvasSnapshotDTO, UUIDString } from '@/shared/api/contracts/studio'

type DraftPositions = Record<string, { x: number; y: number }>

/**
 * 画布控制器各子 hook 共享的可变作用域：当前画布身份与 epoch、命令队列，以及草稿的三份引用（内存权威、持久化目标、已落盘基线）。
 *
 * 切换画布、返回列表与卸载都会推进 epoch；任何异步回调写状态前必须确认 epoch 与 canvasId 仍匹配，旧画布的结果绝不写进新画布。
 * 作用域对象在组件生命周期内保持同一引用，可直接放进依赖数组。
 */
export interface CanvasScope {
  setState: Dispatch<SetStateAction<CanvasLocalState>>
  isMountedRef: MutableRefObject<boolean>
  canvasEpochRef: MutableRefObject<number>
  activeCanvasIdRef: MutableRefObject<UUIDString | null>
  queueRef: MutableRefObject<CanvasCommandQueue | null>
  queueCanvasIdRef: MutableRefObject<UUIDString | null>
  draftsRef: MutableRefObject<Record<string, CanvasNodeDraft>>
  positionsRef: MutableRefObject<DraftPositions>
  targetDraftsRef: MutableRefObject<Record<string, CanvasNodeDraft>>
  draftPersistChainRef: MutableRefObject<Promise<void>>
  /** 该画布已落盘草稿的持久基线；canvasId 不匹配表示尚未加载完成，禁止按差集删除。 */
  draftBaselineRef: MutableRefObject<{ canvasId: string | null; drafts: Record<string, CanvasNodeDraft> }>
}

export function useCanvasScope(
  setState: Dispatch<SetStateAction<CanvasLocalState>>,
  initialCanvasId: UUIDString | null,
): CanvasScope {
  const isMountedRef = useRef(true)
  const canvasEpochRef = useRef(0)
  const activeCanvasIdRef = useRef<UUIDString | null>(initialCanvasId)
  const queueRef = useRef<CanvasCommandQueue | null>(null)
  const queueCanvasIdRef = useRef<UUIDString | null>(null)
  const draftsRef = useRef<Record<string, CanvasNodeDraft>>({})
  const positionsRef = useRef<DraftPositions>({})
  const targetDraftsRef = useRef<Record<string, CanvasNodeDraft>>({})
  const draftPersistChainRef = useRef<Promise<void>>(Promise.resolve())
  const draftBaselineRef = useRef<CanvasScope['draftBaselineRef']['current']>({ canvasId: null, drafts: {} })
  return useMemo(() => ({
    setState,
    isMountedRef,
    canvasEpochRef,
    activeCanvasIdRef,
    queueRef,
    queueCanvasIdRef,
    draftsRef,
    positionsRef,
    targetDraftsRef,
    draftPersistChainRef,
    draftBaselineRef,
  }), [setState])
}

/** 进入新的画布身份（或返回列表时的 null）：推进 epoch，丢弃旧队列与全部草稿引用，持久基线回到「未加载」。 */
export function enterCanvasScope(scope: CanvasScope, canvasId: UUIDString | null): void {
  scope.canvasEpochRef.current += 1
  scope.activeCanvasIdRef.current = canvasId
  scope.queueRef.current = null
  scope.queueCanvasIdRef.current = null
  scope.draftsRef.current = {}
  scope.positionsRef.current = {}
  scope.targetDraftsRef.current = {}
  scope.draftBaselineRef.current = { canvasId: null, drafts: {} }
}

/** 以新草稿表同时替换内存权威与持久化目标，并同步到渲染状态。 */
export function publishDrafts(scope: CanvasScope, drafts: Record<string, CanvasNodeDraft>): void {
  scope.draftsRef.current = drafts
  scope.targetDraftsRef.current = drafts
  scope.setState((current) => ({ ...current, drafts }))
}

/** 当前队列中的最新快照；队列尚未建立时退回查询缓存。 */
export function currentCanvasSnapshot(
  scope: CanvasScope,
  fallback: CanvasSnapshotDTO | undefined,
): CanvasSnapshotDTO | undefined {
  return scope.queueRef.current?.currentSnapshot() ?? fallback
}
