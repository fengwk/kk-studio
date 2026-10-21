import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import type { CanvasResourceReference } from '@/features/canvas/types'
import { isCanonicalUuid } from '@/shared/lib/uuid'

/** 从函数参数中读取合法的 canonical 资源引用；非数组或形状不符的元素一律忽略。 */
export function readResourceReferences(args: Record<string, unknown> | undefined): CanvasResourceReference[] {
  const references = args?.references
  if (!Array.isArray(references)) {
    return []
  }
  return references.filter(
    (item): item is CanvasResourceReference =>
      Boolean(
        item
        && typeof item === 'object'
        && (item as CanvasResourceReference).type === 'resource'
        && isCanonicalUuid((item as CanvasResourceReference).nodeId)
        && Number.isInteger((item as CanvasResourceReference).index)
        && (item as CanvasResourceReference).index >= 0,
      ),
  )
}

/** 把新的引用数组写进节点函数草稿，不推进 generation：引用变更随即以命令提交。 */
export function withDraftReferences(
  draft: CanvasNodeDraft,
  references: CanvasResourceReference[],
): CanvasNodeDraft {
  return {
    ...draft,
    updatedAt: Date.now(),
    function: {
      ...draft.function!,
      args: {
        ...draft.function!.args,
        references,
      },
    },
  }
}
