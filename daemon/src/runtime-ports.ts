import type {
  CapabilityExecutor,
  DaemonConfig,
  DataBindHandler,
  IdeLeaseHandler,
  IdeService,
  ProcessSupervisor,
  ResourceUploader,
} from './contracts.js'
import type { InvocationJournal } from './journal.js'
import type { EnvironmentInfo } from './protocol.js'
import type { UpdateByteProgress } from './runtime-status.js'
import type { ControlTransport } from './transport/control.js'
import type { PresignedPutSender } from './transport/resources.js'

/** 受控临时资源清扫端口；具体 workspace 存储由 coding 片提供。 */
export interface TemporaryResourceSweeper {
  sweep(ttlSeconds: number): Promise<void>
}

/** 受管更新端口；由 {@code src/update} 提供。UPDATE 结果只暴露运行时用于编码 UPDATE_RESULT 的窄结构。 */
export interface UpdateOutcomeLike {
  phase: string
  message?: string | null
  /** 已接受交接的许可释放步骤；由 Runtime 在回执入缓存并完成一次出站尝试后调用，并随重放/重连重发重试。 */
  releasePermit?: () => Promise<void>
}

/**
 * 更新器适配边界。
 *
 * <p>{@link handleUpdate} 的 reportProgress 接收一次下载进度对象，与 updater 的实际入口一致；可选 {@link getProgress}
 * 供没有事件回调的适配器拉取当前字节数。Runtime 只在当前被准入的 operation 上接受进度，并在心跳/重连时把最新快照带给
 * Server。
 */
export type UpdateProgressReporter = (progress: UpdateByteProgress) => void

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
  capabilityCatalogVersion?: string
  daemonVersion?: string
  journal?: InvocationJournal
  /** 自定义资源上传端口；缺省时使用内置控制面客户端。 */
  resources?: ResourceUploader
  /** 内置资源客户端的预签名 PUT 实现；缺省使用 fetch。 */
  putObject?: PresignedPutSender
  update?: UpdatePort
  ide?: IdeService
  /** 受控数据连接绑定处理器；未配置时 DATA_BIND 必须显式报错，不能静默成功。 */
  dataBind?: DataBindHandler
  /** IDE 使用证明处理器；未配置时 IDE_LEASE 必须显式报错。 */
  ideLease?: IdeLeaseHandler
  temporaryResources?: TemporaryResourceSweeper
  heartbeatIntervalMillis?: number
  initialReconnectDelayMillis?: number
  maxReconnectDelayMillis?: number
  maxConcurrentInvocations?: number
  ideCommandTimeoutMillis?: number
  /** IDE 租约轮次的新鲜度判定时钟；缺省使用单调的 performance.now。 */
  monotonicNow?: () => number
}
