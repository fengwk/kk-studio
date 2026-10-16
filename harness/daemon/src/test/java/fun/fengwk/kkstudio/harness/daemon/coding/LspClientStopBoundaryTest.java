package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link LspClient} 关闭边界的行为：协议失败、范围未收敛、在途取消失败与并发等待者中断都不能跳过后续清理，也不能改写首失败身份。
 *
 * <p>这里不启动真实服务器：实例经包内私有构造器反射装配，进程范围是真实 {@link ProcessScope} 但承载受控 {@link Process}，远端是受控代理。
 * 「真实进程/真实自有任务」的物理收敛证据由 {@code LspClientQuiescenceTest} 等物理测试负责，本类只证明关闭路径的控制流、清理顺序与失败身份。
 */
class LspClientStopBoundaryTest {

  private static final Duration STOP_GRACE = Duration.ofMillis(500);
  private static final Duration WAIT = Duration.ofSeconds(20);

  @TempDir Path root;

  private final ExecutorService dispatch = Executors.newCachedThreadPool();

  @AfterEach
  void tearDown() {
    dispatch.shutdownNow();
  }

  /** 意图：{@code shutdown} 请求本身抛出 Error 时保持原身份，但范围、标准流与自有 IO 任务仍必须逐个收敛。 */
  @Test
  void shutdownErrorKeepsIdentityAndStillRunsEveryCleanup() throws Exception {
    RemoteStub remote = new RemoteStub();
    AssertionError shutdown = new AssertionError("synthetic shutdown failure");
    remote.shutdownFailure = shutdown;
    Harness harness = harness(remote);

    Error thrown = assertThrows(AssertionError.class, () -> harness.client().stop(STOP_GRACE));

    assertSame(shutdown, thrown, "首 Error 必须保持身份");
    assertEveryCleanupAttempted(harness);
    // 幂等：其余调用者看到同一条失败，而不是另一次关闭或成功。
    assertSame(
        shutdown, assertThrows(AssertionError.class, () -> harness.client().stop(STOP_GRACE)));
  }

  /** 意图：{@code exit} 通知抛出 Error 时同样保持原身份，且不得跳过后续清理。 */
  @Test
  void exitErrorKeepsIdentityAndStillRunsEveryCleanup() throws Exception {
    RemoteStub remote = new RemoteStub();
    AssertionError exit = new AssertionError("synthetic exit failure");
    remote.exitFailure = exit;
    Harness harness = harness(remote);

    Error thrown = assertThrows(AssertionError.class, () -> harness.client().stop(STOP_GRACE));

    assertSame(exit, thrown, "首 Error 必须保持身份");
    assertEveryCleanupAttempted(harness);
  }

  /** 意图：范围没有证明收敛时按固定安全失败报告，但{@code close}仍必须被调用（状态目录被删除）。 */
  @Test
  void scopeThatDidNotConvergeIsReportedAndCloseIsStillAttempted() throws Exception {
    RemoteStub remote = new RemoteStub();
    Harness harness = harness(remote);
    harness.process().exitsOnWait = false;

    ToolServiceFailureException failure =
        assertThrows(ToolServiceFailureException.class, () -> harness.client().stop(STOP_GRACE));

    assertTrue(failure.getMessage().contains("did not converge"), failure.getMessage());
    assertEveryCleanupAttempted(harness);
    assertEquals(2, harness.process().destroyForciblyCount(), "未证明收敛的范围必须被强制收敛（温和失败后升级）");
  }

  /** 意图：{@code terminate} 抛出的 Error 保持身份，且{@code close}仍必须被调用；第二次{@code terminate}是幂等的。 */
  @Test
  void scopeTerminateErrorKeepsIdentityAndCloseIsStillAttempted() throws Exception {
    RemoteStub remote = new RemoteStub();
    Harness harness = harness(remote);
    AssertionError terminate = new AssertionError("synthetic scope termination failure");
    harness.process().destroyError = terminate;

    Error thrown = assertThrows(AssertionError.class, () -> harness.client().stop(STOP_GRACE));

    assertSame(terminate, thrown, "首个范围收敛 Error 必须保持身份");
    assertEveryCleanupAttempted(harness);
    assertEquals(1, harness.process().destroyForciblyCount(), "已经开始的收敛必须幂等，不得在关闭里重复收敛");
    assertSame(
        terminate, assertThrows(AssertionError.class, () -> harness.client().stop(STOP_GRACE)));
  }

  /** 意图：单个在途请求取消失败（普通异常）不跳过其余请求的取消，也不跳过范围与标准流的收敛。 */
  @Test
  void inFlightCancelRuntimeFailureDoesNotSkipOtherRequestsOrScopeClose() throws Exception {
    IllegalStateException cancelFailure = new IllegalStateException("synthetic cancel failure");
    assertCancelFailureDoesNotSkipCleanup(cancelFailure, IllegalStateException.class);
  }

  /** 意图：单个在途请求取消抛出 Error 时同样不跳过其余请求的取消，也不跳过范围与标准流的收敛。 */
  @Test
  void inFlightCancelErrorDoesNotSkipOtherRequestsOrScopeClose() throws Exception {
    AssertionError cancelFailure = new AssertionError("synthetic cancel failure");
    assertCancelFailureDoesNotSkipCleanup(cancelFailure, AssertionError.class);
  }

  /** 意图：{@code shutdown} 请求以普通失败结束时不是关闭失败：exit 与后续清理照常完成，停止成功返回。 */
  @Test
  void shutdownRequestFailureIsNotACloseFailure() throws Exception {
    RemoteStub remote = new RemoteStub();
    remote.shutdownResult =
        CompletableFuture.failedFuture(new RuntimeException("synthetic rpc failure"));
    Harness harness = harness(remote);

    harness.client().stop(STOP_GRACE);

    assertFalse(harness.client().isAlive());
    assertEveryCleanupAttempted(harness);
  }

  /** 意图：{@code shutdown} 在宽限内没有应答不是关闭失败：exit 与后续清理照常完成，停止成功返回。 */
  @Test
  void shutdownTimeoutIsNotACloseFailure() throws Exception {
    RemoteStub remote = new RemoteStub();
    remote.shutdownResult = new CompletableFuture<>();
    Harness harness = harness(remote);

    harness.client().stop(Duration.ofMillis(100));

    assertFalse(harness.client().isAlive());
    assertEveryCleanupAttempted(harness);
  }

  /** 意图：命令已经退出时不再发关闭协议，但范围、标准流与自有 IO 任务仍必须逐个收敛。 */
  @Test
  void alreadyExitedProcessSkipsProtocolAndStillRunsEveryCleanup() throws Exception {
    RemoteStub remote = new RemoteStub();
    Harness harness = harness(remote);
    harness.process().alive = false;

    harness.client().stop(STOP_GRACE);

    assertEquals(0, remote.shutdownCalls(), "命令已经退出时不得再发 shutdown");
    assertEquals(0, remote.exitCalls(), "命令已经退出时不得再发 exit");
    assertEveryCleanupAttempted(harness);
  }

  /** 意图：{@code exit} 通知以普通失败结束时不是关闭失败，后续清理照常完成。 */
  @Test
  void exitRuntimeFailureIsNotACloseFailure() throws Exception {
    RemoteStub remote = new RemoteStub();
    remote.exitFailure = new IllegalStateException("synthetic exit failure");
    Harness harness = harness(remote);

    harness.client().stop(STOP_GRACE);

    assertEquals(1, remote.exitCalls(), "exit 通知必须已经被尝试");
    assertEveryCleanupAttempted(harness);
  }

  /** 意图：关闭自有标准流本身失败不是关闭失败：收敛事实由范围与任务决定，结论不被改写。 */
  @Test
  void streamCloseFailureDoesNotChangeTheStopConclusion() throws Exception {
    RemoteStub remote = new RemoteStub();
    Harness harness = harness(remote);
    harness.process().output().failOnClose = true;

    harness.client().stop(STOP_GRACE);

    assertTrue(harness.process().output().closed(), "失败的标准流关闭同样是一次尝试");
    assertEveryCleanupAttempted(harness);
  }

  /** 意图：范围状态被破坏（退出码不可读）时不得跳过清理；收敛动作照常完成。 */
  @Test
  void corruptedExitCodeStillRunsEveryCleanup() throws Exception {
    RemoteStub remote = new RemoteStub();
    Harness harness = harness(remote);
    ProcessScopeState.publish(harness.stateDir(), ProcessScopeState.EXIT_FILE, "not-a-number");

    harness.client().stop(STOP_GRACE);

    assertEveryCleanupAttempted(harness);
  }

  /**
   * 意图：停止线程在等待命令退出时被中断，必须如实报告「未证明收敛」，但范围、标准流与自有任务仍必须收敛。
   *
   * <p>同步点是停止线程真的阻塞在生产退出等待上（受控进程的闩锁），因此中断一定发生在 {@code waitForExit} 之内。
   */
  @Test
  void interruptedExitWaitReportsUnprovenConvergenceAndStillConvergesScope() throws Exception {
    RemoteStub remote = new RemoteStub();
    Harness harness = harness(remote);
    harness.process().blockOnWait = true;

    CompletableFuture<Throwable> result = new CompletableFuture<>();
    Thread stopper =
        new Thread(
            () -> {
              try {
                harness.client().stop(STOP_GRACE);
                result.complete(null);
              } catch (Throwable failure) {
                result.complete(failure);
              }
            },
            "lsp-boundary-interrupted-stop");
    stopper.start();
    assertTrue(harness.process().awaitWaitEntered(WAIT), "停止必须真的阻塞在等待命令退出上");
    stopper.interrupt();

    Throwable failure = result.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(
        failure instanceof ToolServiceFailureException
            && failure.getMessage().contains("interrupted"),
        "被中断的停止必须如实报告未证明收敛，实际 " + failure);
    assertEveryCleanupAttempted(harness);
  }

  /**
   * 意图：等待同一次关闭的调用者被中断时如实报告「未证明收敛」，既不把中断当成完成，也不改写首个调用者写下的真实结论。
   *
   * <p>同步点是等待者线程真的阻塞在生产 {@code awaitStopCompletion} 上，随后放行第一次关闭：只有首个调用者写下结论之后，共享完成才成立。
   */
  @Test
  void interruptedConcurrentWaiterDoesNotClaimSharedCompletion() throws Exception {
    RemoteStub remote = new RemoteStub();
    remote.shutdownResult = new CompletableFuture<>();
    Harness harness = harness(remote);

    CompletableFuture<Throwable> first = new CompletableFuture<>();
    Thread stopper =
        new Thread(
            () -> {
              try {
                harness.client().stop(Duration.ofSeconds(5));
                first.complete(null);
              } catch (Throwable failure) {
                first.complete(failure);
              }
            },
            "lsp-boundary-first-stop");
    stopper.start();
    assertTrue(remote.awaitShutdownRequested(WAIT), "第一次关闭必须已经 claim 并进入协议阶段");

    CompletableFuture<Throwable> second = new CompletableFuture<>();
    Thread waiter =
        new Thread(
            () -> {
              try {
                harness.client().stop(Duration.ofSeconds(5));
                second.complete(null);
              } catch (Throwable failure) {
                second.complete(failure);
              }
            },
            "lsp-boundary-waiter");
    waiter.start();
    awaitBlockedIn(waiter, "awaitStopCompletion", WAIT);
    waiter.interrupt();

    Throwable waiterFailure = second.get(WAIT.toSeconds(), TimeUnit.SECONDS);
    assertTrue(
        waiterFailure instanceof ToolServiceFailureException
            && waiterFailure.getMessage().contains("interrupted"),
        "被中断的等待者必须如实报告未证明收敛，实际 " + waiterFailure);
    assertFalse(first.isDone(), "首个调用者还没有写下结论时，等待者的中断不得被当成完成");

    remote.completeShutdown();
    assertNull(first.get(WAIT.toSeconds(), TimeUnit.SECONDS), "首个调用者必须写下真实的成功结论");
    // 共享结论已经写下：第三个调用者看到同一条结论，而不是再一次真实关闭。
    harness.client().stop(STOP_GRACE);
    assertFalse(harness.client().isAlive());
    assertEveryCleanupAttempted(harness);
  }

  private void assertCancelFailureDoesNotSkipCleanup(
      Throwable cancelFailure, Class<? extends Throwable> type) throws Exception {
    RemoteStub remote = new RemoteStub();
    Harness harness = harness(remote);
    CompletableFuture<Object> cancelTarget = new CompletableFuture<>();
    injectInFlightRequests(harness.client(), cancelTarget, new CancelFailingFuture(cancelFailure));

    Throwable thrown = assertThrows(type, () -> harness.client().stop(STOP_GRACE));

    assertSame(cancelFailure, thrown, "取消失败必须原身份成为这次停止的结论");
    assertTrue(cancelTarget.isCancelled(), "其余在途请求仍必须被取消");
    assertEveryCleanupAttempted(harness);
  }

  /** 断言这条关闭路径真的走完了：自有流被关闭、进程范围被收敛、释放被调用、自有 IO 任务退出。 */
  private static void assertEveryCleanupAttempted(Harness harness) throws InterruptedException {
    assertTrue(harness.process().output().closed(), "自有标准输出流必须被关闭");
    assertTrue(harness.process().input().closed(), "自有标准输入流必须被关闭");
    assertTrue(harness.process().error().closed(), "自有标准错误流必须被关闭");
    assertTrue(harness.process().destroyForciblyCount() >= 1, "进程范围必须被收敛");
    assertFalse(Files.exists(harness.stateDir()), "进程范围释放必须被调用");
    assertTrue(harness.taskExited().await(WAIT.toSeconds(), TimeUnit.SECONDS), "自有 IO 任务必须真正退出");
  }

  /** 装配一个受控客户端：真实范围对象 + 受控进程/远端，并提交一个真的会阻塞等待流结束的自有任务。 */
  private Harness harness(RemoteStub remote) throws Exception {
    ControlledProcess process = new ControlledProcess();
    Path stateDir = Files.createTempDirectory(root, ProcessScope.STATE_DIR_PREFIX);
    ProcessScope scope = newProcessScope(process, stateDir);
    LspClientOwnedTasks ioTasks = new LspClientOwnedTasks(dispatch);
    LspServerConfig server =
        new LspServerConfig(
            "boundary", List.of("lsp-boundary"), List.of(".java"), List.of(), List.of());
    LspClient client = newClient(server, root, scope, ioTasks, new StubLauncher(remote.proxy()));
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch exited = new CountDownLatch(1);
    ioTasks.submit(
        () -> {
          started.countDown();
          try {
            drain(process.getInputStream());
          } finally {
            exited.countDown();
          }
        });
    assertTrue(started.await(WAIT.toSeconds(), TimeUnit.SECONDS), "自有 IO 任务必须真的开始");
    return new Harness(client, process, stateDir, exited, remote);
  }

  /** 镜像生产的 stderr 排空：读阻塞在流上，只有流被关闭才可能退出。 */
  private static void drain(InputStream stream) {
    try (InputStream input = stream) {
      byte[] buffer = new byte[4096];
      while (input.read(buffer) >= 0) {
        // 只消费，不需要保留内容。
      }
    } catch (IOException ignored) {
      // 进程退出或流被关闭会结束读取，属于正常收敛路径。
    }
  }

  private static ProcessScope newProcessScope(Process process, Path stateDir) throws Exception {
    Constructor<ProcessScope> constructor =
        ProcessScope.class.getDeclaredConstructor(Process.class, Path.class, boolean.class);
    constructor.setAccessible(true);
    return constructor.newInstance(process, stateDir, false);
  }

  private static LspClient newClient(
      LspServerConfig server,
      Path root,
      ProcessScope scope,
      LspClientOwnedTasks ioTasks,
      Launcher<LspClient.JdtlsServer> launcher)
      throws Exception {
    Constructor<LspClient> constructor =
        LspClient.class.getDeclaredConstructor(
            LspServerConfig.class,
            Path.class,
            ProcessScope.class,
            LspClientOwnedTasks.class,
            Launcher.class);
    constructor.setAccessible(true);
    return constructor.newInstance(server, root, scope, ioTasks, launcher);
  }

  /** 直接把在途请求放进客户端内部的登记集合：只读取 final 字段并修改它持有的集合，不写 final 字段本身。 */
  @SuppressWarnings("unchecked")
  private static void injectInFlightRequests(LspClient client, CompletableFuture<?>... requests)
      throws Exception {
    Field field = LspClient.class.getDeclaredField("inFlightRequests");
    field.setAccessible(true);
    Set<CompletableFuture<?>> registered = (Set<CompletableFuture<?>>) field.get(client);
    for (CompletableFuture<?> request : requests) {
      registered.add(request);
    }
  }

  /** 等待某个生产方法出现在给定线程的调用栈上：不依赖 sleep 猜测同步点。 */
  private static void awaitBlockedIn(Thread thread, String method, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().equals(LspClient.class.getName())
            && frame.getMethodName().equals(method)) {
          return;
        }
      }
      Thread.sleep(10);
    }
    throw new AssertionError(
        "the thread never reached " + LspClient.class.getSimpleName() + "." + method);
  }

  /** 一次受控装配：被测客户端、受控进程、真实状态目录、自有任务退出闩锁与受控远端。 */
  private record Harness(
      LspClient client,
      ControlledProcess process,
      Path stateDir,
      CountDownLatch taskExited,
      RemoteStub remote) {}

  /** 受控远端：只回答关闭协议，其余方法一律视为「本次边界不涉及」。 */
  private static final class RemoteStub implements InvocationHandler {

    private final CountDownLatch shutdownRequested = new CountDownLatch(1);
    private final AtomicInteger shutdownCalls = new AtomicInteger();
    private final AtomicInteger exitCalls = new AtomicInteger();
    private volatile CompletableFuture<Object> shutdownResult =
        CompletableFuture.completedFuture(null);
    private volatile Throwable shutdownFailure;
    private volatile Throwable exitFailure;

    private LspClient.JdtlsServer proxy() {
      return (LspClient.JdtlsServer)
          Proxy.newProxyInstance(
              LspClient.JdtlsServer.class.getClassLoader(),
              new Class<?>[] {LspClient.JdtlsServer.class},
              this);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getName().equals("shutdown")) {
        shutdownCalls.incrementAndGet();
        shutdownRequested.countDown();
        if (shutdownFailure != null) {
          throw shutdownFailure;
        }
        return shutdownResult;
      }
      if (method.getName().equals("exit")) {
        exitCalls.incrementAndGet();
        if (exitFailure != null) {
          throw exitFailure;
        }
        return null;
      }
      throw new UnsupportedOperationException(method.getName());
    }

    private boolean awaitShutdownRequested(Duration timeout) throws InterruptedException {
      return shutdownRequested.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void completeShutdown() {
      shutdownResult.complete(null);
    }

    private int shutdownCalls() {
      return shutdownCalls.get();
    }

    private int exitCalls() {
      return exitCalls.get();
    }
  }

  /** 测试自有启动器：{@code getRemoteProxy} 直接交回受控代理，{@code startListening} 不启动任何线程。 */
  private static final class StubLauncher implements Launcher<LspClient.JdtlsServer> {

    private final LspClient.JdtlsServer remote;

    private StubLauncher(LspClient.JdtlsServer remote) {
      this.remote = remote;
    }

    @Override
    public Future<Void> startListening() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public LspClient.JdtlsServer getRemoteProxy() {
      return remote;
    }

    @Override
    public RemoteEndpoint getRemoteEndpoint() {
      return null;
    }
  }

  /** 取消时抛出指定失败的 Future：用于制造「单个在途请求取消失败」的窗口。 */
  private static final class CancelFailingFuture extends CompletableFuture<Object> {

    private final Throwable failure;

    private CancelFailingFuture(Throwable failure) {
      this.failure = failure;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      if (failure instanceof Error fatal) {
        throw fatal;
      }
      throw (RuntimeException) failure;
    }
  }

  /**
   * 受控进程：只提供范围收敛与流关闭所需的事实，不代表真实 OS 进程。
   *
   * <p>{@code exitsOnWait} 决定温和收敛是否成功；{@code destroyError} 让第一次强杀抛出固定的 Error；{@code blockOnWait}
   * 让第一次 退出等待真的阻塞，供测试在确定的时刻打断它。
   */
  private static final class ControlledProcess extends Process {

    private final BlockingInputStream input = new BlockingInputStream();
    private final BlockingInputStream error = new BlockingInputStream();
    private final CloseRecordingOutputStream output = new CloseRecordingOutputStream();
    private final AtomicInteger destroyForciblyCount = new AtomicInteger();
    private final CountDownLatch waitEntered = new CountDownLatch(1);
    private final CountDownLatch waitGate = new CountDownLatch(1);
    private volatile boolean alive = true;
    private volatile boolean exitsOnWait = true;
    private volatile boolean blockOnWait;
    private volatile Error destroyError;

    private BlockingInputStream input() {
      return input;
    }

    private BlockingInputStream error() {
      return error;
    }

    private CloseRecordingOutputStream output() {
      return output;
    }

    private int destroyForciblyCount() {
      return destroyForciblyCount.get();
    }

    private boolean awaitWaitEntered(Duration timeout) throws InterruptedException {
      return waitEntered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public OutputStream getOutputStream() {
      return output;
    }

    @Override
    public InputStream getInputStream() {
      return input;
    }

    @Override
    public InputStream getErrorStream() {
      return error;
    }

    @Override
    public int waitFor() {
      alive = false;
      return 0;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (blockOnWait) {
        // 一次性：只让第一次（退出等待）阻塞，之后的收敛等待必须能立刻返回事实。
        blockOnWait = false;
        waitEntered.countDown();
        waitGate.await();
        alive = false;
        return true;
      }
      if (exitsOnWait) {
        alive = false;
        return true;
      }
      return false;
    }

    @Override
    public int exitValue() {
      if (alive) {
        throw new IllegalThreadStateException("the controlled process is still running");
      }
      return 0;
    }

    @Override
    public void destroy() {
      alive = false;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public Process destroyForcibly() {
      destroyForciblyCount.incrementAndGet();
      Error failure = destroyError;
      if (failure != null) {
        throw failure;
      }
      alive = false;
      return this;
    }
  }

  /** 读取阻塞直到被关闭：只有流真的关闭之后，自有排空任务才可能结束。 */
  private static final class BlockingInputStream extends InputStream {

    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile boolean closeCalled;

    private boolean closed() {
      return closeCalled;
    }

    @Override
    public int read() throws IOException {
      return read(new byte[1], 0, 1);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      try {
        closed.await();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IOException(error);
      }
      return -1;
    }

    @Override
    public void close() {
      closeCalled = true;
      closed.countDown();
    }
  }

  /** 只记录是否被关闭的输出流；可选让关闭抛出固定失败，用于证明关流失败不改变收敛事实。 */
  private static final class CloseRecordingOutputStream extends OutputStream {

    private volatile boolean closeCalled;
    private volatile boolean failOnClose;

    private boolean closed() {
      return closeCalled;
    }

    @Override
    public void write(int value) {
      // 测试不消费写入内容。
    }

    @Override
    public void close() throws IOException {
      closeCalled = true;
      if (failOnClose) {
        throw new IOException("synthetic stream close failure");
      }
    }
  }
}
