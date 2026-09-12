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
 * ToolInvocation 中 resolved environment binding 的公开表示：canonical UUID + canonical workspace path。
 */
export interface EnvironmentBindingDTO {
  /** canonical UUID 文本形式的环境 ID。 */
  environmentId: string
  /** Environment Root 下 canonical 相对 wire workspace 路径（'.' 表示 root）。 */
  workspacePath: string
}

/** 单层目录列表中的单个子目录条目。 */
export interface EnvironmentDirectoryEntryDTO {
  /** 子目录名（path 的最后一段）。 */
  name: string
  /** 子目录的 canonical 相对路径，可直接继续作为 path 参数浏览。 */
  path: string
}

/**
 * Environment Root 下单层目录浏览结果（control-plane 只读）。
 *
 * path / parentPath 是 canonical 相对 wire 路径（'.' 表示 root）；
 * displayPath 是请求 path 的最后一段（root 为 '.'），只作展示、
 * 绝不暴露 daemon 本地绝对路径；truncated 表示条目数超过单层上限被截断；
 * gitBranch 可空，是浏览目录所在 git 仓库的当前分支。
 */
export interface EnvironmentDirectoryDTO {
  /** 被浏览目录的 canonical 相对路径（'.' 表示 root）。 */
  path: string
  /** 被浏览目录的展示名：请求 path 的最后一段（root 为 '.'），绝不暴露本地绝对路径。 */
  displayPath: string
  /** 父目录的 canonical 相对路径（root 为 '.'）。 */
  parentPath: string
  /** 条目数超过单层上限时为 true。 */
  truncated: boolean
  /** 浏览目录所在 git 仓库的当前分支；未知时为空。 */
  gitBranch: string | null
  /** 按名称稳定排序的直属子目录，不含 symlink。 */
  entries: EnvironmentDirectoryEntryDTO[]
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
