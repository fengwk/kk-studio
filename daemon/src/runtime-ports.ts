import type {
  CapabilityExecutor,
  DaemonConfig,
  IdeLeaseHandler,
  IdeService,
  ProcessSupervisor,
  ResourceUploader,
} from './contracts.js'
import type { InvocationJournal } from './journal.js'
import type { EnvironmentInfo } from './generated/control_pb.js'
import type { UpdateByteProgress } from './runtime-status.js'
import type { ControlTransport } from './transport/index.js'
import type { PresignedPutSender } from './transport/resources.js'

/** 受控临时资源清扫端口；具体 workspace 存储由 coding 片提供。 */
export interface TemporaryResourceSweeper {
  sweep(ttlSeconds: number): Promise<void>
}

/** 释放已交接更新的许可。 */
export type ReleasePermit = () => Promise<void>

/** 安装器交接后仍可观察到失败；返回停止观察的函数。 */
export type UpdateFailureWatcher = (report: (message: string) => void) => () => void

export interface UpdateOutcomeLike {
  phase: 'PREPARED' | 'FAILED'
  message?: string | null
  /** 已接受交接的许可释放步骤；由 Runtime 在回执入缓存并完成一次出站尝试后调用，并随重放/重连重发重试。 */
  releasePermit?: ReleasePermit
  /** 在交接后仍运行的 Daemon 可观察到安装器失败。 */
  watchFailure?: UpdateFailureWatcher
}

/**
 * 更新器适配边界。
 *
 * <p>可选的字节进度端口只在当前被准入的 operation 上接收快照；官方安装器不提供字节进度。
 */
export type UpdateProgressReporter = (progress: UpdateByteProgress) => void

/** 受管更新端口；由 src/installer 提供，结果只暴露运行时编码 UPDATE_RESULT 所需的结构。 */
export interface UpdatePort {
  handleUpdate(
    command: Record<string, unknown>,
    config: DaemonConfig,
    signal: AbortSignal,
    reportProgress?: UpdateProgressReporter,
  ): Promise<UpdateOutcomeLike>
  getProgress?(): UpdateByteProgress | null
}

export interface RuntimeOptions {
  config: DaemonConfig
  transport: ControlTransport
  /** capability id → 本地执行器；由各 capability 片合并后注入。 */
  capabilities: Record<string, CapabilityExecutor>
  /** 子进程监督端口；由 native 片提供。 */
  processes: ProcessSupervisor
  /** READY 上报的宿主 metadata；缺省时按当前进程检测。 */
  environment?: EnvironmentInfo
  daemonVersion?: string
  journal?: InvocationJournal
  /** 自定义资源上传端口；缺省时使用内置控制面客户端。 */
  resources?: ResourceUploader
  /** 内置资源客户端的预签名 PUT 实现；缺省使用 fetch。 */
  putObject?: PresignedPutSender
  update?: UpdatePort
  ide?: IdeService
  /** IDE 使用证明处理器；未配置时 IDE_LEASE 必须显式报错。 */
  ideLease?: IdeLeaseHandler
  temporaryResources?: TemporaryResourceSweeper
  monotonicNow?: () => number
  heartbeatIntervalMillis?: number
  initialReconnectDelayMillis?: number
  maxReconnectDelayMillis?: number
  maxConcurrentInvocations?: number
  ideCommandTimeoutMillis?: number
}
