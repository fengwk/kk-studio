import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

export interface LiveEnvironmentCapabilityDTO {
  id: string
  version: string
}

/**
 * Environment Card DTO（包含稳定 Card 属性与当前 live 连接投影）。
 *
 * id 是 canonical UUID；name 是可编辑 display name。
 * registrationToken 在 create / rotate-token 响应中返回刚生成的新值，列表与详情查询始终为 null；
 * 按需读取当前值走 `GET /harness/environments/{id}/token`（no-store，不进入 query cache）。
 */
export interface EnvironmentCardDTO {
  id: string
  name: string
  registrationToken?: string | null
  /** 当前连接状态：CONNECTING / READY / OFFLINE。 */
  status: string
  /** 是否就绪。 */
  ready: boolean
  /** 最近活跃时间。 */
  lastSeen: InstantTimestamp
  /** 支持的原子能力列表。 */
  capabilities: LiveEnvironmentCapabilityDTO[]
  /** daemon 实际 root display path。 */
  rootPath: string | null
  /** CAS 版本。 */
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/**
 * 当前 registrationToken 的只读投影。
 *
 * 每次读取返回当前存储值且不轮换：连续读取的 registrationToken 与 version 保持一致，
 * updateTime 也不会推进。响应禁止缓存，调用方不得写入 LocalStorage 或 URL。
 */
export interface EnvironmentRegistrationTokenDTO {
  id: string
  registrationToken: string
  /** 读取时刻的 CAS 版本。 */
  version: CatalogVersion
}

export interface EnvironmentCreateDTO {
  name: string
}

export interface EnvironmentUpdateDTO {
  name: string
  expectedVersion: string
}

/** 单个有界扫描诊断响应 DTO。 */
export interface EnvironmentSkillDiagnosticDTO {
  /** 诊断位置（宿主上的目录或文件展示文本）。 */
  location: string
  /** 诊断说明（有界文本）。 */
  message: string
}

/** 来源类型 wire 值：path / git。 */
export type SkillSourceType = 'path' | 'git'

/** 来源应用状态：UNAPPLIED / READY / FAILED。 */
export type SkillSourceStatus = 'UNAPPLIED' | 'READY' | 'FAILED'

/**
 * Environment Skill 来源配置响应 DTO。
 *
 * version 同时是行级 CAS 令牌与下发给 Daemon 的 sourceVersion；
 * defaultSource 由服务端拥有，客户端不可配置。
 */
export interface EnvironmentSkillSourceDTO {
  /** 来源全局唯一 UUID（创建后不可变）。 */
  sourceId: string
  /** 所属 Environment UUID。 */
  environmentId: string
  /** 来源类型 wire 值：path / git。 */
  type: SkillSourceType
  /** PATH 来源目录；GIT 来源为 null。 */
  path: string | null
  /** GIT 来源仓库 URL；PATH 来源为 null。 */
  gitUrl: string | null
  /** GIT 来源 ref；缺省为 null 表示跟踪远端默认 HEAD。 */
  gitRef: string | null
  /** GIT 来源仓库内相对扫描目录；null 表示仓库根。 */
  scanPath: string | null
  /** 是否该 Environment 的缺省来源。 */
  defaultSource: boolean
  /** 行版本（canonical 非负十进制字符串）。 */
  version: CatalogVersion
  /** 应用状态：UNAPPLIED / READY / FAILED。 */
  status: SkillSourceStatus
  /** 最近一次成功应用的配置版本；从未应用时为 null。 */
  appliedVersion: CatalogVersion | null
  /** 最近一次成功应用的内容 revision；从未应用时为 null。 */
  appliedRevision: string | null
  /** 最近一次扫描的有界诊断。 */
  diagnostics: EnvironmentSkillDiagnosticDTO[]
  /** FAILED 状态下的失败分类码；其余状态为 null。 */
  lastErrorCode: string | null
  /** FAILED 状态下的失败描述；其余状态为 null。 */
  lastErrorMessage: string | null
  /** 最近一次成功应用时间；从未应用时为 null。 */
  lastAppliedAt: InstantTimestamp
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/**
 * 创建 Environment Skill 来源请求 DTO。
 */
export interface EnvironmentSkillSourceCreateDTO {
  /** 来源类型 wire 值：path / git。 */
  type: SkillSourceType
  /** PATH 来源目录：~/... 或目标 OS 词法绝对路径。 */
  path?: string | null
  /** GIT 来源仓库 URL。 */
  gitUrl?: string | null
  /** GIT 来源可选 ref；缺省表示跟踪远端默认 HEAD。 */
  gitRef?: string | null
  /** GIT 来源可选仓库内相对扫描目录；缺省表示仓库根。 */
  scanPath?: string | null
}

/**
 * 更新 Environment Skill 来源请求 DTO：整体替换来源配置并携带 CAS 期望版本。
 */
export interface EnvironmentSkillSourceUpdateDTO {
  /** 来源类型 wire 值：path / git。 */
  type: SkillSourceType
  /** PATH 来源目录：~/... 或目标 OS 词法绝对路径。 */
  path?: string | null
  /** GIT 来源仓库 URL。 */
  gitUrl?: string | null
  /** GIT 来源可选 ref；缺省表示跟踪远端默认 HEAD。 */
  gitRef?: string | null
  /** GIT 来源可选仓库内相对扫描目录；缺省表示仓库根。 */
  scanPath?: string | null
  /** 期望的来源行版本（必填，canonical 非负十进制字符串）。 */
  expectedVersion: CatalogVersion
}

/**
 * Environment 持久 inventory 头响应 DTO：期望来源集合代际与最近一次被围栏接受的 READY 报告。
 */
export interface EnvironmentInventoryDTO {
  /** Environment UUID。 */
  environmentId: string
  /** Platform 期望的活跃来源集合代际（canonical 非负十进制字符串）。 */
  sourceSetVersion: CatalogVersion
  /** 最近一次被 READY 围栏接受的来源集合代际；从未接受时为 null。 */
  appliedSourceSetVersion: CatalogVersion | null
  /** 已接受 READY 的 capabilities 协议版本；从未接受时为 null。 */
  capabilitiesVersion: number | null
  /** 已接受 READY 报告的宿主系统 wire 值：windows / wsl / linux / macos。 */
  operatingSystem: string | null
  /** 已接受 READY 报告的 IANA 时区 ID。 */
  timeZone: string | null
  /** 已接受 READY 报告的备注。 */
  note: string | null
  /** 已接受 READY 报告的 Daemon canonical Environment root。 */
  rootPath: string | null
  /** 该 READY 报告被接受的时间。 */
  reportedAt: InstantTimestamp
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/**
 * 持久 Skill inventory 响应 DTO：每个来源最新一次成功扫描发现的单个 Skill。
 */
export interface EnvironmentSkillDTO {
  /** 所属来源的全局唯一 UUID。 */
  sourceId: string
  /** Skill canonical 名（同一 Environment 内全局唯一）。 */
  name: string
  /** 发现该 Skill 时的来源行版本（canonical 非负十进制字符串）。 */
  sourceVersion: CatalogVersion
  /** Skill 描述。 */
  description: string
  /** 宿主上的 Skill 目录。 */
  baseDirectory: string
  /** 内容 revision：小写 64 位 SHA-256。 */
  contentRevision: string
  /** 最近一次发现该 Skill 的时间。 */
  discoveredAt: InstantTimestamp
}

/** 目标资源类型：SKILL_SOURCE / MCP_SERVER。 */
export type EnvironmentOperationResourceType = 'SKILL_SOURCE' | 'MCP_SERVER'

/** Environment Operation 操作类型 wire 值。 */
export type EnvironmentOperationType =
  | 'SKILL_REFRESH'
  | 'SKILL_INSTALL'
  | 'SKILL_UPDATE'
  | 'MCP_SERVER_DISCOVER'

/** Environment Operation 生命周期状态 wire 值。 */
export type EnvironmentOperationStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'UNKNOWN'
  | 'CANCELLED'

/**
 * Environment Skill 来源操作创建请求 DTO。
 */
export interface EnvironmentOperationCreateDTO {
  /** 本次操作执行超时的毫秒数（必填正数）。 */
  timeoutMillis: number
}

/**
 * Environment 管理操作公开安全响应 DTO。
 * 安全边界：移除了私有参数 arguments、leaseToken 与 ownerNodeId。
 * 泛化支持 SKILL_SOURCE 与 MCP_SERVER 两类资源。
 */
export interface EnvironmentOperationDTO {
  /** 操作全局唯一 UUID。 */
  id: string
  /** 所属 Environment UUID。 */
  environmentId: string
  /** 目标资源类型：SKILL_SOURCE / MCP_SERVER。 */
  resourceType: EnvironmentOperationResourceType | string
  /** 目标资源 UUID。 */
  resourceId: string
  /** 操作类型：SKILL_REFRESH / SKILL_INSTALL / SKILL_UPDATE / MCP_SERVER_DISCOVER。 */
  operationType: EnvironmentOperationType | string
  /** 生命周期状态：PENDING / RUNNING / SUCCEEDED / FAILED / UNKNOWN / CANCELLED。 */
  status: EnvironmentOperationStatus
  /** 冻结的目标资源版本（canonical 非负十进制字符串）。 */
  resourceVersion: CatalogVersion
  /** 操作参数的安全结构化摘要。 */
  parameterSummary: Record<string, unknown>
  /** 截止时间戳。 */
  deadlineAt: InstantTimestamp
  /** 开始执行时间戳；从未认领时为 null。 */
  startedAt: InstantTimestamp | null
  /** 终态完成时间戳；未终结时为 null。 */
  finishedAt: InstantTimestamp | null
  /** 执行成功后的结构化结果摘要；未成功时为 null。 */
  resultSummary: Record<string, unknown> | null
  /** 失败或未知状态下的分类错误码；成功或活动状态下为 null。 */
  failureCode: string | null
  /** 失败或未知状态下的安全描述；成功或活动状态下为 null。 */
  failureMessage: string | null
  /** 操作记录创建时间。 */
  createdAt: InstantTimestamp
  /** 操作记录最近更新时间。 */
  updatedAt: InstantTimestamp
}
