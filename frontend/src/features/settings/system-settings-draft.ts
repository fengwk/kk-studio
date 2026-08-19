import type {
  PermissionAction,
  SystemSettingsSectionsDTO,
  SystemSettingsUpdateDTO,
} from '@/shared/api/contracts/system-settings'

/** 前端 draft 的共享数字/文本基元。 */
export type DraftNumericField = string

export interface PermissionRuleDraft {
  pattern: string
  action: PermissionAction
}

/** 保序的 tool 权限分组：tool 名 + 该 tool 下的有序规则数组。 */
export interface PermissionGroupDraft {
  tool: string
  rules: PermissionRuleDraft[]
}

export interface SystemSettingsToolDraft {
  permission: PermissionGroupDraft[]
  defaultYolo: boolean
  modelGatewayBusyRetryMillis: DraftNumericField
  toolGatewayBusyRetryMillis: DraftNumericField
  toolGatewayOverloadRetryMillis: DraftNumericField
  skillLoadTimeoutMillis: DraftNumericField
}

export interface SystemSettingsAiRuntimeDraft {
  retryMaxRetries: DraftNumericField
  retryBackoffStrategy: 'FIXED' | 'EXPONENTIAL'
  retryBaseDelayMillis: DraftNumericField
  retryMaxDelayMillis: DraftNumericField
  compactionEnabled: boolean
  compactionReserveTokens: DraftNumericField
  compactionMaxRecentTokens: DraftNumericField
  subagentMaxDepth: DraftNumericField
  subagentMaxConcurrency: DraftNumericField
  /** '' 表示不额外限制（无 cap）。 */
  subagentMaxTotalConcurrency: DraftNumericField
  /** 0 表示关闭。 */
  subagentIdleTimeoutMillis: DraftNumericField
  subagentMaxTurns: DraftNumericField
}

export interface SystemSettingsEnvironmentDraft {
  maxResourceBytes: DraftNumericField
  maxMessageBytes: DraftNumericField
  heartbeatTimeoutMillis: DraftNumericField
  directoryListTimeoutMillis: DraftNumericField
}

export interface ComfyuiIntegrationDraft {
  enabled: boolean
  baseUrl: string
  connectTimeoutMillis: DraftNumericField
  readTimeoutMillis: DraftNumericField
  websocketTimeoutMillis: DraftNumericField
  maxInputFileBytes: DraftNumericField
}

export interface OpenCliHubIntegrationDraft {
  enabled: boolean
  baseUrl: string
  connectTimeoutMillis: DraftNumericField
  requestTimeoutMillis: DraftNumericField
  longPollTimeoutMillis: DraftNumericField
  streamBufferBytes: DraftNumericField
  maxJsonResponseBytes: DraftNumericField
  maxErrorResponseBytes: DraftNumericField
  maxOutputChars: DraftNumericField
}

export interface SeedanceIntegrationDraft {
  enabled: boolean
  workspaceId: string
  retry: DraftNumericField
  hubExecutionTimeoutMillis: DraftNumericField
  statusPollIntervalMillis: DraftNumericField
  maxWaitMillis: DraftNumericField
}

export interface GptImage2IntegrationDraft {
  paidEnabled: boolean
  askTimeoutSeconds: DraftNumericField
  hubExecutionTimeoutMillis: DraftNumericField
  maxWaitMillis: DraftNumericField
}

export interface MiniMaxH3IntegrationDraft {
  enabled: boolean
  promptAgentName: string
  promptEnvironmentName: string
  promptMaxWaitMillis: DraftNumericField
  comfyBaseUrl: string
  comfyConnectTimeoutMillis: DraftNumericField
  comfyRequestTimeoutMillis: DraftNumericField
  comfyPollIntervalMillis: DraftNumericField
  comfyMaxWaitMillis: DraftNumericField
}

export interface SystemSettingsIntegrationsDraft {
  comfyui: ComfyuiIntegrationDraft
  openCliHub: OpenCliHubIntegrationDraft
  seedance: SeedanceIntegrationDraft
  gptImage2: GptImage2IntegrationDraft
  minimaxH3: MiniMaxH3IntegrationDraft
}

export interface SystemSettingsStorageMediaDraft {
  uploadExpiresSeconds: DraftNumericField
  s3Enabled: boolean
  s3PresignDefaultExpiresSeconds: DraftNumericField
  s3PresignMaxExpiresSeconds: DraftNumericField
  canvasMediaProcessTimeoutMillis: DraftNumericField
  thumbnailMaxDimension: DraftNumericField
  thumbnailQuality: DraftNumericField
}

export interface SystemSettingsAdvancedDraft {
  resourceMaxBytes: DraftNumericField
  processorLeaseDurationMillis: DraftNumericField
  processorHeartbeatIntervalMillis: DraftNumericField
  threadResolveFailureDelayMillis: DraftNumericField
  modelDispatchBusyFallbackDelayMillis: DraftNumericField
  toolPreflightFailureDelayMillis: DraftNumericField
  toolDispatchBusyFallbackDelayMillis: DraftNumericField
  dispatcherLeaseDurationMillis: DraftNumericField
  dispatcherPollIntervalMillis: DraftNumericField
  dispatcherRejectionDelayMillis: DraftNumericField
  dispatcherMaxDispatchTasks: DraftNumericField
  dispatcherWorkerConcurrency: DraftNumericField
  dispatcherWorkerQueueCapacity: DraftNumericField
  canvasRealtimeMaxLength: DraftNumericField
  canvasFunctionExecutorCoreSize: DraftNumericField
  canvasFunctionExecutorMaxSize: DraftNumericField
  canvasFunctionExecutorQueueCapacity: DraftNumericField
  applicationEventQueueCapacity: DraftNumericField
  applicationEventMaxBytes: DraftNumericField
  applicationEventSendTimeoutMillis: DraftNumericField
  applicationEventHeartbeatIntervalMillis: DraftNumericField
  postgresqlWorkNotificationPollMillis: DraftNumericField
  postgresqlWorkReconnectBackoffMillis: DraftNumericField
  redisRealtimeRetryDelayMillis: DraftNumericField
}

export interface SystemSettingsSectionsDraft {
  tool: SystemSettingsToolDraft
  aiRuntime: SystemSettingsAiRuntimeDraft
  environment: SystemSettingsEnvironmentDraft
  integrations: SystemSettingsIntegrationsDraft
  storageMedia: SystemSettingsStorageMediaDraft
  advanced: SystemSettingsAdvancedDraft
}

/** 客户端 draft 校验失败原因；编辑器据此展示明确的双语错误。 */
export type DraftValidationReason =
  | 'blankToolName'
  | 'duplicateToolName'
  | 'blankPattern'
  | 'emptyNumericField'

export class DraftValidationError extends Error {
  readonly reason: DraftValidationReason

  constructor(reason: DraftValidationReason) {
    super(reason)
    this.name = 'DraftValidationError'
    this.reason = reason
  }
}

/** 把 GET 权威聚合转成可编辑 draft：所有数值字段都保持为字符串（非有损），permission 展开为保序分组。 */
export function settingsSectionsToDraft(dto: SystemSettingsSectionsDTO): SystemSettingsSectionsDraft {
  const permission = Object.entries(dto.tool.permission).map(([tool, rules]) => ({
    tool,
    rules: rules.map((rule) => ({ pattern: rule.pattern, action: rule.action })),
  }))

  return {
    tool: {
      permission,
      defaultYolo: dto.tool.defaultYolo,
      modelGatewayBusyRetryMillis: dto.tool.modelGatewayBusyRetryMillis,
      toolGatewayBusyRetryMillis: dto.tool.toolGatewayBusyRetryMillis,
      toolGatewayOverloadRetryMillis: dto.tool.toolGatewayOverloadRetryMillis,
      skillLoadTimeoutMillis: dto.tool.skillLoadTimeoutMillis,
    },
    aiRuntime: aiRuntimeToDraft(dto.aiRuntime),
    environment: {
      maxResourceBytes: dto.environment.maxResourceBytes,
      maxMessageBytes: dto.environment.maxMessageBytes,
      heartbeatTimeoutMillis: dto.environment.heartbeatTimeoutMillis,
      directoryListTimeoutMillis: dto.environment.directoryListTimeoutMillis,
    },
    integrations: integrationsToDraft(dto.integrations),
    storageMedia: {
      uploadExpiresSeconds: dto.storageMedia.uploadExpiresSeconds,
      s3Enabled: dto.storageMedia.s3Enabled,
      s3PresignDefaultExpiresSeconds: dto.storageMedia.s3PresignDefaultExpiresSeconds,
      s3PresignMaxExpiresSeconds: dto.storageMedia.s3PresignMaxExpiresSeconds,
      canvasMediaProcessTimeoutMillis: dto.storageMedia.canvasMediaProcessTimeoutMillis,
      thumbnailMaxDimension: String(dto.storageMedia.thumbnailMaxDimension),
      thumbnailQuality: String(dto.storageMedia.thumbnailQuality),
    },
    advanced: {
      resourceMaxBytes: dto.advanced.resourceMaxBytes,
      processorLeaseDurationMillis: dto.advanced.processorLeaseDurationMillis,
      processorHeartbeatIntervalMillis: dto.advanced.processorHeartbeatIntervalMillis,
      threadResolveFailureDelayMillis: dto.advanced.threadResolveFailureDelayMillis,
      modelDispatchBusyFallbackDelayMillis: dto.advanced.modelDispatchBusyFallbackDelayMillis,
      toolPreflightFailureDelayMillis: dto.advanced.toolPreflightFailureDelayMillis,
      toolDispatchBusyFallbackDelayMillis: dto.advanced.toolDispatchBusyFallbackDelayMillis,
      dispatcherLeaseDurationMillis: dto.advanced.dispatcherLeaseDurationMillis,
      dispatcherPollIntervalMillis: dto.advanced.dispatcherPollIntervalMillis,
      dispatcherRejectionDelayMillis: dto.advanced.dispatcherRejectionDelayMillis,
      dispatcherMaxDispatchTasks: String(dto.advanced.dispatcherMaxDispatchTasks),
      dispatcherWorkerConcurrency: String(dto.advanced.dispatcherWorkerConcurrency),
      dispatcherWorkerQueueCapacity: String(dto.advanced.dispatcherWorkerQueueCapacity),
      canvasRealtimeMaxLength: dto.advanced.canvasRealtimeMaxLength,
      canvasFunctionExecutorCoreSize: String(dto.advanced.canvasFunctionExecutorCoreSize),
      canvasFunctionExecutorMaxSize: String(dto.advanced.canvasFunctionExecutorMaxSize),
      canvasFunctionExecutorQueueCapacity: String(dto.advanced.canvasFunctionExecutorQueueCapacity),
      applicationEventQueueCapacity: String(dto.advanced.applicationEventQueueCapacity),
      applicationEventMaxBytes: dto.advanced.applicationEventMaxBytes,
      applicationEventSendTimeoutMillis: dto.advanced.applicationEventSendTimeoutMillis,
      applicationEventHeartbeatIntervalMillis: dto.advanced.applicationEventHeartbeatIntervalMillis,
      postgresqlWorkNotificationPollMillis:
        dto.advanced.postgresqlWorkNotificationPollMillis,
      postgresqlWorkReconnectBackoffMillis: dto.advanced.postgresqlWorkReconnectBackoffMillis,
      redisRealtimeRetryDelayMillis: dto.advanced.redisRealtimeRetryDelayMillis,
    },
  }
}

function aiRuntimeToDraft(
  dto: SystemSettingsSectionsDTO['aiRuntime'],
): SystemSettingsAiRuntimeDraft {
  return {
    retryMaxRetries: String(dto.retryMaxRetries),
    retryBackoffStrategy: dto.retryBackoffStrategy,
    retryBaseDelayMillis: dto.retryBaseDelayMillis,
    retryMaxDelayMillis: dto.retryMaxDelayMillis,
    compactionEnabled: dto.compactionEnabled,
    compactionReserveTokens: String(dto.compactionReserveTokens),
    compactionMaxRecentTokens: String(dto.compactionMaxRecentTokens),
    subagentMaxDepth: String(dto.subagentMaxDepth),
    subagentMaxConcurrency: String(dto.subagentMaxConcurrency),
    subagentMaxTotalConcurrency:
      dto.subagentMaxTotalConcurrency == null ? '' : String(dto.subagentMaxTotalConcurrency),
    subagentIdleTimeoutMillis: dto.subagentIdleTimeoutMillis,
    subagentMaxTurns: String(dto.subagentMaxTurns),
  }
}

function integrationsToDraft(
  dto: SystemSettingsSectionsDTO['integrations'],
): SystemSettingsIntegrationsDraft {
  return {
    comfyui: {
      enabled: dto.comfyui.enabled,
      baseUrl: dto.comfyui.baseUrl ?? '',
      connectTimeoutMillis: dto.comfyui.connectTimeoutMillis,
      readTimeoutMillis: dto.comfyui.readTimeoutMillis,
      websocketTimeoutMillis: dto.comfyui.websocketTimeoutMillis,
      maxInputFileBytes: dto.comfyui.maxInputFileBytes,
    },
    openCliHub: {
      enabled: dto.openCliHub.enabled,
      baseUrl: dto.openCliHub.baseUrl ?? '',
      connectTimeoutMillis: dto.openCliHub.connectTimeoutMillis,
      requestTimeoutMillis: dto.openCliHub.requestTimeoutMillis,
      longPollTimeoutMillis: dto.openCliHub.longPollTimeoutMillis,
      streamBufferBytes: String(dto.openCliHub.streamBufferBytes),
      maxJsonResponseBytes: String(dto.openCliHub.maxJsonResponseBytes),
      maxErrorResponseBytes: String(dto.openCliHub.maxErrorResponseBytes),
      maxOutputChars: String(dto.openCliHub.maxOutputChars),
    },
    seedance: {
      enabled: dto.seedance.enabled,
      workspaceId: dto.seedance.workspaceId ?? '',
      retry: String(dto.seedance.retry),
      hubExecutionTimeoutMillis: dto.seedance.hubExecutionTimeoutMillis,
      statusPollIntervalMillis: dto.seedance.statusPollIntervalMillis,
      maxWaitMillis: dto.seedance.maxWaitMillis,
    },
    gptImage2: {
      paidEnabled: dto.gptImage2.paidEnabled,
      askTimeoutSeconds: String(dto.gptImage2.askTimeoutSeconds),
      hubExecutionTimeoutMillis: dto.gptImage2.hubExecutionTimeoutMillis,
      maxWaitMillis: dto.gptImage2.maxWaitMillis,
    },
    minimaxH3: {
      enabled: dto.minimaxH3.enabled,
      promptAgentName: dto.minimaxH3.promptAgentName ?? '',
      promptEnvironmentName: dto.minimaxH3.promptEnvironmentName ?? '',
      promptMaxWaitMillis: dto.minimaxH3.promptMaxWaitMillis,
      comfyBaseUrl: dto.minimaxH3.comfyBaseUrl ?? '',
      comfyConnectTimeoutMillis: dto.minimaxH3.comfyConnectTimeoutMillis,
      comfyRequestTimeoutMillis: dto.minimaxH3.comfyRequestTimeoutMillis,
      comfyPollIntervalMillis: dto.minimaxH3.comfyPollIntervalMillis,
      comfyMaxWaitMillis: dto.minimaxH3.comfyMaxWaitMillis,
    },
  }
}

/** 把可编辑 draft 组装为 PUT 请求体；空/非法数字、空 tool 名、trim 后重复的 canonical tool 名与空 pattern 都会抛 {@link DraftValidationError}。 */
export function assembleSettingsUpdate(
  draft: SystemSettingsSectionsDraft,
  expectedVersion: string,
): SystemSettingsUpdateDTO {
  const permissionEntries = new Map<
    string,
    { pattern: string; action: PermissionAction }[]
  >()
  for (const group of draft.tool.permission) {
    const tool = group.tool.trim()
    if (tool === '') {
      throw new DraftValidationError('blankToolName')
    }
    if (permissionEntries.has(tool)) {
      // 两个分组 trim 后 canonical 名相同：JSON 对象键会静默覆盖前面的规则造成数据丢失，必须拒绝。
      throw new DraftValidationError('duplicateToolName')
    }
    const rules = group.rules.map((rule) => {
      const pattern = rule.pattern.trim()
      if (pattern === '') {
        throw new DraftValidationError('blankPattern')
      }
      return { pattern, action: rule.action }
    })
    permissionEntries.set(tool, rules)
  }
  // Object.fromEntries 使用 CreateDataProperty 创建键；`__proto__` 是普通 own property，不会触发
  // Object.prototype 的 legacy setter。
  const permission = Object.fromEntries(permissionEntries)

  return {
    tool: {
      permission,
      defaultYolo: draft.tool.defaultYolo,
      modelGatewayBusyRetryMillis: requiredLong(
        draft.tool.modelGatewayBusyRetryMillis,
      ),
      toolGatewayBusyRetryMillis: requiredLong(
        draft.tool.toolGatewayBusyRetryMillis,
      ),
      toolGatewayOverloadRetryMillis: requiredLong(
        draft.tool.toolGatewayOverloadRetryMillis,
      ),
      skillLoadTimeoutMillis: requiredLong(draft.tool.skillLoadTimeoutMillis),
    },
    aiRuntime: {
      retryMaxRetries: requiredInt(draft.aiRuntime.retryMaxRetries),
      retryBackoffStrategy: draft.aiRuntime.retryBackoffStrategy,
      retryBaseDelayMillis: requiredLong(draft.aiRuntime.retryBaseDelayMillis),
      retryMaxDelayMillis: requiredLong(draft.aiRuntime.retryMaxDelayMillis),
      compactionEnabled: draft.aiRuntime.compactionEnabled,
      compactionReserveTokens: requiredInt(draft.aiRuntime.compactionReserveTokens),
      compactionMaxRecentTokens: requiredInt(draft.aiRuntime.compactionMaxRecentTokens),
      subagentMaxDepth: requiredInt(draft.aiRuntime.subagentMaxDepth),
      subagentMaxConcurrency: requiredInt(draft.aiRuntime.subagentMaxConcurrency),
      subagentMaxTotalConcurrency: nullableInt(
        draft.aiRuntime.subagentMaxTotalConcurrency,
      ),
      subagentIdleTimeoutMillis: requiredLong(draft.aiRuntime.subagentIdleTimeoutMillis),
      subagentMaxTurns: requiredInt(draft.aiRuntime.subagentMaxTurns),
    },
    environment: {
      maxResourceBytes: requiredLong(draft.environment.maxResourceBytes),
      maxMessageBytes: requiredLong(draft.environment.maxMessageBytes),
      heartbeatTimeoutMillis: requiredLong(draft.environment.heartbeatTimeoutMillis),
      directoryListTimeoutMillis: requiredLong(draft.environment.directoryListTimeoutMillis),
    },
    integrations: {
      comfyui: {
        enabled: draft.integrations.comfyui.enabled,
        baseUrl: nullableText(draft.integrations.comfyui.baseUrl),
        connectTimeoutMillis: requiredLong(
          draft.integrations.comfyui.connectTimeoutMillis,
        ),
        readTimeoutMillis: requiredLong(draft.integrations.comfyui.readTimeoutMillis),
        websocketTimeoutMillis: requiredLong(
          draft.integrations.comfyui.websocketTimeoutMillis,
        ),
        maxInputFileBytes: requiredLong(draft.integrations.comfyui.maxInputFileBytes),
      },
      openCliHub: {
        enabled: draft.integrations.openCliHub.enabled,
        baseUrl: nullableText(draft.integrations.openCliHub.baseUrl),
        connectTimeoutMillis: requiredLong(
          draft.integrations.openCliHub.connectTimeoutMillis,
        ),
        requestTimeoutMillis: requiredLong(draft.integrations.openCliHub.requestTimeoutMillis),
        longPollTimeoutMillis: requiredLong(
          draft.integrations.openCliHub.longPollTimeoutMillis,
        ),
        streamBufferBytes: requiredInt(draft.integrations.openCliHub.streamBufferBytes),
        maxJsonResponseBytes: requiredInt(
          draft.integrations.openCliHub.maxJsonResponseBytes,
        ),
        maxErrorResponseBytes: requiredInt(
          draft.integrations.openCliHub.maxErrorResponseBytes,
        ),
        maxOutputChars: requiredInt(draft.integrations.openCliHub.maxOutputChars),
      },
      seedance: {
        enabled: draft.integrations.seedance.enabled,
        workspaceId: nullableText(draft.integrations.seedance.workspaceId),
        retry: requiredInt(draft.integrations.seedance.retry),
        hubExecutionTimeoutMillis: requiredLong(
          draft.integrations.seedance.hubExecutionTimeoutMillis,
        ),
        statusPollIntervalMillis: requiredLong(
          draft.integrations.seedance.statusPollIntervalMillis,
        ),
        maxWaitMillis: requiredLong(draft.integrations.seedance.maxWaitMillis),
      },
      gptImage2: {
        paidEnabled: draft.integrations.gptImage2.paidEnabled,
        askTimeoutSeconds: requiredInt(draft.integrations.gptImage2.askTimeoutSeconds),
        hubExecutionTimeoutMillis: requiredLong(
          draft.integrations.gptImage2.hubExecutionTimeoutMillis,
        ),
        maxWaitMillis: requiredLong(draft.integrations.gptImage2.maxWaitMillis),
      },
      minimaxH3: {
        enabled: draft.integrations.minimaxH3.enabled,
        promptAgentName: nullableText(draft.integrations.minimaxH3.promptAgentName),
        promptEnvironmentName: nullableText(
          draft.integrations.minimaxH3.promptEnvironmentName,
        ),
        promptMaxWaitMillis: requiredLong(
          draft.integrations.minimaxH3.promptMaxWaitMillis,
        ),
        comfyBaseUrl: nullableText(draft.integrations.minimaxH3.comfyBaseUrl),
        comfyConnectTimeoutMillis: requiredLong(
          draft.integrations.minimaxH3.comfyConnectTimeoutMillis,
        ),
        comfyRequestTimeoutMillis: requiredLong(
          draft.integrations.minimaxH3.comfyRequestTimeoutMillis,
        ),
        comfyPollIntervalMillis: requiredLong(
          draft.integrations.minimaxH3.comfyPollIntervalMillis,
        ),
        comfyMaxWaitMillis: requiredLong(
          draft.integrations.minimaxH3.comfyMaxWaitMillis,
        ),
      },
    },
    storageMedia: {
      uploadExpiresSeconds: requiredLong(draft.storageMedia.uploadExpiresSeconds),
      s3Enabled: draft.storageMedia.s3Enabled,
      s3PresignDefaultExpiresSeconds: requiredLong(
        draft.storageMedia.s3PresignDefaultExpiresSeconds,
      ),
      s3PresignMaxExpiresSeconds: requiredLong(
        draft.storageMedia.s3PresignMaxExpiresSeconds,
      ),
      canvasMediaProcessTimeoutMillis: requiredLong(
        draft.storageMedia.canvasMediaProcessTimeoutMillis,
      ),
      thumbnailMaxDimension: requiredInt(draft.storageMedia.thumbnailMaxDimension),
      thumbnailQuality: requiredInt(draft.storageMedia.thumbnailQuality),
    },
    advanced: {
      resourceMaxBytes: requiredLong(draft.advanced.resourceMaxBytes),
      processorLeaseDurationMillis: requiredLong(
        draft.advanced.processorLeaseDurationMillis,
      ),
      processorHeartbeatIntervalMillis: requiredLong(
        draft.advanced.processorHeartbeatIntervalMillis,
      ),
      threadResolveFailureDelayMillis: requiredLong(
        draft.advanced.threadResolveFailureDelayMillis,
      ),
      modelDispatchBusyFallbackDelayMillis: requiredLong(
        draft.advanced.modelDispatchBusyFallbackDelayMillis,
      ),
      toolPreflightFailureDelayMillis: requiredLong(
        draft.advanced.toolPreflightFailureDelayMillis,
      ),
      toolDispatchBusyFallbackDelayMillis: requiredLong(
        draft.advanced.toolDispatchBusyFallbackDelayMillis,
      ),
      dispatcherLeaseDurationMillis: requiredLong(
        draft.advanced.dispatcherLeaseDurationMillis,
      ),
      dispatcherPollIntervalMillis: requiredLong(
        draft.advanced.dispatcherPollIntervalMillis,
      ),
      dispatcherRejectionDelayMillis: requiredLong(
        draft.advanced.dispatcherRejectionDelayMillis,
      ),
      dispatcherMaxDispatchTasks: requiredInt(draft.advanced.dispatcherMaxDispatchTasks),
      dispatcherWorkerConcurrency: requiredInt(draft.advanced.dispatcherWorkerConcurrency),
      dispatcherWorkerQueueCapacity: requiredInt(
        draft.advanced.dispatcherWorkerQueueCapacity,
      ),
      canvasRealtimeMaxLength: requiredLong(draft.advanced.canvasRealtimeMaxLength),
      canvasFunctionExecutorCoreSize: requiredInt(
        draft.advanced.canvasFunctionExecutorCoreSize,
      ),
      canvasFunctionExecutorMaxSize: requiredInt(
        draft.advanced.canvasFunctionExecutorMaxSize,
      ),
      canvasFunctionExecutorQueueCapacity: requiredInt(
        draft.advanced.canvasFunctionExecutorQueueCapacity,
      ),
      applicationEventQueueCapacity: requiredInt(draft.advanced.applicationEventQueueCapacity),
      applicationEventMaxBytes: requiredLong(draft.advanced.applicationEventMaxBytes),
      applicationEventSendTimeoutMillis: requiredLong(
        draft.advanced.applicationEventSendTimeoutMillis,
      ),
      applicationEventHeartbeatIntervalMillis: requiredLong(
        draft.advanced.applicationEventHeartbeatIntervalMillis,
      ),
      postgresqlWorkNotificationPollMillis: requiredLong(
        draft.advanced.postgresqlWorkNotificationPollMillis,
      ),
      postgresqlWorkReconnectBackoffMillis: requiredLong(
        draft.advanced.postgresqlWorkReconnectBackoffMillis,
      ),
      redisRealtimeRetryDelayMillis: requiredLong(draft.advanced.redisRealtimeRetryDelayMillis),
    },
    expectedVersion,
  }
}

function requiredLong(value: string): string {
  if (value.trim() === '' || !/^\d+$/u.test(value.trim())) {
    throw new DraftValidationError('emptyNumericField')
  }
  return value.trim()
}

function requiredInt(value: string): number {
  if (value.trim() === '' || !/^\d+$/u.test(value.trim())) {
    throw new DraftValidationError('emptyNumericField')
  }
  return parseInt(value.trim(), 10)
}

function nullableInt(value: string): number | null {
  const trimmed = value.trim()
  if (trimmed === '') {
    return null
  }
  if (!/^\d+$/u.test(trimmed)) {
    throw new DraftValidationError('emptyNumericField')
  }
  return parseInt(trimmed, 10)
}

function nullableText(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}
