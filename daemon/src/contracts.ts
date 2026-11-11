import type { Readable, Writable } from 'node:stream'

import type { IdeLease } from './generated/control_pb.js'

/** Runtime boundaries shared by capability, native process and transport implementations. */

/** 卡片中可热编辑的完整 Daemon 配置；每次下发/缓存都是完整快照。 */
export interface DaemonConfiguration {
  studioUrl: string
  /** 原始配置值；null 表示使用宿主 PATH 中的 bash。 */
  bashExecutable: string | null
  lsp: Record<string, LspServerConfig>
  proxyUrl: string | null
}

/**
 * 本进程当前已应用配置的执行上下文。
 *
 * <p>安装目录、凭证路径与 distribution 目录是启动即固定的事实；studioUrl/proxyUrl/bash/lsp 随控制通道热更新，
 * 每次切换发布一个新的不可变对象。environmentId 是本进程唯一绑定的卡片身份。
 */
export interface DaemonConfig {
  environmentId: string
  configVersion: bigint
  configPath: string
  dataDir: string
  tokenPath: string
  studioUrl: string
  gatewayUri: string
  /** 已解析的 bash 可执行文件；未解析成功时为 null，候选配置不会发布。 */
  bashExecutable: string | null
  /** 配置中的原始 bash 值，用于持久化与 wire 快照。 */
  bashExecutableSetting: string | null
  lsp: Record<string, LspServerConfig>
  proxyUrl: string | null
  distributionDir: string
}

export interface LspServerConfig {
  command: string[]
  extensions: string[]
  rootMarkers: string[]
  firstMatchMarkers: string[]
}

export interface ProcessSpec {
  executable: string
  args: string[]
  cwd: string
  env?: NodeJS.ProcessEnv
  signal?: AbortSignal
}

export interface ProcessExit {
  exitCode: number | null
  signal: string | null
}

export interface ManagedProcess {
  pid: number
  stdin: Writable
  stdout: Readable
  stderr: Readable
  exit: Promise<ProcessExit>
  terminate(graceMillis?: number): Promise<void>
}

/**
 * 启动失败且失败的执行范围未能被证明已收敛。
 *
 * <p>这是 {@link ProcessSupervisor.start} 唯一表示「可能仍有进程存活」的拒绝；调用方不得据此确认退出。
 */
export class ProcessScopeNotConvergedError extends Error {}

export interface ProcessSupervisor {
  /**
   * 启动一个受管执行范围。
   *
   * <p>拒绝分两类：{@link ProcessScopeNotConvergedError} 表示失败范围未被证明已收敛；其它拒绝保证本次尝试没有遗留进程
   * （尚未 spawn，或已 spawn 的范围已被终止并证明收敛）。
   */
  start(spec: ProcessSpec): Promise<ManagedProcess>
  /** Adds IDE PTY sessions that intentionally establish their own POSIX session. */
  trackDescendant?(rootPid: number, childPid: number): Promise<void>
  close(): Promise<void>
}

export type ResultContent =
  | { type: 'text'; text: string }
  | { type: 'json'; json: unknown }
  | { type: 'binary'; bytes: Uint8Array; mediaType: string; name?: string | null }
  | { type: 'resource'; uploadId: string; mediaType: string; name: string | null; size: number; sha256: string; preview?: string }

export interface CapabilityResult {
  callId: string
  error: boolean
  details: Record<string, unknown>
  contents: ResultContent[]
}

/** File snapshot approved by the user; not part of the model-visible arguments. */
export interface ExpectedFile {
  path: string
  sha256: string
}

export interface CapabilityCall {
  invocationId: string
  capabilityId: string
  arguments: Record<string, unknown>
  timeoutMillis: number
  expectedFile?: ExpectedFile
}

export interface ResourceUploader {
  upload(invocationId: string, content: Extract<ResultContent, { type: 'binary' }>, signal: AbortSignal): Promise<Extract<ResultContent, { type: 'resource' }>>
}

/** 资源上传的安全错误分类：只含固定类别，不含 URL、headers 或令牌。 */
export type ResourceUploadErrorCategory =
  | 'storage-rejected'
  | 'control-unavailable'
  | 'ticket-failed'
  | 'cancelled'
  | 'cleanup-failed'
  | 'boundary-violation'
  | 'unknown'

/** 资源上传阶段：失败发生在哪一层。 */
export type ResourceUploadStage =
  | 'request'
  | 'put'
  | 'commit'
  | 'cleanup'
  | 'internal'

/**
 * 资源上传边界的明确失败类型：区分「交付失败但上传范围已退场」与「退出仍未知」。
 *
 * <p>契约（PLAN 13 节）：
 * - 普通失败（resolve 之外的拒绝）在完成实际网络/控制面释放后必须 `cleanupConfirmed: true`；
 * - 释放本身失败或未能证明完成时 `cleanupConfirmed: false`，调用方不得确认退出；
 * - 注入的 uploader 违反契约（如拒绝且未标记）时由边界转换为 `cleanupConfirmed: false` 的保守事实，
 *   并记录边界违约诊断，绝不自动认定释放成功。
 *
 * <p>message 是安全文案：绝不能携带预签名 URL、signed headers 或令牌；cause 保留原始错误。
 */
export class ResourceUploadError extends Error {
  readonly stage: ResourceUploadStage
  readonly category: ResourceUploadErrorCategory
  readonly cleanupConfirmed: boolean

  constructor(
    stage: ResourceUploadStage,
    category: ResourceUploadErrorCategory,
    cleanupConfirmed: boolean,
    options?: { cause?: unknown; message?: string },
  ) {
    super(options?.message ?? `resource upload failed during ${stage} (${category})`, options)
    this.name = 'ResourceUploadError'
    this.stage = stage
    this.category = category
    this.cleanupConfirmed = cleanupConfirmed
  }
}

/** 按类型判定上传失败是否已确认释放；不从 message 或 cause 文案推断。 */
export function isResourceUploadError(error: unknown): error is ResourceUploadError {
  return error instanceof ResourceUploadError
}

export interface CapabilityContext {
  config: DaemonConfig
  /** INVOKE 中冻结的单次工具输出上限。 */
  maxOutputBytes: number
  signal: AbortSignal
  processes: ProcessSupervisor
  resources: ResourceUploader
  progress(result: CapabilityResult): Promise<void>
  /**
   * 正向退出证据：仅当本次调用实际管理的执行范围已经结束时调用。
   *
   * <p>这不是结果终态。普通 error、timeout、abort 或连接关闭都不能代替它。Runtime 仍要等 executor
   * 返回或抛出，以及本次收尾结束后，才发送 EXECUTION_EXITED。
   */
  confirmExecutionExit(): void
}

export type CapabilityExecutor = (call: CapabilityCall, context: CapabilityContext) => Promise<CapabilityResult>

export interface CapabilityGroup {
  capabilities: Record<string, CapabilityExecutor>
  close(): Promise<void>
}

export type IdeAction =
  | 'OPEN_VIEW'
  | 'CHANGE_DIRECTORY'
  | 'FOCUS_TERMINAL'
  | 'CLOSE_VIEW'
  | 'LIST_VIEWS'
  | 'LIST_DIRECTORIES'
  | 'COLLAPSE_MOBILE'

export type IdeOpenMode = 'create' | 'resume'
export type IdeViewState = 'OPEN' | 'CLOSING'

/**
 * 视图的 owner 身份与本机资源快照。
 *
 * <p>`daemonInstanceId`、`bindingEpoch`、`viewId` 与目录可作为恢复身份持久化；
 * entryUrl、bootstrap token、cookie 与连接证明不得进入浏览器持久存储。
 */
export interface IdeViewSummary {
  viewId: string
  browserSessionId: string
  eventNodeId: string
  connectionId: string
  claimId: string
  bindingEpoch: string
  directory: string
  workspacePath: string
  state: IdeViewState
}

export interface IdeCommand {
  action: IdeAction
  viewId?: string
  browserSessionId?: string
  eventNodeId?: string
  connectionId?: string
  claimId?: string
  bindingEpoch?: string
  mode?: IdeOpenMode
  directory?: string
  discard?: boolean
  expectedBindingEpoch?: string
  expectedDaemonInstanceId?: string
}

export interface IdeReply {
  ok: boolean
  viewId?: string
  browserSessionId?: string
  eventNodeId?: string
  connectionId?: string
  claimId?: string
  bindingEpoch?: string
  directory?: string
  workspacePath?: string
  views?: IdeViewSummary[]
  revision?: string
  directoryListing?: {
    path: string
    parentPath?: string
    children: { name: string; path: string }[]
    truncated: boolean
  }
  code?: string
  message?: string
}

/** 数据面 OPEN 的受限视图请求；具体物理上游仍由 service 解析，适配器不得接受任意目标。 */
export interface IdeStreamRequest {
  mode: 'HTTP' | 'WS'
  method: string
  path: string
  headers: [string, string][]
}

export interface IdeStreamUpstream {
  host: string
  port: number
  path: string
  /** 剔除 hop-by-hop/凭据/连接级头后的上游请求头；适配器仍须注入私有 cookie。 */
  headers: [string, string][]
  /** Daemon 注入的私有上游 cookie，永不来自浏览器。 */
  cookie?: string
  /** WebSocket 子协议；由 service 从过滤后的请求头中解析。 */
  protocols?: string[]
  /** service 关闭视图或停机时中止该逻辑流。 */
  signal: AbortSignal
  /** 由数据适配器在物理流真正结束后调用一次；重复调用无副作用。 */
  release(): void
}

export interface IdeService extends IdeLeaseHandler {
  handle(command: IdeCommand, signal: AbortSignal): Promise<IdeReply>
  /** 只有不存在 OPEN/CLOSING/创建预留与活动流时才允许进入更新。 */
  canStartUpdate(): boolean
  /** 更新准入后停止闲置 code-server，并确认受管进程范围已收敛。 */
  stopForUpdate(signal: AbortSignal): Promise<void>
  /** 当前视图快照，用于诊断，不续租。数据通道使用 resolveView。 */
  getViewState(viewId: string): IdeViewSummary | null
  activeStreamCount(): number
  /** 数据适配器唯一允许的上游解析入口。 */
  resolveView(viewId: string, bindingEpoch: string, request: IdeStreamRequest): Promise<IdeStreamUpstream>
  /** 在显式恢复路径中把私有恢复数据交还普通文本编辑器；不写用户盘文件。 */
  restoreBackup(viewId: string, signal: AbortSignal): Promise<{ restored: number; skipped: number }>
  close(): Promise<void>
}

/**
 * IDE 使用证明处理器；由 IDE 视图权威片实现。
 *
 * <p>Runtime 负责只发送当前控制代际内未过期的轮次并保证一轮最多派发一次；处理器负责核对 round 内的绑定是否是当前视图，
 * 证明本身不能创建或续期视图。第二个参数是本轮心跳发出时的本机单调时间，而不是证明到达时间。
 */
export interface IdeLeaseHandler {
  hasActiveViews(): boolean
  apply(lease: IdeLease, roundStartedAt: number): Promise<void> | void
  getViewState(viewId: string): IdeViewSummary | null
}

export function textResult(callId: string, text: string, details: Record<string, unknown> = {}): CapabilityResult {
  return { callId, error: false, details, contents: [{ type: 'text', text }] }
}

export function errorResult(callId: string, message: string, code?: string): CapabilityResult {
  return {
    callId,
    error: true,
    details: code ? { code } : {},
    contents: [{ type: 'text', text: code === 'OUTPUT_TOO_LARGE' ? message : `Error: ${message}` }],
  }
}
