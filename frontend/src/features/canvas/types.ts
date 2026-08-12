import type {
  CanvasFunctionConfigDTO,
  CanvasFunctionModelDTO,
  CanvasTransformDTO,
  UUIDString,
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

export interface CanvasLinkSelection {
  sourceNodeId: UUIDString
  targetNodeId: UUIDString
}

export type CanvasTextEditorState =
  | { mode: 'create'; nodeId: null; name: string; markdown: string }
  | { mode: 'edit'; nodeId: UUIDString; name: string; markdown: string }

export interface CanvasLocalState {
  view: CanvasView
  canvasId: UUIDString | null
  selectedIds: string[]
  selectedLinks: CanvasLinkSelection[]
  positionDrafts: Record<string, { x: number; y: number }>
  viewport: StoredCanvasViewport
  toast: string | null
  addMenuOpen: boolean
  addMenuIndex: number
  threadOpen: boolean
  uploadProgress: Record<string, number>
  commandPending: boolean
  conflictMessage: string | null
  textEditor: CanvasTextEditorState | null
}

export interface CanvasNodeCallbacks {
  renameNode: (nodeId: UUIDString, name: string) => void
  editTextNode: (node: ResourceNode) => void
  deleteNode: (nodeId: UUIDString) => void
}

export interface PendingFunctionConfig {
  nodeId: UUIDString
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
