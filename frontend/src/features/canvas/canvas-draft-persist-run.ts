import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import {
  deleteCanvasDraft,
  saveCanvasDraft,
} from '@/features/canvas/canvas-draft-storage'

/** 一轮落盘的结果：完成（含无差集）、因画布已切换而放弃、或首条失败。 */
type DraftPersistOutcome = 'completed' | 'abandoned' | 'failed'

/** 持久基线与本轮目标草稿的差集：只有引用不同或已消失的条目需要落盘。 */
interface DraftDiff {
  toSave: Array<[string, CanvasNodeDraft]>
  toDelete: string[]
}

function diffDrafts(
  baselineDrafts: Record<string, CanvasNodeDraft>,
  targetDrafts: Record<string, CanvasNodeDraft>,
): DraftDiff {
  const toSave: Array<[string, CanvasNodeDraft]> = []
  for (const [nodeId, draft] of Object.entries(targetDrafts)) {
    if (baselineDrafts[nodeId] !== draft) {
      toSave.push([nodeId, draft])
    }
  }
  const toDelete = Object.keys(baselineDrafts).filter((nodeId) => !(nodeId in targetDrafts))
  return { toSave, toDelete }
}

export interface DraftPersistenceRun {
  /** 落盘目标画布。 */
  canvasId: string
  viewSlot: number
  /** 当前任务是否仍属同一挂载实例与 epoch；false 时后续写入必须放弃。 */
  isCurrentTask: () => boolean
  baselineDrafts: Record<string, CanvasNodeDraft>
  targetDrafts: Record<string, CanvasNodeDraft>
  /** 存在差集、即将开始写入时调用一次。 */
  onWriteStarted: () => void
  onError: (error: unknown) => void
}

export interface DraftPersistResult {
  /** 本轮是否存在需要落盘的差集。 */
  hasChanges: boolean
  outcome: DraftPersistOutcome
}

/**
 * 把基线与目标草稿的差集串行写入 IndexedDB，成功一项就推进基线。
 *
 * 每个 await 之后都重新确认任务仍属同一 epoch/canvas：画布已切换时立即放弃本轮回调，
 * 绝不把旧画布的写入推进到新画布的基线里。首次失败即中断（后续任务会重新计算差集）。
 */
export async function persistDraftDiff(run: DraftPersistenceRun): Promise<DraftPersistResult> {
  const { canvasId, viewSlot, isCurrentTask, baselineDrafts, targetDrafts, onWriteStarted, onError } = run
  const { toSave, toDelete } = diffDrafts(baselineDrafts, targetDrafts)
  const hasChanges = toSave.length > 0 || toDelete.length > 0
  if (!hasChanges) {
    return { hasChanges, outcome: 'completed' }
  }

  onWriteStarted()
  const saved = await writeDrafts(canvasId, viewSlot, toSave, baselineDrafts, isCurrentTask, onError)
  if (saved !== 'completed') {
    return { hasChanges, outcome: saved }
  }
  const removed = await removeDrafts(canvasId, viewSlot, toDelete, baselineDrafts, isCurrentTask, onError)
  return { hasChanges, outcome: removed }
}

/** 逐条写入新草稿；已成功的条目立刻推进基线，避免重复写入。 */
async function writeDrafts(
  canvasId: string,
  viewSlot: number,
  toSave: Array<[string, CanvasNodeDraft]>,
  baselineDrafts: Record<string, CanvasNodeDraft>,
  isCurrentTask: () => boolean,
  onError: (error: unknown) => void,
): Promise<DraftPersistOutcome> {
  for (const [nodeId, draft] of toSave) {
    if (!isCurrentTask()) {
      return 'abandoned'
    }
    try {
      await saveCanvasDraft(canvasId, nodeId, draft, { viewSlot })
      baselineDrafts[nodeId] = draft
    } catch (err) {
      onError(err)
      return 'failed'
    }
  }
  return 'completed'
}

/** 逐条删除基线中已消失的草稿。 */
async function removeDrafts(
  canvasId: string,
  viewSlot: number,
  toDelete: string[],
  baselineDrafts: Record<string, CanvasNodeDraft>,
  isCurrentTask: () => boolean,
  onError: (error: unknown) => void,
): Promise<DraftPersistOutcome> {
  for (const nodeId of toDelete) {
    if (!isCurrentTask()) {
      return 'abandoned'
    }
    try {
      await deleteCanvasDraft(canvasId, nodeId, { viewSlot })
      delete baselineDrafts[nodeId]
    } catch (err) {
      onError(err)
      return 'failed'
    }
  }
  return 'completed'
}
