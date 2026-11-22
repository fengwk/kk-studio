import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'
import type { Group, ResourceNode } from '@/features/canvas/domain'

import type { CanvasNodeDraft } from '@/features/canvas/canvas-drafts'
import type { TranslationKey } from '@/shared/i18n'
import type {
  CanvasFunctionDefinitionDTO,
  CanvasTransformDTO,
} from '@/shared/api/generated/contracts'

export type CanvasView = 'library' | 'editor'
/** 冲突横幅是否提供整体重试入口，由产生冲突的结构决定，不靠文案字面量匹配。 */
export type CanvasConflictKind = 'recoverable' | 'terminal'
export type AddMenuAction =
  | 'image-resource'
  | 'video-resource'
  | 'audio-resource'
  | 'text-resource'
  | 'image-function'
  | 'video-function'

export interface CanvasLinkSelection {
  sourceNodeId: string
  targetNodeId: string
}

type CanvasTextEditorState =
  | { mode: 'create'; nodeId: null; name: string; markdown: string }
  | { mode: 'edit'; nodeId: string; name: string; markdown: string }

export interface CanvasLocalState {
  view: CanvasView
  canvasId: string | null
  selectedIds: string[]
  selectedLinks: CanvasLinkSelection[]
  positionDrafts: Record<string, { x: number; y: number }>
  drafts: Record<string, CanvasNodeDraft>
  storageError: string | null
  viewport: StoredCanvasViewport
  toast: string | null
  addMenuOpen: boolean
  addMenuIndex: number
  uploadProgress: Record<string, number>
  commandPending: boolean
  draftPersistPending: boolean
  /** 当前冲突横幅的结构化分类：recoverable 可原地重试恢复，terminal 只能就地处理冲突草稿。 */
  conflictKind: CanvasConflictKind | null
  conflictMessage: string | null
  textEditor: CanvasTextEditorState | null
}

export interface CanvasNodeCallbacks {
  /** TEXT 节点进入内嵌编辑面板（双击或右键 Edit 动作共用）。 */
  editTextNode: (node: ResourceNode) => void
}

export interface CanvasResourceReference {
  type: 'resource'
  nodeId: string
  index: number
}

export interface CanvasFunctionConfig {
  prompt?: string
  references: CanvasResourceReference[]
  parameters: Record<string, unknown>
  rawArgs?: Record<string, unknown>
  /** 进入完整 JSON 模式的原因（i18n key），由面板按当前 locale 渲染。 */
  rawErrorKey?: TranslationKey
}

export interface PendingFunctionConfig {
  nodeId: string
  functionName: string
  config: CanvasFunctionConfig
}

interface ResourceFlowNodeData extends Record<string, unknown> {
  kind: 'resource'
  node: ResourceNode
  model: CanvasFunctionDefinitionDTO | null
  callbacks: CanvasNodeCallbacks
}

interface GroupFlowNodeData extends Record<string, unknown> {
  kind: 'group'
  group: Group
}

export type CanvasFlowNodeData = ResourceFlowNodeData | GroupFlowNodeData

export interface CanvasPositionUpdate {
  id: string
  transform: CanvasTransformDTO
  kind: 'resource' | 'group'
}

export interface StageMetrics {
  width: number
  height: number
  dockTop: number
}
