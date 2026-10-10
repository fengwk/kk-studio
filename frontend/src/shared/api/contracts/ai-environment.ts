import type { CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

export type InstallOperatingSystem = 'linux' | 'macos' | 'windows'

export interface DaemonLspServerConfiguration {
  command: string[]
  extensions?: string[] | null
  rootMarkers?: string[] | null
  firstMatchMarkers?: string[] | null
}

export interface DaemonTerminalConfiguration {
  executable?: string | null
  args?: string[] | null
  workdir?: string | null
}

export interface EnvironmentInstallConfigDTO {
  operatingSystem: InstallOperatingSystem
  javaHome?: string | null
  daemon: {
    studioUrl: string
    note?: string | null
    bashExecutable?: string | null
    terminal?: DaemonTerminalConfiguration | null
    lsp?: { servers: Record<string, DaemonLspServerConfiguration> } | null
  }
}

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

export type EnvironmentUpdatePhase =
  | 'PENDING'
  | 'RUNNING'
  | 'PREPARED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'UNKNOWN'

export interface EnvironmentUpdateDTO {
  operationId: string
  targetVersion: string
  phase: EnvironmentUpdatePhase
  error?: string | null
  createdAt: InstantTimestamp
  updatedAt: InstantTimestamp
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
  installConfig?: EnvironmentInstallConfigDTO | null
  name: string
  registrationToken?: string | null
  /** 当前连接状态：CONNECTING / READY / OFFLINE（租约或心跳窗口已过期的连接由服务端派生为 OFFLINE）。 */
  status: string
  /** 是否就绪。 */
  ready: boolean
  /**
   * 当前状态仍然成立的截止时间：有效连接取 min(leaseUntil, lastSeen + heartbeatTimeout)；无连接或已失效时为 null。
   *
   * 它是服务端用同一时钟给出的只读时间投影；页面只按它安排一次回读，不轮询。
   */
  statusExpiresAt: InstantTimestamp | null
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
  /**
   * 最近一次被接受的 READY 上报的实际 daemon 构建版本（JAR manifest 版本；未打包时为 `development`）；从未 READY 时为 null。
   *
   * 它与下方 CAS `version` 是两个不同事实：前者是运行中的二进制版本，后者是配置行的乐观锁版本。
   */
  daemonVersion?: string | null
  /** Latest managed Daemon update operation; absent or null when never updated. */
  update?: EnvironmentUpdateDTO | null
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

/**
 * 5 分钟安装 code。只用于下载安装脚本，不是长期 registrationToken。
 * 有效期内可重复使用；过期或 token 轮换后需重新签发。
 */
export interface EnvironmentInstallCodeDTO {
  code: string
  /** ISO-8601 过期时间。 */
  expiresAt: InstantTimestamp
}

export interface EnvironmentCreateDTO {
  name: string
}
