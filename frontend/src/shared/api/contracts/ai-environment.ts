import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

export interface LiveEnvironmentCapabilityDTO {
  id: string
  version: string
}

/**
 * Environment Card DTO（包含稳定 Card 属性与当前 live 连接投影）。
 *
 * id 是 canonical UUID；name 是全局唯一、创建后不可变身份。
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
  /** 操作系统信息。 */
  operatingSystem?: string | null
  /** 时区信息。 */
  timeZone?: string | null
  /** 节点备注/说明。 */
  note?: string | null
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

/** 当前唯一的 Environment 管理资源类型。 */
export type EnvironmentOperationResourceType = 'MCP_SERVER'

/** Environment Operation 操作类型 wire 值。 */
export type EnvironmentOperationType = 'MCP_SERVER_DISCOVER'

/** Environment Operation 生命周期状态 wire 值。 */
export type EnvironmentOperationStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'UNKNOWN'
  | 'CANCELLED'

/**
 * Environment 管理操作公开安全响应 DTO。
 * 安全边界：移除了私有参数 arguments、leaseToken 与 ownerNodeId。
 */
export interface EnvironmentOperationDTO {
  /** 操作全局唯一 UUID。 */
  id: string
  /** 所属 Environment UUID。 */
  environmentId: string
  /** 目标资源类型：MCP_SERVER。 */
  resourceType: EnvironmentOperationResourceType
  /** 目标资源 UUID。 */
  resourceId: string
  /** 操作类型：MCP_SERVER_DISCOVER。 */
  operationType: EnvironmentOperationType
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
