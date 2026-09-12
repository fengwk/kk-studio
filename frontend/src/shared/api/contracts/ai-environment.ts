import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

export interface LiveEnvironmentCapabilityDTO {
  id: string
  version: string
}

export interface LiveEnvironmentSkillDTO {
  name: string
  description: string | null
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
  /** daemon 通告的技能列表。 */
  skills: LiveEnvironmentSkillDTO[]
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
