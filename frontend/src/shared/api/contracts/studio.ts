import type { CanvasVersion } from '@/shared/api/contracts/base'

/**
 * Canonical UUID ids cross the HTTP boundary as lowercase dashed strings.
 * Canvas/Resource/Group/Upload 实体 id 与所有命令 id 都由客户端或服务端
 * 以 RFC4122 UUID 形式生成（shape-only 校验，不限定 version/variant 位）。
 */
export type UUIDString = `${string}-${string}-${string}-${string}-${string}`

export type CanvasResourceKind = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'TEXT'
export type CanvasFunctionOutputKind = 'IMAGE' | 'VIDEO'
export type CanvasFunctionRunStatus = 'READY' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

/**
 * Canvas 聚合的持久化头。version 是单调递增的 graph 版本，也是
 * command expected 游标与 patch base/version 的公共坐标系；wire 上是
 * canonical 非负十进制字符串（Java long，数据库仍为 bigint），
 * 客户端绝不转换为 JS number。Agent Session/Thread 是独立的 owner 事实，
 * 不属于 Canvas graph document。
 */
export interface CanvasDocumentDTO {
  id: UUIDString
  title: string
  version: CanvasVersion
  createdAt: string
  updatedAt: string
}

export interface CanvasTransformDTO {
  x: number
  y: number
  width: number
  height: number
}

/**
 * Canvas 资源的持久投影。immutable 资源行包含 ownerNodeId/resourceIndex，
 * 与所属节点一起 upsert；kind 由 API 按 mediaType 派生，宽高/时长来自
 * 服务端媒体校验（可为 null，TEXT 资源没有 blob）。
 * 契约绝不暴露 bucket/key/URI，客户端也不能提交对象 key。
 */
export interface CanvasResourceDTO {
  id: UUIDString
  canvasId: UUIDString
  ownerNodeId: UUIDString
  resourceIndex: number
  /** TEXT 资源内容在 textContent 中，无对象存储 blob；其余资源引用共享存储的持久 blob。 */
  blobId: UUIDString | null
  name: string
  textContent: string | null
  kind: CanvasResourceKind
  mediaType: string | null
  /** wire 是 Java long 的十进制字符串或 null，由 studio-codec 归一化为 number|null。 */
  sizeBytes: number | null
  width: number | null
  height: number | null
  /** wire 是 Java long 的十进制字符串或 null，由 studio-codec 归一化为 number|null。 */
  durationMs: number | null
  createdAt: string
}

export interface CanvasFunctionDTO {
  modelKey: string
  configJson: string
}

export interface CanvasFunctionRunDTO {
  nodeId: UUIDString
  requestId: UUIDString
  status: CanvasFunctionRunStatus
  stage: string
  error: string | null
  updatedAt: string
}

/**
 * 完整的资源节点投影：resources/function/run 始终内嵌在 Node DTO 中，
 * 不单独参与 patch。
 */
export interface CanvasResourceNodeDTO {
  id: UUIDString
  canvasId: UUIDString
  name: string
  transform: CanvasTransformDTO
  groupId: UUIDString | null
  resources: CanvasResourceDTO[]
  function: CanvasFunctionDTO | null
  run: CanvasFunctionRunDTO | null
}

export interface CanvasGroupDTO {
  id: UUIDString
  canvasId: UUIDString
  title: string
  transform: CanvasTransformDTO
}

export interface CanvasLinkDTO {
  canvasId: UUIDString
  sourceNodeId: UUIDString
  targetNodeId: UUIDString
}

/** 权威全量投影：初始加载、gap 恢复或 resync 时整体替换客户端状态。 */
export interface CanvasSnapshotDTO {
  document: CanvasDocumentDTO
  nodes: CanvasResourceNodeDTO[]
  groups: CanvasGroupDTO[]
  links: CanvasLinkDTO[]
}

export type CanvasGroupPatchDTO =
  | { op: 'UPSERT'; group: CanvasGroupDTO }
  | { op: 'REMOVE'; groupId: UUIDString }

export type CanvasNodePatchDTO =
  | { op: 'UPSERT'; node: CanvasResourceNodeDTO }
  | { op: 'REMOVE'; nodeId: UUIDString }

export type CanvasLinkPatchDTO =
  | { op: 'UPSERT'; link: CanvasLinkDTO }
  | { op: 'REMOVE'; sourceNodeId: UUIDString; targetNodeId: UUIDString }

/**
 * 幂等 graph patch：仅作为 command 响应由发起命令的窗口本地应用。
 * baseVersion -> version 表示一次连续前进；version <= 客户端当前版本时忽略，
 * baseVersion != 客户端当前版本时改读权威 Snapshot。
 * 两个版本都是 canonical 非负十进制字符串（Java long wire）。
 */
export interface CanvasPatchDTO {
  baseVersion: CanvasVersion
  version: CanvasVersion
  groups: CanvasGroupPatchDTO[]
  nodes: CanvasNodePatchDTO[]
  links: CanvasLinkPatchDTO[]
}

/**
 * 应用事件 WebSocket `version` payload：version 前进提示，客户端随后读取
 * 权威 Snapshot。'resync' 事件无 payload，同样要求全量快照。
 * version 是 canonical 非负十进制字符串；数字/前导零/负数/畸形 payload 一律忽略。
 */
export interface CanvasVersionEventDTO {
  version: CanvasVersion
}

export type PromptSegmentDTO =
  | { type: 'TEXT'; text: string }
  | { type: 'REFERENCE'; nodeId: UUIDString; index: number }

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

export interface CanvasPresignedUrlDTO {
  method: 'GET'
  url: string
  headers: Record<string, string>
  expiresAt: string
}

export interface CreateCanvasRequestDTO {
  title?: string
}

/**
 * 命令批请求。expectedVersion 是精确的 graph 版本 CAS 游标（canonical
 * 非负十进制字符串，Java long wire）；idempotencyKey 是整批的幂等键（客户端 UUID）。
 * 创建类命令额外携带客户端生成的实体 UUID（nodeId/groupId），无时间戳回退；
 * 资源上传句柄由共享存储服务生成，命令只引用 uploadIds。
 */
export interface ApplyCanvasCommandsRequestDTO {
  expectedVersion: CanvasVersion
  idempotencyKey: UUIDString
  commands: CanvasCommandDTO[]
}

export interface CanvasFunctionRunRequestDTO {
  requestId: UUIDString
}

export type CanvasCommandDTO =
  | {
      type: 'CREATE_TEXT_NODE'
      nodeId: UUIDString
      name: string
      markdown: string
      transform: CanvasTransformDTO
    }
  | {
      type: 'UPDATE_TEXT_NODE'
      nodeId: UUIDString
      markdown: string
    }
  | {
      type: 'CREATE_RESOURCE_NODE'
      nodeId: UUIDString
      name: string
      /** 已 complete 的共享存储 upload 句柄（服务端生成，非 canvas-scoped）。 */
      uploadIds: UUIDString[]
      transform: CanvasTransformDTO
    }
  | {
      type: 'CREATE_FUNCTION_NODE'
      nodeId: UUIDString
      name: string
      modelKey: string
      configJson: string
      transform: CanvasTransformDTO
    }
  | {
      type: 'UPDATE_FUNCTION'
      nodeId: UUIDString
      modelKey: string
      configJson: string
    }
  | {
      type: 'RENAME_NODE'
      nodeId: UUIDString
      name: string
    }
  | {
      type: 'UPDATE_NODE_TRANSFORMS'
      updates: Array<{ nodeId: UUIDString; transform: CanvasTransformDTO }>
    }
  | {
      type: 'DELETE_NODE'
      nodeId: UUIDString
    }
  | {
      type: 'CREATE_LINK'
      sourceNodeId: UUIDString
      targetNodeId: UUIDString
    }
  | {
      type: 'DELETE_LINK'
      sourceNodeId: UUIDString
      targetNodeId: UUIDString
    }
  | {
      type: 'CREATE_GROUP'
      groupId: UUIDString
      title: string
      transform: CanvasTransformDTO
      memberNodeIds: UUIDString[]
    }
  | {
      type: 'MOVE_GROUP'
      groupId: UUIDString
      x: number
      y: number
    }
  | {
      type: 'UNGROUP'
      groupId: UUIDString
      memberNodeIds: UUIDString[]
    }
  | {
      type: 'DELETE_GROUP'
      groupId: UUIDString
    }
  | {
      type: 'RENAME_GROUP'
      groupId: UUIDString
      title: string
    }
