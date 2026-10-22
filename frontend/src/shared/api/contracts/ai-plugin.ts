/**
 * 认证状态枚举。
 */
export type PluginStatus =
  | 'NOT_CONNECTED'
  | 'CONNECTED'
  | 'REFRESH_FAILED'
  | 'REFRESH_UNCERTAIN'
  | 'REAUTH_REQUIRED'

export type PluginSecretPatchOperation = 'KEEP' | 'SET' | 'CLEAR'
