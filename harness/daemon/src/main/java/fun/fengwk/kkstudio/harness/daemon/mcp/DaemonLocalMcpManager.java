package fun.fengwk.kkstudio.harness.daemon.mcp;

import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpClientFactory;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpException;
import fun.fengwk.kkstudio.harness.mcp.StdioMcpConfig;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Daemon 本地 MCP 客户端生命周期管理器：
 *
 * <ul>
 *   <li>按 {@code (serverId, configVersion)} 共享唯一的 lazy MCP client / process；
 *   <li>同一 {@code (serverId, configVersion)} 出现与已准入配置不同的解析结果时 fail closed，绝不静默改绑到新配置；
 *   <li>新版本调用到来时，停止同 server 旧版本新调用准入（fencing）；
 *   <li>旧版本当前正在执行的 active 调用正常等待归零后自动关闭进程（drain），随后从代际表移除，不随版本升级累积；
 *   <li>执行失败的 client 从当前绑定脱钩并允许后续新调用重建，但<strong>只在 active 调用归零后</strong>关闭，绝不打断仍在执行的并发调用；
 *   <li>discover 与 call 可复用同版本 client；
 *   <li>不设环境级串行化锁，同版本与不同 server 间支持并发执行；
 *   <li>{@link #close()} 时全量关闭所有 MCP 子进程，且与并发的 {@link #acquire(DaemonLocalMcpConfig)}
 *       互斥，关闭后不可能再创建新进程。
 * </ul>
 */
public final class DaemonLocalMcpManager implements AutoCloseable {

  private final BiFunction<StdioMcpConfig, McpDeadline, McpClient> clientFactory;
  private final Map<String, ServerState> servers = new ConcurrentHashMap<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Object lifecycleLock = new Object();

  public DaemonLocalMcpManager() {
    this(McpClientFactory::createStdio);
  }

  public DaemonLocalMcpManager(BiFunction<StdioMcpConfig, McpDeadline, McpClient> clientFactory) {
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
  }

  /**
   * 申请一次受管 MCP 调用许可。
   *
   * @param config 校验通过的 DaemonLocalMcpConfig
   * @return ManagedCall 调用句柄
   * @throws DaemonMcpFencedException 若该 server 已准入更新的版本，或同版本配置与已准入配置不一致
   * @throws IllegalStateException 若 manager 已关闭
   */
  public ManagedCall acquire(DaemonLocalMcpConfig config) {
    Objects.requireNonNull(config, "config");
    // 与 close() 互斥：否则「检查已关闭 → close() 清空 → 在此新建 ServerState」会在停机后再拉起子进程。
    synchronized (lifecycleLock) {
      if (closed.get()) {
        throw new IllegalStateException("DaemonLocalMcpManager is closed");
      }
      ServerState state = servers.computeIfAbsent(config.serverId(), id -> new ServerState());
      return state.acquireCall(config, clientFactory);
    }
  }

  @Override
  public void close() {
    synchronized (lifecycleLock) {
      if (closed.compareAndSet(false, true)) {
        for (ServerState state : servers.values()) {
          state.closeAll();
        }
        servers.clear();
      }
    }
  }

  /** 当前仍被保留的实例总数（仅供同包测试观测代际清理，不参与生产逻辑）。 */
  int retainedInstanceCount() {
    int total = 0;
    for (ServerState state : servers.values()) {
      total += state.retainedInstanceCount();
    }
    return total;
  }

  /** 针对单个 MCP Server 的代际与实例跟踪状态。 */
  private static final class ServerState {

    private long latestConfigVersion = -1;
    private final Map<Long, ServerInstance> instances = new ConcurrentHashMap<>();

    private ServerState() {}

    private synchronized ManagedCall acquireCall(
        DaemonLocalMcpConfig config,
        BiFunction<StdioMcpConfig, McpDeadline, McpClient> clientFactory) {
      long version = config.configVersion();

      if (version < latestConfigVersion) {
        throw new DaemonMcpFencedException("mcp server version retired");
      }

      if (version > latestConfigVersion) {
        latestConfigVersion = version;
        for (Map.Entry<Long, ServerInstance> entry : instances.entrySet()) {
          if (entry.getKey() < version) {
            // 旧代际停止准入，但绝不打断在途调用：active 归零时才关闭并从代际表移除。
            retire(entry.getValue());
          }
        }
      }

      ServerInstance instance = instances.get(version);
      if (instance != null && (instance.failed || instance.closed)) {
        // 失败/已关闭实例必须与当前绑定脱钩：新调用立刻拿到新实例，而旧实例仍在执行的并发调用照常跑完（drain）。
        // 这里绝不能直接 close()，否则会中断那些调用。
        if (instances.remove(version, instance)) {
          retire(instance);
        }
        instance = null;
      }

      if (instance == null) {
        instance = new ServerInstance(config, clientFactory);
        instances.put(version, instance);
      } else if (!instance.config.equals(config)) {
        // 同 (serverId, configVersion) 必须是同一份配置：否则静默沿用旧进程会以错误身份执行新调用。
        // 错误文本不回显任何配置内容。
        throw new DaemonMcpFencedException("mcp server config changed without version bump");
      }

      if (instance.retired || instance.closed) {
        throw new DaemonMcpFencedException("mcp server version retired");
      }

      instance.activeCalls.incrementAndGet();
      ServerInstance targetInstance = instance;
      return new ManagedCall(targetInstance, failed -> releaseCall(targetInstance, failed));
    }

    /** 停止某实例的新调用准入；已经空闲时立即关闭并脱离代际表，绝不让已关闭的旧代际常驻。调用方必须持有本对象监视器。 */
    private void retire(ServerInstance instance) {
      instance.retired = true;
      if (instance.activeCalls.get() == 0) {
        instance.close();
        instances.remove(instance.config.configVersion(), instance);
      }
    }

    private synchronized void releaseCall(ServerInstance instance, boolean failed) {
      if (failed) {
        instance.failed = true;
      }
      int remaining = instance.activeCalls.decrementAndGet();
      if ((instance.retired || instance.failed) && remaining == 0) {
        instance.close();
        // 反注册只在值仍是本实例时生效：同版本的新实例绝不能被旧实例的释放移除。
        instances.remove(instance.config.configVersion(), instance);
      }
    }

    private synchronized void closeAll() {
      for (ServerInstance instance : instances.values()) {
        instance.close();
      }
      instances.clear();
    }

    private int retainedInstanceCount() {
      return instances.size();
    }
  }

  /** 代表某一个 (serverId, configVersion) 的实例运行状态。 */
  public static final class ServerInstance {

    private final DaemonLocalMcpConfig config;
    private final BiFunction<StdioMcpConfig, McpDeadline, McpClient> clientFactory;
    private final AtomicInteger activeCalls = new AtomicInteger(0);
    private volatile boolean retired = false;
    private volatile boolean failed = false;
    private volatile boolean closed = false;
    private volatile McpClient client;
    private final Object initLock = new Object();

    private ServerInstance(
        DaemonLocalMcpConfig config,
        BiFunction<StdioMcpConfig, McpDeadline, McpClient> clientFactory) {
      this.config = config;
      this.clientFactory = clientFactory;
    }

    /** 惰性创建或复用 MCP client；创建过程消耗传入 deadline 的剩余预算。 */
    public McpClient getOrCreateClient(McpDeadline deadline) {
      if (closed) {
        throw new McpException("mcp client instance is closed");
      }
      if (failed) {
        throw new McpException("mcp client instance has failed");
      }
      McpClient current = client;
      if (current != null) {
        return current;
      }
      synchronized (initLock) {
        if (closed) {
          throw new McpException("mcp client instance is closed");
        }
        if (failed) {
          throw new McpException("mcp client instance has failed");
        }
        if (client != null) {
          return client;
        }
        try {
          StdioMcpConfig stdioConfig =
              new StdioMcpConfig(config.command(), config.cwd(), config.env());
          client = clientFactory.apply(stdioConfig, deadline);
          return client;
        } catch (RuntimeException error) {
          // 创建失败不允许留下半成品 client 被后续调用复用。
          failed = true;
          throw error;
        }
      }
    }

    public int activeCalls() {
      return activeCalls.get();
    }

    public boolean isRetired() {
      return retired;
    }

    public boolean isClosed() {
      return closed;
    }

    public boolean isFailed() {
      return failed;
    }

    private void close() {
      closed = true;
      synchronized (initLock) {
        if (client != null) {
          try {
            client.close();
          } catch (Exception ignored) {
            // 关闭失败不影响清理语义。
          }
          client = null;
        }
      }
    }
  }

  /** 受管调用的句柄，使用完毕后必须释放。 */
  public static final class ManagedCall implements AutoCloseable {

    private final ServerInstance instance;
    private final ReleaseCallback releaseCallback;
    private final AtomicBoolean released = new AtomicBoolean(false);

    private ManagedCall(ServerInstance instance, ReleaseCallback releaseCallback) {
      this.instance = instance;
      this.releaseCallback = releaseCallback;
    }

    public ServerInstance instance() {
      return instance;
    }

    public McpClient getClient(McpDeadline deadline) {
      return instance.getOrCreateClient(deadline);
    }

    /**
     * 释放本调用占用的许可以及是否把该实例标记为失败。
     *
     * <p>{@code failed=true} 表示底层 client 已不可信（例如进程异常退出），实例会被销毁且后续调用重建；取消等调用方原因绝不标记失败，
     * 避免关闭同版本其它并发调用正在使用的 client。
     */
    public void release(boolean failed) {
      if (released.compareAndSet(false, true)) {
        releaseCallback.release(failed);
      }
    }

    @Override
    public void close() {
      release(false);
    }
  }

  @FunctionalInterface
  private interface ReleaseCallback {
    void release(boolean failed);
  }
}
