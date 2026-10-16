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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 按 {@code (项目根, 服务器配置)} 复用 LSP 客户端。
 *
 * <p>同一 key 的并发启动被合并成一次真实启动；没有在途请求且闲置超过 {@code idleTimeout} 的客户端被回收；线程结束不关闭共享实例，只有 Daemon
 * 退出或配置失效才全量关闭。真实停止只有一次、重复调用共享同一条结论，这是 {@link LspClient} 的保证。
 *
 * <p>并发不变量：{@link #clients}、{@link #boots}、{@link #retiring} 与 {@code closed} 只在 {@link #lock}
 * 内判定与迁移， 一切阻塞操作（进程启动、初始化、真实停止、等待完成结论）都在锁外执行。同一个 key 在任何时刻只属于三者之一：可用实例、启动中的实例、
 * 尚未证明收敛的退休；已死与已开始关闭的实例都先移入退休，因此旧实例的物理收敛一定先于同 key 的新实例。启动以终结失败（致命 {@link Error}
 * 或清理没能证明收敛）结束时，这次启动登记会保留到 close：同 key 因此失败关闭，既不会在旧资源可能还活着时重启，也不会把致命失败当成可重试。
 */
final class LspClientPool implements AutoCloseable {

  private final LspDiscovery discovery;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService dispatch;
  private final Duration idleTimeout;
  private final Duration shutdownGrace;
  private final Duration initializeTimeout;

  private final Object lock = new Object();
  private final Map<Key, Client> clients = new HashMap<>();
  private final Map<Key, Boot> boots = new HashMap<>();
  private final Map<Key, Retiring> retiring = new HashMap<>();
  private boolean closed;
  private ScheduledFuture<?> idleCheck;

  /** 全量关闭的完成结论：并发与重复关闭都等它，并看到同一条失败。 */
  private volatile Throwable closeFailure;

  private final CountDownLatch closeDone = new CountDownLatch(1);

  /** 关闭的等待预算：第一次关闭在锁内按当时的资源量算出。 */
  private long closeBudgetMillis;

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
   * <p>同一 key 正在启动时共享同一次启动；正在退休时先在锁外等退休结束再重新判定，因此新实例只在旧实例停止之后才会启动；已经以终结失败结束的启动 会共享同一条失败，因此这个 key
   * 失败关闭，直到 close。
   *
   * @throws IllegalStateException 未配置服务器、服务器未安装、配置无效或服务已关闭
   */
  Client lease(Path file) throws Exception {
    LspServerConfig server = discovery.requireServer(file);
    String executable = discovery.requireExecutable(server);
    Path root = discovery.workspaceRoot(server, file);
    Key key = key(root, server);
    for (; ; ) {
      Retiring retirement;
      Boot boot = null;
      boolean creator = false;
      synchronized (lock) {
        requireOpen();
        Client existing = clients.get(key);
        if (existing != null && existing.client.isAlive()) {
          return existing.leased();
        }
        if (existing != null) {
          // 不可再用的实例与新回收的实例走同一条退休：它真正收敛之后同 key 才允许重新启动。
          clients.remove(key, existing);
          retiring.put(key, new Retiring(key, existing.client));
        }
        retirement = retiring.get(key);
        if (retirement == null) {
          boot = boots.get(key);
          if (boot == null) {
            boot = new Boot(key);
            boots.put(key, boot);
            creator = true;
          }
        }
      }
      if (retirement != null) {
        // 锁外等待：退休自己会做阻塞的真实停止；派发被丢弃时等待者接替完成同一次停止。
        throwIfFailed(retirement.complete(this));
        continue;
      }
      if (creator) {
        start(boot, server, root, executable);
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
              Key key = key(root, server);
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
   * 关闭池：锁内取消空闲检查、取消启动交付并把全部实例移入同一条可 claim 的真实停止登记，锁外逐个停止实例、逐个等启动结束。
   *
   * <p>并发与重复关闭等待同一条完成结论，因此第一次还在收尾时不会声称完成；未证明收敛的退休保留登记，不会用「执行器以后可能执行」冒充完成。 一个资源失败不跳过其余资源：首个 Error
   * 保持身份，后到的 Error 优先于普通异常，其余作为 suppressed 附着。
   */
  @Override
  public void close() {
    CloseWork work = null;
    synchronized (lock) {
      if (!closed) {
        closed = true;
        ScheduledFuture<?> pendingIdleCheck = idleCheck;
        idleCheck = null;
        work = snapshotForClose(pendingIdleCheck);
      }
    }
    if (work != null) {
      try {
        closeFailure = performClose(work);
      } catch (RuntimeException | Error unexpected) {
        // 结论必须发布：收尾内部的意外失败不能让等待者永久挂住。
        closeFailure = unexpected;
      } finally {
        closeDone.countDown();
      }
    }
    throwIfFailed(awaitCloseOutcome());
  }

  /** 锁内快照：取消启动交付、把全部实例移入退休登记，并算出关闭的等待预算。调用方必须持有 {@link #lock}。 */
  private CloseWork snapshotForClose(ScheduledFuture<?> pendingIdleCheck) {
    IllegalStateException closedError = new IllegalStateException("LSP service is closed");
    List<Retiring> retirements = new ArrayList<>();
    for (Client entry : clients.values()) {
      retirements.add(new Retiring(entry.key, entry.client));
    }
    clients.clear();
    List<Boot> outstanding = new ArrayList<>(boots.values());
    boots.clear();
    for (Boot boot : outstanding) {
      // 交付取消与放行许可在同一把锁内判定：取消一定先于迟到的启动看到「已关闭」。
      boot.result.completeExceptionally(closedError);
      if (boot.client != null) {
        retirements.add(new Retiring(boot.key, boot.client));
      }
    }
    for (Retiring retirement : retirements) {
      retiring.put(retirement.key, retirement);
    }
    List<Retiring> allRetirements = List.copyOf(retiring.values());
    closeBudgetMillis =
        (long) allRetirements.size() * LspClient.stopBudgetMillis(shutdownGrace)
            + (long) outstanding.size() * bootBudgetMillis();
    return new CloseWork(allRetirements, outstanding, pendingIdleCheck);
  }

  /** 锁外收尾：逐个真实停止实例，再逐个等启动结束；一个失败不跳过其余，首个失败保持身份。 */
  private Throwable performClose(CloseWork work) {
    Throwable failure = null;
    if (work.idleCheck() != null) {
      try {
        work.idleCheck().cancel(false);
      } catch (RuntimeException | Error cancellationFailure) {
        failure = cancellationFailure;
      }
    }
    for (Retiring retirement : work.retirements()) {
      // 已经在执行的停止在这里共享同一条结论；还没被执行的（队列被丢弃）由本节接替完成。
      failure = LspClient.mergeFailure(failure, retirement.complete(this));
    }
    for (Boot boot : work.boots()) {
      // 启动线程自己负责收敛它拥有的范围与 IO 任务（含尚在构造中的实例），这里只等它真正结束。
      failure = LspClient.mergeFailure(failure, awaitBootCompletion(boot));
    }
    return failure;
  }

  /** 等待同一条关闭结论；超时按未证明收敛处理，绝不静默返回。 */
  private Throwable awaitCloseOutcome() {
    long budget;
    synchronized (lock) {
      budget = closeBudgetMillis;
    }
    try {
      if (!closeDone.await(Math.max(budget, 1), TimeUnit.MILLISECONDS)
          && closeDone.getCount() != 0) {
        return new ToolServiceFailureException("LSP service close did not converge in time");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      if (closeDone.getCount() != 0) {
        return new ToolServiceFailureException(
            "LSP service close was interrupted before it converged");
      }
    }
    return closeFailure;
  }

  /** 停止失败必须被调用者看见：Error 保持身份，普通失败原样抛出。 */
  private static void throwIfFailed(Throwable failure) {
    if (failure instanceof Error fatal) {
      throw fatal;
    }
    if (failure != null) {
      throw (RuntimeException) failure;
    }
  }

  private Client attach(Key key, LspClient client) {
    synchronized (lock) {
      requireOpen();
      Client entry = clients.get(key);
      if (entry == null || entry.client != client) {
        throw new IllegalStateException("LSP service is closed");
      }
      return entry.leased();
    }
  }

  /**
   * 启动一个实例：初始化与发布都在锁外完成，只有状态判定与状态迁移进入锁。
   *
   * <p>失败路径在锁外收敛本线程已经拥有的物理资源（实例或范围），结论作为启动的完成事实交给 {@link #close()}。终结该 key 的
   * 失败（致命启动失败、或清理没能证明收敛）保留启动登记：同 key 的新 lease 因此共享同一条失败，close 也报告同一条 Error；
   * 普通失败只有在清理已经证明收敛时才注销登记，从而允许同 key 重试。
   */
  private void start(Boot boot, LspServerConfig server, Path root, String executable) {
    LspClient client = null;
    boolean published = false;
    Throwable terminalFailure = null;
    try {
      client = LspClient.launch(server, root, executable, dispatch, this::commandPermitted);
      synchronized (lock) {
        // 先登记再握手：关闭可以立即终止仍在初始化的实例。
        boot.client = client;
        requireUsable(boot);
      }
      client.initialize(initializeTimeout);
      synchronized (lock) {
        requireUsable(boot);
        clients.put(boot.key, new Client(boot.key, client));
        boots.remove(boot.key, boot);
        published = true;
      }
      boot.result.complete(client);
    } catch (Exception | Error error) {
      Throwable failure = error;
      // 致命启动失败即使资源已经清理也不允许重试；清理没能证明收敛同样终结该 key。
      boolean terminal = error instanceof Error;
      if (client != null && !published) {
        // 实例已经构造出来：由它完成真实停止，范围与自有 IO 任务都在这条路径里收敛。
        Throwable stopFailure = stopClient(client);
        if (stopFailure != null) {
          terminal = true;
          failure = LspClient.mergeFailure(failure, stopFailure);
        }
      } else if (client == null && LspClient.startupCleanupFailed(error)) {
        // 范围建立之前清理没能证明收敛：旧资源可能还活着，这个 key 不允许重新启动。
        terminal = true;
      }
      if (terminal) {
        // 终结事实留在启动登记里：新 lease 共享同一条失败，close 等这次启动并报告同一条 Error。
        terminalFailure = failure;
      } else {
        synchronized (lock) {
          boots.remove(boot.key, boot);
        }
      }
      if (!boot.result.completeExceptionally(failure) && failure instanceof Error fatal) {
        // 共享交付已被 close 的取消抢先失败：本线程上的致命失败不能被吞掉。
        throw fatal;
      }
    } finally {
      boot.terminalFailure = terminalFailure;
      boot.settled.countDown();
    }
  }

  /** 启动仍可发布时返回；否则在锁内移除登记并抛出取消错误。调用方必须持有 {@link #lock}。 */
  private void requireUsable(Boot boot) {
    if (closed) {
      boots.remove(boot.key, boot);
      throw new IllegalStateException("LSP client startup cancelled");
    }
  }

  /** 放行服务器命令前的最后一道许可：池已关闭时拒绝，命令因此不会产生副作用。 */
  private boolean commandPermitted() {
    synchronized (lock) {
      return !closed;
    }
  }

  /** 等待共享交付；等待有界（启动预算），超时如实报告未完成而不是永久阻塞。 */
  private LspClient waitForBoot(Boot boot) throws Exception {
    try {
      return boot.result.get(bootBudgetMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ToolServiceFailureException("LSP client startup was interrupted");
    } catch (TimeoutException error) {
      throw new ToolServiceFailureException("LSP client startup did not complete in time");
    } catch (CancellationException error) {
      throw new ToolServiceFailureException("LSP client startup was cancelled");
    } catch (ExecutionException error) {
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

  /** 回收空闲客户端：锁内完成「移出可用集合 + 登记退休」的原子迁移，锁外派发真正阻塞的停止；派发被拒时同步兜底。 */
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
            expired.add(new Retiring(entry.key, entry.client));
          }
        }
        for (Retiring retirement : expired) {
          clients.remove(retirement.key);
          retiring.put(retirement.key, retirement);
        }
        registered = true;
      }
    } finally {
      for (Retiring retirement : expired) {
        if (!dispatchRetirement(retirement)) {
          retirement.complete(this);
        }
      }
      if (registered) {
        scheduleIdleCheckQuietly();
      }
    }
  }

  private boolean dispatchRetirement(Retiring retirement) {
    try {
      dispatch.execute(() -> retirement.complete(this));
      return true;
    } catch (RejectedExecutionException rejected) {
      return false;
    }
  }

  /** 注销退休登记：只移除自己那一份；只有真正证明收敛的退休才允许同 key 重新启动。 */
  private void unregister(Retiring retirement) {
    synchronized (lock) {
      retiring.remove(retirement.key, retirement);
    }
  }

  /** 执行或共享一次真实停止；失败作为返回值，调用者据此决定是否注销登记与是否继续其余收尾。 */
  private Throwable stopClient(LspClient client) {
    try {
      client.stop(shutdownGrace);
      return null;
    } catch (RuntimeException | Error failure) {
      return failure;
    }
  }

  /** 一次启动的等待预算：初始化握手 + 启动预算 + 启动线程自己收尾一次真实停止。 */
  private long bootBudgetMillis() {
    return initializeTimeout.toMillis()
        + ProcessScope.startBudgetMillis()
        + LspClient.stopBudgetMillis(shutdownGrace);
  }

  /** 等一个启动的完成结论；超时说明它没能证明收敛，不能当成已经静止。 */
  private Throwable awaitBootCompletion(Boot boot) {
    try {
      if (!boot.settled.await(bootBudgetMillis(), TimeUnit.MILLISECONDS)
          && boot.settled.getCount() != 0) {
        return new ToolServiceFailureException("LSP client startup did not converge in time");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      if (boot.settled.getCount() != 0) {
        return new ToolServiceFailureException("LSP service close was interrupted");
      }
    }
    return boot.terminalFailure;
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

  /** 调用方必须持有 {@link #lock}。 */
  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("LSP service is closed");
    }
  }

  private static Duration requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  /** 复用键工厂：项目根与完整服务器配置共同决定一个实例。 */
  private static Key key(Path root, LspServerConfig server) {
    return new Key(root, server);
  }

  /** 一次关闭的锁内快照：已登记的退休与仍未结束的启动。 */
  private record CloseWork(
      List<Retiring> retirements, List<Boot> boots, ScheduledFuture<?> idleCheck) {}

  /**
   * 结构化复用键：项目根与完整服务器配置共同决定一个实例。
   *
   * <p>record 按值比较结构化字段，避免把 {@code List.toString()} 表示相同、结构却不同的配置误判为同一实例。
   */
  private record Key(Path root, LspServerConfig server) {}

  /** 池中一个客户端条目：在途请求计数与最近活动时间决定它是否可以被回收；字段只在池锁内访问。 */
  static final class Client {
    private final Key key;
    private final LspClient client;
    private int inFlight;
    private long lastUsedAt;

    private Client(Key key, LspClient client) {
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

  /**
   * 一次进行中的启动：等待者共享同一个交付结果。
   *
   * <p>{@link #result} 是调用方看到的交付，{@link #settled} 表示启动路径已经结束并发布了终结结论，不单独代表资源已经收敛；
   * 关闭先取消交付，再等待终结结论与已构造客户端的真实停止。
   *
   * <p>{@link #terminalFailure} 是终结该 key 的失败：致命启动失败（{@link Error}），或清理没能证明收敛（ {@link
   * LspClient#startupCleanupFailed(Throwable)}）。它不是 {@code null} 时这次启动登记会一直保留到 close， 因此同 key
   * 不会在旧资源可能还活着、或已经致命失败时重新启动。
   */
  private final class Boot {

    private final Key key;
    private final CompletableFuture<LspClient> result = new CompletableFuture<>();

    private volatile LspClient client;

    /** 终结该 key 的失败：{@code null} 表示这次启动失败（如果有）允许同 key 重试。 */
    private volatile Throwable terminalFailure;

    private final CountDownLatch settled = new CountDownLatch(1);

    private Boot(Key key) {
      this.key = key;
    }
  }

  /** 退休只持有实例；真实停止与完成结论由客户端唯一负责，等待者中断不能污染该结论。 */
  private record Retiring(Key key, LspClient client) {
    private Throwable complete(LspClientPool pool) {
      Throwable failure = pool.stopClient(client);
      if (failure == null) {
        pool.unregister(this);
      }
      return failure;
    }
  }
}
