import type { InstantTimestamp } from '@/shared/api/contracts/base'

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

/**
 * 显式只读连接检查的请求：只携带调用方所见已应用坐标，不携带远端地址、路径或凭据。
 */
export interface PluginConnectionCheckRequestDTO {
  expectedEpoch: string
  expectedRevision: string
}

/** 网络可达性：未发出请求时是 NOT_CHECKED，无法证明时为 INDETERMINATE。 */
export type PluginConnectionReachability =
  | 'REACHABLE'
  | 'UNREACHABLE'
  | 'NOT_CHECKED'
  | 'INDETERMINATE'

/**
 * 本次凭据的认证结论：只有远端确定性拒绝才是 REJECTED，只有成功合法响应才是 ACCEPTED；
 * UNAVAILABLE 仅表示本地没有可用凭据，其它故障一律 NOT_CHECKED。
 */
export type PluginConnectionAuthentication =
  | 'ACCEPTED'
  | 'REJECTED'
  | 'UNAVAILABLE'
  | 'NOT_CHECKED'

/** 浏览器登录会话：本能力不核验浏览器登录态，当前唯一合法值是 UNKNOWN。 */
export type PluginConnectionBrowserSession = 'UNKNOWN'

/**
 * 显式连接检查的结果：安装身份、本次检查依据的已应用坐标、检查时刻与三个类型化维度。
 * 检查时刻必须存在，不含异常文本、远端地址、远端正文或任何凭据。
 */
export interface PluginConnectionCheckDTO {
  pluginId: string
  appliedEpoch: string
  appliedRevision: string
  checkedAt: Exclude<InstantTimestamp, null>
  reachability: PluginConnectionReachability
  authentication: PluginConnectionAuthentication
  browserSession: PluginConnectionBrowserSession
}
