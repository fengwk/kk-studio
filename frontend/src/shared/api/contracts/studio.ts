import type { CanvasRevision } from '@/shared/api/contracts/base'

/**
 * Canonical UUID ids cross the HTTP boundary as lowercase dashed strings.
 * Canvas/Resource/Group/Upload 实体 id 与所有命令 id 都由客户端或服务端
 * 以 RFC4122 UUID 形式生成（shape-only 校验，不限定 version/variant 位）。
 */
export type UUIDString = `${string}-${string}-${string}-${string}-${string}`

export type CanvasResourceKind = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'TEXT'
export type CanvasFunctionRunStatus = 'READY' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'UNKNOWN'

/**
 * Canvas 聚合的持久化头。revision 是单调递增的 graph 版本修订号；
 * wire 上是 canonical 非负十进制字符串（Java long，数据库仍为 bigint），
 * 客户端绝不转换为 JS number。Agent Session/Thread 是独立的 owner 事实，
 * 不属于 Canvas graph document。
 */
export interface CanvasDocumentDTO {
  id: UUIDString
  title: string
  revision: CanvasRevision
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

/**
 * Canvas 节点的通用函数绑定配置。
 * 函数名指向注册目录中的通用函数，args 携带传给插件运行时的参数（包含派生引用的 resource 指针）。
 */
export interface CanvasFunctionDTO {
  name: string
  args: Record<string, unknown>
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

/**
 * 只读派生引用投影：由服务端通过扫描各节点的 function.args 动态派生，
 * 前端无需也不支持独立的 Link 增删改命令。
 */
export interface CanvasReferenceDTO {
  canvasId: UUIDString
  sourceNodeId: UUIDString
  targetNodeId: UUIDString
  index: number
}

/** 权威全量快照投影：初始加载、gap 恢复或 resync 时整体替换客户端状态。 */
export interface CanvasSnapshotDTO {
  document: CanvasDocumentDTO
  nodes: CanvasResourceNodeDTO[]
  groups: CanvasGroupDTO[]
  references: CanvasReferenceDTO[]
}

export type CanvasGroupPatchDTO =
  | { op: 'UPSERT'; group: CanvasGroupDTO }
  | { op: 'REMOVE'; groupId: UUIDString }

export type CanvasNodePatchDTO =
  | { op: 'UPSERT'; node: CanvasResourceNodeDTO }
  | { op: 'REMOVE'; nodeId: UUIDString }

/**
 * 幂等 graph patch：作为 command 响应返回。
 * revision 表示更新后的权威修订号。
 */
export interface CanvasPatchDTO {
  revision: CanvasRevision
  groups: CanvasGroupPatchDTO[]
  nodes: CanvasNodePatchDTO[]
}

/**
 * 应用事件 WebSocket `revision` payload：revision 前进提示，客户端随后读取
 * 权威 Snapshot。'resync' 事件无 payload，同样要求全量快照。
 */
export interface CanvasRevisionEventDTO {
  revision: CanvasRevision
}

/** typed command 中节点资源数组的槽位输入契约 */
export type CanvasResourceInputDTO =
  | { kind: 'KEEP'; resourceId: UUIDString }
  | { kind: 'TEXT'; name: string; textContent: string }
  | { kind: 'BLOB'; name: string; blobId: UUIDString }

/**
 * 一次命令批中无法按前置条件执行的具体原因，随 409 响应返回。
 */
export type CanvasConflictDTO =
  | { kind: 'TARGET_MISSING'; targetId: string; target: 'NODE' | 'GROUP' }
  | { kind: 'TARGET_PRESENT'; targetId: string; target: 'NODE' | 'GROUP' }
  | { kind: 'STALE_NODE'; nodeId: string; group: string; current: CanvasResourceNodeDTO }
  | { kind: 'STALE_GROUP'; groupId: string; current: CanvasGroupDTO }
  | { kind: 'NODE_RUNNING'; nodeId: string; run: CanvasFunctionRunDTO }
  | { kind: 'NODE_REFERENCED'; nodeId: string; referencingNodeIds: string[] }

/**
 * 11 种类型化 Canvas 命令：
 * 每条命令只针对单一语义维度，显式携带编辑起点的语义前置条件（expected*），
 * 绝不使用整图 CAS 版本。
 */
export type CanvasCommandDTO =
  | {
      type: 'CREATE_NODE'
      nodeId: UUIDString
      name: string
      transform: CanvasTransformDTO
      resources: CanvasResourceInputDTO[]
    }
  | {
      type: 'RENAME_NODE'
      nodeId: UUIDString
      expectedName: string
      name: string
    }
  | {
      type: 'SET_NODE_RESOURCES'
      nodeId: UUIDString
      expectedResourceIds: UUIDString[]
      resources: CanvasResourceInputDTO[]
    }
  | {
      type: 'SET_NODE_FUNCTION'
      nodeId: UUIDString
      expectedFunction: CanvasFunctionDTO | null
      function: CanvasFunctionDTO | null
    }
  | {
      type: 'SET_NODE_GROUP'
      nodeId: UUIDString
      expectedGroupId: UUIDString | null
      groupId: UUIDString | null
    }
  | {
      type: 'DELETE_NODE'
      nodeId: UUIDString
      expectedResourceIds: UUIDString[]
      expectedFunction: CanvasFunctionDTO | null
    }
  | {
      type: 'UPDATE_NODE_TRANSFORM'
      nodeId: UUIDString
      transform: CanvasTransformDTO
      expectedTransform?: CanvasTransformDTO | null
    }
  | {
      type: 'CREATE_GROUP'
      groupId: UUIDString
      title: string
      transform: CanvasTransformDTO
    }
  | {
      type: 'RENAME_GROUP'
      groupId: UUIDString
      expectedTitle: string
      title: string
    }
  | {
      type: 'UPDATE_GROUP_TRANSFORM'
      groupId: UUIDString
      transform: CanvasTransformDTO
      expectedTransform?: CanvasTransformDTO | null
    }
  | {
      type: 'DELETE_GROUP'
      groupId: UUIDString
      expectedMemberNodeIds: UUIDString[]
    }

/**
 * 命令批请求体：POST /api/canvases/{canvasId}/commands
 * 不包含 expectedVersion，通过每条命令的 expected* 进行语义冲突检测。
 */
export interface ApplyCanvasCommandsRequestDTO {
  idempotencyKey: UUIDString
  commands: CanvasCommandDTO[]
}

export interface CanvasFunctionReferencePolicyDTO {
  allowedKinds: CanvasResourceKind[]
  maxReferences?: number | null
  maxByKind?: Partial<Record<CanvasResourceKind, number>>
}

export interface CanvasFunctionOutputDTO {
  kind: string
  name: string | null
}

/** Function 目录项 */
export interface CanvasFunctionDefinitionDTO {
  name: string
  description?: string | null
  argsSchema: Record<string, unknown>
  outputs: CanvasFunctionOutputDTO[]
  referencePolicy?: CanvasFunctionReferencePolicyDTO | null
  available?: boolean
  unavailableReason?: string | null
}

/** 人工核查 UNKNOWN Function Run 的请求体 */
export interface CanvasFunctionUnknownResolutionDTO {
  requestId: UUIDString
  resolution: 'RESUME' | 'FAILED' | 'CANCELLED'
  verification: string
}

export interface CanvasFunctionRunRequestDTO {
  requestId: UUIDString
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


