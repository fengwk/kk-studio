import type { Readable, Writable } from 'node:stream'

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

export interface ProcessSupervisor {
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

export type IdeAction = 'OPEN_VIEW' | 'CHANGE_DIRECTORY' | 'FOCUS_TERMINAL' | 'CLOSE_VIEW' | 'OPEN_STREAM'

export interface IdeCommand {
  action: IdeAction
  viewId?: string
  directory?: string
  ticket?: string
  streamUrl?: string
  mode?: 'HTTP' | 'WS'
  method?: string
  path?: string
  headers?: [string, string][]
}

export interface IdeReply {
  ok: boolean
  viewId?: string
  directory?: string
  workspacePath?: string
  message?: string
}

export interface IdeService {
  handle(command: IdeCommand, signal: AbortSignal): Promise<IdeReply>
  close(): Promise<void>
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
