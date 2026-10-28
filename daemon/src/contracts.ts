import type { Readable, Writable } from 'node:stream'

import type { DataBind, IdeLease } from './protocol.js'

/** Runtime boundaries shared by capability, native process and transport implementations. */
export interface DaemonConfig {
  configPath: string
  dataDir: string
  tokenPath: string
  studioUrl: string
  gatewayUri: string
  bashExecutable: string
  note: string | null
  lsp: Record<string, LspServerConfig>
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

/**
 * Current-user OS proxy lookup.
 *
 * `null` means the operating system explicitly selected a direct connection. A lookup failure,
 * an unsupported proxy, or an unavailable resolver is an error and is never reported as direct.
 */
export interface SystemProxyResolver {
  resolve(uri: string, signal: AbortSignal): Promise<string | null>
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

export interface CapabilityContext {
  config: DaemonConfig
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
  | 'DETACH_VIEW'
  | 'LIST_VIEWS'

export type IdeOpenMode = 'create' | 'resume'
export type IdeViewState = 'OPEN' | 'CLOSING'

/** 视图的 owner 身份与本机资源快照；这类身份只随命令/列表回执返回，不进入浏览器持久存储。 */
export interface IdeViewSummary {
  viewId: string
  documentId: string
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
  documentId?: string
  eventNodeId?: string
  connectionId?: string
  claimId?: string
  bindingEpoch?: string
  mode?: IdeOpenMode
  directory?: string
  discard?: boolean
}

export interface IdeReply {
  ok: boolean
  viewId?: string
  documentId?: string
  eventNodeId?: string
  connectionId?: string
  claimId?: string
  bindingEpoch?: string
  directory?: string
  workspacePath?: string
  views?: IdeViewSummary[]
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
  /** 已移除 hop-by-hop/凭据/连接级头的上游请求头；适配器仍须注入私有 cookie。 */
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
  /** 只有不存在 OPEN/CLOSING/创建预留与活动 mux 流时才允许进入更新。 */
  canStartUpdate(): boolean
  /** 更新准入后停止闲置 code-server，并确认受管进程范围已收敛。 */
  stopForUpdate(signal: AbortSignal): Promise<void>
  /** 当前视图快照；用于数据适配器和诊断，不续租。 */
  getViewState(viewId: string): IdeViewSummary | null
  activeStreamCount(): number
  /** 数据适配器唯一允许的上游解析入口。 */
  resolveView(viewId: string, bindingEpoch: string, request: IdeStreamRequest): Promise<IdeStreamUpstream>
  /** 在显式恢复路径中把私有恢复数据交还普通文本编辑器；不写用户盘文件。 */
  restoreBackup(viewId: string, signal: AbortSignal): Promise<{ restored: number; skipped: number }>
  close(): Promise<void>
}

/** 受控 Studio 数据连接绑定处理器；由 data transport 片实现，Runtime 只做严格解码与代际内派发。 */
export interface DataBindHandler {
  bind(binding: DataBind, signal: AbortSignal): Promise<void> | void
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
    contents: [{ type: 'text', text: `Error: ${message}` }],
  }
}
