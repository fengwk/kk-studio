import type {
  CanvasFunctionConfigDTO,
  CanvasFunctionModelDTO,
  CanvasTransformDTO,
  DecimalString,
} from '@/shared/api/contracts/studio'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'
import type { Group, ResourceNode } from '@/features/canvas/domain'

export type CanvasView = 'library' | 'editor'
export type AddMenuAction =
  | 'image-resource'
  | 'video-resource'
  | 'audio-resource'
  | 'text-resource'
  | 'image-function'
  | 'video-function'
  | 'group'

export type CanvasThreadMessage =
  | { kind: 'user'; text: string }
  | { kind: 'agent'; text: string }

export interface CanvasLinkSelection {
  sourceNodeId: DecimalString
  targetNodeId: DecimalString
}

export type CanvasTextEditorState =
  | { mode: 'create'; nodeId: null; name: string; markdown: string }
  | { mode: 'edit'; nodeId: DecimalString; name: string; markdown: string }

export interface CanvasLocalState {
  view: CanvasView
  canvasId: DecimalString | null
  selectedIds: string[]
  selectedLinks: CanvasLinkSelection[]
  positionDrafts: Record<string, { x: number; y: number }>
  viewport: StoredCanvasViewport
  toast: string | null
  addMenuOpen: boolean
  addMenuIndex: number
  threadOpen: boolean
  agentPrompt: string
  messages: CanvasThreadMessage[]
  uploadProgress: Record<string, number>
  commandPending: boolean
  conflictMessage: string | null
  textEditor: CanvasTextEditorState | null
}

export interface CanvasNodeCallbacks {
  renameNode: (nodeId: DecimalString, name: string) => void
  editTextNode: (node: ResourceNode) => void
  deleteNode: (nodeId: DecimalString) => void
}

export interface PendingFunctionConfig {
  nodeId: DecimalString
  modelKey: string
  config: CanvasFunctionConfigDTO
}

export interface ResourceFlowNodeData extends Record<string, unknown> {
  kind: 'resource'
  node: ResourceNode
  model: CanvasFunctionModelDTO | null
  callbacks: CanvasNodeCallbacks
}

export interface GroupFlowNodeData extends Record<string, unknown> {
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
