/**
 * Canvas 本地草稿与纯函数层
 *
 * 依据 docs/canvas-project.md §2.4：
 * - 服务端已确认快照 + 待确认操作 + 编辑草稿 = 当前展示。
 * - 草稿保存编辑基线 (baseline) 和本地 generation，位于节点组件之外。
 * - 远端更新只更新确认层，不能重置 dirty 输入、关闭编辑器或改变用户选择。
 * - ACK 只清除对应 operation/generation，旧响应不能清掉后来输入。
 * - 解决内容冲突是新请求，必须保留我的草稿与远端内容，支持合并保存、另存新节点或明确放弃草稿；
 *   绝不用新基线偷偷重试旧内容。
 */

import type {
  CanvasConflictDTO,
  CanvasFunctionDTO,
  CanvasTransformDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import type { CanvasRevision } from '@/shared/api/contracts/base'
import type { ResourceNode } from './domain'

export interface CanvasNodeDraft {
  /** 本地递增世代计数器：ACK 响应仅在 generation 匹配时清除，保护后来追加的本地输入 */
  generation: number
  /** 最近更新时间戳 */
  updatedAt: number
  /** 本地未保存的位置草稿 */
  position?: { x: number; y: number }
  /** 本地未保存的文本与标题草稿 */
  text?: { name?: string; markdown: string }
  /** 本地未保存的通用函数配置草稿 */
  function?: CanvasFunctionDTO | null
  /** 本地未保存的分组归属草稿 */
  groupId?: UUIDString | null
  /** 编辑起点基线（Snapshot 修订号及初始内容），用于冲突检测与对比；解决冲突必须明确对比，绝不可自动重试 */
  baseline?: {
    revision?: CanvasRevision
    name?: string
    markdown?: string
    resourceIds?: UUIDString[]
    function?: CanvasFunctionDTO | null
    groupId?: UUIDString | null
    transform?: CanvasTransformDTO
    position?: { x: number; y: number }
  }
  /** 冲突状态 */
  conflict?: {
    kind?: string
    type?: 'conflict' | 'remote_deleted' | 'error' | string
    message?: string
    remoteValue?: unknown
    conflicts?: CanvasConflictDTO[]
  }
}

/**
 * 判断指定草稿是否包含实质未保存内容。
 */
export function hasDraftContent(draft?: CanvasNodeDraft | null): boolean {
  if (!draft) {
    return false
  }
  return Boolean(
    draft.position ||
    draft.text ||
    draft.function !== undefined ||
    draft.groupId !== undefined,
  )
}

/**
 * 将单个节点的本地未提交草稿叠加到权威节点之上，用于视图展示与当前投影。
 */
export function overlayNodeWithDraft(node: ResourceNode, draft?: CanvasNodeDraft | null): ResourceNode {
  if (!draft) {
    return node
  }
  let transformedNode = node

  // 叠加位置草稿
  if (draft.position) {
    transformedNode = {
      ...transformedNode,
      transform: {
        ...transformedNode.transform,
        x: draft.position.x,
        y: draft.position.y,
      },
    }
  }

  // 叠加文本资源草稿（内容与节点名称）
  if (draft.text) {
    const updatedName = draft.text.name !== undefined ? draft.text.name : transformedNode.name
    let updatedResources = transformedNode.resources

    if (draft.text.markdown !== undefined && updatedResources.length > 0) {
      updatedResources = updatedResources.map((res, index) => {
        if (index === 0 && res.kind === 'TEXT') {
          return {
            ...res,
            name: draft.text?.name ?? res.name,
            textContent: draft.text?.markdown ?? res.textContent,
          }
        }
        return res
      })
    }

    transformedNode = {
      ...transformedNode,
      name: updatedName,
      resources: updatedResources,
    }
  }

  // 叠加函数配置草稿
  if (draft.function !== undefined) {
    transformedNode = {
      ...transformedNode,
      function: draft.function ? {
        name: draft.function.name,
        args: draft.function.args,
        modelKey: draft.function.name,
        configJson: typeof draft.function.args?.configJson === 'string'
          ? (draft.function.args.configJson as string)
          : JSON.stringify(draft.function.args ?? {}),
      } : null,
    }
  }

  // 叠加分组归属草稿
  if (draft.groupId !== undefined) {
    transformedNode = {
      ...transformedNode,
      groupId: draft.groupId,
    }
  }

  return transformedNode
}

/**
 * 从草稿中精准移除已成功确认（ACK）的特定字段。
 * 核心原则：如果 draft.generation > ackGeneration，说明在在途请求等待期间用户又键入了新输入，绝不能清除！
 * 若无其他脏字段和冲突，返回 null 以便从存储中清理。
 */
export function removeDraftField(
  draft: CanvasNodeDraft,
  field: 'position' | 'text' | 'function' | 'groupId',
  ackGeneration?: number,
): CanvasNodeDraft | null {
  if (ackGeneration !== undefined && draft.generation > ackGeneration) {
    return draft
  }

  const next: CanvasNodeDraft = {
    ...draft,
    updatedAt: Date.now(),
  }

  delete next[field]

  if (
    next.position ||
    next.text ||
    next.function !== undefined ||
    next.groupId !== undefined ||
    next.conflict
  ) {
    return next
  }
  return null
}

/**
 * 检查节点在特定字段或全局是否存在未保存的脏输入。
 */
export function isNodeDirty(
  draft?: CanvasNodeDraft | null,
  field?: 'position' | 'text' | 'function' | 'groupId',
): boolean {
  if (!draft) {
    return false
  }
  if (field) {
    return Boolean(draft[field] !== undefined)
  }
  return Boolean(
    draft.position ||
    draft.text ||
    draft.function !== undefined ||
    draft.groupId !== undefined,
  )
}
