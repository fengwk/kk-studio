/**
 * 由生产 HTTP Jackson 3 mapper 生成的稳定传输契约；请勿手改。
 *
 * 生成器：web 测试源码 StableTsContractGenerator
 * 重新生成：scripts/dev/verify/generated-contracts/write.sh
 * 漂移门禁：StableTsContractArtifactTest（mvn verify 覆盖）
 */

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.Reachability（mapper 序列化每个常量得到的 wire 值） */
export type PluginConnectionReachability =
  | 'REACHABLE'
  | 'UNREACHABLE'
  | 'NOT_CHECKED'
  | 'INDETERMINATE'

/** Java 声明顺序的 wire 值，供严格解码器复用。 */
export const PLUGIN_CONNECTION_REACHABILITY_VALUES: readonly PluginConnectionReachability[] = [
  'REACHABLE',
  'UNREACHABLE',
  'NOT_CHECKED',
  'INDETERMINATE',
]

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.Authentication（mapper 序列化每个常量得到的 wire 值） */
export type PluginConnectionAuthentication =
  | 'ACCEPTED'
  | 'REJECTED'
  | 'UNAVAILABLE'
  | 'NOT_CHECKED'

/** Java 声明顺序的 wire 值，供严格解码器复用。 */
export const PLUGIN_CONNECTION_AUTHENTICATION_VALUES: readonly PluginConnectionAuthentication[] = [
  'ACCEPTED',
  'REJECTED',
  'UNAVAILABLE',
  'NOT_CHECKED',
]

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.BrowserSession（mapper 序列化每个常量得到的 wire 值） */
export type PluginConnectionBrowserSession = 'UNKNOWN'

/** Java 声明顺序的 wire 值，供严格解码器复用。 */
export const PLUGIN_CONNECTION_BROWSER_SESSION_VALUES: readonly PluginConnectionBrowserSession[] = [
  'UNKNOWN',
]

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO */
export interface PluginConnectionCheckDTO {
  pluginId: string
  appliedRevision: string
  checkedAt: number
  reachability: PluginConnectionReachability
  authentication: PluginConnectionAuthentication
  browserSession: PluginConnectionBrowserSession
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckRequestDTO */
export interface PluginConnectionCheckRequestDTO {
  expectedRevision: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionVersionDTO */
export interface CanvasFunctionVersionDTO {
  definitionFingerprint: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionRunRequestDTO */
export interface CanvasFunctionRunRequestDTO {
  requestId: string
  expectedVersion: CanvasFunctionVersionDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionCancelRequestDTO */
export interface CanvasFunctionCancelRequestDTO {
  requestId: string
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncKind（mapper 序列化每个常量得到的 wire 值） */
export type ConfigSyncKind =
  | 'providers'
  | 'models'
  | 'agents'
  | 'skillPackages'
  | 'environments'
  | 'mcpServers'
  | 'settings'
  | 'plugins'

/** Java 声明顺序的 wire 值，供严格解码器复用。 */
export const CONFIG_SYNC_KIND_VALUES: readonly ConfigSyncKind[] = [
  'providers',
  'models',
  'agents',
  'skillPackages',
  'environments',
  'mcpServers',
  'settings',
  'plugins',
]
