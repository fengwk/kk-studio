/**
 * 由 Web 控制器签名与生产 HTTP Jackson 3 mapper 生成的全部传输契约；请勿手改。
 *
 * 生成器：web 测试源码 TsContractGenerator
 * 重新生成：scripts/dev/verify/generated-contracts/write.sh
 * 漂移门禁：TsContractArtifactTest（mvn verify 覆盖）
 */

/** Java：fun.fengwk.kkstudio.project.dto.AddIssueEvidenceRequestDTO */
export interface AddIssueEvidenceRequestDTO {
  uploadId: string
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentDefinitionConfigDTO */
export interface AgentDefinitionConfigDTO {
  tools: string[]
  skills: SkillRefDTO[]
  subagents: string[]
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentDefinitionCreateDTO */
export interface AgentDefinitionCreateDTO {
  description?: string | null
  systemPrompt?: string | null
  execution?: HarnessExecutionSelectionDTO | null
  config: AgentDefinitionConfigDTO
  name: string
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentDefinitionDTO */
export interface AgentDefinitionDTO {
  name: string
  type: AgentDefinitionType
  description?: string | null
  systemPrompt?: string | null
  execution: HarnessExecutionSelectionDTO | null
  config: AgentDefinitionConfigDTO
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentDefinitionType */
export type AgentDefinitionType =
  | 'USER'
  | 'BUILTIN'

export const AGENT_DEFINITION_TYPE_VALUES: readonly AgentDefinitionType[] = [
  'USER',
  'BUILTIN',
]

/** Java：fun.fengwk.kkstudio.share.catalog.AgentDefinitionUpdateDTO */
export interface AgentDefinitionUpdateDTO {
  description?: string | null
  systemPrompt?: string | null
  execution?: HarnessExecutionSelectionDTO | null
  config: AgentDefinitionConfigDTO
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelAbilitiesDTO */
export interface AgentModelAbilitiesDTO {
  tools: boolean
  reasoning: boolean
  inputModalities: AgentModelInputModality[]
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelConfigDTO */
export interface AgentModelConfigDTO {
  limit: AgentModelLimitDTO
  abilities: AgentModelAbilitiesDTO
  pricing?: AgentModelPricingDTO | null
  defaultVariant?: string | null
  variants: AgentModelVariantDTO[]
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelCreateDTO */
export interface AgentModelCreateDTO {
  name: string
  kind: AgentModelKind
  description?: string | null
  providerName?: string | null
  modelId?: string | null
  config?: AgentModelConfigDTO | null
  group?: AgentModelGroupDTO | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelDTO */
export interface AgentModelDTO {
  name: string
  kind: AgentModelKind
  description?: string | null
  providerName?: string | null
  modelId?: string | null
  config?: AgentModelConfigDTO | null
  group?: AgentModelGroupDTO | null
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelGroupDTO */
export interface AgentModelGroupDTO {
  members: AgentModelGroupMemberDTO[]
  fallbackStatusCodes: number[]
  cooldownSeconds: number
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelGroupMemberDTO */
export interface AgentModelGroupMemberDTO {
  modelName: string
  variant: string
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelInputModality */
export type AgentModelInputModality =
  | 'TEXT'
  | 'IMAGE'
  | 'AUDIO'
  | 'VIDEO'
  | 'DOCUMENT'

export const AGENT_MODEL_INPUT_MODALITY_VALUES: readonly AgentModelInputModality[] = [
  'TEXT',
  'IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelKind */
export type AgentModelKind =
  | 'MODEL'
  | 'GROUP'

export const AGENT_MODEL_KIND_VALUES: readonly AgentModelKind[] = [
  'MODEL',
  'GROUP',
]

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelLimitDTO */
export interface AgentModelLimitDTO {
  context: number
  output: number
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingConditionDTO */
export interface AgentModelPricingConditionDTO {
  context?: AgentModelPricingContextConditionDTO | null
  serviceTier?: string[] | null
  date?: AgentModelPricingDateConditionDTO | null
  time?: AgentModelPricingTimeConditionDTO | null
  requestBody?: AgentModelPricingRequestBodyConditionDTO | null
  all?: AgentModelPricingConditionDTO[] | null
  any?: AgentModelPricingConditionDTO[] | null
  not?: AgentModelPricingConditionDTO | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingContextConditionDTO */
export interface AgentModelPricingContextConditionDTO {
  gt?: number | null
  gte?: number | null
  lt?: number | null
  lte?: number | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingDTO */
export interface AgentModelPricingDTO {
  base: AgentModelRateSetDTO
  overrides: AgentModelPricingOverrideDTO[]
  multipliers: AgentModelPricingMultiplierDTO[]
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingDateConditionDTO */
export interface AgentModelPricingDateConditionDTO {
  gte?: string | null
  lt?: string | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingFactorsDTO */
export interface AgentModelPricingFactorsDTO {
  input?: string | null
  output?: string | null
  cacheRead?: string | null
  cacheWrite?: string | null
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingMultiplierDTO */
export interface AgentModelPricingMultiplierDTO {
  id: string
  when: AgentModelPricingConditionDTO
  factors: AgentModelPricingFactorsDTO
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingOverrideDTO */
export interface AgentModelPricingOverrideDTO {
  id: string
  when: AgentModelPricingConditionDTO
  rates: AgentModelRatePatchDTO
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingRequestBodyConditionDTO */
export interface AgentModelPricingRequestBodyConditionDTO {
  path?: string | null
  equals?: unknown | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelPricingTimeConditionDTO */
export interface AgentModelPricingTimeConditionDTO {
  start: string
  end: string
  timezone: string
  weekdays?: number[] | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelRatePatchDTO */
export interface AgentModelRatePatchDTO {
  input?: string | null
  output?: string | null
  cacheRead?: string | null
  cacheWrite?: string | null
  cacheWriteByTtl?: Record<string, string> | null
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelRateSetDTO */
export interface AgentModelRateSetDTO {
  input: string
  output: string
  cacheRead: string
  cacheWrite: string
  cacheWriteByTtl: Record<string, string>
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelUpdateDTO */
export interface AgentModelUpdateDTO {
  name: string
  kind: AgentModelKind
  description?: string | null
  providerName?: string | null
  modelId?: string | null
  config?: AgentModelConfigDTO | null
  group?: AgentModelGroupDTO | null
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentModelVariantDTO */
export interface AgentModelVariantDTO {
  id: string
  promptCacheRetention: PromptCacheRetention
  reasoningEffort?: string | null
  protocolOptionsJson?: string | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentProviderCreateDTO */
export interface AgentProviderCreateDTO {
  description?: string | null
  providerType: string
  baseUrl?: string | null
  credential?: string | null
  modelCallTimeoutMillis?: number | null
  modelCallIdleTimeoutMillis?: number | null
  modelHttpRetryStatusCodes?: number[] | null
  name: string
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentProviderDTO */
export interface AgentProviderDTO {
  name: string
  description?: string | null
  providerType: string
  baseUrl?: string | null
  configured: boolean
  modelCallTimeoutMillis: string
  modelCallIdleTimeoutMillis: string
  modelHttpRetryStatusCodes: number[] | null
  promptCacheRetentions: PromptCacheRetention[]
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.catalog.AgentProviderUpdateDTO */
export interface AgentProviderUpdateDTO {
  description?: string | null
  providerType: string
  baseUrl?: string | null
  credential?: string | null
  modelCallTimeoutMillis?: number | null
  modelCallIdleTimeoutMillis?: number | null
  modelHttpRetryStatusCodes?: number[] | null
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.AgentTaskDTO */
export interface AgentTaskDTO {
  ticketId: string
  threadId: string
  agent: string
  state: string
  report: string
  partialResult: string
  error: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.AgentTaskSubmitDTO */
export interface AgentTaskSubmitDTO {
  ticketId: string
  agent: string
  prompt: string
  maxTurns?: number | null
}

/** Java：fun.fengwk.kkstudio.project.dto.AppendIssueActivityRequestDTO */
export interface AppendIssueActivityRequestDTO {
  expectedVersion: string
  requestKey: string
  kind?: string | null
  body: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.ApplyCanvasCommandsRequestDTO */
export interface ApplyCanvasCommandsRequestDTO {
  idempotencyKey: string
  commands: CanvasCommandDTO[]
}

/** Java：fun.fengwk.kkstudio.project.dto.ArchiveIssueRequestDTO */
export interface ArchiveIssueRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.project.dto.BlockIssueRequestDTO */
export interface BlockIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  reason: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.CreateNode */
export interface CanvasCommandCreateNode {
  type: 'CREATE_NODE'
  nodeId: string
  name: string
  transform: CanvasTransformDTO
  resources: CanvasResourceInputDTO[]
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.RenameNode */
export interface CanvasCommandRenameNode {
  type: 'RENAME_NODE'
  nodeId: string
  expectedName: string
  name: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.SetNodeResources */
export interface CanvasCommandSetNodeResources {
  type: 'SET_NODE_RESOURCES'
  nodeId: string
  expectedResourceIds: string[]
  resources: CanvasResourceInputDTO[]
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.SetNodeFunction */
export interface CanvasCommandSetNodeFunction {
  type: 'SET_NODE_FUNCTION'
  nodeId: string
  expectedFunction?: CanvasFunctionDTO | null
  function?: CanvasFunctionDTO | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.SetNodeGroup */
export interface CanvasCommandSetNodeGroup {
  type: 'SET_NODE_GROUP'
  nodeId: string
  expectedGroupId?: string | null
  groupId?: string | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.DeleteNode */
export interface CanvasCommandDeleteNode {
  type: 'DELETE_NODE'
  nodeId: string
  expectedResourceIds: string[]
  expectedFunction?: CanvasFunctionDTO | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.UpdateNodeTransform */
export interface CanvasCommandUpdateNodeTransform {
  type: 'UPDATE_NODE_TRANSFORM'
  nodeId: string
  transform: CanvasTransformDTO
  expectedTransform?: CanvasTransformDTO | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.CreateGroup */
export interface CanvasCommandCreateGroup {
  type: 'CREATE_GROUP'
  groupId: string
  title: string
  transform: CanvasTransformDTO
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.RenameGroup */
export interface CanvasCommandRenameGroup {
  type: 'RENAME_GROUP'
  groupId: string
  expectedTitle: string
  title: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.UpdateGroupTransform */
export interface CanvasCommandUpdateGroupTransform {
  type: 'UPDATE_GROUP_TRANSFORM'
  groupId: string
  transform: CanvasTransformDTO
  expectedTransform?: CanvasTransformDTO | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO.DeleteGroup */
export interface CanvasCommandDeleteGroup {
  type: 'DELETE_GROUP'
  groupId: string
  expectedMemberNodeIds: string[]
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasCommandDTO */
export type CanvasCommandDTO =
  | CanvasCommandCreateNode
  | CanvasCommandRenameNode
  | CanvasCommandSetNodeResources
  | CanvasCommandSetNodeFunction
  | CanvasCommandSetNodeGroup
  | CanvasCommandDeleteNode
  | CanvasCommandUpdateNodeTransform
  | CanvasCommandCreateGroup
  | CanvasCommandRenameGroup
  | CanvasCommandUpdateGroupTransform
  | CanvasCommandDeleteGroup

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO.TargetMissing */
export interface CanvasConflictTargetMissing {
  kind: 'TARGET_MISSING'
  targetId: string
  target: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO.TargetPresent */
export interface CanvasConflictTargetPresent {
  kind: 'TARGET_PRESENT'
  targetId: string
  target: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO.StaleNode */
export interface CanvasConflictStaleNode {
  kind: 'STALE_NODE'
  nodeId: string
  group: string
  current: CanvasResourceNodeDTO
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO.StaleGroup */
export interface CanvasConflictStaleGroup {
  kind: 'STALE_GROUP'
  groupId: string
  current: CanvasGroupDTO
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO.NodeRunning */
export interface CanvasConflictNodeRunning {
  kind: 'NODE_RUNNING'
  nodeId: string
  run: CanvasFunctionRunDTO
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO.NodeReferenced */
export interface CanvasConflictNodeReferenced {
  kind: 'NODE_REFERENCED'
  nodeId: string
  referencingNodeIds: string[]
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO */
export type CanvasConflictDTO =
  | CanvasConflictTargetMissing
  | CanvasConflictTargetPresent
  | CanvasConflictStaleNode
  | CanvasConflictStaleGroup
  | CanvasConflictNodeRunning
  | CanvasConflictNodeReferenced

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasDocumentDTO */
export interface CanvasDocumentDTO {
  id: string
  title: string
  revision: string
  createdAt: string
  updatedAt: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionCancelRequestDTO */
export interface CanvasFunctionCancelRequestDTO {
  requestId: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionDTO */
export interface CanvasFunctionDTO {
  name: string
  args: Record<string, unknown>
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionDefinitionDTO */
export interface CanvasFunctionDefinitionDTO {
  name: string
  description?: string | null
  argsSchema: Record<string, unknown>
  outputs: CanvasFunctionOutputDTO[]
  referencePolicy?: CanvasFunctionReferencePolicyDTO | null
  available?: boolean | null
  version: CanvasFunctionVersionDTO
  unavailableReason: string | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionOutputDTO */
export interface CanvasFunctionOutputDTO {
  kind: string
  name?: string | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionReferencePolicyDTO */
export interface CanvasFunctionReferencePolicyDTO {
  allowedKinds: string[]
  maxReferences?: number | null
  maxByKind?: Record<string, number> | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionRunDTO */
export interface CanvasFunctionRunDTO {
  nodeId: string
  requestId: string
  status: string
  stage: string
  error: string | null
  updatedAt: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionRunRequestDTO */
export interface CanvasFunctionRunRequestDTO {
  requestId: string
  expectedVersion: CanvasFunctionVersionDTO
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionUnknownResolutionDTO */
export interface CanvasFunctionUnknownResolutionDTO {
  requestId: string
  resolution: string
  verification: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionVersionDTO */
export interface CanvasFunctionVersionDTO {
  definitionFingerprint: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasGroupDTO */
export interface CanvasGroupDTO {
  id: string
  canvasId: string
  title: string
  transform: CanvasTransformDTO
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasGroupPatchDTO.Upsert */
export interface CanvasGroupPatchUpsert {
  op: 'UPSERT'
  group: CanvasGroupDTO
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasGroupPatchDTO.Remove */
export interface CanvasGroupPatchRemove {
  op: 'REMOVE'
  groupId: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasGroupPatchDTO */
export type CanvasGroupPatchDTO =
  | CanvasGroupPatchUpsert
  | CanvasGroupPatchRemove

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasNodePatchDTO.Upsert */
export interface CanvasNodePatchUpsert {
  op: 'UPSERT'
  node: CanvasResourceNodeDTO
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasNodePatchDTO.Remove */
export interface CanvasNodePatchRemove {
  op: 'REMOVE'
  nodeId: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasNodePatchDTO */
export type CanvasNodePatchDTO =
  | CanvasNodePatchUpsert
  | CanvasNodePatchRemove

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasPatchDTO */
export interface CanvasPatchDTO {
  revision: string
  groups: CanvasGroupPatchDTO[]
  nodes: CanvasNodePatchDTO[]
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasPresignedUrlDTO */
export interface CanvasPresignedUrlDTO {
  method: string
  url: string
  headers: Record<string, string>
  expiresAt: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasReferenceDTO */
export interface CanvasReferenceDTO {
  canvasId: string
  sourceNodeId: string
  targetNodeId: string
  index: number
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceDTO */
export interface CanvasResourceDTO {
  id: string
  canvasId: string
  ownerNodeId: string
  resourceIndex: number
  blobId: string | null
  name: string
  textContent: string | null
  kind: string
  mediaType: string | null
  sizeBytes: string | null
  width: number | null
  height: number | null
  durationMs: string | null
  createdAt: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceInputDTO.Keep */
export interface CanvasResourceInputKeep {
  kind: 'KEEP'
  resourceId: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceInputDTO.Text */
export interface CanvasResourceInputText {
  kind: 'TEXT'
  name: string
  textContent: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceInputDTO.Blob */
export interface CanvasResourceInputBlob {
  kind: 'BLOB'
  name: string
  blobId: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceInputDTO */
export type CanvasResourceInputDTO =
  | CanvasResourceInputKeep
  | CanvasResourceInputText
  | CanvasResourceInputBlob

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceNodeDTO */
export interface CanvasResourceNodeDTO {
  id: string
  canvasId: string
  name: string
  transform: CanvasTransformDTO
  groupId: string | null
  resources: CanvasResourceDTO[]
  function: CanvasFunctionDTO | null
  run: CanvasFunctionRunDTO | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasSnapshotDTO */
export interface CanvasSnapshotDTO {
  document: CanvasDocumentDTO
  nodes: CanvasResourceNodeDTO[]
  groups: CanvasGroupDTO[]
  references: CanvasReferenceDTO[]
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasTransformDTO */
export interface CanvasTransformDTO {
  x: number
  y: number
  width: number
  height: number
}

/** Java：fun.fengwk.kkstudio.chat.dto.ChatCreateDTO */
export interface ChatCreateDTO {
  title: string
  agentName: string
  environmentName?: string | null
  yoloEnabled?: boolean | null
}

/** Java：fun.fengwk.kkstudio.chat.dto.ChatDTO */
export interface ChatDTO {
  id: string
  title?: string | null
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.chat.dto.ChatPageDTO */
export interface ChatPageDTO {
  items: ChatDTO[]
  nextCursor: string | null
}

/** Java：fun.fengwk.kkstudio.chat.dto.ChatSessionPageDTO */
export interface ChatSessionPageDTO {
  items: HarnessSessionSummaryDTO[]
  nextCursor: string | null
}

/** Java：fun.fengwk.kkstudio.chat.dto.ChatUpdateDTO */
export interface ChatUpdateDTO {
  title: string
  agentName: string
  environmentName?: string | null
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.chat.dto.ChatYoloUpdateDTO */
export interface ChatYoloUpdateDTO {
  yoloEnabled: boolean
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncExportDTO */
export interface ConfigSyncExportDTO {
  yaml: string
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncExportRequestDTO */
export interface ConfigSyncExportRequestDTO {
  items: ConfigSyncRef[]
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckDTO */
export interface ConfigSyncImportCheckDTO {
  created: ConfigSyncRef[]
  updated: ConfigSyncRef[]
  skipped: ConfigSyncSkipped[]
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncImportCheckRequestDTO */
export interface ConfigSyncImportCheckRequestDTO {
  yaml: string
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncImportRequestDTO */
export interface ConfigSyncImportRequestDTO {
  yaml: string
  allowPartial?: boolean | null
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncImportResultDTO */
export interface ConfigSyncImportResultDTO {
  imported: ConfigSyncRef[]
  skipped: ConfigSyncSkipped[]
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncInventoryDTO */
export interface ConfigSyncInventoryDTO {
  items: ConfigSyncItem[]
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncItem */
export interface ConfigSyncItem {
  kind: ConfigSyncKind
  name: string
  dependencies: ConfigSyncRef[]
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncKind */
export type ConfigSyncKind =
  | 'providers'
  | 'models'
  | 'agents'
  | 'skillPackages'
  | 'environments'
  | 'mcpServers'
  | 'settings'
  | 'plugins'

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

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncRef */
export interface ConfigSyncRef {
  kind: ConfigSyncKind
  name: string
}

/** Java：fun.fengwk.kkstudio.share.configsync.ConfigSyncSkipped */
export interface ConfigSyncSkipped {
  kind: string
  name: string
  reason: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CreateCanvasRequestDTO */
export interface CreateCanvasRequestDTO {
  title?: string | null
}

/** Java：fun.fengwk.kkstudio.project.dto.CreateIssueRequestDTO */
export interface CreateIssueRequestDTO {
  title: string
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.project.dto.CreateProjectRequestDTO */
export interface CreateProjectRequestDTO {
  title: string
  description?: string | null
  yoloEnabled?: boolean | null
}

/** Java：fun.fengwk.kkstudio.share.environment.DaemonConfiguration */
export interface DaemonConfiguration {
  studioUrl: string
  note?: string | null
  bashExecutable?: string | null
  lsp?: DaemonLspConfiguration | null
}

/** Java：fun.fengwk.kkstudio.share.environment.DaemonLspConfiguration */
export interface DaemonLspConfiguration {
  servers: Record<string, DaemonLspServerConfiguration>
}

/** Java：fun.fengwk.kkstudio.share.environment.DaemonLspServerConfiguration */
export interface DaemonLspServerConfiguration {
  command: string[]
  extensions?: string[] | null
  rootMarkers?: string[] | null
  firstMatchMarkers?: string[] | null
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentCardDTO */
export interface EnvironmentCardDTO {
  id: string
  name: string
  installConfig?: EnvironmentInstallConfigDTO | null
  registrationToken?: string | null
  status: string
  ready: boolean
  statusExpiresAt: number | null
  lastSeen?: number | null
  operatingSystem?: string | null
  timeZone?: string | null
  userName?: string | null
  homeDirectory?: string | null
  note?: string | null
  daemonVersion?: string | null
  capabilities: LiveEnvironmentCapabilityDTO[]
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentCreateDTO */
export interface EnvironmentCreateDTO {
  name: string
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentEventDTO */
export interface EnvironmentEventDTO {
  time: number
  level: string
  type: string
  message: string
}

/** Java：fun.fengwk.kkstudio.ide.dto.EnvironmentIdeChangeDirectoryDTO */
export interface EnvironmentIdeChangeDirectoryDTO {
  documentId: string
  bindingEpoch: string
  directory: string
}

/** Java：fun.fengwk.kkstudio.ide.dto.EnvironmentIdeCloseViewDTO */
export interface EnvironmentIdeCloseViewDTO {
  documentId: string
  bindingEpoch: string
  discard: boolean
}

/** Java：fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO */
export interface EnvironmentIdeDirectoryListingDTO {
  path: string
  parentPath: string | null
  children: EnvironmentIdeDirectoryListingEntry[]
  truncated: boolean
}

/** Java：fun.fengwk.kkstudio.ide.dto.EnvironmentIdeDirectoryListingDTO.Entry */
export interface EnvironmentIdeDirectoryListingEntry {
  name: string
  path: string
}

/** Java：fun.fengwk.kkstudio.ide.dto.EnvironmentIdeOpenViewDTO */
export interface EnvironmentIdeOpenViewDTO {
  viewId: string
  documentId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  mode: string
  directory?: string | null
}

/** Java：fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewBindingDTO */
export interface EnvironmentIdeViewBindingDTO {
  documentId: string
  bindingEpoch: string
}

/** Java：fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewDTO */
export interface EnvironmentIdeViewDTO {
  viewId: string
  environmentId: string
  documentId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  daemonInstanceId: string
  bindingEpoch: string
  directory: string
  workspacePath: string
  state: string
  entryUrl?: string | null
}

/** Java：fun.fengwk.kkstudio.ide.dto.EnvironmentIdeViewsDTO */
export interface EnvironmentIdeViewsDTO {
  views: EnvironmentIdeViewDTO[]
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentInstallCodeDTO */
export interface EnvironmentInstallCodeDTO {
  code: string
  expiresAt: number
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentInstallCodeRequestDTO */
export interface EnvironmentInstallCodeRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentInstallConfigDTO */
export interface EnvironmentInstallConfigDTO {
  operatingSystem: string
  daemon: DaemonConfiguration
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentRotateTokenDTO */
export interface EnvironmentRotateTokenDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentRuntimeDTO */
export interface EnvironmentRuntimeDTO {
  daemonVersion: string
  update: EnvironmentUpdateProgressDTO | null
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentRuntimeStatusDTO */
export interface EnvironmentRuntimeStatusDTO {
  revision: string
  status: EnvironmentRuntimeDTO
}

/** Java：fun.fengwk.kkstudio.share.catalog.EnvironmentSupportDTO */
export type EnvironmentSupportDTO =
  | 'NONE'
  | 'ROUTED'
  | 'REQUIRED'

export const ENVIRONMENT_SUPPORT_DTO_VALUES: readonly EnvironmentSupportDTO[] = [
  'NONE',
  'ROUTED',
  'REQUIRED',
]

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentUpdateProgressDTO */
export interface EnvironmentUpdateProgressDTO {
  targetVersion: string
  downloadedBytes: string
  totalBytes: string | null
}

/** Java：fun.fengwk.kkstudio.share.environment.EnvironmentUpdateStartDTO */
export interface EnvironmentUpdateStartDTO {
  runtimeStatus: EnvironmentRuntimeStatusDTO
  commandStatus: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessAcceptedCommandsDTO */
export interface HarnessAcceptedCommandsDTO {
  session: HarnessSessionDTO
  rootEntry: HarnessSessionEntryDTO
  thread: HarnessThreadDTO
  acceptedCommands: HarnessThreadCommandDTO[]
  replayed: boolean
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessActiveAssistantOutputDTO */
export interface HarnessActiveAssistantOutputDTO {
  generationId: string
  turnStartEntryId: string
  requestHeadEntryId: string
  status: string
  attempt: number
  sequence: string
  contents: HarnessAgentMessageContentDTO[]
  error: HarnessAssistantErrorDTO | null
  resultEntryId: string | null
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessAgentMessageContentDTO */
export interface HarnessAgentMessageContentDTO {
  type: string
  text?: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessAssistantErrorDTO */
export interface HarnessAssistantErrorDTO {
  code: string
  message: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessBranchSettingsDTO */
export interface HarnessBranchSettingsDTO {
  agentName: string
  execution: HarnessExecutionSelectionDTO
  environmentName: string | null
  goal: HarnessGoalSettingDTO | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessCancelledInputDTO */
export interface HarnessCancelledInputDTO {
  sequence: string
  idempotencyKey: string
  type: string
  payloadJson: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessCommandBatchDTO */
export interface HarnessCommandBatchDTO {
  owner: HarnessCommandOwnerDTO
  target: HarnessCommandTargetDTO
  commands: HarnessCommandCreateDTO[]
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessCommandCreateDTO */
export interface HarnessCommandCreateDTO {
  type: string
  idempotencyKey: string
  contents?: HarnessUserMessageContentDTO[] | null
  agentName?: string | null
  execution?: HarnessExecutionSelectionDTO | null
  environmentName?: string | null
  text?: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessCommandOwnerDTO */
export interface HarnessCommandOwnerDTO {
  kind: string
  id: string
  title?: string | null
  link?: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessCommandTargetDTO */
export interface HarnessCommandTargetDTO {
  type: string
  sessionId?: string | null
  startEntryId?: string | null
  threadId?: string | null
  threadName?: string | null
  sourceThreadId?: string | null
  rootSettings?: HarnessBranchSettingsDTO | null
  yoloEnabled?: boolean | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessDraftPreviewRequestDTO */
export interface HarnessDraftPreviewRequestDTO {
  startEntryId: string
  commands: HarnessCommandCreateDTO[]
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessExecutionAbandonDTO */
export interface HarnessExecutionAbandonDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessExecutionExitDTO */
export interface HarnessExecutionExitDTO {
  executionId: string
  invocationId: string
  attempt: number
  kind: string
  registeredAt: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessExecutionSelectionDTO */
export interface HarnessExecutionSelectionDTO {
  driver: string
  settings: Record<string, unknown>
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessGenerationFailureDTO */
export interface HarnessGenerationFailureDTO {
  generationId: string
  turnStartEntryId: string
  requestHeadEntryId: string
  attempt: number
  sequence: string
  text: string
  thinking: string
  errorCode: string
  errorMessage: string
  failedAt: number
  retryAt: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessGoalSettingDTO */
export interface HarnessGoalSettingDTO {
  id: string
  text: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessManualCompactionDTO */
export interface HarnessManualCompactionDTO {
  available: boolean
  disabledReason: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessModelRequestDebugDTO.CacheControlDTO */
export interface HarnessModelRequestDebugCacheControlDTO {
  retention: string
  key: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessModelRequestDebugDTO */
export interface HarnessModelRequestDebugDTO {
  kind: string
  generatedAt: number
  model: HarnessModelSelectionDTO
  environmentName: string | null
  systemInstruction: string
  tools: HarnessModelRequestDebugToolDTO[]
  skills: HarnessModelRequestDebugSkillDTO[]
  subagents: HarnessModelRequestDebugSubagentDTO[]
  cacheControl: HarnessModelRequestDebugCacheControlDTO | null
  planningError: string | null
  frozenInvocation: HarnessModelRequestDebugFrozenInvocationDTO | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessModelRequestDebugDTO.FrozenInvocationDTO */
export interface HarnessModelRequestDebugFrozenInvocationDTO {
  kind: string
  generationId: string
  attempt: number
  requestJson: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessModelRequestDebugRequestDTO */
export interface HarnessModelRequestDebugRequestDTO {
  model: HarnessModelSelectionDTO
  environmentName?: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessModelRequestDebugDTO.SkillDTO */
export interface HarnessModelRequestDebugSkillDTO {
  packageName: string
  name: string
  description: string
  path: string
  delivery: string
  packageVersion: string
  headCommit: string | null
  artifactSha256: string
  installedSha256: string | null
  promptXml: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessModelRequestDebugDTO.SubagentDTO */
export interface HarnessModelRequestDebugSubagentDTO {
  name: string
  description: string
  tools: string[]
  skills: SkillRefDTO[]
  subagents: string[]
  configurationJson: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessModelRequestDebugDTO.ToolDTO */
export interface HarnessModelRequestDebugToolDTO {
  name: string
  description: string
  inputSchemaJson: string
  environmentSupport: EnvironmentSupportDTO
  provenance: string
  state: string
  filterReason: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessModelSelectionDTO */
export interface HarnessModelSelectionDTO {
  modelName: string
  variant?: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessNameUpdateDTO */
export interface HarnessNameUpdateDTO {
  name: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessProviderRequestPreviewDTO */
export interface HarnessProviderRequestPreviewDTO {
  kind: string
  generatedAt: number
  providerType: string
  modelName: string
  bodyByteSize: number
  bodyJson: string
  sourceHeadEntryId?: string | null
  notice?: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessSessionDTO */
export interface HarnessSessionDTO {
  sessionId: string
  name: string
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessSessionEntryDTO */
export interface HarnessSessionEntryDTO {
  entryId: string
  sessionId: string
  parentEntryId: string | null
  entryType: string
  payloadJson: string
  createTime: number
  usageCost: HarnessUsageCostDTO | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessSessionEntryPageDTO */
export interface HarnessSessionEntryPageDTO {
  items: HarnessSessionEntryDTO[]
  nextCursor: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessSessionFileContentDTO */
export interface HarnessSessionFileContentDTO {
  sessionId: string
  path: string
  sizeBytes: string
  revision: number
  updatedAt: string
  text: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessSessionFileEntryDTO */
export interface HarnessSessionFileEntryDTO {
  name: string
  path: string
  directory: boolean
  sizeBytes?: string | null
  revision?: number | null
  updatedAt?: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessSessionFileListDTO */
export interface HarnessSessionFileListDTO {
  sessionId: string
  path: string
  entries: HarnessSessionFileEntryDTO[]
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessSessionSummaryDTO */
export interface HarnessSessionSummaryDTO {
  sessionId: string
  name: string
  createdAt: number
  lastActivityAt: number
  firstMessagePreview: string | null
  threadCount: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessStoppedThreadReceiptDTO */
export interface HarnessStoppedThreadReceiptDTO {
  threadId: string
  stopRequestId: string
  stoppedTurnEndEntryId: string | null
  cancelledCommandCount: number
  cancelledInputs: HarnessCancelledInputDTO[]
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadCommandBatchDTO */
export interface HarnessThreadCommandBatchDTO {
  expectedHeadEntryId?: string | null
  expectedNextCommandSequence?: string | null
  commands: HarnessCommandCreateDTO[]
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadCommandDTO */
export interface HarnessThreadCommandDTO {
  threadId: string
  sequence: string
  type: string
  state: string
  idempotencyKey: string
  payloadJson: string
  cancelledAt: number | null
  createTime: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadCompactDTO */
export interface HarnessThreadCompactDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadCompactResultDTO */
export interface HarnessThreadCompactResultDTO {
  thread: HarnessThreadDTO
  turnStartEntryId: string
  childThreadId: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadDTO */
export interface HarnessThreadDTO {
  threadId: string
  parentThreadId: string | null
  name: string
  sessionId: string
  headEntryId: string
  rootThreadId: string
  yoloEnabled: boolean
  nextCommandSequence: string
  version: string
  status: string
  executionControl: string
  branchSettings: HarnessBranchSettingsDTO
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadSnapshotDTO */
export interface HarnessThreadSnapshotDTO {
  version: string
  thread: HarnessThreadDTO
  baseEntryId: string | null
  entries: HarnessSessionEntryDTO[]
  queuedCommands: HarnessThreadCommandDTO[]
  executionStatus: string
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null
  generationFailures: HarnessGenerationFailureDTO[]
  toolInvocations: ToolInvocationDTO[]
  stopReceipts: HarnessStoppedThreadReceiptDTO[]
  unconfirmedExecutions: HarnessExecutionExitDTO[]
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadStopDTO */
export interface HarnessThreadStopDTO {
  stopRequestId: string
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadStopResultDTO */
export interface HarnessThreadStopResultDTO {
  status: string
  thread: HarnessThreadDTO
  stoppedThreads: HarnessStoppedThreadReceiptDTO[]
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadStreamCatchupDTO */
export interface HarnessThreadStreamCatchupDTO {
  invocationId: string
  attempt: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadSummaryDTO */
export interface HarnessThreadSummaryDTO {
  threadId: string
  parentThreadId: string | null
  name: string
  createdAt: number
  updatedAt: number
  status: string
  executionControl: string
  execution: HarnessExecutionSelectionDTO
  headMessagePreview: string | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadTreeNodeDTO */
export interface HarnessThreadTreeNodeDTO {
  threadId: string
  parentThreadId: string | null
  name: string
  agentName: string
  execution: HarnessExecutionSelectionDTO
  status: string
  executionControl: string
  turnCount: number
  toolCallCount: number
  outcome: string | null
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessThreadYoloUpdateDTO */
export interface HarnessThreadYoloUpdateDTO {
  yoloEnabled: boolean
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessToolApprovalDTO */
export interface HarnessToolApprovalDTO {
  decision: string
  decisionId: string
  reason?: string | null
}

/** Java：fun.fengwk.kkstudio.share.interaction.HarnessToolInputDTO */
export interface HarnessToolInputDTO {
  threadId: string
  submissionId: string
  declined?: boolean | null
  answers?: (string[])[] | null
}

/** Java：fun.fengwk.kkstudio.share.interaction.HarnessToolInputResultDTO */
export interface HarnessToolInputResultDTO {
  threadId: string
  interactionId: string
  submissionId: string
  actor: string
  acceptedAt: number
  materialized: boolean
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessUsageCostDTO */
export interface HarnessUsageCostDTO {
  currency: string
  amount: string
}

/** Java：fun.fengwk.kkstudio.share.runtime.HarnessUserMessageContentDTO */
export interface HarnessUserMessageContentDTO {
  type: string
  text?: string | null
  uploadId?: string | null
  blobId?: string | null
  name?: string | null
  preview?: string | null
  imageTier?: string | null
}

/** Java：fun.fengwk.kkstudio.ide.browser.IdeBrowserUseIntentReply */
export interface IdeBrowserUseIntentReply {
  matched: boolean
}

/** Java：fun.fengwk.kkstudio.ide.browser.IdeBrowserUseSnapshot */
export interface IdeBrowserUseSnapshot {
  eventNodeId: string
  connectionId: string
  documentId: string
  revision: string
  views: IdeBrowserUseSnapshotView[]
}

/** Java：fun.fengwk.kkstudio.ide.browser.IdeBrowserUseSnapshot.View */
export interface IdeBrowserUseSnapshotView {
  environmentId: string
  viewId: string
  claimId: string
  bindingEpoch: string
}

/** Java：fun.fengwk.kkstudio.share.interaction.InteractionDTO */
export interface InteractionDTO {
  type: string
  interactionId: string | null
  status: string | null
  threadId: string | null
  sessionId: string | null
  rootThreadId: string
  owner: InteractionOwnerDTO
  toolCallId: string | null
  toolName: string | null
  argumentsJson: string | null
  approvalJson: string | null
  environmentId: string | null
  environmentName: string | null
  waitingCount: number | null
  createTime: number
}

/** Java：fun.fengwk.kkstudio.share.interaction.InteractionOwnerDTO */
export interface InteractionOwnerDTO {
  kind: string
  id: string
  title: string | null
  link: string | null
}

/** Java：fun.fengwk.kkstudio.share.interaction.InteractionPageDTO */
export interface InteractionPageDTO {
  items: InteractionDTO[]
  nextCursor: string | null
  total: number
  freshnessAt: number | null
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueActivityDTO */
export interface IssueActivityDTO {
  issueId: string
  sequence: string
  kind: string
  actorType: string
  actorAgentName: string | null
  runId: string | null
  body: string | null
  data?: unknown | null
  createdAt: string
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueAgentThreadDTO */
export interface IssueAgentThreadDTO {
  issueId: string
  agentName: string
  threadId: string
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueDTO */
export interface IssueDTO {
  id: string
  projectId: string
  number: string
  title: string
  description: string
  state: string
  blockedFromState: string | null
  blockReason: string | null
  pauseReason: string | null
  pauseDetail: string | null
  version: string
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueDetailDTO */
export interface IssueDetailDTO {
  issue: IssueDTO
  activities: IssueActivityDTO[]
  nextActivityCursor: string | null
  runs: IssueRunDTO[]
  currentRun: IssueRunDTO | null
  latestRun: IssueRunDTO | null
  stageBudgets: IssueStageBudgetDTO[]
  agentThreads: IssueAgentThreadDTO[]
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueEvidenceDTO */
export interface IssueEvidenceDTO {
  issueId: string
  blobId: string
  uri: string
  name?: string | null
  actorAgentName: string | null
  runId: string | null
  createdAt: string
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueRunDTO */
export interface IssueRunDTO {
  id: string
  issueId: string
  ordinal: string
  state: string
  agentName: string | null
  sessionId: string
  threadId: string
  status: string
  startEntryId: string
  endEntryId: string | null
  finalAnswerEntryId: string | null
  nextState: string | null
  observedActivitySequence: string
  remainingExecutionMs: string
  error: string | null
  version: string
  startedAt: string
  endedAt: string | null
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueRunSummaryDTO */
export interface IssueRunSummaryDTO {
  id: string
  issueId: string
  ordinal: string
  state: string
  status: string
  agentName: string | null
  startedAt: string
  endedAt: string | null
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueStageBudgetDTO */
export interface IssueStageBudgetDTO {
  state: string
  maxRuns: number
  budgetAfterOrdinal: string
  usedRuns: string
  remainingRuns: string
}

/** Java：fun.fengwk.kkstudio.share.environment.LiveEnvironmentCapabilityDTO */
export interface LiveEnvironmentCapabilityDTO {
  id: string
}

/** Java：fun.fengwk.kkstudio.share.mcp.McpServerConfigDTO */
export interface McpServerConfigDTO {
  name: string
  version: string
  url: string
  headers?: Record<string, string> | null
}

/** Java：fun.fengwk.kkstudio.share.mcp.McpServerCreateDTO */
export interface McpServerCreateDTO {
  name: string
  url: string
  headers?: Record<string, string> | null
  enabled?: boolean | null
  timeoutMillis?: number | null
}

/** Java：fun.fengwk.kkstudio.share.mcp.McpServerDTO */
export interface McpServerDTO {
  name: string
  enabled: boolean
  timeoutMillis: string
  discoveryStatus: string
  toolCount: number
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.mcp.McpServerUpdateDTO */
export interface McpServerUpdateDTO {
  expectedVersion: string
  url: string
  headers?: Record<string, string> | null
  enabled?: boolean | null
  timeoutMillis?: number | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.ModelCatalogPageDTO */
export interface ModelCatalogPageDTO {
  generation: string
  fetchedAt?: number | null
  loading: boolean
  error?: string | null
  items: ModelDefaultCandidateDTO[]
  nextCursor?: string | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.ModelDefaultAbilitiesDTO */
export interface ModelDefaultAbilitiesDTO {
  tools?: boolean | null
  reasoning?: boolean | null
  inputModalities?: AgentModelInputModality[] | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.ModelDefaultCandidateDTO */
export interface ModelDefaultCandidateDTO {
  id: string
  provider: string
  modelId: string
  name: string
  limit?: ModelDefaultLimitDTO | null
  abilities: ModelDefaultAbilitiesDTO
  pricing?: AgentModelPricingDTO | null
  variants?: AgentModelVariantDTO[] | null
  defaultVariant?: string | null
  supportIssues: string[]
  pricingSupported: boolean
}

/** Java：fun.fengwk.kkstudio.share.catalog.ModelDefaultLimitDTO */
export interface ModelDefaultLimitDTO {
  context?: number | null
  output?: number | null
}

/** Java：fun.fengwk.kkstudio.common.page.Page */
export interface Page<T> {
  pageNumber: number
  pageSize: number
  results: T[]
  totalCount: string
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginAuthCompleteRequestDTO */
export interface PluginAuthCompleteRequestDTO {
  authAttemptId: string
  callbackUrl: string
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginAuthKindDTO.DeepLink */
export interface PluginAuthKindDeepLink {
  type: 'DEEP_LINK'
  regionCandidates: string[]
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginAuthKindDTO.StaticKey */
export interface PluginAuthKindStaticKey {
  type: 'STATIC_KEY'
  secretField: string
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginAuthKindDTO */
export type PluginAuthKindDTO =
  | PluginAuthKindDeepLink
  | PluginAuthKindStaticKey

/** Java：fun.fengwk.kkstudio.share.plugin.PluginAuthPrepareDTO */
export interface PluginAuthPrepareDTO {
  authAttemptId: string
  loginUrl: string
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginAuthPrepareRequestDTO */
export interface PluginAuthPrepareRequestDTO {
  region: string
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginConfigurationDTO */
export interface PluginConfigurationDTO {
  revision?: string | null
  valuesJson: string
  valuesSchema: string
  defaultsJson: string
  secrets: Record<string, PluginSecretProjectionDTO>
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginConfigurationUpdateRequestDTO */
export interface PluginConfigurationUpdateRequestDTO {
  expectedRevision: string
  valuesJson: string
  secretPatch?: Record<string, PluginSecretPatchDTO> | null
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginConnectionCheckDTO.Authentication */
export type PluginConnectionCheckAuthentication =
  | 'ACCEPTED'
  | 'REJECTED'
  | 'UNAVAILABLE'
  | 'NOT_CHECKED'

export const PLUGIN_CONNECTION_CHECK_AUTHENTICATION_VALUES: readonly PluginConnectionCheckAuthentication[] = [
  'ACCEPTED',
  'REJECTED',
  'UNAVAILABLE',
  'NOT_CHECKED',
]

/** Java：fun.fengwk.kkstudio.share.plugin.PluginConnectionCheckDTO.BrowserSession */
export type PluginConnectionCheckBrowserSession = 'UNKNOWN'

export const PLUGIN_CONNECTION_CHECK_BROWSER_SESSION_VALUES: readonly PluginConnectionCheckBrowserSession[] = [
  'UNKNOWN',
]

/** Java：fun.fengwk.kkstudio.share.plugin.PluginConnectionCheckDTO */
export interface PluginConnectionCheckDTO {
  pluginId: string
  appliedRevision: string
  checkedAt: number
  reachability: PluginConnectionCheckReachability
  authentication: PluginConnectionCheckAuthentication
  browserSession: PluginConnectionCheckBrowserSession
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginConnectionCheckDTO.Reachability */
export type PluginConnectionCheckReachability =
  | 'REACHABLE'
  | 'UNREACHABLE'
  | 'NOT_CHECKED'
  | 'INDETERMINATE'

export const PLUGIN_CONNECTION_CHECK_REACHABILITY_VALUES: readonly PluginConnectionCheckReachability[] = [
  'REACHABLE',
  'UNREACHABLE',
  'NOT_CHECKED',
  'INDETERMINATE',
]

/** Java：fun.fengwk.kkstudio.share.plugin.PluginConnectionCheckRequestDTO */
export interface PluginConnectionCheckRequestDTO {
  expectedRevision: string
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginDTO */
export interface PluginDTO {
  pluginId: string
  name: string
  version: string
  authKind?: PluginAuthKindDTO | null
  status?: string | null
  region?: string | null
  expiresAt?: number | null
  nextRefreshAt?: number | null
  lastRefreshedAt?: number | null
  lastRefreshError?: string | null
  configuration: PluginConfigurationDTO
  desiredRevision: string
  appliedRevision?: string | null
  connectionCheckSupported: boolean
  harnessToolNames: string[]
  canvasFunctionNames: string[]
  applicationError?: string | null
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginSecretPatchDTO */
export interface PluginSecretPatchDTO {
  operation: string
  value?: string | null
}

/** Java：fun.fengwk.kkstudio.share.plugin.PluginSecretProjectionDTO */
export interface PluginSecretProjectionDTO {
  description: string
  configured: boolean
}

/** Java：fun.fengwk.kkstudio.project.dto.ProjectDTO */
export interface ProjectDTO {
  id: string
  title: string
  description: string
  workflow: ProjectWorkflowDTO
  yoloEnabled: boolean
  nextIssueNumber: string
  version: string
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

/** Java：fun.fengwk.kkstudio.project.dto.ProjectIssueSnapshotDTO */
export interface ProjectIssueSnapshotDTO {
  issue: IssueDTO
  currentOrLatestRun: IssueRunSummaryDTO | null
}

/** Java：fun.fengwk.kkstudio.project.dto.ProjectSnapshotDTO */
export interface ProjectSnapshotDTO {
  project: ProjectDTO
  issues: ProjectIssueSnapshotDTO[]
  referencedStateCodes: string[]
}

/** Java：fun.fengwk.kkstudio.project.dto.ProjectVersionRequestDTO */
export interface ProjectVersionRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.project.dto.ProjectWorkflowDTO */
export interface ProjectWorkflowDTO {
  states: ProjectWorkflowStateDTO[]
}

/** Java：fun.fengwk.kkstudio.project.dto.ProjectWorkflowStateDTO */
export interface ProjectWorkflowStateDTO {
  state: string
  name: string
  agent: string | null
  environment: string | null
  instructions: string | null
  maxRuns: string | null
  enabled?: boolean | null
  next?: string[] | null
}

/** Java：fun.fengwk.kkstudio.share.catalog.PromptCacheRetention */
export type PromptCacheRetention =
  | 'NONE'
  | 'SHORT'
  | 'LONG'

export const PROMPT_CACHE_RETENTION_VALUES: readonly PromptCacheRetention[] = [
  'NONE',
  'SHORT',
  'LONG',
]

/** Java：fun.fengwk.kkstudio.project.dto.RecoverIssueRequestDTO */
export interface RecoverIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

/** Java：fun.fengwk.kkstudio.project.dto.ReopenIssueRequestDTO */
export interface ReopenIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

/** Java：fun.fengwk.kkstudio.project.dto.ResetStageBudgetRequestDTO */
export interface ResetStageBudgetRequestDTO {
  expectedVersion: string
  requestKey: string
  state: string
  maxRuns: number
}

/** Java：fun.fengwk.kkstudio.project.dto.ResumeIssueRequestDTO */
export interface ResumeIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillArchiveDownloadDTO */
export interface SkillArchiveDownloadDTO {
  filename: string
  sha256: string
  sizeBytes: string
  download: StoragePresignedUrlDTO
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillEnvironmentSyncDTO */
export interface SkillEnvironmentSyncDTO {
  environmentId: string
  installedSha256: string | null
  localPath: string | null
  error: string | null
  upToDate: boolean
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillGitSourceDTO */
export interface SkillGitSourceDTO {
  repositoryUrl: string
  branch: string
  hasToken: boolean
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillManifestEntryDTO */
export interface SkillManifestEntryDTO {
  name: string
  description: string
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageCheckDTO */
export interface SkillPackageCheckDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageDetailDTO */
export interface SkillPackageDetailDTO {
  packageName: string
  description?: string | null
  packageVersion: string
  publishedAt: number
  skills: SkillManifestEntryDTO[]
  source: string
  git: SkillGitSourceDTO | null
  headCommit: string | null
  headCheckedAt: number | null
  headCheckError: string | null
  updateAvailable: boolean
  artifactSha256: string
  artifactSizeBytes: string
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageEditDTO */
export interface SkillPackageEditDTO {
  expectedVersion: string
  description?: string | null
  branch?: string | null
  token?: string | null
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageFileDTO */
export interface SkillPackageFileDTO {
  path: string
  size: string
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageGitCreateDTO */
export interface SkillPackageGitCreateDTO {
  packageName: string
  description?: string | null
  repositoryUrl: string
  branch: string
  token?: string | null
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageGitPublishDTO */
export interface SkillPackageGitPublishDTO {
  expectedVersion: string
  targetCommit: string
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackagePreviewDTO */
export interface SkillPackagePreviewDTO {
  stagingUploadId: string
  packageName: string
  packageVersion: string
  description?: string | null
  skills: SkillManifestEntryDTO[]
  ignoredDirectories: string[]
  archiveSha256: string
  archiveSizeBytes: string
  contentSizeBytes: string
  fileCount: number
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageSummaryDTO */
export interface SkillPackageSummaryDTO {
  packageName: string
  description?: string | null
  packageVersion: string
  publishedAt: number
  skills: SkillManifestEntryDTO[]
  source: string
  updateAvailable: boolean
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageUploadPreviewDTO */
export interface SkillPackageUploadPreviewDTO {
  uploadId: string
  packageName: string
  packageVersion: string
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillPackageUploadPublishDTO */
export interface SkillPackageUploadPublishDTO {
  stagingUploadId: string
  packageName: string
  packageVersion: string
  description?: string | null
  expectedVersion?: string | null
}

/** Java：fun.fengwk.kkstudio.share.skill.SkillRefDTO */
export interface SkillRefDTO {
  packageName: string
  name: string
}

/** Java：fun.fengwk.kkstudio.project.dto.StopIssueRequestDTO */
export interface StopIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  detail?: string | null
}

/** Java：fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO */
export interface StoragePresignedUrlDTO {
  method: string
  url: string
  headers: Record<string, string>
  expiresAt: string
  mediaType: string | null
  sizeBytes: string | null
}

/** Java：fun.fengwk.kkstudio.share.storage.StorageUploadDTO */
export interface StorageUploadDTO {
  id: string
  state: StorageUploadState
  blobId: string | null
  presignedPut: StoragePresignedUrlDTO | null
  expiresAt: number
}

/** Java：fun.fengwk.kkstudio.share.storage.StorageUploadReserveRequestDTO */
export interface StorageUploadReserveRequestDTO {
  filename: string
  mediaType: string
  sha256: string
  sizeBytes: number
}

/** Java：fun.fengwk.kkstudio.share.storage.StorageUploadState */
export type StorageUploadState =
  | 'PENDING'
  | 'READY'

export const STORAGE_UPLOAD_STATE_VALUES: readonly StorageUploadState[] = [
  'PENDING',
  'READY',
]

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsAdvancedDTO */
export interface SystemSettingsAdvancedDTO {
  resourceMaxBytes: string
  processorLeaseDurationMillis: string
  processorHeartbeatIntervalMillis: string
  threadResolveFailureDelayMillis: string
  modelDispatchBusyFallbackDelayMillis: string
  toolPreflightFailureDelayMillis: string
  toolDispatchBusyFallbackDelayMillis: string
  applicationEventQueueCapacity: number
  applicationEventMaxBytes: string
  applicationEventSendTimeoutMillis: string
  applicationEventHeartbeatIntervalMillis: string
  postgresqlWorkNotificationPollMillis: string
  postgresqlWorkReconnectBackoffMillis: string
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsAiRuntimeDTO */
export interface SystemSettingsAiRuntimeDTO {
  retryMaxRetries: number
  retryBackoffStrategy: string
  retryBaseDelayMillis: string
  retryMaxDelayMillis: string
  compactionKeepRecentTokens: number
  subagentMaxDepth: number
  subagentMaxConcurrency: number
  subagentMaxTotalConcurrency: number
  subagentMaxTurns: number
  modelHttpRetryStatusCodes: number[]
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsDTO */
export interface SystemSettingsDTO {
  tool: SystemSettingsToolDTO
  aiRuntime: SystemSettingsAiRuntimeDTO
  environment: SystemSettingsEnvironmentDTO
  network: SystemSettingsNetworkDTO
  storageMedia: SystemSettingsStorageMediaDTO
  advanced: SystemSettingsAdvancedDTO
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsEnvironmentDTO */
export interface SystemSettingsEnvironmentDTO {
  maxResourceBytes: string
  heartbeatTimeoutMillis: string
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsNetworkDTO */
export interface SystemSettingsNetworkDTO {
  proxyUrl?: string | null
  noProxyHosts: string
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO.ApplyTiming */
export type SystemSettingsSchemaApplyTiming =
  | 'NEXT_INVOCATION'
  | 'NEXT_HTTP_REQUEST'
  | 'RESTART'

export const SYSTEM_SETTINGS_SCHEMA_APPLY_TIMING_VALUES: readonly SystemSettingsSchemaApplyTiming[] = [
  'NEXT_INVOCATION',
  'NEXT_HTTP_REQUEST',
  'RESTART',
]

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO */
export interface SystemSettingsSchemaDTO {
  sections: SystemSettingsSchemaSectionDTO[]
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO.FieldDTO */
export interface SystemSettingsSchemaFieldDTO {
  path: string
  labelKey: string
  hintKey: string | null
  type: SystemSettingsSchemaFieldType
  nullable: boolean
  min: number | null
  max: number | null
  options: SystemSettingsSchemaOptionDTO[] | null
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO.FieldType */
export type SystemSettingsSchemaFieldType =
  | 'BOOLEAN'
  | 'INTEGER'
  | 'INTEGER_LIST'
  | 'LONG'
  | 'TEXT'
  | 'ENUM'
  | 'PERMISSION'

export const SYSTEM_SETTINGS_SCHEMA_FIELD_TYPE_VALUES: readonly SystemSettingsSchemaFieldType[] = [
  'BOOLEAN',
  'INTEGER',
  'INTEGER_LIST',
  'LONG',
  'TEXT',
  'ENUM',
  'PERMISSION',
]

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO.GroupDTO */
export interface SystemSettingsSchemaGroupDTO {
  key: string
  labelKey: string
  descriptionKey: string
  restartRequired: boolean
  applyTiming?: SystemSettingsSchemaApplyTiming | null
  fields: SystemSettingsSchemaFieldDTO[]
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO.OptionDTO */
export interface SystemSettingsSchemaOptionDTO {
  value: string
  labelKey: string
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsSchemaDTO.SectionDTO */
export interface SystemSettingsSchemaSectionDTO {
  key: string
  labelKey: string
  descriptionKey: string
  restartRequired: boolean
  groups: SystemSettingsSchemaGroupDTO[]
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsStorageMediaDTO */
export interface SystemSettingsStorageMediaDTO {
  uploadExpiresSeconds: string
  s3PresignDefaultExpiresSeconds: string
  s3PresignMaxExpiresSeconds: string
  canvasMediaProcessTimeoutMillis: string
  thumbnailMaxDimension: number
  thumbnailQuality: number
  temporaryResourceTtlSeconds: string
  temporaryResourceCleanupIntervalSeconds: string
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsToolDTO */
export interface SystemSettingsToolDTO {
  permission: Record<string, SystemSettingsToolPermissionRuleDTO[]>
  modelGatewayBusyRetryMillis: string
  toolGatewayOverloadRetryMillis: string
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsToolDTO.PermissionRuleDTO */
export interface SystemSettingsToolPermissionRuleDTO {
  pattern: string
  action: string
}

/** Java：fun.fengwk.kkstudio.share.systemsettings.SystemSettingsUpdateDTO */
export interface SystemSettingsUpdateDTO {
  tool: SystemSettingsToolDTO
  aiRuntime: SystemSettingsAiRuntimeDTO
  environment: SystemSettingsEnvironmentDTO
  network: SystemSettingsNetworkDTO
  storageMedia: SystemSettingsStorageMediaDTO
  advanced: SystemSettingsAdvancedDTO
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.catalog.ToolCatalogEntryDTO */
export interface ToolCatalogEntryDTO {
  name: string
  description: string
  environmentSupport?: EnvironmentSupportDTO | null
}

/** Java：fun.fengwk.kkstudio.share.runtime.ToolInvocationDTO */
export interface ToolInvocationDTO {
  id: string
  threadId: string
  turnStartEntryId: string
  assistantEntryId: string
  callIndex: number
  status: string
  attempt: number
  toolCallId: string
  toolName: string
  rendererKey: string
  environmentId: string | null
  requiredEnvironmentId: string | null
  requiredEnvironmentName: string | null
  environmentWaitFreshnessAt: number | null
  waitingForEnvironment: boolean
  argumentsJson: string
  approvalJson: string | null
  resultJson: string | null
  errorJson: string | null
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.project.dto.TransitionIssueRequestDTO */
export interface TransitionIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  toState: string
}

/** Java：fun.fengwk.kkstudio.project.dto.UnarchiveIssueRequestDTO */
export interface UnarchiveIssueRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.project.dto.UpdateIssueRequestDTO */
export interface UpdateIssueRequestDTO {
  expectedVersion: string
  title?: string | null
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.project.dto.UpdateProjectRequestDTO */
export interface UpdateProjectRequestDTO {
  expectedVersion: string
  title?: string | null
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.project.dto.UpdateProjectWorkflowRequestDTO */
export interface UpdateProjectWorkflowRequestDTO {
  expectedVersion: string
  workflow: ProjectWorkflowDTO
}

/** Java：fun.fengwk.kkstudio.project.dto.UpdateProjectYoloRequestDTO */
export interface UpdateProjectYoloRequestDTO {
  expectedVersion: string
  yoloEnabled: boolean
}

export interface ApiRoutes {
  'DELETE /api/agents/{name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/chat/chats/{chatId}': {
    path: { chatId: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/environments/{environmentId}': {
    path: { environmentId: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/environments/{environmentId}/ide/views/{viewId}': {
    path: { environmentId: string; viewId: string }
    body: EnvironmentIdeCloseViewDTO
    response: void
  }
  'DELETE /api/issues/{issueId}': {
    path: { issueId: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/mcp-servers/{name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/models/{*modelName}': {
    path: { modelName: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/plugins/{pluginId}/auth': {
    path: { pluginId: string }
    response: void
  }
  'DELETE /api/projects/{projectId}': {
    path: { projectId: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/providers/{name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/skill-packages/{name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/storage/uploads/{uploadId}': {
    path: { uploadId: string }
    response: void
  }
  'GET /api/agent-tasks/{ticketId}': {
    path: { ticketId: string }
    response: AgentTaskDTO
  }
  'GET /api/agents': {
    query: { pageNumber?: number; pageSize?: number }
    response: Page<AgentDefinitionDTO>
  }
  'GET /api/canvas-functions': {
    response: CanvasFunctionDefinitionDTO[]
  }
  'GET /api/canvases': {
    response: CanvasDocumentDTO[]
  }
  'GET /api/canvases/{canvasId}': {
    path: { canvasId: string }
    response: CanvasSnapshotDTO
  }
  'GET /api/canvases/{canvasId}/nodes/{nodeId}/function-run': {
    path: { canvasId: string; nodeId: string }
    response: CanvasFunctionRunDTO
  }
  'GET /api/chat/chats': {
    query: { cursor?: string; limit?: number }
    response: ChatPageDTO
  }
  'GET /api/chat/chats/{chatId}': {
    path: { chatId: string }
    response: ChatDTO
  }
  'GET /api/chat/chats/{chatId}/sessions': {
    path: { chatId: string }
    query: { cursor?: string; limit?: number }
    response: ChatSessionPageDTO
  }
  'GET /api/chat/chats/{chatId}/sessions/{sessionId}': {
    path: { chatId: string; sessionId: string }
    response: HarnessSessionSummaryDTO
  }
  'GET /api/environments': {
    response: EnvironmentCardDTO[]
  }
  'GET /api/environments/uninstall/{operatingSystem}': {
    path: { operatingSystem: string }
    response: string
    responseType: 'text'
  }
  'GET /api/environments/{environmentId}': {
    path: { environmentId: string }
    response: EnvironmentCardDTO
  }
  'GET /api/environments/{environmentId}/events': {
    path: { environmentId: string }
    response: EnvironmentEventDTO[]
  }
  'GET /api/environments/{environmentId}/ide/directories': {
    path: { environmentId: string }
    query: { path?: string }
    response: EnvironmentIdeDirectoryListingDTO
  }
  'GET /api/environments/{environmentId}/install': {
    path: { environmentId: string }
    query: { code: string }
    response: string
    responseType: 'text'
  }
  'GET /api/environments/{environmentId}/runtime': {
    path: { environmentId: string }
    response: EnvironmentRuntimeStatusDTO
  }
  'GET /api/interactions': {
    query: { cursor?: string; limit?: string; rootThreadId?: string }
    response: InteractionPageDTO
  }
  'GET /api/issues/{issueId}': {
    path: { issueId: string }
    query: { afterSequence?: string; limit?: string }
    response: IssueDetailDTO
  }
  'GET /api/issues/{issueId}/evidence': {
    path: { issueId: string }
    response: IssueEvidenceDTO[]
  }
  'GET /api/mcp-servers': {
    query: { pageNumber?: number; pageSize?: number }
    response: Page<McpServerDTO>
  }
  'GET /api/mcp-servers/{name}': {
    path: { name: string }
    response: McpServerDTO
  }
  'GET /api/mcp-servers/{name}/config': {
    path: { name: string }
    response: McpServerConfigDTO
  }
  'GET /api/model-defaults': {
    query: { cursor?: string; pageSize?: number; query?: string }
    response: ModelCatalogPageDTO
  }
  'GET /api/models': {
    query: { pageNumber?: number; pageSize?: number }
    response: Page<AgentModelDTO>
  }
  'GET /api/plugins': {
    response: PluginDTO[]
  }
  'GET /api/plugins/{pluginId}': {
    path: { pluginId: string }
    response: PluginDTO
  }
  'GET /api/projects': {
    query: { includeArchived?: boolean }
    response: ProjectDTO[]
  }
  'GET /api/projects/{projectId}': {
    path: { projectId: string }
    response: ProjectDTO
  }
  'GET /api/projects/{projectId}/snapshot': {
    path: { projectId: string }
    response: ProjectSnapshotDTO
  }
  'GET /api/providers': {
    query: { pageNumber?: number; pageSize?: number }
    response: Page<AgentProviderDTO>
  }
  'GET /api/providers/{name}/remote-models': {
    path: { name: string }
    response: string[]
  }
  'GET /api/sessions/{sessionId}/entries': {
    path: { sessionId: string }
    query: { cursor?: string; limit?: string }
    response: HarnessSessionEntryPageDTO
  }
  'GET /api/sessions/{sessionId}/entries/{entryId}/provider-request-preview': {
    path: { entryId: string; sessionId: string }
    response: HarnessProviderRequestPreviewDTO
  }
  'GET /api/sessions/{sessionId}/files': {
    path: { sessionId: string }
    query: { ownerId: string; ownerKind: string; path?: string }
    response: HarnessSessionFileListDTO
  }
  'GET /api/sessions/{sessionId}/files/content': {
    path: { sessionId: string }
    query: { columnOffset?: number; limit?: number; offset?: number; ownerId: string; ownerKind: string; path: string }
    response: HarnessSessionFileContentDTO
  }
  'GET /api/sessions/{sessionId}/files/download': {
    path: { sessionId: string }
    query: { ownerId: string; ownerKind: string; path: string }
    response: Blob
    responseType: 'blob'
  }
  'GET /api/sessions/{sessionId}/threads': {
    path: { sessionId: string }
    response: HarnessThreadSummaryDTO[]
  }
  'GET /api/settings': {
    response: SystemSettingsDTO
  }
  'GET /api/settings/schema': {
    response: SystemSettingsSchemaDTO
  }
  'GET /api/settings/sync': {
    response: ConfigSyncInventoryDTO
  }
  'GET /api/skill-packages': {
    response: SkillPackageSummaryDTO[]
  }
  'GET /api/skill-packages/{name}': {
    path: { name: string }
    response: SkillPackageDetailDTO
  }
  'GET /api/skill-packages/{name}/environments': {
    path: { name: string }
    response: SkillEnvironmentSyncDTO[]
  }
  'GET /api/skill-packages/{name}/files': {
    path: { name: string }
    response: SkillPackageFileDTO[]
  }
  'GET /api/threads/{threadId}': {
    path: { threadId: string }
    query: { afterEntryId?: string }
    response: HarnessThreadSnapshotDTO
  }
  'GET /api/threads/{threadId}/compaction-availability': {
    path: { threadId: string }
    response: HarnessManualCompactionDTO
  }
  'GET /api/threads/{threadId}/tree': {
    path: { threadId: string }
    response: HarnessThreadTreeNodeDTO[]
  }
  'GET /api/tools': {
    response: ToolCatalogEntryDTO[]
  }
  'POST /api/agent-tasks': {
    body: AgentTaskSubmitDTO
    response: AgentTaskDTO
  }
  'POST /api/agent-tasks/{ticketId}/stop': {
    path: { ticketId: string }
    response: AgentTaskDTO
  }
  'POST /api/agents': {
    body: AgentDefinitionCreateDTO
    response: AgentDefinitionDTO
  }
  'POST /api/canvases': {
    body?: CreateCanvasRequestDTO
    response: CanvasDocumentDTO
  }
  'POST /api/canvases/{canvasId}/commands': {
    path: { canvasId: string }
    body?: ApplyCanvasCommandsRequestDTO
    response: CanvasPatchDTO
  }
  'POST /api/canvases/{canvasId}/nodes/{nodeId}/function-run': {
    path: { canvasId: string; nodeId: string }
    body: CanvasFunctionRunRequestDTO
    response: CanvasFunctionRunDTO
  }
  'POST /api/canvases/{canvasId}/nodes/{nodeId}/function-run/cancel': {
    path: { canvasId: string; nodeId: string }
    body: CanvasFunctionCancelRequestDTO
    response: CanvasFunctionRunDTO
  }
  'POST /api/canvases/{canvasId}/nodes/{nodeId}/function-run/resolve': {
    path: { canvasId: string; nodeId: string }
    body: CanvasFunctionUnknownResolutionDTO
    response: CanvasFunctionRunDTO
  }
  'POST /api/canvases/{canvasId}/resources/{resourceId}/download-url': {
    path: { canvasId: string; resourceId: string }
    response: CanvasPresignedUrlDTO
  }
  'POST /api/canvases/{canvasId}/resources/{resourceId}/preview-url': {
    path: { canvasId: string; resourceId: string }
    response: CanvasPresignedUrlDTO
  }
  'POST /api/chat/chats': {
    body: ChatCreateDTO
    response: ChatDTO
  }
  'POST /api/command-batches': {
    body: HarnessCommandBatchDTO
    response: HarnessAcceptedCommandsDTO
  }
  'POST /api/environments': {
    body: EnvironmentCreateDTO
    response: EnvironmentCardDTO
  }
  'POST /api/environments/{environmentId}/ide/views': {
    path: { environmentId: string }
    body: EnvironmentIdeOpenViewDTO
    response: EnvironmentIdeViewDTO
  }
  'POST /api/environments/{environmentId}/ide/views/{viewId}/mobile-layout': {
    path: { environmentId: string; viewId: string }
    body: EnvironmentIdeViewBindingDTO
    response: void
  }
  'POST /api/environments/{environmentId}/ide/views/{viewId}/terminal': {
    path: { environmentId: string; viewId: string }
    body: EnvironmentIdeViewBindingDTO
    response: void
  }
  'POST /api/environments/{environmentId}/install-code': {
    path: { environmentId: string }
    body: EnvironmentInstallCodeRequestDTO
    response: EnvironmentInstallCodeDTO
  }
  'POST /api/environments/{environmentId}/registration-token': {
    path: { environmentId: string }
    body: EnvironmentRotateTokenDTO
    response: EnvironmentCardDTO
  }
  'POST /api/environments/{environmentId}/skills/resync': {
    path: { environmentId: string }
    response: void
  }
  'POST /api/environments/{environmentId}/update': {
    path: { environmentId: string }
    response: EnvironmentUpdateStartDTO
  }
  'POST /api/interactions/{interactionId}/input': {
    path: { interactionId: string }
    body: HarnessToolInputDTO
    response: HarnessToolInputResultDTO
  }
  'POST /api/issues/{issueId}/activities': {
    path: { issueId: string }
    body: AppendIssueActivityRequestDTO
    response: IssueActivityDTO
  }
  'POST /api/issues/{issueId}/archive': {
    path: { issueId: string }
    body: ArchiveIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/issues/{issueId}/block': {
    path: { issueId: string }
    body: BlockIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/issues/{issueId}/budget-reset': {
    path: { issueId: string }
    body: ResetStageBudgetRequestDTO
    response: IssueStageBudgetDTO
  }
  'POST /api/issues/{issueId}/evidence': {
    path: { issueId: string }
    body: AddIssueEvidenceRequestDTO
    response: IssueEvidenceDTO
  }
  'POST /api/issues/{issueId}/recover': {
    path: { issueId: string }
    body: RecoverIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/issues/{issueId}/reopen': {
    path: { issueId: string }
    body: ReopenIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/issues/{issueId}/resume': {
    path: { issueId: string }
    body: ResumeIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/issues/{issueId}/stop': {
    path: { issueId: string }
    body: StopIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/issues/{issueId}/transition': {
    path: { issueId: string }
    body: TransitionIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/issues/{issueId}/unarchive': {
    path: { issueId: string }
    body: UnarchiveIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/mcp-servers': {
    body: McpServerCreateDTO
    response: McpServerDTO
  }
  'POST /api/mcp-servers/{name}/discover': {
    path: { name: string }
    query: { expectedVersion: string }
    response: McpServerDTO
  }
  'POST /api/model-defaults/refresh': {
    response: ModelCatalogPageDTO
  }
  'POST /api/models': {
    body: AgentModelCreateDTO
    response: AgentModelDTO
  }
  'POST /api/plugins/{pluginId}/auth/complete': {
    path: { pluginId: string }
    body: PluginAuthCompleteRequestDTO
    response: PluginDTO
  }
  'POST /api/plugins/{pluginId}/auth/prepare': {
    path: { pluginId: string }
    body: PluginAuthPrepareRequestDTO
    response: PluginAuthPrepareDTO
  }
  'POST /api/plugins/{pluginId}/connection-check': {
    path: { pluginId: string }
    body: PluginConnectionCheckRequestDTO
    response: PluginConnectionCheckDTO
  }
  'POST /api/projects': {
    body: CreateProjectRequestDTO
    response: ProjectDTO
  }
  'POST /api/projects/{projectId}/archive': {
    path: { projectId: string }
    body: ProjectVersionRequestDTO
    response: ProjectDTO
  }
  'POST /api/projects/{projectId}/issues': {
    path: { projectId: string }
    body: CreateIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/projects/{projectId}/unarchive': {
    path: { projectId: string }
    body: ProjectVersionRequestDTO
    response: ProjectDTO
  }
  'POST /api/providers': {
    body: AgentProviderCreateDTO
    response: AgentProviderDTO
  }
  'POST /api/sessions/{sessionId}/provider-request-preview': {
    path: { sessionId: string }
    body: HarnessDraftPreviewRequestDTO
    response: HarnessProviderRequestPreviewDTO
  }
  'POST /api/settings/sync/export': {
    body: ConfigSyncExportRequestDTO
    response: ConfigSyncExportDTO
  }
  'POST /api/settings/sync/import': {
    body: ConfigSyncImportRequestDTO
    response: ConfigSyncImportResultDTO
  }
  'POST /api/settings/sync/import/check': {
    body: ConfigSyncImportCheckRequestDTO
    response: ConfigSyncImportCheckDTO
  }
  'POST /api/skill-packages/git': {
    body: SkillPackageGitCreateDTO
    response: SkillPackageDetailDTO
  }
  'POST /api/skill-packages/git/{name}/check': {
    path: { name: string }
    body: SkillPackageCheckDTO
    response: SkillPackageDetailDTO
  }
  'POST /api/skill-packages/git/{name}/publish': {
    path: { name: string }
    body: SkillPackageGitPublishDTO
    response: SkillPackageDetailDTO
  }
  'POST /api/skill-packages/uploads/preview': {
    body: SkillPackageUploadPreviewDTO
    response: SkillPackagePreviewDTO
  }
  'POST /api/skill-packages/uploads/publish': {
    body: SkillPackageUploadPublishDTO
    response: SkillPackageDetailDTO
  }
  'POST /api/skill-packages/uploads/{stagingUploadId}/cancel': {
    path: { stagingUploadId: string }
    response: void
  }
  'POST /api/skill-packages/{name}/archive-url': {
    path: { name: string }
    response: SkillArchiveDownloadDTO
  }
  'POST /api/storage/blobs/{blobId}/download-url': {
    path: { blobId: string }
    response: StoragePresignedUrlDTO
  }
  'POST /api/storage/blobs/{blobId}/preview-url': {
    path: { blobId: string }
    response: StoragePresignedUrlDTO
  }
  'POST /api/storage/uploads': {
    body: StorageUploadReserveRequestDTO
    response: StorageUploadDTO
  }
  'POST /api/storage/uploads/{uploadId}/complete': {
    path: { uploadId: string }
    response: StorageUploadDTO
  }
  'POST /api/threads/{threadId}/command-batches': {
    path: { threadId: string }
    body: HarnessThreadCommandBatchDTO
    response: HarnessAcceptedCommandsDTO
  }
  'POST /api/threads/{threadId}/compact': {
    path: { threadId: string }
    body: HarnessThreadCompactDTO
    response: HarnessThreadCompactResultDTO
  }
  'POST /api/threads/{threadId}/executions/{executionId}/abandon': {
    path: { executionId: string; threadId: string }
    body: HarnessExecutionAbandonDTO
    response: HarnessThreadSnapshotDTO
  }
  'POST /api/threads/{threadId}/model-request-debug': {
    path: { threadId: string }
    body: HarnessModelRequestDebugRequestDTO
    response: HarnessModelRequestDebugDTO
  }
  'POST /api/threads/{threadId}/provider-request-preview': {
    path: { threadId: string }
    body: HarnessThreadCommandBatchDTO
    response: HarnessProviderRequestPreviewDTO
  }
  'POST /api/threads/{threadId}/stop': {
    path: { threadId: string }
    body: HarnessThreadStopDTO
    response: HarnessThreadStopResultDTO
  }
  'POST /api/threads/{threadId}/stream-catchup': {
    path: { threadId: string }
    body: HarnessThreadStreamCatchupDTO
    response: void
  }
  'PUT /api/agents/{name}': {
    path: { name: string }
    body: AgentDefinitionUpdateDTO
    response: AgentDefinitionDTO
  }
  'PUT /api/chat/chats/{chatId}': {
    path: { chatId: string }
    body: ChatUpdateDTO
    response: ChatDTO
  }
  'PUT /api/chat/chats/{chatId}/yolo': {
    path: { chatId: string }
    body: ChatYoloUpdateDTO
    response: ChatDTO
  }
  'PUT /api/environments/{environmentId}/ide/views/{viewId}/directory': {
    path: { environmentId: string; viewId: string }
    body: EnvironmentIdeChangeDirectoryDTO
    response: EnvironmentIdeViewDTO
  }
  'PUT /api/environments/{environmentId}/install-config': {
    path: { environmentId: string }
    body: Record<string, unknown>
    response: EnvironmentCardDTO
  }
  'PUT /api/issues/{issueId}': {
    path: { issueId: string }
    body: UpdateIssueRequestDTO
    response: IssueDTO
  }
  'PUT /api/mcp-servers/{name}': {
    path: { name: string }
    body: McpServerUpdateDTO
    response: McpServerDTO
  }
  'PUT /api/models/{*modelName}': {
    path: { modelName: string }
    body: AgentModelUpdateDTO
    response: AgentModelDTO
  }
  'PUT /api/plugins/{pluginId}/configuration': {
    path: { pluginId: string }
    body: PluginConfigurationUpdateRequestDTO
    response: PluginDTO
  }
  'PUT /api/projects/{projectId}': {
    path: { projectId: string }
    body: UpdateProjectRequestDTO
    response: ProjectDTO
  }
  'PUT /api/projects/{projectId}/workflow': {
    path: { projectId: string }
    body: UpdateProjectWorkflowRequestDTO
    response: ProjectDTO
  }
  'PUT /api/projects/{projectId}/yolo': {
    path: { projectId: string }
    body: UpdateProjectYoloRequestDTO
    response: ProjectDTO
  }
  'PUT /api/providers/{name}': {
    path: { name: string }
    body: AgentProviderUpdateDTO
    response: AgentProviderDTO
  }
  'PUT /api/sessions/{sessionId}/name': {
    path: { sessionId: string }
    body: HarnessNameUpdateDTO
    response: HarnessSessionDTO
  }
  'PUT /api/settings': {
    body: SystemSettingsUpdateDTO
    response: SystemSettingsDTO
  }
  'PUT /api/skill-packages/{name}': {
    path: { name: string }
    body: SkillPackageEditDTO
    response: SkillPackageDetailDTO
  }
  'PUT /api/threads/{threadId}/name': {
    path: { threadId: string }
    body: HarnessNameUpdateDTO
    response: HarnessThreadDTO
  }
  'PUT /api/threads/{threadId}/tool-invocations/{toolInvocationId}/approval': {
    path: { threadId: string; toolInvocationId: string }
    body: HarnessToolApprovalDTO
    response: ToolInvocationDTO
  }
  'PUT /api/threads/{threadId}/yolo': {
    path: { threadId: string }
    body: HarnessThreadYoloUpdateDTO
    response: HarnessThreadDTO
  }
}

export const API_RESPONSE_TYPES: Partial<Record<keyof ApiRoutes, 'text' | 'blob'>> = {
  'GET /api/environments/uninstall/{operatingSystem}': 'text',
  'GET /api/environments/{environmentId}/install': 'text',
  'GET /api/sessions/{sessionId}/files/download': 'blob',
}
