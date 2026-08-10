/** Canonical bigint values cross the HTTP boundary as base-10 JSON strings. */
export type DecimalString = `${bigint}`

export type CanvasResourceKind = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'TEXT'
export type CanvasFunctionOutputKind = 'IMAGE' | 'VIDEO'
export type CanvasFunctionRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

export interface CanvasDocumentDTO {
  id: DecimalString
  title: string
  graphRevision: DecimalString
  createdAt: string
  updatedAt: string
}

export interface CanvasTransformDTO {
  x: number
  y: number
  width: number
  height: number
}

export interface CanvasResourceDTO {
  id: DecimalString
  canvasId: DecimalString
  kind: CanvasResourceKind
  mediaType: string
  name: string
  size: DecimalString
  textContent: string | null
  metadataJson: string
  createdAt: string
}

export interface CanvasFunctionDTO {
  modelKey: string
  configJson: string
}

export interface CanvasFunctionRunDTO {
  nodeId: DecimalString
  requestId: string
  status: CanvasFunctionRunStatus
  stage: string
  error: string | null
  updatedAt: string
}

export interface CanvasResourceNodeDTO {
  id: DecimalString
  canvasId: DecimalString
  name: string
  transform: CanvasTransformDTO
  groupId: DecimalString | null
  resources: CanvasResourceDTO[]
  function: CanvasFunctionDTO | null
  run: CanvasFunctionRunDTO | null
}

export interface CanvasGroupDTO {
  id: DecimalString
  canvasId: DecimalString
  title: string
  transform: CanvasTransformDTO
}

export interface CanvasLinkDTO {
  canvasId: DecimalString
  sourceNodeId: DecimalString
  targetNodeId: DecimalString
}

export interface CanvasSnapshotDTO {
  document: CanvasDocumentDTO
  nodes: CanvasResourceNodeDTO[]
  groups: CanvasGroupDTO[]
  links: CanvasLinkDTO[]
}

export type PromptSegmentDTO =
  | { type: 'TEXT'; text: string }
  | { type: 'REFERENCE'; nodeId: DecimalString; index: number }

export interface CanvasFunctionConfigDTO {
  prompt: {
    segments: PromptSegmentDTO[]
  }
  parameters: Record<string, string | number>
}

export type CanvasFunctionParameterType = 'ENUM' | 'INTEGER'

export interface CanvasFunctionParameterDefinitionDTO {
  key: string
  label: string
  type: CanvasFunctionParameterType
  required: boolean
  defaultValue: string | number | null
  options: string[]
  min: number | null
  max: number | null
}

export interface CanvasFunctionReferencePolicyDTO {
  allowedKinds: CanvasResourceKind[]
  maxReferences: number
  maxByKind: Partial<Record<CanvasResourceKind, number>>
}

export interface CanvasFunctionModelDTO {
  key: string
  label: string
  outputKind: CanvasFunctionOutputKind
  referencePolicy: CanvasFunctionReferencePolicyDTO
  parameters: CanvasFunctionParameterDefinitionDTO[]
  available: boolean
  unavailableReason: string | null
}

export interface CanvasUploadReservationDTO {
  uploadId: DecimalString
  method: 'PUT'
  url: string
  headers: Record<string, string>
  expiresAt: string
}

export interface CanvasPresignedUrlDTO {
  method: 'GET'
  url: string
  headers: Record<string, string>
  expiresAt: string
}

export interface CreateCanvasRequestDTO {
  title?: string
}

export interface ApplyCanvasCommandsRequestDTO {
  expectedRevision: DecimalString
  commandId: string
  commands: CanvasCommandDTO[]
}

export interface CreateCanvasUploadRequestDTO {
  kind: Exclude<CanvasResourceKind, 'TEXT'>
  filename: string
  mediaType: string
  size: DecimalString
}

export interface CanvasFunctionRunRequestDTO {
  requestId: string
}

export type CanvasCommandDTO =
  | {
      type: 'CREATE_TEXT_NODE'
      name: string
      markdown: string
      transform: CanvasTransformDTO
    }
  | {
      type: 'UPDATE_TEXT_NODE'
      nodeId: DecimalString
      markdown: string
    }
  | {
      type: 'CREATE_RESOURCE_NODE'
      name: string
      resourceIds: DecimalString[]
      transform: CanvasTransformDTO
    }
  | {
      type: 'CREATE_FUNCTION_NODE'
      name: string
      modelKey: string
      configJson: string
      transform: CanvasTransformDTO
    }
  | {
      type: 'UPDATE_FUNCTION'
      nodeId: DecimalString
      modelKey: string
      configJson: string
    }
  | {
      type: 'RENAME_NODE'
      nodeId: DecimalString
      name: string
    }
  | {
      type: 'UPDATE_NODE_TRANSFORMS'
      updates: Array<{ nodeId: DecimalString; transform: CanvasTransformDTO }>
    }
  | {
      type: 'DELETE_NODE'
      nodeId: DecimalString
    }
  | {
      type: 'CREATE_LINK'
      sourceNodeId: DecimalString
      targetNodeId: DecimalString
    }
  | {
      type: 'DELETE_LINK'
      sourceNodeId: DecimalString
      targetNodeId: DecimalString
    }
  | {
      type: 'CREATE_GROUP'
      title: string
      transform: CanvasTransformDTO
      memberNodeIds: DecimalString[]
    }
  | {
      type: 'MOVE_GROUP'
      groupId: DecimalString
      x: number
      y: number
    }
  | {
      type: 'UNGROUP'
      groupId: DecimalString
      memberNodeIds: DecimalString[]
    }
  | {
      type: 'DELETE_GROUP'
      groupId: DecimalString
    }
