import type { InstantTimestamp } from '@/shared/api/contracts/base'
import type { PluginConnectionCheckDTO } from '@/shared/api/generated/stable-contracts'

/**
 * Plugin 认证交互类型：封闭 union，只有 DEEP_LINK 与 STATIC_KEY。
 */
export interface PluginAuthKindDeepLinkDTO {
  type: 'DEEP_LINK'
  regionCandidates: string[]
}

export interface PluginAuthKindStaticKeyDTO {
  type: 'STATIC_KEY'
  secretField: string
}

export type PluginAuthKindDTO = PluginAuthKindDeepLinkDTO | PluginAuthKindStaticKeyDTO

/**
 * 认证状态枚举。
 */
export type PluginStatus =
  | 'NOT_CONNECTED'
  | 'KEY_UNAVAILABLE'
  | 'CONNECTED'
  | 'REFRESH_FAILED'
  | 'REFRESH_UNCERTAIN'
  | 'REAUTH_REQUIRED'

/**
 * 单个已声明秘密字段的安全投影。
 */
export interface PluginSecretProjectionDTO {
  description: string
  configured: boolean
}

/**
 * 权威 catalog 当前 desired 配置的安全投影。
 */
export interface PluginConfigurationDTO {
  revision: string | null
  valuesJson: string
  valuesSchema: string
  defaultsJson: string
  secrets: Record<string, PluginSecretProjectionDTO>
}

/**
 * 已安装 Plugin 的安全投影。
 * 绝不包含密文、token、密钥或 client identity。
 */
export interface PluginDTO {
  pluginId: string
  name: string
  version: string
  authKind: PluginAuthKindDTO | null
  status: PluginStatus | null
  region: string | null
  expiresAt: InstantTimestamp
  nextRefreshAt: InstantTimestamp
  lastRefreshedAt: InstantTimestamp
  lastRefreshError: string | null
  configuration: PluginConfigurationDTO
  desiredEpoch: string
  appliedEpoch: string | null
  desiredRevision: string
  appliedRevision: string | null
  /** 当前已应用实例是否声明显式只读连接检查能力；能力来自实例本身，绝不按 pluginId 推断。 */
  connectionCheckSupported: boolean
  harnessToolNames: string[]
  canvasFunctionNames: string[]
  applicationError: string | null
}

export interface PluginAuthPrepareRequestDTO {
  region: string
}

export interface PluginAuthPrepareDTO {
  authAttemptId: string
  loginUrl: string
}

export interface PluginAuthCompleteRequestDTO {
  authAttemptId: string
  callbackUrl: string
}

export type PluginSecretPatchOperation = 'KEEP' | 'SET' | 'CLEAR'

export interface PluginSecretPatchDTO {
  operation: PluginSecretPatchOperation
  value?: string
}

export interface PluginConfigurationUpdateRequestDTO {
  expectedEpoch: string
  expectedRevision: string
  valuesJson: string
  secretPatch?: Record<string, PluginSecretPatchDTO>
}

/*
 * 显式只读连接检查的 wire 契约由真实 Boot4/Jackson3 mapper 生成（见
 * `@/shared/api/generated/stable-contracts`），此处只做重新导出，避免再手抄一份类型或 enum 字面量。
 */
export type {
  PluginConnectionAuthentication,
  PluginConnectionBrowserSession,
  PluginConnectionCheckDTO,
  PluginConnectionCheckRequestDTO,
  PluginConnectionReachability,
} from '@/shared/api/generated/stable-contracts'

/** 生成契约里的 enum wire 值数组：严格解码器直接复用，不再重列字面量。 */
export {
  PLUGIN_CONNECTION_AUTHENTICATION_VALUES,
  PLUGIN_CONNECTION_BROWSER_SESSION_VALUES,
  PLUGIN_CONNECTION_REACHABILITY_VALUES,
} from '@/shared/api/generated/stable-contracts'

/**
 * 严格解码后的连接检查投影。
 *
 * wire 上的 `checkedAt` 是 epoch 秒 number（生成契约 `PluginConnectionCheckDTO` 已如实声明），但严格解码器同时接受可
 * 解析的 ISO 文本并原样保留，所以解码结果的 `checkedAt` 是 `number | string`；它是独立的 decoded/domain 时间类型，
 * 绝不冒充当前后端序列化形态。
 */
export type PluginConnectionCheckResult = Omit<PluginConnectionCheckDTO, 'checkedAt'> & {
  checkedAt: Exclude<InstantTimestamp, null>
}
