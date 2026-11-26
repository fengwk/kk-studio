import { useMemo, useRef, type Dispatch, type MutableRefObject, type SetStateAction } from 'react'
import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import type { CanvasCommandQueue } from '@/features/canvas/command-queue'
import type { CanvasLocalState } from '@/features/canvas/types'
import type { CanvasSnapshotDTO } from '@/shared/api/generated/contracts'

type DraftPositions = Record<string, { x: number; y: number }>

/**
 * 画布控制器各子 hook 共享的可变作用域：当前画布身份与 epoch、命令队列，以及草稿的三份引用（内存权威、持久化目标、已落盘基线）。
 *
 * 宿主卸载时推进 epoch；任何异步回调写状态前必须确认 epoch 与 canvasId 仍匹配，旧画布的结果绝不写进新画布。
 * 作用域对象在组件生命周期内保持同一引用，可直接放进依赖数组。
 */
export interface CanvasScope {
  setState: Dispatch<SetStateAction<CanvasLocalState>>
  isMountedRef: MutableRefObject<boolean>
  canvasEpochRef: MutableRefObject<number>
  activeCanvasIdRef: MutableRefObject<string>
  queueRef: MutableRefObject<CanvasCommandQueue | null>
  queueCanvasIdRef: MutableRefObject<string | null>
  draftsRef: MutableRefObject<Record<string, CanvasNodeDraft>>
  positionsRef: MutableRefObject<DraftPositions>
  targetDraftsRef: MutableRefObject<Record<string, CanvasNodeDraft>>
  draftPersistChainRef: MutableRefObject<Promise<void>>
  /** 该画布已落盘草稿的持久基线；canvasId 不匹配表示尚未加载完成，禁止按差集删除。 */
  draftBaselineRef: MutableRefObject<{ canvasId: string | null; drafts: Record<string, CanvasNodeDraft> }>
}

export function useCanvasScope(
  setState: Dispatch<SetStateAction<CanvasLocalState>>,
  canvasId: string,
): CanvasScope {
  const isMountedRef = useRef(true)
  const canvasEpochRef = useRef(0)
  const activeCanvasIdRef = useRef(canvasId)
  const queueRef = useRef<CanvasCommandQueue | null>(null)
  const queueCanvasIdRef = useRef<string | null>(null)
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
