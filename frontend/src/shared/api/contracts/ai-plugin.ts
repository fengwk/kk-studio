/**
 * Plugin 认证交互类型：封闭 union，当前只有 DEEP_LINK。
 */
export interface PluginAuthKindDeepLinkDTO {
  type: 'DEEP_LINK'
  regionCandidates: string[]
}

export type PluginAuthKindDTO = PluginAuthKindDeepLinkDTO

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
 * 已安装 Plugin 的安全投影。
 * 绝不包含密文、token、密钥或 client identity。
 */
export interface PluginDTO {
  pluginId: string
  name: string
  version: string
  authKind: PluginAuthKindDTO | null
  status: PluginStatus
  region: string | null
  expiresAt: string | null
  nextRefreshAt: string | null
  lastRefreshedAt: string | null
  lastRefreshError: string | null
}

export interface PluginAuthPrepareRequestDTO {
  region: string
}

export interface PluginAuthPrepareDTO {
  loginUrl: string
}

export interface PluginAuthCompleteRequestDTO {
  callbackUrl: string
}
