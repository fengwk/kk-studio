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

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.AgentConfigDTO */
export interface AgentConfigDTO {
  tools: string[]
  skills: SkillRefDTO[]
  subagents: string[]
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.AgentCreateDTO */
export interface AgentCreateDTO {
  description?: string | null
  systemPrompt?: string | null
  execution?: HarnessExecutionSelectionDTO | null
  config: AgentConfigDTO
  name: string
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.AgentDTO */
export interface AgentDTO {
  name: string
  type: AgentType
  description?: string | null
  systemPrompt?: string | null
  execution: HarnessExecutionSelectionDTO | null
  config: AgentConfigDTO
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.definition.AgentType */
export type AgentType =
  | 'USER'
  | 'BUILTIN'

export const AGENT_TYPE_VALUES: readonly AgentType[] = [
  'USER',
  'BUILTIN',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.AgentUpdateDTO */
export interface AgentUpdateDTO {
  description?: string | null
  systemPrompt?: string | null
  execution?: HarnessExecutionSelectionDTO | null
  config: AgentConfigDTO
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.project.dto.AppendIssueActivityRequestDTO */
export interface AppendIssueActivityRequestDTO {
  expectedVersion: string
  requestKey: string
  kind?: IssueActivityKind | null
  body: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.ApplyCanvasCommandsRequestDTO */
export interface ApplyCanvasCommandsRequestDTO {
  idempotencyKey: string
  commands: CanvasCommandDTO[]
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
  target: CanvasConflictTarget
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO.TargetPresent */
export interface CanvasConflictTargetPresent {
  kind: 'TARGET_PRESENT'
  targetId: string
  target: CanvasConflictTarget
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasConflictDTO.StaleNode */
export interface CanvasConflictStaleNode {
  kind: 'STALE_NODE'
  nodeId: string
  group: CanvasConflictNodeGroup
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

/** Java：fun.fengwk.kkstudio.canvas.CanvasConflict.NodeGroup */
export type CanvasConflictNodeGroup =
  | 'NAME'
  | 'RESOURCES'
  | 'FUNCTION'
  | 'MEMBERSHIP'
  | 'LAYOUT'

export const CANVAS_CONFLICT_NODE_GROUP_VALUES: readonly CanvasConflictNodeGroup[] = [
  'NAME',
  'RESOURCES',
  'FUNCTION',
  'MEMBERSHIP',
  'LAYOUT',
]

/** Java：fun.fengwk.kkstudio.canvas.CanvasConflict.Target */
export type CanvasConflictTarget =
  | 'NODE'
  | 'GROUP'

export const CANVAS_CONFLICT_TARGET_VALUES: readonly CanvasConflictTarget[] = [
  'NODE',
  'GROUP',
]

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasDocumentDTO */
export interface CanvasDocumentDTO {
  id: string
  title: string
  revision: string
  createdAt: number
  updatedAt: number
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
  kind: CanvasResourceKind
  name?: string | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionReferencePolicyDTO */
export interface CanvasFunctionReferencePolicyDTO {
  allowedKinds: CanvasResourceKind[]
  maxReferences?: number | null
  maxByKind?: Record<string, number> | null
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionRunDTO */
export interface CanvasFunctionRunDTO {
  nodeId: string
  requestId: string
  status: CanvasFunctionRunStatus
  stage: string
  error: string | null
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionRunRequestDTO */
export interface CanvasFunctionRunRequestDTO {
  requestId: string
  expectedVersion: CanvasFunctionVersionDTO
}

/** Java：fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus */
export type CanvasFunctionRunStatus =
  | 'READY'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'UNKNOWN'

export const CANVAS_FUNCTION_RUN_STATUS_VALUES: readonly CanvasFunctionRunStatus[] = [
  'READY',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'UNKNOWN',
]

/** Java：fun.fengwk.kkstudio.canvas.CanvasFunctionUnknownResolution */
export type CanvasFunctionUnknownResolution =
  | 'RESUME'
  | 'FAILED'
  | 'CANCELLED'

export const CANVAS_FUNCTION_UNKNOWN_RESOLUTION_VALUES: readonly CanvasFunctionUnknownResolution[] = [
  'RESUME',
  'FAILED',
  'CANCELLED',
]

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasFunctionUnknownResolutionDTO */
export interface CanvasFunctionUnknownResolutionDTO {
  requestId: string
  resolution: CanvasFunctionUnknownResolution
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
  expiresAt: number
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasReferenceDTO */
export interface CanvasReferenceDTO {
  canvasId: string
  sourceNodeId: string
  targetNodeId: string
  index: number
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceDTO.Text */
export interface CanvasResourceText {
  type: 'TEXT'
  id: string
  canvasId: string
  ownerNodeId: string
  resourceIndex: number
  name: string
  textContent: string
  kind: CanvasResourceKind
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceDTO.Blob */
export interface CanvasResourceBlob {
  type: 'BLOB'
  id: string
  canvasId: string
  ownerNodeId: string
  resourceIndex: number
  blobId: string
  name: string
  kind: CanvasResourceKind
  mediaType: string
  sizeBytes: string | null
  width: number | null
  height: number | null
  durationMs: string | null
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CanvasResourceDTO */
export type CanvasResourceDTO =
  | CanvasResourceText
  | CanvasResourceBlob

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

/** Java：fun.fengwk.kkstudio.canvas.function.CanvasResourceKind */
export type CanvasResourceKind =
  | 'IMAGE'
  | 'VIDEO'
  | 'AUDIO'
  | 'TEXT'

export const CANVAS_RESOURCE_KIND_VALUES: readonly CanvasResourceKind[] = [
  'IMAGE',
  'VIDEO',
  'AUDIO',
  'TEXT',
]

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

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncExportDTO */
export interface ConfigSyncExportDTO {
  yaml: string
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncExportRequestDTO */
export interface ConfigSyncExportRequestDTO {
  items: ConfigSyncRef[]
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportCheckDTO */
export interface ConfigSyncImportCheckDTO {
  created: ConfigSyncRef[]
  updated: ConfigSyncRef[]
  skipped: ConfigSyncSkipped[]
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportCheckRequestDTO */
export interface ConfigSyncImportCheckRequestDTO {
  yaml: string
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportRequestDTO */
export interface ConfigSyncImportRequestDTO {
  yaml: string
  allowPartial?: boolean | null
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncImportResultDTO */
export interface ConfigSyncImportResultDTO {
  imported: ConfigSyncRef[]
  skipped: ConfigSyncSkipped[]
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncInventoryDTO */
export interface ConfigSyncInventoryDTO {
  items: ConfigSyncItem[]
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncItem */
export interface ConfigSyncItem {
  kind: ConfigSyncKind
  name: string
  dependencies: ConfigSyncRef[]
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncKind */
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

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncRef */
export interface ConfigSyncRef {
  kind: ConfigSyncKind
  name: string
}

/** Java：fun.fengwk.kkstudio.platform.configsync.dto.ConfigSyncSkipped */
export interface ConfigSyncSkipped {
  kind?: ConfigSyncKind | null
  name: string
  reason: string
}

/** Java：fun.fengwk.kkstudio.canvas.dto.CreateCanvasRequestDTO */
export interface CreateCanvasRequestDTO {
  title: string
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

/** Java：fun.fengwk.kkstudio.platform.environment.dto.DaemonConfiguration */
export interface DaemonConfiguration {
  studioUrl: string
  note?: string | null
  bashExecutable?: string | null
  lsp?: DaemonLspConfiguration | null
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.DaemonLspConfiguration */
export interface DaemonLspConfiguration {
  servers: Record<string, DaemonLspServerConfiguration>
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.DaemonLspServerConfiguration */
export interface DaemonLspServerConfiguration {
  command: string[]
  extensions?: string[] | null
  rootMarkers?: string[] | null
  firstMatchMarkers?: string[] | null
}

/** Java：fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem */
export type DaemonOperatingSystem =
  | 'windows'
  | 'wsl'
  | 'linux'
  | 'macos'

export const DAEMON_OPERATING_SYSTEM_VALUES: readonly DaemonOperatingSystem[] = [
  'windows',
  'wsl',
  'linux',
  'macos',
]

/** Java：fun.fengwk.kkstudio.harness.api.history.EntryType */
export type EntryType =
  | 'ROOT'
  | 'TURN_START'
  | 'MESSAGE'
  | 'CUSTOM'
  | 'MODEL_ATTEMPT_FAILURE'
  | 'NATIVE_COMPACTION'
  | 'CUSTOM_MESSAGE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'
  | 'COMPACTION'
  | 'TURN_END'
  | 'NOTIFICATION'
  | 'SETTINGS'
  | 'FORK'

export const ENTRY_TYPE_VALUES: readonly EntryType[] = [
  'ROOT',
  'TURN_START',
  'MESSAGE',
  'CUSTOM',
  'MODEL_ATTEMPT_FAILURE',
  'NATIVE_COMPACTION',
  'CUSTOM_MESSAGE',
  'ASSISTANT_ERROR',
  'ASSISTANT_ABORTED',
  'COMPACTION',
  'TURN_END',
  'NOTIFICATION',
  'SETTINGS',
  'FORK',
]

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentCreateDTO */
export interface EnvironmentCreateDTO {
  name: string
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentDTO */
export interface EnvironmentDTO {
  id: string
  name: string
  installConfig?: EnvironmentInstallConfigDTO | null
  registrationToken?: string | null
  status: EnvironmentStatus
  ready: boolean
  statusExpiresAt: number | null
  lastSeenAt?: number | null
  operatingSystem?: DaemonOperatingSystem | null
  timeZone?: string | null
  userName?: string | null
  homeDirectory?: string | null
  note?: string | null
  daemonVersion?: string | null
  capabilities: LiveEnvironmentCapabilityDTO[]
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventDTO */
export interface EnvironmentEventDTO {
  occurredAt: number
  level: EnvironmentEventLevel
  type: EnvironmentEventType
  message: string
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventLevel */
export type EnvironmentEventLevel =
  | 'INFO'
  | 'WARN'
  | 'ERROR'

export const ENVIRONMENT_EVENT_LEVEL_VALUES: readonly EnvironmentEventLevel[] = [
  'INFO',
  'WARN',
  'ERROR',
]

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventType */
export type EnvironmentEventType =
  | 'CONNECTING'
  | 'READY'
  | 'DISCONNECTED'
  | 'SKILL_SYNC_STARTED'
  | 'SKILL_SYNC_SUCCEEDED'
  | 'SKILL_SYNC_FAILED'
  | 'SKILL_STATE_UPDATED'
  | 'UPDATE_FAILED'

export const ENVIRONMENT_EVENT_TYPE_VALUES: readonly EnvironmentEventType[] = [
  'CONNECTING',
  'READY',
  'DISCONNECTED',
  'SKILL_SYNC_STARTED',
  'SKILL_SYNC_SUCCEEDED',
  'SKILL_SYNC_FAILED',
  'SKILL_STATE_UPDATED',
  'UPDATE_FAILED',
]

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

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallCodeDTO */
export interface EnvironmentInstallCodeDTO {
  code: string
  expiresAt: number
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallCodeRequestDTO */
export interface EnvironmentInstallCodeRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallConfigDTO */
export interface EnvironmentInstallConfigDTO {
  operatingSystem: DaemonOperatingSystem
  daemon: DaemonConfiguration
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallConfigUpdateDTO */
export interface EnvironmentInstallConfigUpdateDTO {
  expectedVersion: string
  installConfig: EnvironmentInstallConfigDTO
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentRotateTokenDTO */
export interface EnvironmentRotateTokenDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentRuntimeDTO */
export interface EnvironmentRuntimeDTO {
  daemonVersion: string
  update: EnvironmentUpdateProgressDTO | null
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentRuntimeStatusDTO */
export interface EnvironmentRuntimeStatusDTO {
  revision: string
  status: EnvironmentRuntimeDTO
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentStatus */
export type EnvironmentStatus =
  | 'CONNECTING'
  | 'READY'
  | 'OFFLINE'

export const ENVIRONMENT_STATUS_VALUES: readonly EnvironmentStatus[] = [
  'CONNECTING',
  'READY',
  'OFFLINE',
]

/** Java：fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport */
export type EnvironmentSupport =
  | 'NONE'
  | 'ROUTED'
  | 'REQUIRED'

export const ENVIRONMENT_SUPPORT_VALUES: readonly EnvironmentSupport[] = [
  'NONE',
  'ROUTED',
  'REQUIRED',
]

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

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateCommandStatus */
export type EnvironmentUpdateCommandStatus =
  | 'CURRENT'
  | 'IN_PROGRESS'
  | 'SENT_UNCONFIRMED'

export const ENVIRONMENT_UPDATE_COMMAND_STATUS_VALUES: readonly EnvironmentUpdateCommandStatus[] = [
  'CURRENT',
  'IN_PROGRESS',
  'SENT_UNCONFIRMED',
]

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateProgressDTO */
export interface EnvironmentUpdateProgressDTO {
  targetVersion: string
  downloadedBytes: string
  totalBytes: string | null
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.EnvironmentUpdateStartDTO */
export interface EnvironmentUpdateStartDTO {
  runtimeStatus: EnvironmentRuntimeStatusDTO
  commandStatus: EnvironmentUpdateCommandStatus
}

/** Java：fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind */
export type ExecutionExitKind =
  | 'DRIVER'
  | 'TOOL'
  | 'REMOTE'

export const EXECUTION_EXIT_KIND_VALUES: readonly ExecutionExitKind[] = [
  'DRIVER',
  'TOOL',
  'REMOTE',
]

/** Java：fun.fengwk.kkstudio.harness.api.generation.GenerationStatus */
export type GenerationStatus =
  | 'READY'
  | 'RUNNING'
  | 'WAITING_RETRY'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'BLOCKED'

export const GENERATION_STATUS_VALUES: readonly GenerationStatus[] = [
  'READY',
  'RUNNING',
  'WAITING_RETRY',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'BLOCKED',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessAcceptedCommandsDTO */
export interface HarnessAcceptedCommandsDTO {
  session: HarnessSessionDTO
  rootEntry: HarnessSessionEntryDTO
  thread: HarnessThreadDTO
  acceptedCommands: HarnessThreadCommandDTO[]
  replayed: boolean
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessActiveAssistantOutputDTO */
export interface HarnessActiveAssistantOutputDTO {
  generationId: string
  turnStartEntryId: string
  requestHeadEntryId: string
  status: GenerationStatus
  attempt: number
  sequence: string
  contents: HarnessAgentMessageContentDTO[]
  error: HarnessAssistantErrorDTO | null
  resultEntryId: string | null
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessAgentMessageContentDTO */
export interface HarnessAgentMessageContentDTO {
  type: HarnessAgentMessageContentType
  text?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessAgentMessageContentDTO.Type */
export type HarnessAgentMessageContentType =
  | 'text'
  | 'thinking'

export const HARNESS_AGENT_MESSAGE_CONTENT_TYPE_VALUES: readonly HarnessAgentMessageContentType[] = [
  'text',
  'thinking',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessAssistantErrorDTO */
export interface HarnessAssistantErrorDTO {
  code: string
  message: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessBranchSettingsDTO */
export interface HarnessBranchSettingsDTO {
  agentName: string
  execution: HarnessExecutionSelectionDTO
  environmentName: string | null
  goal: HarnessGoalSettingDTO | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCancelledInputDTO */
export interface HarnessCancelledInputDTO {
  sequence: string
  idempotencyKey: string
  type: ThreadCommandType
  payloadJson: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandBatchDTO */
export interface HarnessCommandBatchDTO {
  owner: HarnessCommandOwnerDTO
  target: HarnessCommandTargetDTO
  commands: HarnessCommandCreateDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO.UserMessageDTO */
export interface HarnessCommandCreateUserMessageDTO {
  type: 'USER_MESSAGE'
  idempotencyKey: string
  contents: HarnessUserMessageContentDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO.GoalDTO */
export interface HarnessCommandCreateGoalDTO {
  type: 'GOAL'
  idempotencyKey: string
  text?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO.SetAgentDTO */
export interface HarnessCommandCreateSetAgentDTO {
  type: 'SET_AGENT'
  idempotencyKey: string
  agentName: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO.SetExecutionDTO */
export interface HarnessCommandCreateSetExecutionDTO {
  type: 'SET_EXECUTION'
  idempotencyKey: string
  execution: HarnessExecutionSelectionDTO
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO.SetEnvironmentDTO */
export interface HarnessCommandCreateSetEnvironmentDTO {
  type: 'SET_ENVIRONMENT'
  idempotencyKey: string
  environmentName?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandCreateDTO */
export type HarnessCommandCreateDTO =
  | HarnessCommandCreateUserMessageDTO
  | HarnessCommandCreateGoalDTO
  | HarnessCommandCreateSetAgentDTO
  | HarnessCommandCreateSetExecutionDTO
  | HarnessCommandCreateSetEnvironmentDTO

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandOwnerDTO */
export interface HarnessCommandOwnerDTO {
  kind: string
  id: string
  title?: string | null
  link?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandTargetDTO.NewSessionDTO */
export interface HarnessCommandTargetNewSessionDTO {
  type: 'NEW_SESSION'
  sessionId: string
  threadId: string
  rootSettings: HarnessBranchSettingsDTO
  yoloEnabled: boolean
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandTargetDTO.NewThreadDTO */
export interface HarnessCommandTargetNewThreadDTO {
  type: 'NEW_THREAD'
  sessionId: string
  startEntryId: string
  threadId: string
  threadName: string
  yoloEnabled: boolean
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandTargetDTO.NewForkedSessionDTO */
export interface HarnessCommandTargetNewForkedSessionDTO {
  type: 'NEW_FORKED_SESSION'
  sourceThreadId: string
  startEntryId: string
  sessionId: string
  threadId: string
  yoloEnabled: boolean
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessCommandTargetDTO */
export type HarnessCommandTargetDTO =
  | HarnessCommandTargetNewSessionDTO
  | HarnessCommandTargetNewThreadDTO
  | HarnessCommandTargetNewForkedSessionDTO

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessExecutionAbandonDTO */
export interface HarnessExecutionAbandonDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessExecutionExitDTO */
export interface HarnessExecutionExitDTO {
  executionId: string
  invocationId: string
  attempt: number
  kind: ExecutionExitKind
  registeredAt: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO */
export interface HarnessExecutionSelectionDTO {
  driver: string
  settings: Record<string, unknown>
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessGenerationFailureDTO */
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

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessGoalSettingDTO */
export interface HarnessGoalSettingDTO {
  id: string
  text: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessImageInputTier */
export type HarnessImageInputTier =
  | '720P'
  | '1080P'
  | 'ORIGINAL'

export const HARNESS_IMAGE_INPUT_TIER_VALUES: readonly HarnessImageInputTier[] = [
  '720P',
  '1080P',
  'ORIGINAL',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessManualCompactionDTO */
export interface HarnessManualCompactionDTO {
  available: boolean
  disabledReason: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO.CacheControlDTO */
export interface HarnessModelRequestDebugCacheControlDTO {
  retention: HarnessModelRequestDebugCacheControlRetention
  key: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO.CacheControlDTO.Retention */
export type HarnessModelRequestDebugCacheControlRetention =
  | 'NONE'
  | 'SHORT'
  | 'LONG'

export const HARNESS_MODEL_REQUEST_DEBUG_CACHE_CONTROL_RETENTION_VALUES: readonly HarnessModelRequestDebugCacheControlRetention[] = [
  'NONE',
  'SHORT',
  'LONG',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO */
export interface HarnessModelRequestDebugDTO {
  kind: HarnessModelRequestDebugKind
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

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO.FrozenInvocationDTO */
export interface HarnessModelRequestDebugFrozenInvocationDTO {
  kind: HarnessModelRequestDebugFrozenInvocationFrozenKind
  generationId: string
  attempt: number
  requestJson: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO.FrozenInvocationDTO.FrozenKind */
export type HarnessModelRequestDebugFrozenInvocationFrozenKind = 'FROZEN_INVOCATION'

export const HARNESS_MODEL_REQUEST_DEBUG_FROZEN_INVOCATION_FROZEN_KIND_VALUES: readonly HarnessModelRequestDebugFrozenInvocationFrozenKind[] = [
  'FROZEN_INVOCATION',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO.Kind */
export type HarnessModelRequestDebugKind = 'NEXT_REQUEST_PREVIEW'

export const HARNESS_MODEL_REQUEST_DEBUG_KIND_VALUES: readonly HarnessModelRequestDebugKind[] = [
  'NEXT_REQUEST_PREVIEW',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugRequestDTO */
export interface HarnessModelRequestDebugRequestDTO {
  model: HarnessModelSelectionDTO
  environmentName?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO.SkillDTO */
export interface HarnessModelRequestDebugSkillDTO {
  packageName: string
  name: string
  description: string
  path: string
  delivery: SkillPromptResolutionDelivery
  packageVersion: string
  headCommit: string | null
  artifactSha256: string
  installedSha256: string | null
  promptXml: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO.SubagentDTO */
export interface HarnessModelRequestDebugSubagentDTO {
  name: string
  description: string
  tools: string[]
  skills: SkillRefDTO[]
  subagents: string[]
  configurationJson: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelRequestDebugDTO.ToolDTO */
export interface HarnessModelRequestDebugToolDTO {
  name: string
  description: string
  inputSchemaJson: string
  environmentSupport: EnvironmentSupportDTO
  provenance: string
  state: LiveTurnPlanToolState
  filterReason: LiveTurnPlanFilterReason | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessModelSelectionDTO */
export interface HarnessModelSelectionDTO {
  modelName: string
  variant?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessNameUpdateDTO */
export interface HarnessNameUpdateDTO {
  name: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessProviderRequestPreviewDTO */
export interface HarnessProviderRequestPreviewDTO {
  kind: HarnessProviderRequestPreviewKind
  generatedAt: number
  providerType: string
  modelName: string
  bodyByteSize: number
  bodyJson: string
  sourceHeadEntryId?: string | null
  notice: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessProviderRequestPreviewDTO.Kind */
export type HarnessProviderRequestPreviewKind = 'HISTORICAL_REQUEST_PREVIEW'

export const HARNESS_PROVIDER_REQUEST_PREVIEW_KIND_VALUES: readonly HarnessProviderRequestPreviewKind[] = [
  'HISTORICAL_REQUEST_PREVIEW',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionDTO */
export interface HarnessSessionDTO {
  sessionId: string
  name: string
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionEntryDTO */
export interface HarnessSessionEntryDTO {
  entryId: string
  sessionId: string
  parentEntryId: string | null
  entryType: EntryType
  payloadJson: string
  createdAt: number
  usageCost: HarnessUsageCostDTO | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionEntryPageDTO */
export interface HarnessSessionEntryPageDTO {
  items: HarnessSessionEntryDTO[]
  nextCursor: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileContentDTO */
export interface HarnessSessionFileContentDTO {
  sessionId: string
  path: string
  sizeBytes: string
  revision: number
  updatedAt: number
  text: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileEntryDTO */
export interface HarnessSessionFileEntryDTO {
  name: string
  path: string
  directory: boolean
  sizeBytes?: string | null
  revision?: number | null
  updatedAt?: number | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionFileListDTO */
export interface HarnessSessionFileListDTO {
  sessionId: string
  path: string
  entries: HarnessSessionFileEntryDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionSummaryDTO */
export interface HarnessSessionSummaryDTO {
  sessionId: string
  name: string
  createdAt: number
  lastActivityAt: number
  firstMessagePreview: string | null
  threadCount: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessStoppedThreadReceiptDTO */
export interface HarnessStoppedThreadReceiptDTO {
  threadId: string
  stopRequestId: string
  stoppedTurnEndEntryId: string | null
  cancelledCommandCount: number
  cancelledInputs: HarnessCancelledInputDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCommandBatchDTO */
export interface HarnessThreadCommandBatchDTO {
  expectedHeadEntryId?: string | null
  expectedNextCommandSequence?: string | null
  commands: HarnessCommandCreateDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCommandDTO */
export interface HarnessThreadCommandDTO {
  threadId: string
  sequence: string
  type: ThreadCommandType
  state: ThreadCommandState
  idempotencyKey: string
  payloadJson: string
  cancelledAt: number | null
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactDTO */
export interface HarnessThreadCompactDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadCompactResultDTO */
export interface HarnessThreadCompactResultDTO {
  thread: HarnessThreadDTO
  turnStartEntryId: string
  childThreadId: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadDTO */
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
  status: ThreadRuntimeStatus
  executionControl: ThreadExecutionControl
  branchSettings: HarnessBranchSettingsDTO
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSnapshotDTO */
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

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopDTO */
export interface HarnessThreadStopDTO {
  stopRequestId: string
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopResultDTO */
export interface HarnessThreadStopResultDTO {
  status: HarnessThreadStopResultStatus
  thread: HarnessThreadDTO
  stoppedThreads: HarnessStoppedThreadReceiptDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStopResultDTO.Status */
export type HarnessThreadStopResultStatus =
  | 'STOPPED'
  | 'REPLAYED'

export const HARNESS_THREAD_STOP_RESULT_STATUS_VALUES: readonly HarnessThreadStopResultStatus[] = [
  'STOPPED',
  'REPLAYED',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadStreamCatchupDTO */
export interface HarnessThreadStreamCatchupDTO {
  invocationId: string
  attempt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSummaryDTO */
export interface HarnessThreadSummaryDTO {
  threadId: string
  parentThreadId: string | null
  name: string
  createdAt: number
  updatedAt: number
  status: ThreadRuntimeStatus
  executionControl: ThreadExecutionControl
  execution: HarnessExecutionSelectionDTO
  headMessagePreview: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadTreeNodeDTO */
export interface HarnessThreadTreeNodeDTO {
  threadId: string
  parentThreadId: string | null
  name: string
  agentName: string
  execution: HarnessExecutionSelectionDTO
  status: ThreadRuntimeStatus
  executionControl: ThreadExecutionControl
  turnCount: number
  toolCallCount: number
  outcome: TurnEndOutcome | null
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadYoloUpdateDTO */
export interface HarnessThreadYoloUpdateDTO {
  yoloEnabled: boolean
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessToolApprovalDTO */
export interface HarnessToolApprovalDTO {
  decision: HarnessToolApprovalDecision
  decisionId: string
  reason?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessToolApprovalDTO.Decision */
export type HarnessToolApprovalDecision =
  | 'ALLOW'
  | 'DENY'

export const HARNESS_TOOL_APPROVAL_DECISION_VALUES: readonly HarnessToolApprovalDecision[] = [
  'ALLOW',
  'DENY',
]

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessToolInputDTO.Answer */
export interface HarnessToolInputAnswer {
  type: 'ANSWER'
  submissionId: string
  answers: (string[])[]
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessToolInputDTO.Decline */
export interface HarnessToolInputDecline {
  type: 'DECLINE'
  submissionId: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessToolInputDTO */
export type HarnessToolInputDTO =
  | HarnessToolInputAnswer
  | HarnessToolInputDecline

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessToolInputResultDTO */
export interface HarnessToolInputResultDTO {
  threadId: string
  toolInvocationId: string
  submissionId: string
  actor: string
  acceptedAt: number
  materialized: boolean
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessUsageCostDTO */
export interface HarnessUsageCostDTO {
  currency: string
  amount: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO.TextDTO */
export interface HarnessUserMessageContentTextDTO {
  type: 'TEXT'
  text: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO.AttachmentDTO */
export interface HarnessUserMessageContentAttachmentDTO {
  type: 'ATTACHMENT'
  uploadId: string
  imageTier?: HarnessImageInputTier | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO.ResourceDTO */
export interface HarnessUserMessageContentResourceDTO {
  type: 'RESOURCE'
  blobId: string
  name: string
  preview?: string | null
  imageTier?: HarnessImageInputTier | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.HarnessUserMessageContentDTO */
export type HarnessUserMessageContentDTO =
  | HarnessUserMessageContentTextDTO
  | HarnessUserMessageContentAttachmentDTO
  | HarnessUserMessageContentResourceDTO

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

/** Java：fun.fengwk.kkstudio.platform.harness.dto.InteractionDTO.Input */
export interface InteractionInput {
  type: 'INPUT'
  toolInvocationId: string
  threadId: string
  sessionId: string
  rootThreadId: string
  owner: InteractionOwnerDTO
  toolCallId: string
  toolName: string
  argumentsJson: string
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.InteractionDTO.Approval */
export interface InteractionApproval {
  type: 'APPROVAL'
  toolInvocationId: string
  threadId: string
  sessionId: string
  rootThreadId: string
  owner: InteractionOwnerDTO
  toolCallId: string
  toolName: string
  argumentsJson: string
  approvalJson?: string | null
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.InteractionDTO.EnvironmentWait */
export interface InteractionEnvironmentWait {
  type: 'ENVIRONMENT_WAIT'
  rootThreadId: string
  owner: InteractionOwnerDTO
  environmentId: string
  environmentName: string
  waitingCount: number
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.InteractionDTO */
export type InteractionDTO =
  | InteractionInput
  | InteractionApproval
  | InteractionEnvironmentWait

/** Java：fun.fengwk.kkstudio.platform.harness.dto.InteractionOwnerDTO */
export interface InteractionOwnerDTO {
  kind: string
  id: string
  title?: string | null
  link?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.InteractionPageDTO */
export interface InteractionPageDTO {
  items: InteractionDTO[]
  nextCursor?: string | null
  total: number
  freshnessAt?: number | null
}

/** Java：fun.fengwk.kkstudio.harness.api.retry.InvocationRetryBackoffStrategy */
export type InvocationRetryBackoffStrategy =
  | 'FIXED'
  | 'EXPONENTIAL'

export const INVOCATION_RETRY_BACKOFF_STRATEGY_VALUES: readonly InvocationRetryBackoffStrategy[] = [
  'FIXED',
  'EXPONENTIAL',
]

/** Java：fun.fengwk.kkstudio.project.model.IssueActivityActorType */
export type IssueActivityActorType =
  | 'HUMAN'
  | 'AGENT'
  | 'SYSTEM'

export const ISSUE_ACTIVITY_ACTOR_TYPE_VALUES: readonly IssueActivityActorType[] = [
  'HUMAN',
  'AGENT',
  'SYSTEM',
]

/** Java：fun.fengwk.kkstudio.project.dto.IssueActivityDTO */
export interface IssueActivityDTO {
  issueId: string
  sequence: string
  kind: IssueActivityKind
  actorType: IssueActivityActorType
  actorAgentName: string | null
  runId: string | null
  body: string | null
  data?: unknown | null
  createdAt: number
}

/** Java：fun.fengwk.kkstudio.project.model.IssueActivityKind */
export type IssueActivityKind =
  | 'COMMENT'
  | 'RUN'
  | 'INSTRUCTION'
  | 'SPEC_CHANGE'
  | 'STATE_CHANGE'
  | 'CONTROL'

export const ISSUE_ACTIVITY_KIND_VALUES: readonly IssueActivityKind[] = [
  'COMMENT',
  'RUN',
  'INSTRUCTION',
  'SPEC_CHANGE',
  'STATE_CHANGE',
  'CONTROL',
]

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
  pauseReason: PauseReason | null
  pauseDetail: string | null
  version: string
  createdAt: number
  updatedAt: number
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
  createdAt: number
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
  status: IssueRunStatus
  startEntryId: string
  endEntryId: string | null
  finalAnswerEntryId: string | null
  nextState: string | null
  observedActivitySequence: string
  remainingExecutionMs: string
  error: string | null
  version: string
  startedAt: number
  endedAt: number | null
}

/** Java：fun.fengwk.kkstudio.project.domain.IssueRunStatus */
export type IssueRunStatus =
  | 'RUNNING'
  | 'WAITING'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

export const ISSUE_RUN_STATUS_VALUES: readonly IssueRunStatus[] = [
  'RUNNING',
  'WAITING',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
]

/** Java：fun.fengwk.kkstudio.project.dto.IssueRunSummaryDTO */
export interface IssueRunSummaryDTO {
  id: string
  issueId: string
  ordinal: string
  state: string
  status: IssueRunStatus
  agentName: string | null
  startedAt: number
  endedAt: number | null
}

/** Java：fun.fengwk.kkstudio.project.dto.IssueStageBudgetDTO */
export interface IssueStageBudgetDTO {
  state: string
  maxRuns: number
  budgetAfterOrdinal: string
  usedRuns: string
  remainingRuns: string
}

/** Java：fun.fengwk.kkstudio.platform.environment.dto.LiveEnvironmentCapabilityDTO */
export interface LiveEnvironmentCapabilityDTO {
  id: string
}

/** Java：fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan.FilterReason */
export type LiveTurnPlanFilterReason = 'ENVIRONMENT_NOT_SELECTED'

export const LIVE_TURN_PLAN_FILTER_REASON_VALUES: readonly LiveTurnPlanFilterReason[] = [
  'ENVIRONMENT_NOT_SELECTED',
]

/** Java：fun.fengwk.kkstudio.platform.harness.thread.command.LiveTurnPlan.ToolState */
export type LiveTurnPlanToolState =
  | 'SENT'
  | 'FILTERED'

export const LIVE_TURN_PLAN_TOOL_STATE_VALUES: readonly LiveTurnPlanToolState[] = [
  'SENT',
  'FILTERED',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.mcp.McpDiscoveryStatus */
export type McpDiscoveryStatus =
  | 'UNVERIFIED'
  | 'AVAILABLE'
  | 'FAILED'

export const MCP_DISCOVERY_STATUS_VALUES: readonly McpDiscoveryStatus[] = [
  'UNVERIFIED',
  'AVAILABLE',
  'FAILED',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.McpServerCreateDTO */
export interface McpServerCreateDTO {
  name: string
  url: string
  headers?: SecretUpdate | null
  enabled?: boolean | null
  timeoutMillis?: number | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.McpServerDTO */
export interface McpServerDTO {
  name: string
  url: string
  enabled: boolean
  timeoutMillis: string
  discoveryStatus: McpDiscoveryStatus
  headers: SecretConfiguredDTO
  toolCount: number
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.McpServerDiscoverDTO */
export interface McpServerDiscoverDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.McpServerSummaryDTO */
export interface McpServerSummaryDTO {
  name: string
  enabled: boolean
  timeoutMillis: string
  discoveryStatus: McpDiscoveryStatus
  toolCount: number
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.McpServerUpdateDTO */
export interface McpServerUpdateDTO {
  expectedVersion: string
  url: string
  headers: SecretUpdate
  enabled: boolean
  timeoutMillis: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelAbilitiesDTO */
export interface ModelAbilitiesDTO {
  tools: boolean
  reasoning: boolean
  inputModalities: ModelInputModality[]
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogAbilitiesDTO */
export interface ModelCatalogAbilitiesDTO {
  tools?: boolean | null
  reasoning?: boolean | null
  inputModalities?: ModelInputModality[] | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogCandidateDTO */
export interface ModelCatalogCandidateDTO {
  id: string
  provider: string
  modelId: string
  name: string
  limit?: ModelCatalogLimitDTO | null
  abilities: ModelCatalogAbilitiesDTO
  pricing?: ModelPricingDTO | null
  variants?: ModelVariantDTO[] | null
  defaultVariant?: string | null
  supportIssues: string[]
  pricingSupported: boolean
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogLimitDTO */
export interface ModelCatalogLimitDTO {
  context?: number | null
  output?: number | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelCatalogPageDTO */
export interface ModelCatalogPageDTO {
  generation: string
  fetchedAt?: number | null
  loading: boolean
  error?: string | null
  items: ModelCatalogCandidateDTO[]
  nextCursor?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelConfigDTO */
export interface ModelConfigDTO {
  limit: ModelLimitDTO
  abilities: ModelAbilitiesDTO
  pricing?: ModelPricingDTO | null
  defaultVariant?: string | null
  variants: ModelVariantDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SingleModelCreateDTO */
export interface SingleModelCreateDTO {
  type: 'MODEL'
  name: string
  description?: string | null
  providerName: string
  modelId: string
  config: ModelConfigDTO
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupCreateDTO */
export interface ModelGroupCreateDTO {
  type: 'GROUP'
  name: string
  description?: string | null
  group: ModelGroupConfigDTO
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelCreateDTO */
export type ModelCreateDTO =
  | SingleModelCreateDTO
  | ModelGroupCreateDTO

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SingleModelDTO */
export interface SingleModelDTO {
  type: 'MODEL'
  name: string
  description?: string | null
  providerName: string
  modelId: string
  config: ModelConfigDTO
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupDTO */
export interface ModelGroupDTO {
  type: 'GROUP'
  name: string
  description?: string | null
  group: ModelGroupConfigDTO
  config: ModelConfigDTO
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelDTO */
export type ModelDTO =
  | SingleModelDTO
  | ModelGroupDTO

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupConfigDTO */
export interface ModelGroupConfigDTO {
  members: ModelGroupMemberDTO[]
  fallbackStatusCodes: number[]
  cooldownSeconds: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupMemberDTO */
export interface ModelGroupMemberDTO {
  modelName: string
  variant: string
}

/** Java：fun.fengwk.kkstudio.harness.model.ModelInputModality */
export type ModelInputModality =
  | 'TEXT'
  | 'IMAGE'
  | 'AUDIO'
  | 'VIDEO'
  | 'DOCUMENT'

export const MODEL_INPUT_MODALITY_VALUES: readonly ModelInputModality[] = [
  'TEXT',
  'IMAGE',
  'AUDIO',
  'VIDEO',
  'DOCUMENT',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelLimitDTO */
export interface ModelLimitDTO {
  context: number
  output: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingConditionDTO */
export interface ModelPricingConditionDTO {
  context?: ModelPricingContextConditionDTO | null
  serviceTier?: string[] | null
  date?: ModelPricingDateConditionDTO | null
  time?: ModelPricingTimeConditionDTO | null
  requestBody?: ModelPricingRequestBodyConditionDTO | null
  all?: ModelPricingConditionDTO[] | null
  any?: ModelPricingConditionDTO[] | null
  not?: ModelPricingConditionDTO | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingContextConditionDTO */
export interface ModelPricingContextConditionDTO {
  gt?: number | null
  gte?: number | null
  lt?: number | null
  lte?: number | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDTO */
export interface ModelPricingDTO {
  base: ModelRateSetDTO
  overrides: ModelPricingOverrideDTO[]
  multipliers: ModelPricingMultiplierDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingDateConditionDTO */
export interface ModelPricingDateConditionDTO {
  gte?: string | null
  lt?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingFactorsDTO */
export interface ModelPricingFactorsDTO {
  input?: string | null
  output?: string | null
  cacheRead?: string | null
  cacheWrite?: string | null
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingMultiplierDTO */
export interface ModelPricingMultiplierDTO {
  id: string
  when: ModelPricingConditionDTO
  factors: ModelPricingFactorsDTO
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingOverrideDTO */
export interface ModelPricingOverrideDTO {
  id: string
  when: ModelPricingConditionDTO
  rates: ModelRatePatchDTO
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingRequestBodyConditionDTO */
export interface ModelPricingRequestBodyConditionDTO {
  path?: string | null
  equals?: unknown | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelPricingTimeConditionDTO */
export interface ModelPricingTimeConditionDTO {
  start: string
  end: string
  timezone: string
  weekdays?: number[] | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelRatePatchDTO */
export interface ModelRatePatchDTO {
  input?: string | null
  output?: string | null
  cacheRead?: string | null
  cacheWrite?: string | null
  cacheWriteByTtl?: Record<string, string> | null
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelRateSetDTO */
export interface ModelRateSetDTO {
  input: string
  output: string
  cacheRead: string
  cacheWrite: string
  cacheWriteByTtl: Record<string, string>
  reasoning?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SingleModelUpdateDTO */
export interface SingleModelUpdateDTO {
  type: 'MODEL'
  name: string
  description?: string | null
  providerName: string
  modelId: string
  config: ModelConfigDTO
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelGroupUpdateDTO */
export interface ModelGroupUpdateDTO {
  type: 'GROUP'
  name: string
  description?: string | null
  group: ModelGroupConfigDTO
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelUpdateDTO */
export type ModelUpdateDTO =
  | SingleModelUpdateDTO
  | ModelGroupUpdateDTO

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ModelVariantDTO */
export interface ModelVariantDTO {
  id: string
  promptCacheRetention: PromptCacheRetention
  reasoningEffort?: string | null
  protocolOptionsJson?: string | null
}

/** Java：fun.fengwk.kkstudio.project.model.PauseReason */
export type PauseReason =
  | 'USER'
  | 'ERROR'

export const PAUSE_REASON_VALUES: readonly PauseReason[] = [
  'USER',
  'ERROR',
]

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthCompleteRequestDTO */
export interface PluginAuthCompleteRequestDTO {
  authAttemptId: string
  callbackUrl: string
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthKindDTO.DeepLink */
export interface PluginAuthKindDeepLink {
  type: 'DEEP_LINK'
  regionCandidates: string[]
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthKindDTO.StaticKey */
export interface PluginAuthKindStaticKey {
  type: 'STATIC_KEY'
  secretField: string
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthKindDTO */
export type PluginAuthKindDTO =
  | PluginAuthKindDeepLink
  | PluginAuthKindStaticKey

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthPrepareDTO */
export interface PluginAuthPrepareDTO {
  authAttemptId: string
  loginUrl: string
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginAuthPrepareRequestDTO */
export interface PluginAuthPrepareRequestDTO {
  region: string
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConfigurationDTO */
export interface PluginConfigurationDTO {
  version: string
  values: unknown
  valuesSchema: unknown
  defaults: unknown
  secrets: Record<string, PluginSecretProjectionDTO>
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConfigurationUpdateRequestDTO */
export interface PluginConfigurationUpdateRequestDTO {
  expectedVersion: string
  values: unknown
  secretPatch?: Record<string, SecretUpdate> | null
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO.Authentication */
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

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO.BrowserSession */
export type PluginConnectionCheckBrowserSession = 'UNKNOWN'

export const PLUGIN_CONNECTION_CHECK_BROWSER_SESSION_VALUES: readonly PluginConnectionCheckBrowserSession[] = [
  'UNKNOWN',
]

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO */
export interface PluginConnectionCheckDTO {
  pluginId: string
  appliedVersion: string
  checkedAt: number
  reachability: PluginConnectionCheckReachability
  authentication: PluginConnectionCheckAuthentication
  browserSession: PluginConnectionCheckBrowserSession
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckDTO.Reachability */
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

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginConnectionCheckRequestDTO */
export interface PluginConnectionCheckRequestDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus */
export type PluginCredentialStatus =
  | 'NOT_CONNECTED'
  | 'CONNECTED'
  | 'REFRESH_FAILED'
  | 'REFRESH_UNCERTAIN'
  | 'REAUTH_REQUIRED'

export const PLUGIN_CREDENTIAL_STATUS_VALUES: readonly PluginCredentialStatus[] = [
  'NOT_CONNECTED',
  'CONNECTED',
  'REFRESH_FAILED',
  'REFRESH_UNCERTAIN',
  'REAUTH_REQUIRED',
]

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginDTO */
export interface PluginDTO {
  pluginId: string
  name: string
  version: string
  authKind?: PluginAuthKindDTO | null
  status?: PluginCredentialStatus | null
  region?: string | null
  expiresAt?: number | null
  nextRefreshAt?: number | null
  lastRefreshedAt?: number | null
  lastRefreshError?: string | null
  configuration: PluginConfigurationDTO
  desiredVersion: string
  appliedVersion?: string | null
  connectionCheckSupported: boolean
  harnessToolNames: string[]
  canvasFunctionNames: string[]
  applicationError?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.pluginhost.dto.PluginSecretProjectionDTO */
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
  archivedAt: number | null
  createdAt: number
  updatedAt: number
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

/** Java：fun.fengwk.kkstudio.harness.model.cache.PromptCacheRetention */
export type PromptCacheRetention =
  | 'NONE'
  | 'SHORT'
  | 'LONG'

export const PROMPT_CACHE_RETENTION_VALUES: readonly PromptCacheRetention[] = [
  'NONE',
  'SHORT',
  'LONG',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ProviderCreateDTO */
export interface ProviderCreateDTO {
  name: string
  providerType: ProviderType
  description?: string | null
  baseUrl?: string | null
  credential?: string | null
  modelCallTimeoutMillis?: number | null
  modelCallIdleTimeoutMillis?: number | null
  modelHttpRetryStatusCodes?: number[] | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ProviderDTO */
export interface ProviderDTO {
  name: string
  description?: string | null
  providerType: ProviderType
  baseUrl?: string | null
  credential: SecretConfiguredDTO
  modelCallTimeoutMillis: string
  modelCallIdleTimeoutMillis: string
  modelHttpRetryStatusCodes: number[] | null
  promptCacheRetentions: PromptCacheRetention[]
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.harness.model.provider.ProviderType */
export type ProviderType =
  | 'openai'
  | 'openai_responses'
  | 'anthropic'
  | 'google'

export const PROVIDER_TYPE_VALUES: readonly ProviderType[] = [
  'openai',
  'openai_responses',
  'anthropic',
  'google',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ProviderUpdateDTO */
export interface ProviderUpdateDTO {
  description?: string | null
  baseUrl?: string | null
  credential: SecretUpdate
  modelCallTimeoutMillis: number
  modelCallIdleTimeoutMillis: number
  modelHttpRetryStatusCodes?: number[] | null
  expectedVersion: string
}

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

/** Java：fun.fengwk.kkstudio.platform.secret.SecretConfiguredDTO */
export interface SecretConfiguredDTO {
  configured: boolean
}

/** Java：fun.fengwk.kkstudio.platform.secret.SecretUpdate */
export interface SecretUpdate {
  operation: SecretUpdateOperation
  value?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.secret.SecretUpdate.Operation */
export type SecretUpdateOperation =
  | 'KEEP'
  | 'SET'
  | 'CLEAR'

export const SECRET_UPDATE_OPERATION_VALUES: readonly SecretUpdateOperation[] = [
  'KEEP',
  'SET',
  'CLEAR',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillArchiveDownloadDTO */
export interface SkillArchiveDownloadDTO {
  filename: string
  sha256: string
  sizeBytes: string
  download: StoragePresignedUrlDTO
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillEnvironmentSyncDTO */
export interface SkillEnvironmentSyncDTO {
  environmentId: string
  installedSha256: string | null
  localPath: string | null
  error: string | null
  upToDate: boolean
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillGitSourceDTO */
export interface SkillGitSourceDTO {
  repositoryUrl: string
  branch: string
  token: SecretConfiguredDTO
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillManifestEntryDTO */
export interface SkillManifestEntryDTO {
  name: string
  description: string
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCheckDTO */
export interface SkillPackageCheckDTO {
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageCreateDTO */
export interface SkillPackageCreateDTO {
  packageName: string
  description?: string | null
  repositoryUrl: string
  branch: string
  token?: SecretUpdate | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageDetailDTO */
export interface SkillPackageDetailDTO {
  packageName: string
  description?: string | null
  packageVersion: string
  publishedAt: number
  skills: SkillManifestEntryDTO[]
  source: SkillPackageSource
  git: SkillGitSourceDTO | null
  headCommit: string | null
  headCheckedAt: number | null
  headCheckError: string | null
  updateAvailable: boolean
  artifactSha256: string
  artifactSizeBytes: string
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageEditDTO */
export interface SkillPackageEditDTO {
  expectedVersion: string
  description?: string | null
  branch?: string | null
  token: SecretUpdate
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageFileDTO */
export interface SkillPackageFileDTO {
  path: string
  size: string
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackagePublishDTO */
export interface SkillPackagePublishDTO {
  expectedVersion: string
  targetCommit: string
}

/** Java：fun.fengwk.kkstudio.platform.catalog.skill.SkillPackageSource */
export type SkillPackageSource =
  | 'GIT'
  | 'UPLOAD'

export const SKILL_PACKAGE_SOURCE_VALUES: readonly SkillPackageSource[] = [
  'GIT',
  'UPLOAD',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingCreateDTO */
export interface SkillPackageStagingCreateDTO {
  uploadId: string
  packageName: string
  packageVersion: string
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingDTO */
export interface SkillPackageStagingDTO {
  id: string
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

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageStagingPublishDTO */
export interface SkillPackageStagingPublishDTO {
  packageName: string
  packageVersion: string
  description?: string | null
  expectedVersion?: string | null
}

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.SkillPackageSummaryDTO */
export interface SkillPackageSummaryDTO {
  packageName: string
  description?: string | null
  packageVersion: string
  publishedAt: number
  skills: SkillManifestEntryDTO[]
  source: SkillPackageSource
  updateAvailable: boolean
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.environment.skill.SkillPromptResolution.Delivery */
export type SkillPromptResolutionDelivery =
  | 'LOCAL'
  | 'PLATFORM'

export const SKILL_PROMPT_RESOLUTION_DELIVERY_VALUES: readonly SkillPromptResolutionDelivery[] = [
  'LOCAL',
  'PLATFORM',
]

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

/** Java：fun.fengwk.kkstudio.platform.storage.dto.StoragePresignedUrlDTO */
export interface StoragePresignedUrlDTO {
  method: StorageUrlMethod
  url: string
  headers: Record<string, string>
  expiresAt: number
  mediaType: string | null
  sizeBytes: string | null
}

/** Java：fun.fengwk.kkstudio.platform.storage.dto.StorageUploadPendingDTO */
export interface StorageUploadPendingDTO {
  type: 'PENDING'
  id: string
  presignedPut: StoragePresignedUrlDTO
  expiresAt: number
}

/** Java：fun.fengwk.kkstudio.platform.storage.dto.StorageUploadReadyDTO */
export interface StorageUploadReadyDTO {
  type: 'READY'
  id: string
  blobId: string
  expiresAt: number
}

/** Java：fun.fengwk.kkstudio.platform.storage.dto.StorageUploadDTO */
export type StorageUploadDTO =
  | StorageUploadPendingDTO
  | StorageUploadReadyDTO

/** Java：fun.fengwk.kkstudio.platform.storage.dto.StorageUploadReserveRequestDTO */
export interface StorageUploadReserveRequestDTO {
  filename: string
  mediaType: string
  sizeBytes: number
  sha256: string
}

/** Java：fun.fengwk.kkstudio.platform.storage.dto.StorageUrlMethod */
export type StorageUrlMethod =
  | 'PUT'
  | 'GET'

export const STORAGE_URL_METHOD_VALUES: readonly StorageUrlMethod[] = [
  'PUT',
  'GET',
]

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsAdvancedDTO */
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

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsAiRuntimeDTO */
export interface SystemSettingsAiRuntimeDTO {
  retryMaxRetries: number
  retryBackoffStrategy: InvocationRetryBackoffStrategy
  retryBaseDelayMillis: string
  retryMaxDelayMillis: string
  compactionKeepRecentTokens: number
  subagentMaxDepth: number
  subagentMaxConcurrency: number
  subagentMaxTotalConcurrency: number
  subagentMaxTurns: number
  modelHttpRetryStatusCodes: number[]
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsDTO */
export interface SystemSettingsDTO {
  tool: SystemSettingsToolDTO
  aiRuntime: SystemSettingsAiRuntimeDTO
  environment: SystemSettingsEnvironmentDTO
  network: SystemSettingsNetworkDTO
  storageMedia: SystemSettingsStorageMediaDTO
  advanced: SystemSettingsAdvancedDTO
  version: string
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsEnvironmentDTO */
export interface SystemSettingsEnvironmentDTO {
  maxResourceBytes: string
  heartbeatTimeoutMillis: string
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsNetworkDTO */
export interface SystemSettingsNetworkDTO {
  proxyUrl?: string | null
  noProxyHosts: string
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.ApplyTiming */
export type SystemSettingsSchemaApplyTiming =
  | 'NEXT_INVOCATION'
  | 'NEXT_HTTP_REQUEST'
  | 'RESTART'

export const SYSTEM_SETTINGS_SCHEMA_APPLY_TIMING_VALUES: readonly SystemSettingsSchemaApplyTiming[] = [
  'NEXT_INVOCATION',
  'NEXT_HTTP_REQUEST',
  'RESTART',
]

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO */
export interface SystemSettingsSchemaDTO {
  sections: SystemSettingsSchemaSectionDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldDTO */
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

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.FieldType */
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

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.GroupDTO */
export interface SystemSettingsSchemaGroupDTO {
  key: string
  labelKey: string
  descriptionKey: string
  restartRequired: boolean
  applyTiming?: SystemSettingsSchemaApplyTiming | null
  fields: SystemSettingsSchemaFieldDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.OptionDTO */
export interface SystemSettingsSchemaOptionDTO {
  value: string
  labelKey: string
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsSchemaDTO.SectionDTO */
export interface SystemSettingsSchemaSectionDTO {
  key: string
  labelKey: string
  descriptionKey: string
  restartRequired: boolean
  groups: SystemSettingsSchemaGroupDTO[]
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsStorageMediaDTO */
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

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsToolDTO */
export interface SystemSettingsToolDTO {
  permission: Record<string, SystemSettingsToolPermissionRuleDTO[]>
  modelGatewayBusyRetryMillis: string
  toolGatewayOverloadRetryMillis: string
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsToolDTO.PermissionRuleDTO */
export interface SystemSettingsToolPermissionRuleDTO {
  pattern: string
  action: string
}

/** Java：fun.fengwk.kkstudio.platform.settings.dto.SystemSettingsUpdateDTO */
export interface SystemSettingsUpdateDTO {
  tool: SystemSettingsToolDTO
  aiRuntime: SystemSettingsAiRuntimeDTO
  environment: SystemSettingsEnvironmentDTO
  network: SystemSettingsNetworkDTO
  storageMedia: SystemSettingsStorageMediaDTO
  advanced: SystemSettingsAdvancedDTO
  expectedVersion: string
}

/** Java：fun.fengwk.kkstudio.harness.api.command.ThreadCommandState */
export type ThreadCommandState =
  | 'QUEUED'
  | 'APPLIED'
  | 'CANCELLED'

export const THREAD_COMMAND_STATE_VALUES: readonly ThreadCommandState[] = [
  'QUEUED',
  'APPLIED',
  'CANCELLED',
]

/** Java：fun.fengwk.kkstudio.harness.api.command.ThreadCommandType */
export type ThreadCommandType =
  | 'USER_MESSAGE'
  | 'CUSTOM_MESSAGE'
  | 'GOAL'
  | 'SET_AGENT'
  | 'SET_EXECUTION'
  | 'SET_ENVIRONMENT'
  | 'NOTIFICATION'

export const THREAD_COMMAND_TYPE_VALUES: readonly ThreadCommandType[] = [
  'USER_MESSAGE',
  'CUSTOM_MESSAGE',
  'GOAL',
  'SET_AGENT',
  'SET_EXECUTION',
  'SET_ENVIRONMENT',
  'NOTIFICATION',
]

/** Java：fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl */
export type ThreadExecutionControl =
  | 'RUNNABLE'
  | 'STOPPED'

export const THREAD_EXECUTION_CONTROL_VALUES: readonly ThreadExecutionControl[] = [
  'RUNNABLE',
  'STOPPED',
]

/** Java：fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus */
export type ThreadRuntimeStatus =
  | 'RUNNING'
  | 'WAITING'
  | 'IDLE'

export const THREAD_RUNTIME_STATUS_VALUES: readonly ThreadRuntimeStatus[] = [
  'RUNNING',
  'WAITING',
  'IDLE',
]

/** Java：fun.fengwk.kkstudio.platform.catalog.dto.ToolCatalogEntryDTO */
export interface ToolCatalogEntryDTO {
  name: string
  description: string
  environmentSupport?: EnvironmentSupport | null
}

/** Java：fun.fengwk.kkstudio.platform.harness.dto.ToolInvocationDTO */
export interface ToolInvocationDTO {
  id: string
  threadId: string
  turnStartEntryId: string
  assistantEntryId: string
  callIndex: number
  status: ToolInvocationStatus
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
  createdAt: number
  updatedAt: number
}

/** Java：fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus */
export type ToolInvocationStatus =
  | 'WAITING_APPROVAL'
  | 'WAITING_INPUT'
  | 'READY'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'UNKNOWN'

export const TOOL_INVOCATION_STATUS_VALUES: readonly ToolInvocationStatus[] = [
  'WAITING_APPROVAL',
  'WAITING_INPUT',
  'READY',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'UNKNOWN',
]

/** Java：fun.fengwk.kkstudio.project.dto.TransitionIssueRequestDTO */
export interface TransitionIssueRequestDTO {
  expectedVersion: string
  requestKey: string
  toState: string
}

/** Java：fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome */
export type TurnEndOutcome =
  | 'COMPLETED'
  | 'FAILED'
  | 'STOPPED'
  | 'CANCELLED'

export const TURN_END_OUTCOME_VALUES: readonly TurnEndOutcome[] = [
  'COMPLETED',
  'FAILED',
  'STOPPED',
  'CANCELLED',
]

/** Java：fun.fengwk.kkstudio.project.dto.UpdateIssueRequestDTO */
export interface UpdateIssueRequestDTO {
  expectedVersion: string
  title: string
  description?: string | null
}

/** Java：fun.fengwk.kkstudio.project.dto.UpdateProjectRequestDTO */
export interface UpdateProjectRequestDTO {
  expectedVersion: string
  title: string
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
  'DELETE /api/chat/chats/{chatId}': {
    path: { chatId: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/platform/agents/{name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/platform/environments/{environmentId}': {
    path: { environmentId: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/platform/mcp-servers/{name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/platform/models/{*name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/platform/plugins/{pluginId}/auth': {
    path: { pluginId: string }
    response: void
  }
  'DELETE /api/platform/providers/{name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/platform/skill-package-stagings/{stagingId}': {
    path: { stagingId: string }
    response: void
  }
  'DELETE /api/platform/skill-packages/{name}': {
    path: { name: string }
    query: { expectedVersion: string }
    response: void
  }
  'DELETE /api/platform/storage/uploads/{uploadId}': {
    path: { uploadId: string }
    response: void
  }
  'DELETE /api/project/issues/{issueId}': {
    path: { issueId: string }
    query: { expectedVersion: number }
    response: void
  }
  'DELETE /api/project/projects/{projectId}': {
    path: { projectId: string }
    query: { expectedVersion: number }
    response: void
  }
  'GET /api/canvas/canvases': {
    response: CanvasDocumentDTO[]
  }
  'GET /api/canvas/canvases/{canvasId}': {
    path: { canvasId: string }
    response: CanvasSnapshotDTO
  }
  'GET /api/canvas/canvases/{canvasId}/nodes/{nodeId}/function-run': {
    path: { canvasId: string; nodeId: string }
    response: CanvasFunctionRunDTO
  }
  'GET /api/canvas/functions': {
    response: CanvasFunctionDefinitionDTO[]
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
  'GET /api/harness/builtin/sessions/{sessionId}/entries/{entryId}/provider-request': {
    path: { entryId: string; sessionId: string }
    response: HarnessProviderRequestPreviewDTO
  }
  'GET /api/harness/interactions': {
    query: { cursor?: string; limit?: number; rootThreadId?: string }
    response: InteractionPageDTO
  }
  'GET /api/harness/sessions/{sessionId}/entries': {
    path: { sessionId: string }
    query: { cursor?: string; limit?: number }
    response: HarnessSessionEntryPageDTO
  }
  'GET /api/harness/sessions/{sessionId}/files': {
    path: { sessionId: string }
    query: { path?: string }
    response: HarnessSessionFileListDTO
  }
  'GET /api/harness/sessions/{sessionId}/files/content': {
    path: { sessionId: string }
    query: { columnOffset?: number; limit?: number; offset?: number; path: string }
    response: HarnessSessionFileContentDTO
  }
  'GET /api/harness/sessions/{sessionId}/files/download': {
    path: { sessionId: string }
    query: { path: string }
    response: Blob
    responseType: 'blob'
  }
  'GET /api/harness/sessions/{sessionId}/threads': {
    path: { sessionId: string }
    response: HarnessThreadSummaryDTO[]
  }
  'GET /api/harness/threads/{threadId}': {
    path: { threadId: string }
    query: { afterEntryId?: string }
    response: HarnessThreadSnapshotDTO
  }
  'GET /api/harness/threads/{threadId}/compaction-availability': {
    path: { threadId: string }
    response: HarnessManualCompactionDTO
  }
  'GET /api/harness/threads/{threadId}/tree': {
    path: { threadId: string }
    response: HarnessThreadTreeNodeDTO[]
  }
  'GET /api/ide/environments/{environmentId}/directories': {
    path: { environmentId: string }
    query: { path?: string }
    response: EnvironmentIdeDirectoryListingDTO
  }
  'GET /api/platform/agents': {
    response: AgentDTO[]
  }
  'GET /api/platform/config-sync': {
    response: ConfigSyncInventoryDTO
  }
  'GET /api/platform/daemon-uninstall-scripts/{operatingSystem}': {
    path: { operatingSystem: DaemonOperatingSystem }
    response: string
    responseType: 'text'
  }
  'GET /api/platform/environments': {
    response: EnvironmentDTO[]
  }
  'GET /api/platform/environments/{environmentId}': {
    path: { environmentId: string }
    response: EnvironmentDTO
  }
  'GET /api/platform/environments/{environmentId}/events': {
    path: { environmentId: string }
    response: EnvironmentEventDTO[]
  }
  'GET /api/platform/environments/{environmentId}/install-script': {
    path: { environmentId: string }
    query: { code: string }
    response: string
    responseType: 'text'
  }
  'GET /api/platform/environments/{environmentId}/runtime': {
    path: { environmentId: string }
    response: EnvironmentRuntimeStatusDTO
  }
  'GET /api/platform/mcp-servers': {
    response: McpServerSummaryDTO[]
  }
  'GET /api/platform/mcp-servers/{name}': {
    path: { name: string }
    response: McpServerDTO
  }
  'GET /api/platform/model-catalog': {
    query: { cursor?: string; limit?: number; query?: string }
    response: ModelCatalogPageDTO
  }
  'GET /api/platform/models': {
    response: ModelDTO[]
  }
  'GET /api/platform/plugins': {
    response: PluginDTO[]
  }
  'GET /api/platform/plugins/{pluginId}': {
    path: { pluginId: string }
    response: PluginDTO
  }
  'GET /api/platform/providers': {
    response: ProviderDTO[]
  }
  'GET /api/platform/providers/{name}/remote-models': {
    path: { name: string }
    response: string[]
  }
  'GET /api/platform/settings': {
    response: SystemSettingsDTO
  }
  'GET /api/platform/settings/schema': {
    response: SystemSettingsSchemaDTO
  }
  'GET /api/platform/skill-packages': {
    response: SkillPackageSummaryDTO[]
  }
  'GET /api/platform/skill-packages/{name}': {
    path: { name: string }
    response: SkillPackageDetailDTO
  }
  'GET /api/platform/skill-packages/{name}/environments': {
    path: { name: string }
    response: SkillEnvironmentSyncDTO[]
  }
  'GET /api/platform/skill-packages/{name}/files': {
    path: { name: string }
    response: SkillPackageFileDTO[]
  }
  'GET /api/platform/tools': {
    response: ToolCatalogEntryDTO[]
  }
  'GET /api/project/issues/{issueId}': {
    path: { issueId: string }
    query: { afterSequence?: number; limit?: number }
    response: IssueDetailDTO
  }
  'GET /api/project/issues/{issueId}/evidence': {
    path: { issueId: string }
    response: IssueEvidenceDTO[]
  }
  'GET /api/project/projects': {
    query: { includeArchived?: boolean }
    response: ProjectDTO[]
  }
  'GET /api/project/projects/{projectId}': {
    path: { projectId: string }
    response: ProjectDTO
  }
  'GET /api/project/projects/{projectId}/snapshot': {
    path: { projectId: string }
    response: ProjectSnapshotDTO
  }
  'POST /api/canvas/canvases': {
    body: CreateCanvasRequestDTO
    response: CanvasDocumentDTO
  }
  'POST /api/canvas/canvases/{canvasId}/commands': {
    path: { canvasId: string }
    body: ApplyCanvasCommandsRequestDTO
    response: CanvasPatchDTO
  }
  'POST /api/canvas/canvases/{canvasId}/nodes/{nodeId}/function-run': {
    path: { canvasId: string; nodeId: string }
    body: CanvasFunctionRunRequestDTO
    response: CanvasFunctionRunDTO
  }
  'POST /api/canvas/canvases/{canvasId}/nodes/{nodeId}/function-run/cancel': {
    path: { canvasId: string; nodeId: string }
    body: CanvasFunctionCancelRequestDTO
    response: CanvasFunctionRunDTO
  }
  'POST /api/canvas/canvases/{canvasId}/nodes/{nodeId}/function-run/resolve': {
    path: { canvasId: string; nodeId: string }
    body: CanvasFunctionUnknownResolutionDTO
    response: CanvasFunctionRunDTO
  }
  'POST /api/canvas/canvases/{canvasId}/resources/{resourceId}/download-url': {
    path: { canvasId: string; resourceId: string }
    response: CanvasPresignedUrlDTO
  }
  'POST /api/canvas/canvases/{canvasId}/resources/{resourceId}/preview-url': {
    path: { canvasId: string; resourceId: string }
    response: CanvasPresignedUrlDTO
  }
  'POST /api/chat/chats': {
    body: ChatCreateDTO
    response: ChatDTO
  }
  'POST /api/harness/builtin/threads/{threadId}/model-request-debug': {
    path: { threadId: string }
    body: HarnessModelRequestDebugRequestDTO
    response: HarnessModelRequestDebugDTO
  }
  'POST /api/harness/builtin/threads/{threadId}/stream-catchup': {
    path: { threadId: string }
    body: HarnessThreadStreamCatchupDTO
    response: void
  }
  'POST /api/harness/command-batches': {
    body: HarnessCommandBatchDTO
    response: HarnessAcceptedCommandsDTO
  }
  'POST /api/harness/threads/{threadId}/command-batches': {
    path: { threadId: string }
    body: HarnessThreadCommandBatchDTO
    response: HarnessAcceptedCommandsDTO
  }
  'POST /api/harness/threads/{threadId}/compact': {
    path: { threadId: string }
    body: HarnessThreadCompactDTO
    response: HarnessThreadCompactResultDTO
  }
  'POST /api/harness/threads/{threadId}/executions/{executionId}/abandon': {
    path: { executionId: string; threadId: string }
    body: HarnessExecutionAbandonDTO
    response: HarnessThreadSnapshotDTO
  }
  'POST /api/harness/threads/{threadId}/stop': {
    path: { threadId: string }
    body: HarnessThreadStopDTO
    response: HarnessThreadStopResultDTO
  }
  'POST /api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/input': {
    path: { threadId: string; toolInvocationId: string }
    body: HarnessToolInputDTO
    response: HarnessToolInputResultDTO
  }
  'POST /api/ide/environments/{environmentId}/views': {
    path: { environmentId: string }
    body: EnvironmentIdeOpenViewDTO
    response: EnvironmentIdeViewDTO
  }
  'POST /api/ide/environments/{environmentId}/views/{viewId}/apply-mobile-layout': {
    path: { environmentId: string; viewId: string }
    body: EnvironmentIdeViewBindingDTO
    response: void
  }
  'POST /api/ide/environments/{environmentId}/views/{viewId}/close': {
    path: { environmentId: string; viewId: string }
    body: EnvironmentIdeCloseViewDTO
    response: void
  }
  'POST /api/ide/environments/{environmentId}/views/{viewId}/focus-terminal': {
    path: { environmentId: string; viewId: string }
    body: EnvironmentIdeViewBindingDTO
    response: void
  }
  'POST /api/platform/agents': {
    body: AgentCreateDTO
    response: AgentDTO
  }
  'POST /api/platform/config-sync/check-import': {
    body: ConfigSyncImportCheckRequestDTO
    response: ConfigSyncImportCheckDTO
  }
  'POST /api/platform/config-sync/export': {
    body: ConfigSyncExportRequestDTO
    response: ConfigSyncExportDTO
  }
  'POST /api/platform/config-sync/import': {
    body: ConfigSyncImportRequestDTO
    response: ConfigSyncImportResultDTO
  }
  'POST /api/platform/environments': {
    body: EnvironmentCreateDTO
    response: EnvironmentDTO
  }
  'POST /api/platform/environments/{environmentId}/install-codes': {
    path: { environmentId: string }
    body: EnvironmentInstallCodeRequestDTO
    response: EnvironmentInstallCodeDTO
  }
  'POST /api/platform/environments/{environmentId}/resync-skills': {
    path: { environmentId: string }
    response: void
  }
  'POST /api/platform/environments/{environmentId}/rotate-token': {
    path: { environmentId: string }
    body: EnvironmentRotateTokenDTO
    response: EnvironmentDTO
  }
  'POST /api/platform/environments/{environmentId}/update-daemon': {
    path: { environmentId: string }
    response: EnvironmentUpdateStartDTO
  }
  'POST /api/platform/mcp-servers': {
    body: McpServerCreateDTO
    response: McpServerDTO
  }
  'POST /api/platform/mcp-servers/{name}/discover': {
    path: { name: string }
    body: McpServerDiscoverDTO
    response: McpServerDTO
  }
  'POST /api/platform/model-catalog/refresh': {
    response: ModelCatalogPageDTO
  }
  'POST /api/platform/models': {
    body: ModelCreateDTO
    response: ModelDTO
  }
  'POST /api/platform/plugins/{pluginId}/auth/complete': {
    path: { pluginId: string }
    body: PluginAuthCompleteRequestDTO
    response: PluginDTO
  }
  'POST /api/platform/plugins/{pluginId}/auth/prepare': {
    path: { pluginId: string }
    body: PluginAuthPrepareRequestDTO
    response: PluginAuthPrepareDTO
  }
  'POST /api/platform/plugins/{pluginId}/check-connection': {
    path: { pluginId: string }
    body: PluginConnectionCheckRequestDTO
    response: PluginConnectionCheckDTO
  }
  'POST /api/platform/providers': {
    body: ProviderCreateDTO
    response: ProviderDTO
  }
  'POST /api/platform/skill-package-stagings': {
    body: SkillPackageStagingCreateDTO
    response: SkillPackageStagingDTO
  }
  'POST /api/platform/skill-package-stagings/{stagingId}/publish': {
    path: { stagingId: string }
    body: SkillPackageStagingPublishDTO
    response: SkillPackageDetailDTO
  }
  'POST /api/platform/skill-packages': {
    body: SkillPackageCreateDTO
    response: SkillPackageDetailDTO
  }
  'POST /api/platform/skill-packages/{name}/archive-url': {
    path: { name: string }
    response: SkillArchiveDownloadDTO
  }
  'POST /api/platform/skill-packages/{name}/check': {
    path: { name: string }
    body: SkillPackageCheckDTO
    response: SkillPackageDetailDTO
  }
  'POST /api/platform/skill-packages/{name}/publish': {
    path: { name: string }
    body: SkillPackagePublishDTO
    response: SkillPackageDetailDTO
  }
  'POST /api/platform/storage/blobs/{blobId}/download-url': {
    path: { blobId: string }
    response: StoragePresignedUrlDTO
  }
  'POST /api/platform/storage/blobs/{blobId}/preview-url': {
    path: { blobId: string }
    response: StoragePresignedUrlDTO
  }
  'POST /api/platform/storage/uploads': {
    body: StorageUploadReserveRequestDTO
    response: StorageUploadDTO
  }
  'POST /api/platform/storage/uploads/{uploadId}/complete': {
    path: { uploadId: string }
    response: StorageUploadDTO
  }
  'POST /api/project/issues/{issueId}/activities': {
    path: { issueId: string }
    body: AppendIssueActivityRequestDTO
    response: IssueActivityDTO
  }
  'POST /api/project/issues/{issueId}/block': {
    path: { issueId: string }
    body: BlockIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/project/issues/{issueId}/evidence': {
    path: { issueId: string }
    body: AddIssueEvidenceRequestDTO
    response: IssueEvidenceDTO
  }
  'POST /api/project/issues/{issueId}/recover': {
    path: { issueId: string }
    body: RecoverIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/project/issues/{issueId}/reopen': {
    path: { issueId: string }
    body: ReopenIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/project/issues/{issueId}/reset-budget': {
    path: { issueId: string }
    body: ResetStageBudgetRequestDTO
    response: IssueStageBudgetDTO
  }
  'POST /api/project/issues/{issueId}/resume': {
    path: { issueId: string }
    body: ResumeIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/project/issues/{issueId}/stop': {
    path: { issueId: string }
    body: StopIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/project/issues/{issueId}/transition': {
    path: { issueId: string }
    body: TransitionIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/project/projects': {
    body: CreateProjectRequestDTO
    response: ProjectDTO
  }
  'POST /api/project/projects/{projectId}/archive': {
    path: { projectId: string }
    body: ProjectVersionRequestDTO
    response: ProjectDTO
  }
  'POST /api/project/projects/{projectId}/issues': {
    path: { projectId: string }
    body: CreateIssueRequestDTO
    response: IssueDTO
  }
  'POST /api/project/projects/{projectId}/unarchive': {
    path: { projectId: string }
    body: ProjectVersionRequestDTO
    response: ProjectDTO
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
  'PUT /api/harness/sessions/{sessionId}/name': {
    path: { sessionId: string }
    body: HarnessNameUpdateDTO
    response: HarnessSessionDTO
  }
  'PUT /api/harness/threads/{threadId}/name': {
    path: { threadId: string }
    body: HarnessNameUpdateDTO
    response: HarnessThreadDTO
  }
  'PUT /api/harness/threads/{threadId}/tool-invocations/{toolInvocationId}/approval': {
    path: { threadId: string; toolInvocationId: string }
    body: HarnessToolApprovalDTO
    response: ToolInvocationDTO
  }
  'PUT /api/harness/threads/{threadId}/yolo': {
    path: { threadId: string }
    body: HarnessThreadYoloUpdateDTO
    response: HarnessThreadDTO
  }
  'PUT /api/ide/environments/{environmentId}/views/{viewId}/directory': {
    path: { environmentId: string; viewId: string }
    body: EnvironmentIdeChangeDirectoryDTO
    response: EnvironmentIdeViewDTO
  }
  'PUT /api/platform/agents/{name}': {
    path: { name: string }
    body: AgentUpdateDTO
    response: AgentDTO
  }
  'PUT /api/platform/environments/{environmentId}/install-settings': {
    path: { environmentId: string }
    body: EnvironmentInstallConfigUpdateDTO
    response: EnvironmentDTO
  }
  'PUT /api/platform/mcp-servers/{name}': {
    path: { name: string }
    body: McpServerUpdateDTO
    response: McpServerDTO
  }
  'PUT /api/platform/models/{*name}': {
    path: { name: string }
    body: ModelUpdateDTO
    response: ModelDTO
  }
  'PUT /api/platform/plugins/{pluginId}/configuration': {
    path: { pluginId: string }
    body: PluginConfigurationUpdateRequestDTO
    response: PluginDTO
  }
  'PUT /api/platform/providers/{name}': {
    path: { name: string }
    body: ProviderUpdateDTO
    response: ProviderDTO
  }
  'PUT /api/platform/settings': {
    body: SystemSettingsUpdateDTO
    response: SystemSettingsDTO
  }
  'PUT /api/platform/skill-packages/{name}': {
    path: { name: string }
    body: SkillPackageEditDTO
    response: SkillPackageDetailDTO
  }
  'PUT /api/project/issues/{issueId}': {
    path: { issueId: string }
    body: UpdateIssueRequestDTO
    response: IssueDTO
  }
  'PUT /api/project/projects/{projectId}': {
    path: { projectId: string }
    body: UpdateProjectRequestDTO
    response: ProjectDTO
  }
  'PUT /api/project/projects/{projectId}/workflow': {
    path: { projectId: string }
    body: UpdateProjectWorkflowRequestDTO
    response: ProjectDTO
  }
  'PUT /api/project/projects/{projectId}/yolo': {
    path: { projectId: string }
    body: UpdateProjectYoloRequestDTO
    response: ProjectDTO
  }
}

export const API_RESPONSE_TYPES: Partial<Record<keyof ApiRoutes, 'text' | 'blob'>> = {
  'GET /api/harness/sessions/{sessionId}/files/download': 'blob',
  'GET /api/platform/daemon-uninstall-scripts/{operatingSystem}': 'text',
  'GET /api/platform/environments/{environmentId}/install-script': 'text',
}
