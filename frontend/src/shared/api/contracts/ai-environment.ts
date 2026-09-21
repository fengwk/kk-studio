import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

export interface LiveEnvironmentCapabilityDTO {
  id: string
  version: string
}

export type EnvironmentEventLevel = 'INFO' | 'WARN' | 'ERROR'

export type EnvironmentEventType =
  | 'CONNECTING'
  | 'READY'
  | 'DISCONNECTED'
  | 'SKILL_SYNC_STARTED'
  | 'SKILL_SYNC_SUCCEEDED'
  | 'SKILL_SYNC_FAILED'

/**
 * Environment 连接与 Skill 同步的运行事件投影。
 *
 * 与后端 `EnvironmentEventDTO` 逐字段对应：level / type 是封闭枚举，message 永远存在
 * （后端只写程序构造的去敏文本），因此这里不使用宽化联合或可选字段。
 */
export interface EnvironmentEventDTO {
  /** 事件发生时间。 */
  time: InstantTimestamp
  /** 级别：INFO / WARN / ERROR。 */
  level: EnvironmentEventLevel
  /** 事件类型：CONNECTING / READY / DISCONNECTED / SKILL_SYNC_STARTED / SKILL_SYNC_SUCCEEDED / SKILL_SYNC_FAILED。 */
  type: EnvironmentEventType
  /** 去敏后的有界说明。 */
  message: string
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
  /** 最近活跃时间；从未连接过的 Environment 为 null。 */
  lastSeen: InstantTimestamp | null
  /** 支持的原子能力列表。 */
  capabilities: LiveEnvironmentCapabilityDTO[]
  /** 最近一次被接受的 READY 宿主 Daemon 进程用户；从未 READY 时为 null，仅用于展示。 */
  userName?: string | null
  /** 最近一次被接受的 READY 宿主进程用户 canonical HOME；从未 READY 时为 null，仅用于展示。 */
  homeDirectory?: string | null
  /** 操作系统信息。 */
  operatingSystem?: string | null
  /** 时区信息。 */
  timeZone?: string | null
  /** 节点备注/说明。 */
  note?: string | null
  /** 最近一条 WARN/ERROR 运维事件；没有任何此类事件时为 null。 */
  lastEvent?: EnvironmentEventDTO | null
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
