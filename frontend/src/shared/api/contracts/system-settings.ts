import type { DecimalLong, InstantTimestamp } from '@/shared/api/contracts/base'

/**
 * GET/PUT {@code /api/settings} 的严格 wire 契约（对齐 share/systemsettings 各 DTO）。
 *
 * - Java {@code Long}/{@code long} 字段在 wire 上是 canonical 非负十进制字符串（{@link DecimalLong}）；
 * - Java {@code Integer}/{@code boolean} 字段是 JSON 数字 / 布尔；
 * - {@code version} 是字符串（非负十进制乐观锁 token）；
 * - 七个 section 全部必填；PUT 必须回传完整聚合。
 */

/** {@code tool.permission} 单条规则；数组顺序即求值顺序，必须保持。 */
export interface PermissionRuleDTO {
  pattern: string
  action: PermissionAction
}

export type PermissionAction = 'allow' | 'ask' | 'deny'

export interface SystemSettingsToolDTO {
  /** permission key 为全局通配符 `*` 或模型可见工具名；键与数组顺序都由后端返回并原样回写。 */
  permission: Record<string, PermissionRuleDTO[]>
  modelGatewayBusyRetryMillis: DecimalLong
  toolGatewayBusyRetryMillis: DecimalLong
  toolGatewayOverloadRetryMillis: DecimalLong
}

export type RetryBackoffStrategy = 'FIXED' | 'EXPONENTIAL'

export interface SystemSettingsAiRuntimeDTO {
  retryMaxRetries: number
  retryBackoffStrategy: RetryBackoffStrategy
  retryBaseDelayMillis: DecimalLong
  retryMaxDelayMillis: DecimalLong
  compactionKeepRecentTokens: number
  subagentMaxDepth: number
  subagentMaxConcurrency: number
  /** 0 表示不额外限制（无 cap）。 */
  subagentMaxTotalConcurrency: number
  subagentMaxTurns: number
  modelHttpRetryStatusCodes: number[]
}

export interface SystemSettingsEnvironmentDTO {
  maxResourceBytes: DecimalLong
  heartbeatTimeoutMillis: DecimalLong
}

export interface SystemSettingsNetworkDTO {
  /** null 表示 Backend 强制直连；非空仅支持无认证的 http://host:port。 */
  proxyUrl: string | null
  /** 逗号分隔的代理绕过规则。 */
  noProxyHosts: string
}

export interface ComfyuiIntegrationDTO {
  enabled: boolean
  baseUrl: string | null
  connectTimeoutMillis: DecimalLong
  readTimeoutMillis: DecimalLong
  websocketTimeoutMillis: DecimalLong
  maxInputFileBytes: DecimalLong
}

export interface OpenCliHubIntegrationDTO {
  enabled: boolean
  baseUrl: string | null
  connectTimeoutMillis: DecimalLong
  requestTimeoutMillis: DecimalLong
  longPollTimeoutMillis: DecimalLong
  streamBufferBytes: number
  maxJsonResponseBytes: number
  maxErrorResponseBytes: number
  maxOutputChars: number
}

export interface SeedanceIntegrationDTO {
  enabled: boolean
  workspaceId: string | null
  retry: number
  hubExecutionTimeoutMillis: DecimalLong
  statusPollIntervalMillis: DecimalLong
  maxWaitMillis: DecimalLong
}

export interface GptImage2IntegrationDTO {
  paidEnabled: boolean
  askTimeoutSeconds: number
  hubExecutionTimeoutMillis: DecimalLong
  maxWaitMillis: DecimalLong
}

export interface MiniMaxH3IntegrationDTO {
  enabled: boolean
  promptAgentName: string | null
  promptMaxWaitMillis: DecimalLong
  comfyBaseUrl: string | null
  comfyConnectTimeoutMillis: DecimalLong
  comfyRequestTimeoutMillis: DecimalLong
  comfyPollIntervalMillis: DecimalLong
  comfyMaxWaitMillis: DecimalLong
}

export interface SystemSettingsIntegrationsDTO {
  comfyui: ComfyuiIntegrationDTO
  openCliHub: OpenCliHubIntegrationDTO
  seedance: SeedanceIntegrationDTO
  gptImage2: GptImage2IntegrationDTO
  minimaxH3: MiniMaxH3IntegrationDTO
}

export interface SystemSettingsStorageMediaDTO {
  uploadExpiresSeconds: DecimalLong
  s3PresignDefaultExpiresSeconds: DecimalLong
  s3PresignMaxExpiresSeconds: DecimalLong
  canvasMediaProcessTimeoutMillis: DecimalLong
  thumbnailMaxDimension: number
  thumbnailQuality: number
  temporaryResourceTtlSeconds: DecimalLong
  temporaryResourceCleanupIntervalSeconds: DecimalLong
}

export interface SystemSettingsAdvancedDTO {
  resourceMaxBytes: DecimalLong
  processorLeaseDurationMillis: DecimalLong
  processorHeartbeatIntervalMillis: DecimalLong
  threadResolveFailureDelayMillis: DecimalLong
  modelDispatchBusyFallbackDelayMillis: DecimalLong
  toolPreflightFailureDelayMillis: DecimalLong
  toolDispatchBusyFallbackDelayMillis: DecimalLong
  applicationEventQueueCapacity: number
  applicationEventMaxBytes: DecimalLong
  applicationEventSendTimeoutMillis: DecimalLong
  applicationEventHeartbeatIntervalMillis: DecimalLong
  postgresqlWorkNotificationPollMillis: DecimalLong
  postgresqlWorkReconnectBackoffMillis: DecimalLong
}

/** GET/PUT 的公共 section 载体（tool / aiRuntime / environment / network / integrations / storageMedia / advanced）。 */
export interface SystemSettingsSectionsDTO {
  tool: SystemSettingsToolDTO
  aiRuntime: SystemSettingsAiRuntimeDTO
  environment: SystemSettingsEnvironmentDTO
  network: SystemSettingsNetworkDTO
  integrations: SystemSettingsIntegrationsDTO
  storageMedia: SystemSettingsStorageMediaDTO
  advanced: SystemSettingsAdvancedDTO
}

/** GET {@code /api/settings} 完整聚合：七个 section + 乐观锁版本与只读时戳。 */
export interface SystemSettingsDTO extends SystemSettingsSectionsDTO {
  version: string
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/** PUT {@code /api/settings} 请求体：完整 section 聚合 + 必填 expectedVersion CAS 令牌。 */
export interface SystemSettingsUpdateDTO extends SystemSettingsSectionsDTO {
  expectedVersion: string
}

export type SystemSettingsSchemaFieldType =
  | 'BOOLEAN'
  | 'INTEGER'
  | 'LONG'
  | 'TEXT'
  | 'ENUM'
  | 'PERMISSION'
  | 'INTEGER_LIST'

export type SystemSettingsSchemaApplyTiming =
  | 'NEXT_INVOCATION'
  | 'NEXT_HTTP_REQUEST'
  | 'RESTART'

export interface SystemSettingsSchemaOption {
  value: string
  labelKey: string
}

export interface SystemSettingsSchemaField {
  path: string
  labelKey: string
  hintKey: string | null
  type: SystemSettingsSchemaFieldType
  nullable: boolean
  min: number | null
  max: number | null
  options: SystemSettingsSchemaOption[] | null
}

export interface SystemSettingsSchemaGroup {
  key: string
  labelKey: string
  descriptionKey: string
  restartRequired: boolean
  applyTiming: SystemSettingsSchemaApplyTiming | null
  fields: SystemSettingsSchemaField[]
}

export interface SystemSettingsSchemaSection {
  key: string
  labelKey: string
  descriptionKey: string
  restartRequired: boolean
  groups: SystemSettingsSchemaGroup[]
}

export interface SystemSettingsSchemaDTO {
  sections: SystemSettingsSchemaSection[]
}
