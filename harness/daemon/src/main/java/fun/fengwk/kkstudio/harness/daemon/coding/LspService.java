package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Daemon 进程内唯一的 LSP 门面：配置发现、客户端复用与三类查询。
 *
 * <p>客户端归 Daemon 所有：没有在途请求且闲置 {@link #DEFAULT_IDLE_TIMEOUT} 后回收，Thread 结束不关闭共享实例， {@link #close()}
 * 才全量关闭（shutdown、exit、有界等待、必要时强制终止整棵进程树）。外部语言服务器必须预先配置，这里不做安装。
 *
 * <p>调用方关心的两个接入点：
 *
 * <ul>
 *   <li>{@link #support(Path)}：read header 的只读发现，只匹配配置并探测可执行程序，不启动任何服务器。
 *   <li>{@link #fileChanged(Path)}：write/edit 提交后的尽力同步；它只刷新已打开文档，任何失败都不会影响已经提交的修改。
 * </ul>
 */
public final class LspService implements AutoCloseable {

  /** 无在途请求且闲置多久后回收客户端。 */
  public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(5);

  private static final Duration DEFAULT_SHUTDOWN_GRACE = Duration.ofSeconds(1);
  private static final Duration DEFAULT_INITIALIZE_TIMEOUT = Duration.ofSeconds(60);

  private final LspDiscovery discovery;
  private final LspClientPool pool;

  private LspService(
      LspDiscovery discovery,
      ExecutorService dispatch,
      ScheduledExecutorService scheduler,
      Duration idleTimeout,
      Duration shutdownGrace,
      Duration initializeTimeout) {
    this.discovery = Objects.requireNonNull(discovery, "discovery");
    this.pool =
        new LspClientPool(
            discovery,
            Objects.requireNonNull(scheduler, "scheduler"),
            Objects.requireNonNull(dispatch, "dispatch"),
            idleTimeout,
            shutdownGrace,
            initializeTimeout);
  }

  /**
   * Daemon 装配：真实进程、默认空闲回收与关闭宽限。
   *
   * @param dispatch LSP 客户端 stdio 的阻塞 I/O 执行器，由 Daemon 拥有
   * @param scheduler 空闲回收计时器，由 Daemon 拥有
   */
  public static LspService create(
      LspDiscovery discovery, ExecutorService dispatch, ScheduledExecutorService scheduler) {
    return new LspService(
        discovery,
        dispatch,
        scheduler,
        DEFAULT_IDLE_TIMEOUT,
        DEFAULT_SHUTDOWN_GRACE,
        DEFAULT_INITIALIZE_TIMEOUT);
  }

  /** 测试装配：可收紧回收与关闭时间参数，其余与生产一致。 */
  static LspService create(
      LspDiscovery discovery,
      ExecutorService dispatch,
      ScheduledExecutorService scheduler,
      Duration idleTimeout,
      Duration shutdownGrace,
      Duration initializeTimeout) {
    return new LspService(
        discovery, dispatch, scheduler, idleTimeout, shutdownGrace, initializeTimeout);
  }

  /**
   * read header 的 LSP 状态：只读发现文件对应的服务器配置与其可执行程序，不启动服务器。
   *
   * <p>这是 read 侧唯一的接入点；{@link LspSupport} 区分未配置、已配置但未安装与可用三态。
   */
  public LspSupport support(Path file) {
    return discovery.support(file);
  }

  /** 解析符号定义，返回 {@code 路径:行:列} 行；{@code jdt://} 虚拟位置原样保留。 */
  public String gotoDefinition(Path file, int line, int character, Duration timeout)
      throws Exception {
    LspClientPool.Client entry = pool.lease(file);
    try {
      return String.join("\n", entry.client().definition(file, line, character, timeout));
    } finally {
      pool.release(entry);
    }
  }

  /** 搜索 workspace 符号，返回 {@code 名称 (类型) - 位置} 行，最多 {@code limit} 条。 */
  public String workspaceSymbols(Path file, String query, int limit, Duration timeout)
      throws Exception {
    LspClientPool.Client entry = pool.lease(file);
    try {
      return String.join("\n", entry.client().workspaceSymbols(query, limit, timeout));
    } finally {
      pool.release(entry);
    }
  }

  /**
   * 反编译 Java class：{@code jdt://} 目标走 jdtls 的 class contents，绝对本地 class 路径或 {@code file:} URI 交给
   * jdtls 的 decompile 命令。返回源码原样透传。
   */
  public String javaDecompile(Path file, String target, Duration timeout) throws Exception {
    LspClientPool.Client entry = pool.lease(file);
    try {
      return entry.client().javaDecompile(target, timeout);
    } finally {
      pool.release(entry);
    }
  }

  /**
   * write/edit 提交后的尽力同步：已打开文档会收到新的全量内容，未打开文档留给下一次查询时同步。
   *
   * <p>本方法不抛异常：展示或通知失败绝不能把已经提交的修改报成失败。
   */
  public void fileChanged(Path file) {
    try {
      pool.client(file).ifPresent(client -> client.refresh(file));
    } catch (RuntimeException ignored) {
      // 尽力而为：同步失败不影响调用方已经完成的写入。
    }
  }

  /** 关闭全部客户端；执行资源属于 Daemon，本方法不关闭它们。幂等。 */
  @Override
  public void close() {
    pool.close();
  }

  /** 只读探测当前存活的客户端数量，用于装配与回收断言。 */
  int activeClientCount() {
    return pool.activeCount();
  }
}
