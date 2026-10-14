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
  expiresAt: string | null
  nextRefreshAt: string | null
  lastRefreshedAt: string | null
  lastRefreshError: string | null
  configuration: PluginConfigurationDTO
  desiredEpoch: string
  appliedEpoch: string | null
  desiredRevision: string
  appliedRevision: string | null
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
