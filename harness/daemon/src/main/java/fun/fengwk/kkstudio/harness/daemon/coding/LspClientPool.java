package fun.fengwk.kkstudio.harness.daemon.coding;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 {@code (项目根, 服务器 id, 配置指纹)} 复用 LSP 客户端。
 *
 * <p>同一 key 的并发启动被合并成一次真实启动；没有在途请求且闲置超过 {@code idleTimeout} 的客户端被回收；线程结束不关闭共享实例，只有 Daemon
 * 退出或配置失效才全量关闭。关闭顺序与失败清理都由 {@link LspClient} 保证。
 *
 * <p>并发不变量：{@link #clients}、{@link #boots}、{@link #retiring}、{@code generation}、{@code closed}
 * 与空闲检查句柄是同一份共享状态， 全部只在 {@link #lock} 内读取或改写，不依赖任何 {@code volatile} 或弱一致视图。阻塞操作（进程启动、初始化、{@code
 * stop}、future 工作等待）一律在锁外执行，锁只覆盖状态判定与状态迁移；因此同一 key 的调用方在观察到「先退休、后启动」的顺序时不会看见第二个实例并行存在。
 */
final class LspClientPool implements AutoCloseable {

  private final LspDiscovery discovery;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService dispatch;
  private final Duration idleTimeout;
  private final Duration shutdownGrace;
  private final Duration initializeTimeout;

  private final Object lock = new Object();
  private final Map<String, Client> clients = new HashMap<>();
  private final Map<String, Boot> boots = new HashMap<>();
  private final Map<String, Retiring> retiring = new HashMap<>();
  private long generation;
  private boolean closed;
  private ScheduledFuture<?> idleCheck;

  LspClientPool(
      LspDiscovery discovery,
      ScheduledExecutorService scheduler,
      ExecutorService dispatch,
      Duration idleTimeout,
      Duration shutdownGrace,
      Duration initializeTimeout) {
    this.discovery = Objects.requireNonNull(discovery, "discovery");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.dispatch = Objects.requireNonNull(dispatch, "dispatch");
    this.idleTimeout = requirePositive(idleTimeout, "idleTimeout");
    this.shutdownGrace = requirePositive(shutdownGrace, "shutdownGrace");
    this.initializeTimeout = requirePositive(initializeTimeout, "initializeTimeout");
  }

  /**
   * 为文件对应的服务器借出一个客户端，并把它标记为有在途请求。
   *
   * <p>同一 key 正在退休时先在锁外等待退休完成，然后重新判定，所以新实例只在旧实例停止之后才会启动。
   *
   * @throws IllegalStateException 未配置服务器、服务器未安装、配置无效或服务已关闭
   */
  Client lease(Path file) throws Exception {
    LspServerConfig server = discovery.requireServer(file);
    String executable = discovery.requireExecutable(server);
    Path root = discovery.workspaceRoot(server, file);
    String key = key(root, server);
    for (; ; ) {
      Retiring retirement;
      Boot boot = null;
      boolean creator = false;
      synchronized (lock) {
        requireOpen();
        Client existing = clients.get(key);
        if (existing != null) {
          if (existing.client.isAlive()) {
            return existing.leased();
          }
          clients.remove(key, existing);
        }
        retirement = retiring.get(key);
        if (retirement == null) {
          boot = boots.get(key);
          if (boot == null) {
            boot = new Boot(generation);
            boots.put(key, boot);
            creator = true;
          }
        }
      }
      if (retirement != null) {
        // 锁外等待：退休任务本身会做阻塞 stop，绝不能在锁内 join；派发被丢弃时等待者自己接替完成。
        retirement.awaitCompletion(this, shutdownGrace);
        continue;
      }
      if (creator) {
        start(key, boot, server, root, executable);
      }
      return attach(key, waitForBoot(boot));
    }
  }

  /**
   * 已存在的存活客户端，不会启动新进程；写工具提交后的同步与只读探测用它。
   *
   * <p>这是尽力而为的旁路：与空闲回收竞争时可能返回空，此时同步留给下一次 {@link #lease} 前的文档同步补齐，绝不为此启动服务器。
   */
  Optional<LspClient> client(Path file) {
    return discovery
        .server(file)
        .flatMap(
            server -> {
              Path root = discovery.workspaceRoot(server, file);
              String key = key(root, server);
              synchronized (lock) {
                Client existing = clients.get(key);
                return existing != null && existing.client.isAlive()
                    ? Optional.of(existing.client)
                    : Optional.empty();
              }
            });
  }

  /** 归还借出的客户端；最后一个在途请求结束后重新计算空闲截止时间。调度器已停止时只放弃重新排期，绝不把调度错误冒泡成工具结果错误。 */
  void release(Client entry) {
    synchronized (lock) {
      entry.inFlight--;
      if (clients.get(entry.key) == entry) {
        entry.touch();
        armIdleCheckQuietly();
      }
    }
  }

  /** 当前存活、尚未退休的客户端数量，用于回收行为断言。 */
  int activeCount() {
    synchronized (lock) {
      return clients.size();
    }
  }

  /**
   * 关闭池：快照可用实例、启动中的实例与正在退休的实例并统一收尾。从未被派发执行的退休登记（例如队列已被丢弃） 由本节主动接替完成，因此关闭返回后不会留下任何语言服务器进程，也不会留下等待者。
   */
  @Override
  public void close() {
    List<LspClient> stopping = new ArrayList<>();
    List<Boot> cancelled = new ArrayList<>();
    List<Retiring> retirements;
    synchronized (lock) {
      if (closed) {
        return;
      }
      closed = true;
      generation++;
      cancelIdleCheck();
      for (Client entry : clients.values()) {
        stopping.add(entry.client);
      }
      clients.clear();
      for (Boot boot : boots.values()) {
        cancelled.add(boot);
        if (boot.client != null) {
          stopping.add(boot.client);
        }
      }
      boots.clear();
      retirements = List.copyOf(retiring.values());
    }
    IllegalStateException closedError = new IllegalStateException("LSP service is closed");
    for (Boot boot : cancelled) {
      boot.result.completeExceptionally(closedError);
    }
    // 未开始的退休在这里同步接替（不持池锁）；已在执行的退休随后有界等待它真正结束。
    for (Retiring retirement : retirements) {
      retirement.stop(this);
    }
    stopAll(stopping);
    awaitRetirements(retirements);
  }

  /** 有界等待仍在执行的退休；单个退休超时不影响其余收尾，也绝不阻塞在池锁上。 */
  private void awaitRetirements(List<Retiring> retirements) {
    long deadline =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownGrace.toMillis() + 2000);
    for (Retiring retirement : retirements) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        return;
      }
      retirement.awaitDone(Duration.ofNanos(remaining));
    }
  }

  private Client attach(String key, LspClient client) {
    synchronized (lock) {
      requireOpen();
      Client entry = clients.get(key);
      if (entry == null || entry.client != client) {
        throw new IllegalStateException("LSP service is closed");
      }
      return entry.leased();
    }
  }

  /** 启动一个实例：进程登记、初始化与发布都在锁外完成，只有状态判定与状态迁移进入锁；任何失败路径都在锁内移除登记， 并在锁外停止已经启动的进程。 */
  private void start(String key, Boot boot, LspServerConfig server, Path root, String executable) {
    LspClient client = null;
    boolean published = false;
    try {
      client = LspClient.launch(server, root, executable, dispatch);
      synchronized (lock) {
        // 先登记再握手：关闭与配置失效可以立即终止仍在初始化的实例。
        boot.client = client;
        requireUsable(key, boot);
      }
      client.initialize(initializeTimeout);
      synchronized (lock) {
        requireUsable(key, boot);
        clients.put(key, new Client(key, client));
        boots.remove(key, boot);
        published = true;
      }
      boot.result.complete(client);
    } catch (Exception error) {
      if (client != null && !published) {
        stopQuietly(client, null);
      }
      synchronized (lock) {
        boots.remove(key, boot);
      }
      boot.result.completeExceptionally(error);
    }
  }

  /** 启动仍可发布时返回；否则在锁内移除登记并抛出取消错误。调用方必须持有 {@link #lock}。 */
  private void requireUsable(String key, Boot boot) {
    if (closed || boot.generation != generation) {
      boots.remove(key, boot);
      throw new IllegalStateException("LSP client startup cancelled");
    }
  }

  private LspClient waitForBoot(Boot boot) throws Exception {
    try {
      return boot.result.join();
    } catch (CompletionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      if (cause instanceof Error fatal) {
        throw fatal;
      }
      throw error;
    }
  }

  /** 触发一次空闲检查：只有最早到期的可回收客户端决定下一次检查时刻。调用方必须持有 {@link #lock}。 */
  private void armIdleCheck() {
    cancelIdleCheck();
    if (closed || clients.isEmpty()) {
      return;
    }
    long now = System.currentTimeMillis();
    long earliest = Long.MAX_VALUE;
    for (Client entry : clients.values()) {
      if (entry.inFlight == 0) {
        earliest = Math.min(earliest, entry.lastUsedAt + idleTimeout.toMillis());
      }
    }
    if (earliest == Long.MAX_VALUE) {
      // 所有客户端都有在途请求：请求结束时的 release 会重新排期，避免无意义的轮询。
      return;
    }
    idleCheck =
        scheduler.schedule(this::evictIdle, Math.max(earliest - now, 0), TimeUnit.MILLISECONDS);
  }

  /** 调用方必须持有 {@link #lock}。 */
  private void cancelIdleCheck() {
    if (idleCheck != null) {
      idleCheck.cancel(false);
      idleCheck = null;
    }
  }

  /**
   * 回收空闲客户端：在锁内完成「移出可用集合 + 登记退休」的原子迁移，锁外派发真正阻塞的 stop， 所以调用方在观察到退休登记后拿不到旧实例，也不会看到新实例提前启动。
   *
   * <p>已经登记的退休保证会被派发或同步接替；重新排期失败也不能跳过这段清理，因此不会出现只登记、不停止的实例。
   */
  private void evictIdle() {
    List<Retiring> expired = new ArrayList<>();
    boolean registered = false;
    try {
      synchronized (lock) {
        if (closed) {
          return;
        }
        long now = System.currentTimeMillis();
        for (Client entry : clients.values()) {
          if (entry.inFlight == 0 && now - entry.lastUsedAt >= idleTimeout.toMillis()) {
            expired.add(new Retiring(entry));
          }
        }
        for (Retiring retirement : expired) {
          clients.remove(retirement.entry.key, retirement.entry);
          retiring.put(retirement.entry.key, retirement);
        }
        registered = true;
      }
    } finally {
      for (Retiring retirement : expired) {
        if (!dispatchRetirement(retirement)) {
          // 执行器拒绝派发：同步兜底，绝不让旧实例继续存活或让等待者永久阻塞。
          retirement.stop(this);
        }
      }
      if (registered) {
        scheduleIdleCheckQuietly();
      }
    }
  }

  private boolean dispatchRetirement(Retiring retirement) {
    try {
      dispatch.execute(() -> retirement.stop(this));
      return true;
    } catch (RejectedExecutionException rejected) {
      return false;
    }
  }

  /** 注销退休登记：只移除自己那一份。 */
  private void unregister(Retiring retirement) {
    synchronized (lock) {
      retiring.remove(retirement.entry.key, retirement);
    }
  }

  /** 调度器已停止时放弃重新排期：空闲回收由 {@link #close()} 的全量收尾兜底，不向调用方抛错。 */
  private void armIdleCheckQuietly() {
    try {
      armIdleCheck();
    } catch (RejectedExecutionException rejected) {
      // 调度器已关闭：不再排期。
    }
  }

  private void scheduleIdleCheckQuietly() {
    synchronized (lock) {
      if (closed) {
        return;
      }
      armIdleCheckQuietly();
    }
  }

  /** 关闭全部客户端：每个实例独立兜底，一个派发被拒或一个实例失败都不影响其余实例。 */
  private void stopAll(List<LspClient> stopping) {
    if (stopping.isEmpty()) {
      return;
    }
    List<CompletableFuture<Void>> stops = new ArrayList<>(stopping.size());
    for (LspClient client : stopping) {
      CompletableFuture<Void> stop = new CompletableFuture<>();
      stops.add(stop);
      boolean dispatched;
      try {
        dispatch.execute(() -> stopQuietly(client, stop));
        dispatched = true;
      } catch (RejectedExecutionException rejected) {
        dispatched = false;
      }
      if (!dispatched) {
        stopQuietly(client, stop);
      }
    }
    try {
      CompletableFuture.allOf(stops.toArray(CompletableFuture[]::new))
          .get(shutdownGrace.toMillis() + 2000, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException error) {
      // 已经尽力等待：超时或个别失败都不能阻止关闭流程结束。
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  private void stopQuietly(LspClient client, CompletableFuture<Void> stop) {
    try {
      client.stop(shutdownGrace);
    } catch (RuntimeException ignored) {
      // 关闭尽力而为：异常不得让关闭流程悬挂。
    } finally {
      if (stop != null) {
        stop.complete(null);
      }
    }
  }

  /** 调用方必须持有 {@link #lock}。 */
  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("LSP service is closed");
    }
  }

  /** 复用键：项目根、服务器 id 与配置指纹共同决定一个实例。 */
  private static String key(Path root, LspServerConfig server) {
    return root
        + "\n"
        + server.id()
        + "\n"
        + server.command()
        + server.extensions()
        + server.rootMarkers()
        + server.firstMatchMarkers();
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  /** 池中一个客户端条目：在途请求计数与最近活动时间决定它是否可以被回收；字段只在池锁内访问。 */
  static final class Client {
    private final String key;
    private final LspClient client;
    private int inFlight;
    private long lastUsedAt;

    private Client(String key, LspClient client) {
      this.key = key;
      this.client = client;
      this.lastUsedAt = System.currentTimeMillis();
    }

    LspClient client() {
      return client;
    }

    /** 调用方必须持有池锁。 */
    private Client leased() {
      inFlight++;
      touch();
      return this;
    }

    /** 调用方必须持有池锁。 */
    private void touch() {
      lastUsedAt = System.currentTimeMillis();
    }
  }

  /** 一次进行中的启动：等待者共享同一个结果，关闭时集中取消。 */
  private static final class Boot {
    private final long generation;
    private final CompletableFuture<LspClient> result = new CompletableFuture<>();
    private LspClient client;

    private Boot(long generation) {
      this.generation = generation;
    }
  }

  /**
   * 一次进行中的退休：真正阻塞的 stop 只允许执行一次，谁抢到 claim 谁负责在 stop 结束之后完成 {@link #done}。
   *
   * <p>重复清理（派发任务、派发被拒后的同步兜底、{@link #close()}）都先尝试 claim：抢不到只说明已有人在停，直接返回， 不再调用一次立即返回的 {@code stop}
   * 来假装结束。等待者一律在锁外等待这个共享 future。
   */
  private static final class Retiring {
    private final Client entry;
    private final CompletableFuture<Void> done = new CompletableFuture<>();
    private final AtomicBoolean claimed = new AtomicBoolean();

    private Retiring(Client entry) {
      this.entry = entry;
    }

    /** 抢到 claim 的调用方同步执行真正的 stop，并在 stop 返回后注销登记、完成共享 future。 */
    private void stop(LspClientPool pool) {
      if (!claimed.compareAndSet(false, true)) {
        return;
      }
      try {
        pool.stopQuietly(entry.client, null);
      } finally {
        pool.unregister(this);
        done.complete(null);
      }
    }

    /** 等待退休真正结束：有界等待后由等待者接替执行，避免派发被丢弃时永久阻塞。 */
    private void awaitCompletion(LspClientPool pool, Duration grace) {
      try {
        done.get(grace.toMillis() + 2000, TimeUnit.MILLISECONDS);
        return;
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      } catch (TimeoutException | ExecutionException error) {
        // 派发可能已被丢弃：由等待者自己 claim 并同步完成真正的 stop。
      }
      stop(pool);
      awaitUninterruptibly();
    }

    /** 有界等待，用于关闭路径。 */
    private void awaitDone(Duration timeout) {
      try {
        done.get(Math.max(timeout.toMillis(), 1), TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      } catch (TimeoutException | ExecutionException error) {
        // 关闭是有界的：超时后不再等待，进程终止由 stop 自身的宽限与强制收敛保证。
      }
    }

    private void awaitUninterruptibly() {
      try {
        done.join();
      } catch (CancellationException | CompletionException ignored) {
        // done 只会正常完成；异常完成同样意味着退休流程已经结束。
      }
    }
  }
}
