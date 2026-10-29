/**
 * 由 Web 控制器签名与生产 HTTP Jackson 3 mapper 生成的全部传输契约；请勿手改。
 *
 * 生成器：web 测试源码 TsContractGenerator
 * 重新生成：scripts/dev/verify/generated-contracts/write.sh
 * 漂移门禁：TsContractArtifactTest（mvn verify 覆盖）
 */

/** Java：fun.fengwk.kkstudio.share.project.AddIssueEvidenceRequestDTO */
export interface AddIssueEvidenceRequestDTO {
  uploadId: string
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionConfigDTO */
export interface AgentDefinitionConfigDTO {
  tools: string[]
  skills: SkillRefDTO[]
  subagents: string[]
  inheritParentEnvironment: boolean
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionCreateDTO */
export interface AgentDefinitionCreateDTO {
  description?: string | null
  systemPrompt?: string | null
  model?: string | null
  variant?: string | null
  config: AgentDefinitionConfigDTO
  name: string
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionDTO */
export interface AgentDefinitionDTO {
  name: string
  type: AgentDefinitionType
  description?: string | null
  systemPrompt?: string | null
  model: string | null
  variant?: string | null
  config: AgentDefinitionConfigDTO
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionType */
export type AgentDefinitionType =
  | 'USER'
  | 'BUILTIN'

export const AGENT_DEFINITION_TYPE_VALUES: readonly AgentDefinitionType[] = [
  'USER',
  'BUILTIN',
]

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentDefinitionUpdateDTO */
export interface AgentDefinitionUpdateDTO {
  description?: string | null
  systemPrompt?: string | null
  model?: string | null
  variant?: string | null
  config: AgentDefinitionConfigDTO
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelAbilitiesDTO */
export interface AgentModelAbilitiesDTO {
  tools: boolean
  reasoning: boolean
  inputModalities: AgentModelInputModality[]
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelConfigDTO */
export interface AgentModelConfigDTO {
  limit: AgentModelLimitDTO
  abilities: AgentModelAbilitiesDTO
  pricing?: AgentModelPricingDTO | null
  defaultVariant?: string | null
  variants: AgentModelVariantDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelCreateDTO */
export interface AgentModelCreateDTO {
  name: string
  kind: AgentModelKind
  description?: string | null
  providerName?: string | null
  modelId?: string | null
  config?: AgentModelConfigDTO | null
  group?: AgentModelGroupDTO | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupDTO */
export interface AgentModelGroupDTO {
  members: AgentModelGroupMemberDTO[]
  fallbackStatusCodes: number[]
  cooldownSeconds: number
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelGroupMemberDTO */
export interface AgentModelGroupMemberDTO {
  modelName: string
  variant: string
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelInputModality */
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

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelKind */
export type AgentModelKind =
  | 'MODEL'
  | 'GROUP'

export const AGENT_MODEL_KIND_VALUES: readonly AgentModelKind[] = [
  'MODEL',
  'GROUP',
]

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelLimitDTO */
export interface AgentModelLimitDTO {
  context: number
  output: number
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingConditionDTO */
export interface AgentModelPricingConditionDTO {
  context?: AgentModelPricingContextConditionDTO | null
  serviceTier?: string[] | null
  date?: AgentModelPricingDateConditionDTO | null
  time?: AgentModelPricingTimeConditionDTO | null
  all?: AgentModelPricingConditionDTO[] | null
  any?: AgentModelPricingConditionDTO[] | null
  not?: AgentModelPricingConditionDTO | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingContextConditionDTO */
export interface AgentModelPricingContextConditionDTO {
  gt?: number | null
  gte?: number | null
  lt?: number | null
  lte?: number | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDTO */
export interface AgentModelPricingDTO {
  base: AgentModelRateSetDTO
  overrides: AgentModelPricingOverrideDTO[]
  multipliers: AgentModelPricingMultiplierDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingDateConditionDTO */
export interface AgentModelPricingDateConditionDTO {
  gte?: string | null
  lt?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingFactorsDTO */
export interface AgentModelPricingFactorsDTO {
  input?: string | null
  output?: string | null
  cacheRead?: string | null
  cacheWrite?: string | null
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingMultiplierDTO */
export interface AgentModelPricingMultiplierDTO {
  id: string
  when: AgentModelPricingConditionDTO
  factors: AgentModelPricingFactorsDTO
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingOverrideDTO */
export interface AgentModelPricingOverrideDTO {
  id: string
  when: AgentModelPricingConditionDTO
  rates: AgentModelRatePatchDTO
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelPricingTimeConditionDTO */
export interface AgentModelPricingTimeConditionDTO {
  start: string
  end: string
  timezone: string
  weekdays?: number[] | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelRatePatchDTO */
export interface AgentModelRatePatchDTO {
  input?: string | null
  output?: string | null
  cacheRead?: string | null
  cacheWrite?: string | null
  cacheWriteByTtl?: Record<string, string> | null
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelRateSetDTO */
export interface AgentModelRateSetDTO {
  input: string
  output: string
  cacheRead: string
  cacheWrite: string
  cacheWriteByTtl: Record<string, string>
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelUpdateDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentModelVariantDTO */
export interface AgentModelVariantDTO {
  id: string
  reasoningEffort?: string | null
  protocolOptionsJson?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentProviderCreateDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentProviderDTO */
export interface AgentProviderDTO {
  name: string
  description?: string | null
  providerType: string
  baseUrl?: string | null
  configured: boolean
  modelCallTimeoutMillis: string
  modelCallIdleTimeoutMillis: string
  modelHttpRetryStatusCodes: number[] | null
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.AgentProviderUpdateDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.runtime.AgentTaskDTO */
export interface AgentTaskDTO {
  ticketId: string
  threadId: string
  agent: string
  state: string
  report: string
  partialResult: string
  error: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.AgentTaskSubmitDTO */
export interface AgentTaskSubmitDTO {
  ticketId: string
  agent: string
  prompt: string
  maxTurns: number
}

/** Java：fun.fengwk.kkstudio.share.project.AppendIssueActivityRequestDTO */
export interface AppendIssueActivityRequestDTO {
  expectedVersion: string
  requestKey: string
  kind?: string | null
  body: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.ApplyCanvasCommandsRequestDTO */
export interface ApplyCanvasCommandsRequestDTO {
  idempotencyKey: string
  commands: CanvasCommandDTO[]
}

/** Java：fun.fengwk.kkstudio.share.project.ArchiveIssueRequestDTO */
export interface ArchiveIssueRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.project.BlockIssueRequestDTO */
export interface BlockIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  reason: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.CreateNode */
export interface CanvasCommandCreateNode {
  type: 'CREATE_NODE'
  nodeId: string
  name: string
  transform: CanvasTransformDTO
  resources: CanvasResourceInputDTO[]
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.RenameNode */
export interface CanvasCommandRenameNode {
  type: 'RENAME_NODE'
  nodeId: string
  expectedName: string
  name: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.SetNodeResources */
export interface CanvasCommandSetNodeResources {
  type: 'SET_NODE_RESOURCES'
  nodeId: string
  expectedResourceIds: string[]
  resources: CanvasResourceInputDTO[]
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.SetNodeFunction */
export interface CanvasCommandSetNodeFunction {
  type: 'SET_NODE_FUNCTION'
  nodeId: string
  expectedFunction?: CanvasFunctionDTO | null
  function?: CanvasFunctionDTO | null
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.SetNodeGroup */
export interface CanvasCommandSetNodeGroup {
  type: 'SET_NODE_GROUP'
  nodeId: string
  expectedGroupId?: string | null
  groupId?: string | null
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.DeleteNode */
export interface CanvasCommandDeleteNode {
  type: 'DELETE_NODE'
  nodeId: string
  expectedResourceIds: string[]
  expectedFunction?: CanvasFunctionDTO | null
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.UpdateNodeTransform */
export interface CanvasCommandUpdateNodeTransform {
  type: 'UPDATE_NODE_TRANSFORM'
  nodeId: string
  transform: CanvasTransformDTO
  expectedTransform?: CanvasTransformDTO | null
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.CreateGroup */
export interface CanvasCommandCreateGroup {
  type: 'CREATE_GROUP'
  groupId: string
  title: string
  transform: CanvasTransformDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.RenameGroup */
export interface CanvasCommandRenameGroup {
  type: 'RENAME_GROUP'
  groupId: string
  expectedTitle: string
  title: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.UpdateGroupTransform */
export interface CanvasCommandUpdateGroupTransform {
  type: 'UPDATE_GROUP_TRANSFORM'
  groupId: string
  transform: CanvasTransformDTO
  expectedTransform?: CanvasTransformDTO | null
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO.DeleteGroup */
export interface CanvasCommandDeleteGroup {
  type: 'DELETE_GROUP'
  groupId: string
  expectedMemberNodeIds: string[]
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasCommandDTO */
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

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO.TargetMissing */
export interface CanvasConflictTargetMissing {
  kind: 'TARGET_MISSING'
  targetId: string
  target: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO.TargetPresent */
export interface CanvasConflictTargetPresent {
  kind: 'TARGET_PRESENT'
  targetId: string
  target: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO.StaleNode */
export interface CanvasConflictStaleNode {
  kind: 'STALE_NODE'
  nodeId: string
  group: string
  current: CanvasResourceNodeDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO.StaleGroup */
export interface CanvasConflictStaleGroup {
  kind: 'STALE_GROUP'
  groupId: string
  current: CanvasGroupDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO.NodeRunning */
export interface CanvasConflictNodeRunning {
  kind: 'NODE_RUNNING'
  nodeId: string
  run: CanvasFunctionRunDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO.NodeReferenced */
export interface CanvasConflictNodeReferenced {
  kind: 'NODE_REFERENCED'
  nodeId: string
  referencingNodeIds: string[]
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasConflictDTO */
export type CanvasConflictDTO =
  | CanvasConflictTargetMissing
  | CanvasConflictTargetPresent
  | CanvasConflictStaleNode
  | CanvasConflictStaleGroup
  | CanvasConflictNodeRunning
  | CanvasConflictNodeReferenced

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasDocumentDTO */
export interface CanvasDocumentDTO {
  id: string
  title: string
  revision: string
  createdAt: string
  updatedAt: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionCancelRequestDTO */
export interface CanvasFunctionCancelRequestDTO {
  requestId: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionDTO */
export interface CanvasFunctionDTO {
  name: string
  args: Record<string, unknown>
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionDefinitionDTO */
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

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionOutputDTO */
export interface CanvasFunctionOutputDTO {
  kind: string
  name?: string | null
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionReferencePolicyDTO */
export interface CanvasFunctionReferencePolicyDTO {
  allowedKinds: string[]
  maxReferences?: number | null
  maxByKind?: Record<string, number> | null
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionRunDTO */
export interface CanvasFunctionRunDTO {
  nodeId: string
  requestId: string
  status: string
  stage: string
  error: string | null
  updatedAt: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionRunRequestDTO */
export interface CanvasFunctionRunRequestDTO {
  requestId: string
  expectedVersion: CanvasFunctionVersionDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionUnknownResolutionDTO */
export interface CanvasFunctionUnknownResolutionDTO {
  requestId: string
  resolution: string
  verification: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasFunctionVersionDTO */
export interface CanvasFunctionVersionDTO {
  definitionFingerprint: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasGroupDTO */
export interface CanvasGroupDTO {
  id: string
  canvasId: string
  title: string
  transform: CanvasTransformDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasGroupPatchDTO.Upsert */
export interface CanvasGroupPatchUpsert {
  op: 'UPSERT'
  group: CanvasGroupDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasGroupPatchDTO.Remove */
export interface CanvasGroupPatchRemove {
  op: 'REMOVE'
  groupId: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasGroupPatchDTO */
export type CanvasGroupPatchDTO =
  | CanvasGroupPatchUpsert
  | CanvasGroupPatchRemove

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasNodePatchDTO.Upsert */
export interface CanvasNodePatchUpsert {
  op: 'UPSERT'
  node: CanvasResourceNodeDTO
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasNodePatchDTO.Remove */
export interface CanvasNodePatchRemove {
  op: 'REMOVE'
  nodeId: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasNodePatchDTO */
export type CanvasNodePatchDTO =
  | CanvasNodePatchUpsert
  | CanvasNodePatchRemove

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasPatchDTO */
export interface CanvasPatchDTO {
  revision: string
  groups: CanvasGroupPatchDTO[]
  nodes: CanvasNodePatchDTO[]
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasPresignedUrlDTO */
export interface CanvasPresignedUrlDTO {
  method: string
  url: string
  headers: Record<string, string>
  expiresAt: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasReferenceDTO */
export interface CanvasReferenceDTO {
  canvasId: string
  sourceNodeId: string
  targetNodeId: string
  index: number
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasResourceDTO */
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

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasResourceInputDTO.Keep */
export interface CanvasResourceInputKeep {
  kind: 'KEEP'
  resourceId: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasResourceInputDTO.Text */
export interface CanvasResourceInputText {
  kind: 'TEXT'
  name: string
  textContent: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasResourceInputDTO.Blob */
export interface CanvasResourceInputBlob {
  kind: 'BLOB'
  name: string
  blobId: string
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasResourceInputDTO */
export type CanvasResourceInputDTO =
  | CanvasResourceInputKeep
  | CanvasResourceInputText
  | CanvasResourceInputBlob

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasResourceNodeDTO */
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

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasSnapshotDTO */
export interface CanvasSnapshotDTO {
  document: CanvasDocumentDTO
  nodes: CanvasResourceNodeDTO[]
  groups: CanvasGroupDTO[]
  references: CanvasReferenceDTO[]
}

/** Java：fun.fengwk.kkstudio.share.canvas.CanvasTransformDTO */
export interface CanvasTransformDTO {
  x: number
  y: number
  width: number
  height: number
}

/** Java：fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO */
export interface ChatCreateDTO {
  title?: string | null
  agentName: string
  environmentName?: string | null
  yoloEnabled?: boolean | null
}

/** Java：fun.fengwk.kkstudio.share.ai.chat.ChatDTO */
export interface ChatDTO {
  id: string
  title?: string | null
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO */
export interface ChatUpdateDTO {
  title?: string | null
  agentName?: string | null
  expectedVersion: string
  environmentName?: string | null
  yoloEnabled?: boolean | null
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
  allowPartial: boolean
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

/** Java：fun.fengwk.kkstudio.share.canvas.CreateCanvasRequestDTO */
export interface CreateCanvasRequestDTO {
  title?: string | null
}

/** Java：fun.fengwk.kkstudio.share.project.CreateIssueRequestDTO */
export interface CreateIssueRequestDTO {
  title: string
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.share.project.CreateProjectRequestDTO */
export interface CreateProjectRequestDTO {
  title: string
  description?: string | null
  yoloEnabled?: boolean | null
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.DaemonConfiguration */
export interface DaemonConfiguration {
  studioUrl: string
  note?: string | null
  bashExecutable?: string | null
  lsp?: DaemonLspConfiguration | null
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.DaemonLspConfiguration */
export interface DaemonLspConfiguration {
  servers: Record<string, DaemonLspServerConfiguration>
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.DaemonLspServerConfiguration */
export interface DaemonLspServerConfiguration {
  command: string[]
  extensions?: string[] | null
  rootMarkers?: string[] | null
  firstMatchMarkers?: string[] | null
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO */
export interface EnvironmentCreateDTO {
  name: string
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentEventDTO */
export interface EnvironmentEventDTO {
  time: number
  level: string
  type: string
  message: string
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeChangeDirectoryDTO */
export interface EnvironmentIdeChangeDirectoryDTO {
  documentId: string
  bindingEpoch: string
  directory: string
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeCloseViewDTO */
export interface EnvironmentIdeCloseViewDTO {
  documentId: string
  bindingEpoch: string
  discard: boolean
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeOpenViewDTO */
export interface EnvironmentIdeOpenViewDTO {
  viewId: string
  documentId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  mode: string
  directory?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewBindingDTO */
export interface EnvironmentIdeViewBindingDTO {
  documentId: string
  bindingEpoch: string
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentIdeViewsDTO */
export interface EnvironmentIdeViewsDTO {
  views: EnvironmentIdeViewDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO */
export interface EnvironmentInstallCodeDTO {
  code: string
  expiresAt: number
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeRequestDTO */
export interface EnvironmentInstallCodeRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO */
export interface EnvironmentInstallConfigDTO {
  operatingSystem: string
  daemon: DaemonConfiguration
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentRotateTokenDTO */
export interface EnvironmentRotateTokenDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeDTO */
export interface EnvironmentRuntimeDTO {
  daemonVersion: string
  update: EnvironmentUpdateProgressDTO | null
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentRuntimeStatusDTO */
export interface EnvironmentRuntimeStatusDTO {
  revision: string
  status: EnvironmentRuntimeDTO
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.EnvironmentSupportDTO */
export type EnvironmentSupportDTO =
  | 'NONE'
  | 'OPTIONAL'
  | 'REQUIRED'

export const ENVIRONMENT_SUPPORT_DTO_VALUES: readonly EnvironmentSupportDTO[] = [
  'NONE',
  'OPTIONAL',
  'REQUIRED',
]

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateProgressDTO */
export interface EnvironmentUpdateProgressDTO {
  targetVersion: string
  downloadedBytes: string
  totalBytes: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateStartDTO */
export interface EnvironmentUpdateStartDTO {
  runtimeStatus: EnvironmentRuntimeStatusDTO
  commandStatus: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessAcceptedCommandsDTO */
export interface HarnessAcceptedCommandsDTO {
  session: HarnessSessionDTO
  rootEntry: HarnessSessionEntryDTO
  thread: HarnessThreadDTO
  acceptedCommands: HarnessThreadCommandDTO[]
  replayed: boolean
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessActiveAssistantOutputDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessAgentMessageContentDTO */
export interface HarnessAgentMessageContentDTO {
  type: string
  text?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessAssistantErrorDTO */
export interface HarnessAssistantErrorDTO {
  code: string
  message: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessBranchSettingsDTO */
export interface HarnessBranchSettingsDTO {
  agentName: string
  model: HarnessModelSelectionDTO
  environmentName: string | null
  goal: HarnessGoalSettingDTO | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessCancelledInputDTO */
export interface HarnessCancelledInputDTO {
  sequence: string
  idempotencyKey: string
  type: string
  payloadJson: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandBatchDTO */
export interface HarnessCommandBatchDTO {
  owner: HarnessCommandOwnerDTO
  target: HarnessCommandTargetDTO
  commands: HarnessCommandCreateDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandCreateDTO */
export interface HarnessCommandCreateDTO {
  type: string
  idempotencyKey: string
  contents?: HarnessUserMessageContentDTO[] | null
  agentName?: string | null
  model?: HarnessModelSelectionDTO | null
  environmentName?: string | null
  text?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandOwnerDTO */
export interface HarnessCommandOwnerDTO {
  type: string
  chatId?: string | null
  issueId?: string | null
  agentName?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessCommandTargetDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessDraftPreviewRequestDTO */
export interface HarnessDraftPreviewRequestDTO {
  startEntryId: string
  commands: HarnessCommandCreateDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessExecutionAbandonDTO */
export interface HarnessExecutionAbandonDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessExecutionExitDTO */
export interface HarnessExecutionExitDTO {
  executionId: string
  invocationId: string
  attempt: number
  kind: string
  registeredAt: number
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessGenerationFailureDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessGoalSettingDTO */
export interface HarnessGoalSettingDTO {
  id: string
  text: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessManualCompactionDTO */
export interface HarnessManualCompactionDTO {
  available: boolean
  disabledReason: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO.CacheControlDTO */
export interface HarnessModelRequestDebugCacheControlDTO {
  retention: string
  key: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO.FrozenInvocationDTO */
export interface HarnessModelRequestDebugFrozenInvocationDTO {
  kind: string
  generationId: string
  attempt: number
  requestJson: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugRequestDTO */
export interface HarnessModelRequestDebugRequestDTO {
  model: HarnessModelSelectionDTO
  environmentName?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO.SkillDTO */
export interface HarnessModelRequestDebugSkillDTO {
  packageName: string
  name: string
  description: string
  path: string
  delivery: string
  currentCommit: string
  observedHeadCommit: string | null
  installedCommit: string | null
  promptXml: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO.SubagentDTO */
export interface HarnessModelRequestDebugSubagentDTO {
  name: string
  description: string
  tools: string[]
  skills: SkillRefDTO[]
  subagents: string[]
  configurationJson: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessModelRequestDebugDTO.ToolDTO */
export interface HarnessModelRequestDebugToolDTO {
  name: string
  description: string
  inputSchemaJson: string
  environmentSupport: EnvironmentSupportDTO
  requiredEnvironmentId: string | null
  provenance: string
  state: string
  filterReason: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO */
export interface HarnessModelSelectionDTO {
  modelName: string
  variant?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessNameUpdateDTO */
export interface HarnessNameUpdateDTO {
  name: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessProviderRequestPreviewDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO */
export interface HarnessSessionDTO {
  sessionId: string
  name: string
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO */
export interface HarnessSessionEntryDTO {
  entryId: string
  sessionId: string
  parentEntryId: string | null
  entryType: string
  payloadJson: string
  createTime: number
  usageCost: HarnessUsageCostDTO | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO */
export interface HarnessSessionSummaryDTO {
  sessionId: string
  name: string
  createdAt: number
  lastActivityAt: number
  firstMessagePreview: string | null
  threadCount: number
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessStoppedThreadReceiptDTO */
export interface HarnessStoppedThreadReceiptDTO {
  threadId: string
  stopRequestId: string
  stoppedTurnEndEntryId: string | null
  cancelledCommandCount: number
  cancelledInputs: HarnessCancelledInputDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandBatchDTO */
export interface HarnessThreadCommandBatchDTO {
  expectedHeadEntryId?: string | null
  expectedNextCommandSequence?: string | null
  commands: HarnessCommandCreateDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCommandDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactDTO */
export interface HarnessThreadCompactDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCompactResultDTO */
export interface HarnessThreadCompactResultDTO {
  thread: HarnessThreadDTO
  turnStartEntryId: string
  childThreadId: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO */
export interface HarnessThreadSnapshotDTO {
  version: string
  thread: HarnessThreadDTO
  entries: HarnessSessionEntryDTO[]
  queuedCommands: HarnessThreadCommandDTO[]
  executionStatus: string
  activeAssistantOutput: HarnessActiveAssistantOutputDTO | null
  generationFailures: HarnessGenerationFailureDTO[]
  toolInvocations: ToolInvocationDTO[]
  manualCompaction: HarnessManualCompactionDTO
  stopReceipts: HarnessStoppedThreadReceiptDTO[]
  unconfirmedExecutions: HarnessExecutionExitDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO */
export interface HarnessThreadStopDTO {
  stopRequestId: string
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO */
export interface HarnessThreadStopResultDTO {
  status: string
  thread: HarnessThreadDTO
  stoppedThreads: HarnessStoppedThreadReceiptDTO[]
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStreamCatchupDTO */
export interface HarnessThreadStreamCatchupDTO {
  invocationId: string
  attempt: number
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO */
export interface HarnessThreadSummaryDTO {
  threadId: string
  parentThreadId: string | null
  name: string
  createdAt: number
  updatedAt: number
  status: string
  executionControl: string
  model: HarnessModelSelectionDTO
  headMessagePreview: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadTreeNodeDTO */
export interface HarnessThreadTreeNodeDTO {
  threadId: string
  parentThreadId: string | null
  name: string
  agentName: string
  model: HarnessModelSelectionDTO
  status: string
  executionControl: string
  turnCount: number
  toolCallCount: number
  outcome: string | null
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadYoloUpdateDTO */
export interface HarnessThreadYoloUpdateDTO {
  yoloEnabled: boolean
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessToolApprovalDTO */
export interface HarnessToolApprovalDTO {
  decision: string
  decisionId: string
  reason?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.interaction.HarnessToolInputDTO */
export interface HarnessToolInputDTO {
  threadId: string
  submissionId: string
  declined?: boolean | null
  answers?: (string[])[] | null
}

/** Java：fun.fengwk.kkstudio.share.ai.interaction.HarnessToolInputResultDTO */
export interface HarnessToolInputResultDTO {
  threadId: string
  interactionId: string
  submissionId: string
  actor: string
  acceptedAt: number
  materialized: boolean
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessUsageCostDTO */
export interface HarnessUsageCostDTO {
  currency: string
  amount: string
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.HarnessUserMessageContentDTO */
export interface HarnessUserMessageContentDTO {
  type: string
  text?: string | null
  uploadId?: string | null
  blobId?: string | null
  name?: string | null
  preview?: string | null
  imageTier?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.interaction.InteractionDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.interaction.InteractionOwnerDTO */
export interface InteractionOwnerDTO {
  type: string
  chatId: string | null
  chatTitle: string | null
  issueId: string | null
  issueTitle: string | null
  agentName: string | null
  rootThreadName: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.interaction.InteractionPageDTO */
export interface InteractionPageDTO {
  items: InteractionDTO[]
  nextCursor: string | null
  total: number
  freshnessAt: number | null
}

/** Java：fun.fengwk.kkstudio.share.project.IssueActivityDTO */
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

/** Java：fun.fengwk.kkstudio.share.project.IssueAgentThreadDTO */
export interface IssueAgentThreadDTO {
  issueId: string
  agentName: string
  threadId: string
}

/** Java：fun.fengwk.kkstudio.share.project.IssueDTO */
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

/** Java：fun.fengwk.kkstudio.share.project.IssueDetailDTO */
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

/** Java：fun.fengwk.kkstudio.share.project.IssueEvidenceDTO */
export interface IssueEvidenceDTO {
  issueId: string
  blobId: string
  uri: string
  name?: string | null
  actorAgentName: string | null
  runId: string | null
  createdAt: string
}

/** Java：fun.fengwk.kkstudio.share.project.IssueRunDTO */
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

/** Java：fun.fengwk.kkstudio.share.project.IssueRunSummaryDTO */
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

/** Java：fun.fengwk.kkstudio.share.project.IssueStageBudgetDTO */
export interface IssueStageBudgetDTO {
  state: string
  maxRuns: number
  budgetAfterOrdinal: string
  usedRuns: string
  remainingRuns: string
}

/** Java：fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentCapabilityDTO */
export interface LiveEnvironmentCapabilityDTO {
  id: string
  version: string
}

/** Java：fun.fengwk.kkstudio.share.ai.mcp.McpServerConfigDTO */
export interface McpServerConfigDTO {
  name: string
  version: string
  url: string
  headers?: Record<string, string> | null
}

/** Java：fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO */
export interface McpServerCreateDTO {
  name: string
  url: string
  headers?: Record<string, string> | null
  enabled?: boolean | null
  timeoutMillis?: number | null
}

/** Java：fun.fengwk.kkstudio.share.ai.mcp.McpServerDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO */
export interface McpServerUpdateDTO {
  expectedVersion: string
  url: string
  headers?: Record<string, string> | null
  enabled?: boolean | null
  timeoutMillis?: number | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogPageDTO */
export interface ModelCatalogPageDTO {
  source: ModelCatalogSource
  generation: string
  fetchedAt?: number | null
  loading: boolean
  error?: string | null
  items: ModelDefaultCandidateDTO[]
  nextCursor?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.ModelCatalogSource */
export type ModelCatalogSource =
  | 'models_dev_v1'
  | 'models_dev_v2'

export const MODEL_CATALOG_SOURCE_VALUES: readonly ModelCatalogSource[] = [
  'models_dev_v1',
  'models_dev_v2',
]

/** Java：fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultAbilitiesDTO */
export interface ModelDefaultAbilitiesDTO {
  tools?: boolean | null
  reasoning?: boolean | null
  inputModalities?: AgentModelInputModality[] | null
}

/** Java：fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultCandidateDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.catalog.ModelDefaultLimitDTO */
export interface ModelDefaultLimitDTO {
  context?: number | null
  output?: number | null
}

/** Java：fun.fengwk.kkstudio.share.project.PauseIssueRequestDTO */
export interface PauseIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  reason: string
  detail?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginAuthCompleteRequestDTO */
export interface PluginAuthCompleteRequestDTO {
  authAttemptId: string
  callbackUrl: string
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO.DeepLink */
export interface PluginAuthKindDeepLink {
  type: 'DEEP_LINK'
  regionCandidates: string[]
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO.StaticKey */
export interface PluginAuthKindStaticKey {
  type: 'STATIC_KEY'
  secretField: string
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO */
export type PluginAuthKindDTO =
  | PluginAuthKindDeepLink
  | PluginAuthKindStaticKey

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO */
export interface PluginAuthPrepareDTO {
  authAttemptId: string
  loginUrl: string
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareRequestDTO */
export interface PluginAuthPrepareRequestDTO {
  region: string
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConfigurationDTO */
export interface PluginConfigurationDTO {
  revision?: string | null
  valuesJson: string
  valuesSchema: string
  defaultsJson: string
  secrets: Record<string, PluginSecretProjectionDTO>
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConfigurationUpdateRequestDTO */
export interface PluginConfigurationUpdateRequestDTO {
  expectedRevision: string
  valuesJson: string
  secretPatch?: Record<string, PluginSecretPatchDTO> | null
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.Authentication */
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

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.BrowserSession */
export type PluginConnectionCheckBrowserSession = 'UNKNOWN'

export const PLUGIN_CONNECTION_CHECK_BROWSER_SESSION_VALUES: readonly PluginConnectionCheckBrowserSession[] = [
  'UNKNOWN',
]

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO */
export interface PluginConnectionCheckDTO {
  pluginId: string
  appliedRevision: string
  checkedAt: number
  reachability: PluginConnectionCheckReachability
  authentication: PluginConnectionCheckAuthentication
  browserSession: PluginConnectionCheckBrowserSession
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO.Reachability */
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

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckRequestDTO */
export interface PluginConnectionCheckRequestDTO {
  expectedRevision: string
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginDTO */
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

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginSecretPatchDTO */
export interface PluginSecretPatchDTO {
  operation: string
  value?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.plugin.PluginSecretProjectionDTO */
export interface PluginSecretProjectionDTO {
  description: string
  configured: boolean
}

/** Java：fun.fengwk.kkstudio.share.project.ProjectDTO */
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

/** Java：fun.fengwk.kkstudio.share.project.ProjectIssueSnapshotDTO */
export interface ProjectIssueSnapshotDTO {
  issue: IssueDTO
  currentOrLatestRun: IssueRunSummaryDTO | null
}

/** Java：fun.fengwk.kkstudio.share.project.ProjectSnapshotDTO */
export interface ProjectSnapshotDTO {
  project: ProjectDTO
  issues: ProjectIssueSnapshotDTO[]
  referencedStateCodes: string[]
}

/** Java：fun.fengwk.kkstudio.share.project.ProjectVersionRequestDTO */
export interface ProjectVersionRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.project.ProjectWorkflowDTO */
export interface ProjectWorkflowDTO {
  states: ProjectWorkflowStateDTO[]
}

/** Java：fun.fengwk.kkstudio.share.project.ProjectWorkflowStateDTO */
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

/** Java：fun.fengwk.kkstudio.share.project.RecoverIssueRequestDTO */
export interface RecoverIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

/** Java：fun.fengwk.kkstudio.share.project.ReopenIssueRequestDTO */
export interface ReopenIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

/** Java：fun.fengwk.kkstudio.share.project.ResetStageBudgetRequestDTO */
export interface ResetStageBudgetRequestDTO {
  expectedVersion: string
  requestKey: string
  state: string
  maxRuns: number
}

/** Java：fun.fengwk.kkstudio.share.project.ResolveUnknownIssueRequestDTO */
export interface ResolveUnknownIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  verification: string
}

/** Java：fun.fengwk.kkstudio.share.project.ResumeIssueRequestDTO */
export interface ResumeIssueRequestDTO {
  expectedVersion: string
  requestKey: string
}

/** Java：fun.fengwk.kkstudio.share.ai.skill.SkillManifestEntryDTO */
export interface SkillManifestEntryDTO {
  name: string
  description: string
}

/** Java：fun.fengwk.kkstudio.share.ai.skill.SkillPackageCheckDTO */
export interface SkillPackageCheckDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.ai.skill.SkillPackageCreateDTO */
export interface SkillPackageCreateDTO {
  packageName: string
  description?: string | null
  repositoryUrl: string
  branch: string
  token?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.skill.SkillPackageDTO */
export interface SkillPackageDTO {
  packageName: string
  description?: string | null
  repositoryUrl: string
  branch: string
  hasToken: boolean
  currentCommit: string
  observedHeadCommit: string | null
  headCheckedAt: number | null
  headCheckError: string | null
  checkStatus: string
  skills: SkillManifestEntryDTO[]
  version: string
  createTime: number
  updateTime: number
}

/** Java：fun.fengwk.kkstudio.share.ai.skill.SkillPackageEditDTO */
export interface SkillPackageEditDTO {
  expectedVersion: string
  description?: string | null
  branch: string
  token?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.skill.SkillPackagePublishDTO */
export interface SkillPackagePublishDTO {
  expectedVersion: string
  targetCommit: string
}

/** Java：fun.fengwk.kkstudio.share.ai.skill.SkillRefDTO */
export interface SkillRefDTO {
  packageName: string
  name: string
}

/** Java：fun.fengwk.kkstudio.share.project.StopIssueRequestDTO */
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
  modelCatalogSource: string
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
  toolGatewayBusyRetryMillis: string
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

/** Java：fun.fengwk.kkstudio.share.ai.catalog.ToolCatalogEntryDTO */
export interface ToolCatalogEntryDTO {
  name: string
  description: string
  environmentSupport?: EnvironmentSupportDTO | null
  requiredEnvironmentId?: string | null
}

/** Java：fun.fengwk.kkstudio.share.ai.runtime.ToolInvocationDTO */
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

/** Java：fun.fengwk.kkstudio.share.project.TransitionIssueRequestDTO */
export interface TransitionIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  toState: string
}

/** Java：fun.fengwk.kkstudio.share.project.UnarchiveIssueRequestDTO */
export interface UnarchiveIssueRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.share.project.UpdateIssueRequestDTO */
export interface UpdateIssueRequestDTO {
  expectedVersion: string
  title?: string | null
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.share.project.UpdateProjectRequestDTO */
export interface UpdateProjectRequestDTO {
  expectedVersion: string
  title?: string | null
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.share.project.UpdateProjectWorkflowRequestDTO */
export interface UpdateProjectWorkflowRequestDTO {
  expectedVersion: string
  workflow: ProjectWorkflowDTO
}

/** Java：fun.fengwk.kkstudio.share.project.UpdateProjectYoloRequestDTO */
export interface UpdateProjectYoloRequestDTO {
  expectedVersion: string
  yoloEnabled: boolean
}
