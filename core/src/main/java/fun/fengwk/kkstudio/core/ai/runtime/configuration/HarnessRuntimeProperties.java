package fun.fengwk.kkstudio.core.ai.runtime.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/** Harness Runtime 部署配置：进程内 worker 开关、资源边界、Redis realtime overlay 与 processor/dispatcher 参数。 */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.runtime")
public class HarnessRuntimeProperties {

  /** 是否启动本进程的 Work dispatcher / listener；关闭时控制/查询平面仍然可用。 */
  private boolean workersEnabled = true;

  /** Environment 沙箱根目录：绝对路径标准化后作为工具 workdir 的边界；未配置时取进程当前目录 （user.dir）。 */
  private Path environmentRoot = Path.of(System.getProperty("user.dir", "."));

  /** 默认工作目录：绝对路径直接使用，相对路径基于 environmentRoot 解析，必须位于 environmentRoot 之内。 */
  private Path workdir = Path.of(".");

  /** 本地 {@code ResourceStore} 的单对象字节预算（默认 16 MiB）。 */
  private int resourceMaxBytes = 16 * 1024 * 1024;

  /** Redis realtime overlay 的 stream key 前缀。 */
  private String redisPrefix = "kk-studio:harness:realtime:";

  /** 每个 Thread Redis Stream 的 exact maxlen。 */
  private long redisMaxLength = 5_000L;

  /** processor 共用的 claim lease 时长。 */
  private Duration processorLeaseDuration = Duration.ofSeconds(30);

  /** processor 本地 heartbeat 间隔；必须严格小于 {@link #processorLeaseDuration}。 */
  private Duration processorHeartbeatInterval = Duration.ofSeconds(10);

  /** ThreadProcessor 单次 claim 的有界步骤上限。 */
  private int threadStepLimit = 16;

  /** ThreadProcessor 的 Resolver 异常 / null、heartbeat 失败与 step limit 共用的重排延迟。 */
  private Duration threadResolveFailureDelay = Duration.ofSeconds(1);

  /** ModelProcessor safe checkpoint 的节流写入间隔。 */
  private Duration modelCheckpointFlushInterval = Duration.ofMillis(100);

  /** ModelProcessor 在 Gateway start 抛异常（肯定未接受）时的 reschedule 延迟。 */
  private Duration modelDispatchBusyFallbackDelay = Duration.ofSeconds(1);

  /** ToolProcessor 在 preflight 抛异常 / 返回 null（确定无副作用）时的 reschedule 延迟。 */
  private Duration toolPreflightFailureDelay = Duration.ofSeconds(1);

  /** ToolProcessor 在 Gateway start 抛异常（肯定未接受）时的 reschedule 延迟。 */
  private Duration toolDispatchBusyFallbackDelay = Duration.ofSeconds(1);

  /** HarnessWorkDispatcher 对 THREAD / MODEL / TOOL claim 共用的 lease 时长。 */
  private Duration dispatcherLeaseDuration = Duration.ofSeconds(30);

  /** HarnessWorkDispatcher fixed-delay periodic poll 间隔。 */
  private Duration dispatcherPollInterval = Duration.ofSeconds(1);

  /** worker executor 拒绝 handoff task 后仍 owned claim 的归还重排延迟。 */
  private Duration dispatcherRejectionDelay = Duration.ofSeconds(1);

  /** dispatcher 本地 queued/running 的 processor handoff task 总数上限。 */
  private int dispatcherMaxDispatchTasks = 64;

  /** dispatcher worker executor 的并发度。 */
  private int dispatcherWorkerConcurrency = 16;

  /** dispatcher worker executor 的有界队列容量。 */
  private int dispatcherWorkerQueueCapacity = 64;

  /** 自动对话压缩开关（默认开启）。 */
  private boolean compactionEnabled = true;

  /**
   * 压缩 Model 调用的预留 token 预算：summary prompt + output（FULL/HISTORY 输出上限 floor(0.8*r)，TURN_PREFIX
   * floor(0.5*r)）。
   */
  private int compactionReserveTokens = 16_384;

  /** 压缩后保留的最近上下文 token 上限（与 floor(contextWindow * 0.5) 取小为有效保留量）。 */
  private int compactionMaxRecentTokens = 20_000;

  /** task 委派最大深度；普通根 Thread 的 depth 为 1。 */
  private int subagentMaxDepth = 2;

  /** 单个父 Thread 同时运行的直接子 Agent 上限。 */
  private int subagentMaxConcurrency = 10;

  /** 整棵委派树的同时运行上限；null 表示不额外限制。 */
  private Integer subagentMaxTotalConcurrency;

  /** 子 Agent 无 durable 进展的超时；零表示关闭，active Tool 执行期间不计时。 */
  private Duration subagentIdleTimeout = Duration.ZERO;

  /** task.maxTurns 未指定时的默认软回合预算。 */
  private int subagentMaxTurns = 50;

  /** task Tool 观察子 Thread durable 状态的轮询间隔。 */
  private Duration subagentPollInterval = Duration.ofMillis(100);

  public Path resolvedEnvironmentRoot() {
    if (environmentRoot == null) {
      throw new IllegalArgumentException(
          "kk-studio.harness.runtime.environment-root must not be null");
    }
    return environmentRoot.toAbsolutePath().normalize();
  }

  public Path resolvedWorkdir() {
    if (workdir == null) {
      throw new IllegalArgumentException("kk-studio.harness.runtime.workdir must not be null");
    }
    Path root = resolvedEnvironmentRoot();
    Path resolved = workdir.isAbsolute() ? workdir.normalize() : root.resolve(workdir).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException(
          "kk-studio.harness.runtime.workdir must stay within environment-root");
    }
    return resolved;
  }
}
