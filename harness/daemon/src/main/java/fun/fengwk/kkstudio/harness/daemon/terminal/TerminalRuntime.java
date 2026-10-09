package fun.fengwk.kkstudio.harness.daemon.terminal;

import fun.fengwk.kkstudio.harness.daemon.process.ProcessScope;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalLimits;
import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个人工终端的可信运行时边界：唯一 scoped PTY、唯一 {@link TerminalKernel} 与唯一有界写队列的所有者。
 *
 * <p>本类不创建、不关闭、也不直接启动线程：VT 解释运行在调用方注入的 {@code ExecutorService} 上，PTY 阻塞读写与收敛运行在调用方注入的阻塞 I/O
 * executor 上，native 截止时间调度运行在调用方注入的 {@code ScheduledExecutorService} 上。因为预留的 lifecycle
 * 任务、读任务与写任务都会阻塞，注入的 I/O executor 必须能并发运行这三者（例如缓存线程或等价的非绑定阻塞 executor）；单线程 executor
 * 会让运行时无法在读写进行中收敛。写入方只需注入已解析的 {@link TerminalLaunchSpec}、尺寸/历史、继承环境与启动闸门，运行时按原样 argv 启动 scoped
 * PTY，并声明 {@code TERM=xterm-256color}/{@code COLORTERM=truecolor}。
 *
 * <p>收敛只有一个执行者：构造时先在阻塞 I/O executor 上预留唯一 lifecycle 任务，它等待停止信号后完成全部清理。read failure、kernel
 * failure、native 超时、自然退出与显式 {@link #close()} 都只设置失败原因并唤醒该任务，绝不在 reader/writer/VT owner/scheduler 或
 * I/O 线程上就地收敛，也不会既等待自己又等待别人。构造期间所有任务先停在启动闸门上，全部任务与 kernel 终止钩子登记完成后才放行；任一 executor 拒绝都会先收敛 已预留的任务与
 * native 范围再失败。
 *
 * <p>PTY 输出全部按顺序进入内核，不缓存 raw 输出、不调用第二解释器；用户写入、尺寸调整与内核查询应答共用唯一有界写队列与唯一写任务。用户写入携带其编码所用的 {@code
 * inputModeRevision}：写任务在真正落笔前用内核 FIFO 屏障核对当前模式版本，不匹配时明确未写并拒绝。
 *
 * <p>写任务用一个 native gate 原子地推进每个操作的状态。进入 native 之前失败（模式过期、核验失败、截止时间不可用、会话结束）一律确定未执行；一旦进入 native
 * 写或尺寸调整，异常、超时或关闭都可能已产生前缀或部分变更，因此以 {@link OutcomeUnknownException} 报告结果不确定并终止整个 {@link
 * ProcessScope}。native 截止时间与关闭仲裁同一 gate：只有截止时间在 native
 * 进行中获胜才致结果不确定并终止；操作已完成后到达的截止时间被忽略，不会用迟到的超时杀掉已完成的会话，也不会有 在 future 失败之后才开始写的情况。
 *
 * <p>自然退出时排空 PTY 到真正 EOF、由 lifecycle 任务有界读取退出码、捕获一份有界末屏，再释放 PTY/内核；退出后 {@link #snapshot()}
 * 仍返回该末屏。{@link #close()} 幂等且统一有界，收敛失败或等待超时都显式报告，绝不静默宣称资源已释放。所有异常、{@code toString}
 * 与日志都不携带输入、画面、argv 或原异常 cause 文本。
 */
public final class TerminalRuntime implements AutoCloseable {

  /** 普通用户帧字节数上限。 */
  public static final int MAX_USER_FRAME_BYTES = 4096;

  /** 内核查询应答等内部帧的字节数上限。 */
  public static final int MAX_INTERNAL_FRAME_BYTES = 64 * 1024;

  /** 写队列最多容纳的操作项数（含正在处理的一项）。 */
  public static final int WRITE_QUEUE_CAPACITY = 128;

  /** 写队列 pending 字节上限（含正在处理的一项）。 */
  public static final int WRITE_QUEUE_MAX_BYTES = 512 * 1024;

  /** 单次 native 写/尺寸调整的截止秒数；固定值，不随调用变化。 */
  static final long NATIVE_OPERATION_TIMEOUT_SECONDS = 10L;

  /** 内核控制操作（捕获、resize、模式核对）的有界等待秒数。 */
  private static final long KERNEL_OPERATION_TIMEOUT_SECONDS = 5L;

  /** 读写任务收尾的有界等待秒数。 */
  private static final long TASK_JOIN_TIMEOUT_SECONDS = 5L;

  /** 退出码发布的预算；PTY 真正 EOF 之后最多等这么久。 */
  private static final long NATURAL_EXIT_BUDGET_MILLIS = 10_000L;

  /** 请求者等待本次收敛完成的上限；必须覆盖 scope 收敛、内核关闭与任务收尾的全部内部预算。 */
  private static final long CONVERGE_WAIT_SECONDS = 90L;

  private static final String STARTUP_FAILURE_MESSAGE = "terminal runtime could not be started";
  private static final String SESSION_CLOSED_MESSAGE = "terminal session is closed";
  private static final String QUEUE_FULL_MESSAGE = "terminal write queue is full";
  private static final String QUEUE_BUDGET_MESSAGE = "terminal write queue budget is exhausted";
  private static final String STALE_MODE_MESSAGE = "terminal input mode revision is stale";
  private static final String WRITE_UNCERTAIN_MESSAGE =
      "terminal write did not complete; the operation may have partially reached the PTY";
  private static final String RESIZE_UNCERTAIN_MESSAGE =
      "terminal resize did not complete; the size change may have partially applied";
  private static final String READ_FAILURE_MESSAGE = "terminal PTY read failed";
  private static final String KERNEL_FAILURE_MESSAGE = "terminal kernel failed";
  private static final String NATURAL_EXIT_MISSING_MESSAGE =
      "terminal process scope ended without a natural exit code";
  private static final String CAPTURE_FAILURE_MESSAGE =
      "terminal runtime could not capture the final view";
  private static final String CONVERGENCE_FAILURE_MESSAGE =
      "terminal process scope did not converge";
  private static final String KERNEL_CLOSE_FAILURE_MESSAGE = "terminal kernel could not be closed";
  private static final String TASK_FAILURE_MESSAGE =
      "terminal runtime task did not stop within its budget";
  private static final String TASK_INTERRUPTED_MESSAGE = "terminal runtime task was interrupted";
  private static final String SHUTDOWN_FAILURE_MESSAGE = "terminal process scope did not shut down";
  private static final String DEADLINE_UNAVAILABLE_MESSAGE =
      "terminal write deadline is unavailable";
  private static final String CLOSE_INTERRUPTED_MESSAGE = "terminal close was interrupted";
  private static final String CLOSE_TIMEOUT_MESSAGE =
      "terminal close did not converge within its budget";

  private final ProcessScope scope;
  private final InputStream ptyInput;
  private final OutputStream ptyOutput;
  private final ExecutorService ioExecutor;
  private final ScheduledExecutorService scheduler;
  private final TerminalKernel kernel;

  /** 写队列与其准入；同时是 native 阶段与关闭仲裁的唯一 gate。 */
  private final Object queueLock = new Object();

  private final ArrayDeque<Operation> queue = new ArrayDeque<>();
  private long queuedBytes;
  private Operation activeOperation;
  private boolean writerShutdown;

  /** 启动闸门：构造未完成前，读写与 lifecycle 任务都停在这里。 */
  private final CountDownLatch startGate = new CountDownLatch(1);

  /** 唯一停止信号：任何来源都只释放它，不自己收敛。 */
  private final CountDownLatch stopSignal = new CountDownLatch(1);

  private final AtomicReference<Throwable> failureRef = new AtomicReference<>();
  private final AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
  private final CompletableFuture<Void> terminated = new CompletableFuture<>();
  private final CountDownLatch convergeDone = new CountDownLatch(1);

  private volatile Future<?> readerTask;
  private volatile Future<?> writerTask;
  private final AtomicBoolean readerStarted = new AtomicBoolean();
  private final AtomicBoolean writerStarted = new AtomicBoolean();
  private final CountDownLatch readerDone = new CountDownLatch(1);
  private final CountDownLatch writerDone = new CountDownLatch(1);

  /** 会话进入收尾：不再接受新的 native 工作。 */
  private volatile boolean closing;

  /** 构造失败：已放行的任务必须立即退出而不触碰资源。 */
  private volatile boolean aborting;

  /** 观察到 PTY 真正 EOF；由 reader 置位，lifecycle 任务据此读取退出码。 */
  private volatile boolean ptyEof;

  private volatile Integer exitCode;
  private volatile TerminalView finalView;

  /**
   * 生产入口：以真实 scoped PTY 启动已解析的启动规格。
   *
   * <p>尺寸（{@link TerminalLimits#MIN_COLUMNS}..{@link TerminalLimits#MAX_COLUMNS} 列、{@link
   * TerminalLimits#MIN_ROWS}..{@link TerminalLimits#MAX_ROWS} 行）与历史（0..{@link
   * TerminalLimits#MAX_HISTORY_LINES}）在 native 启动前校验。启动失败不回显 argv、路径、内容或原 cause 消息。
   *
   * @param spec 已解析的可执行程序、原样 argv 与绝对工作目录
   * @param columns 初始列数
   * @param rows 初始行数
   * @param maxHistoryLines 有界历史行数
   * @param environment 被继承的环境变量
   * @param gate 启动闸门，拒绝时不会执行任何用户命令
   * @param vtExecutor VT 内核唯一 owner 使用的调用方 executor
   * @param ioExecutor PTY 阻塞读写与收敛使用的调用方阻塞 I/O executor；必须能并发运行预留的 lifecycle 任务与读、写任务
   * @param scheduler native 截止时间使用的调用方 scheduler
   */
  public static TerminalRuntime start(
      TerminalLaunchSpec spec,
      int columns,
      int rows,
      int maxHistoryLines,
      Map<String, String> environment,
      ProcessScope.StartGate gate,
      ExecutorService vtExecutor,
      ExecutorService ioExecutor,
      ScheduledExecutorService scheduler)
      throws IOException {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(environment, "environment");
    Objects.requireNonNull(gate, "gate");
    requireSize(columns, rows);
    requireHistory(maxHistoryLines);
    List<String> argv = new ArrayList<>();
    argv.add(spec.executable());
    argv.addAll(spec.args());
    Map<String, String> effectiveEnvironment = new HashMap<>(environment);
    // 终端类型与 TrueColor 由 Daemon 声明；不改用户 locale，也不绕过 PowerShell 执行策略。
    effectiveEnvironment.put("TERM", "xterm-256color");
    effectiveEnvironment.put("COLORTERM", "truecolor");
    ProcessScope scope;
    try {
      scope =
          ProcessScope.startPty(spec.workdir(), argv, columns, rows, effectiveEnvironment, gate);
    } catch (IOException error) {
      throw new IOException(STARTUP_FAILURE_MESSAGE);
    } catch (RuntimeException error) {
      throw new IllegalStateException(STARTUP_FAILURE_MESSAGE);
    }
    try {
      return new TerminalRuntime(
          scope,
          scope.process().getInputStream(),
          scope.process().getOutputStream(),
          columns,
          rows,
          maxHistoryLines,
          vtExecutor,
          ioExecutor,
          scheduler);
    } catch (RuntimeException | Error error) {
      // 运行时未能建立：释放刚启动的 native 范围。释放失败必须以固定异常显式保留 shutdown 失败，
      // 但不能回显原始 cause 或路径。
      try {
        scope.close();
      } catch (RuntimeException closeFailure) {
        throw new IllegalStateException(SHUTDOWN_FAILURE_MESSAGE);
      }
      throw error;
    }
  }

  /**
   * 窄的测试入口：接收真实 {@link ProcessScope} 与可替换的 PTY 流，用于确定性构造满队列、partial-write 与 blocked-write。
   *
   * <p>生产工厂仍获取 scope 的真实流；本入口不改变任何公共语义。
   */
  TerminalRuntime(
      ProcessScope scope,
      InputStream ptyInput,
      OutputStream ptyOutput,
      int columns,
      int rows,
      int maxHistoryLines,
      ExecutorService vtExecutor,
      ExecutorService ioExecutor,
      ScheduledExecutorService scheduler) {
    requireSize(columns, rows);
    requireHistory(maxHistoryLines);
    this.scope = Objects.requireNonNull(scope, "scope");
    this.ptyInput = Objects.requireNonNull(ptyInput, "ptyInput");
    this.ptyOutput = Objects.requireNonNull(ptyOutput, "ptyOutput");
    this.ioExecutor = Objects.requireNonNull(ioExecutor, "ioExecutor");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    Objects.requireNonNull(vtExecutor, "vtExecutor");
    TerminalKernel created;
    try {
      created =
          new TerminalKernel(columns, rows, maxHistoryLines, vtExecutor, this::onKernelResponse);
    } catch (RuntimeException | Error error) {
      // 尚无任何本类持有的资源需要释放。
      throw new IllegalStateException(STARTUP_FAILURE_MESSAGE);
    }
    this.kernel = created;
    boolean lifecycleReserved = false;
    try {
      // 先预留唯一 lifecycle 任务：它是唯一的收敛执行者，因此任何来源都只需发信号。
      ioExecutor.execute(this::runLifecycle);
      lifecycleReserved = true;
      readerTask = ioExecutor.submit(this::runReaderGuarded);
      writerTask = ioExecutor.submit(this::runWriterGuarded);
      registerKernelTermination();
    } catch (RuntimeException | Error error) {
      aborting = true;
      startGate.countDown();
      requestStop(new IllegalStateException(STARTUP_FAILURE_MESSAGE));
      if (lifecycleReserved) {
        awaitConvergence();
      } else {
        converge();
      }
      throw new IllegalStateException(STARTUP_FAILURE_MESSAGE);
    }
    startGate.countDown();
  }

  /** 用其编码所用的输入模式版本写入一帧用户输入；仅当完整写加 flush 成功时 future 才成功。 */
  public CompletableFuture<Void> writeInput(byte[] data, long inputModeRevision) {
    Objects.requireNonNull(data, "data");
    if (data.length < 1 || data.length > MAX_USER_FRAME_BYTES) {
      throw new IllegalArgumentException(
          "terminal input frame must be 1.." + MAX_USER_FRAME_BYTES + " bytes");
    }
    if (inputModeRevision < 1L) {
      throw new IllegalArgumentException("terminal input mode revision must be positive");
    }
    Frame frame = new Frame(data.clone(), inputModeRevision);
    Rejection rejection = offer(frame);
    return rejection == Rejection.NONE ? frame.future : failed(rejection);
  }

  /** 走同一操作 FIFO 调整尺寸：先完成内核 resize，再调整 PTY 窗口；无效尺寸同步拒绝。 */
  public CompletableFuture<Void> resize(int columns, int rows) {
    requireSize(columns, rows);
    ResizeOperation operation = new ResizeOperation(columns, rows);
    Rejection rejection = offer(operation);
    return rejection == Rejection.NONE ? operation.future : failed(rejection);
  }

  /** 捕获当前一致画面；会话结束（自然退出或关闭）后返回保留的末屏。 */
  public CompletableFuture<TerminalView> snapshot() {
    TerminalView captured = finalView;
    if (captured != null) {
      return CompletableFuture.completedFuture(captured);
    }
    Throwable failure = failureRef.get();
    if (failure != null) {
      return CompletableFuture.failedFuture(failure);
    }
    return kernel.snapshot();
  }

  /** 本次收敛完成的只读信号；会话失败时异常完成。 */
  public CompletableFuture<Void> termination() {
    return terminated.copy();
  }

  /** 自然退出码；尚未自然退出时为 {@code null}。 */
  public Integer exitCode() {
    return exitCode;
  }

  /** 保留的有界末屏；无法捕获（如内核失败）时为 {@code null}。 */
  public TerminalView finalView() {
    return finalView;
  }

  /** 幂等且有界地收敛：终止进程范围、释放 PTY 与内核、终结全部未决操作。 */
  @Override
  public void close() {
    requestStop(null);
    awaitConvergence();
  }

  // ------------------------------------------------------------------ 启动与任务

  /** 停在闸门上；被中断也必须继续等待，不能越过尚未装配好的状态。 */
  private static void awaitLatch(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException ignored) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void runReaderGuarded() {
    readerStarted.set(true);
    try {
      runReader();
    } finally {
      readerDone.countDown();
    }
  }

  private void runWriterGuarded() {
    writerStarted.set(true);
    try {
      runWriter();
    } finally {
      writerDone.countDown();
    }
  }

  private void runLifecycle() {
    awaitLatch(startGate);
    awaitLatch(stopSignal);
    converge();
  }

  private void registerKernelTermination() {
    kernel
        .termination()
        .whenComplete(
            (ignored, error) -> {
              if (error != null) {
                requestStop(new IllegalStateException(KERNEL_FAILURE_MESSAGE));
              }
            });
  }

  // ------------------------------------------------------------------ 读取

  private void runReader() {
    awaitLatch(startGate);
    if (aborting) {
      return;
    }
    byte[] buffer = new byte[MAX_USER_FRAME_BYTES];
    try {
      while (!closing) {
        int read = ptyInput.read(buffer, 0, buffer.length);
        if (read < 0) {
          // 真正 EOF：PTY 已排空。退出码由 lifecycle 任务有界读取，避免读任务被长等待拖住。
          ptyEof = true;
          requestStop(null);
          return;
        }
        if (read == 0) {
          continue;
        }
        byte[] chunk = new byte[read];
        System.arraycopy(buffer, 0, chunk, 0, read);
        try {
          // 有界背压：按顺序提交并等待内核受理，形成 reader 侧的自然限速。
          kernel.feed(chunk).get(KERNEL_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          // 被调用方提前中断：必须发停止信号，否则无人收敛会让 termination 永不完成；已在关闭中则无需额外失败。
          onTaskInterrupted();
          return;
        } catch (Exception error) {
          requestStop(new IllegalStateException(READ_FAILURE_MESSAGE));
          return;
        }
      }
    } catch (IOException error) {
      if (!closing) {
        requestStop(new IllegalStateException(READ_FAILURE_MESSAGE));
      }
    }
  }

  // ------------------------------------------------------------------ 写入

  private void runWriter() {
    awaitLatch(startGate);
    while (true) {
      Operation operation;
      synchronized (queueLock) {
        while (queue.isEmpty() && !writerShutdown) {
          try {
            queueLock.wait();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writerShutdown = true;
            // 被调用方提前中断：发停止信号收敛；已在关闭中则无需额外失败，避免忙循环。
            onTaskInterrupted();
          }
        }
        if (writerShutdown || queue.isEmpty()) {
          return;
        }
        operation = queue.removeFirst();
        queuedBytes -= operation.bytes();
        activeOperation = operation;
      }
      try {
        process(operation);
      } finally {
        synchronized (queueLock) {
          activeOperation = null;
          queueLock.notifyAll();
        }
      }
    }
  }

  private void process(Operation operation) {
    boolean runnable;
    synchronized (queueLock) {
      runnable = !closing && !writerShutdown && !operation.future.isDone();
      operation.phase = runnable ? Phase.PREPARING : Phase.DONE;
    }
    if (!runnable) {
      // 关闭或调用方取消：确定未执行。
      operation.future.completeExceptionally(new IllegalStateException(SESSION_CLOSED_MESSAGE));
      return;
    }
    if (operation instanceof Frame frame) {
      processFrame(frame);
    } else {
      resizeNative((ResizeOperation) operation);
    }
  }

  private void processFrame(Frame frame) {
    if (frame.revision() >= 1L && !modeRevisionMatches(frame)) {
      return;
    }
    writeNative(frame);
  }

  /** 用户帧必须以内核 FIFO 屏障看到的最新模式版本为编码依据；不匹配或未通过核验时确定未写。 */
  private boolean modeRevisionMatches(Frame frame) {
    long current;
    try {
      current =
          kernel
              .snapshot()
              .get(KERNEL_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
              .inputModeRevision();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      notExecuted(frame, SESSION_CLOSED_MESSAGE);
      // 模式核验被中断：该帧确定未写；必须发停止信号，否则 writer/interrupt 会持续忙循环。
      onTaskInterrupted();
      return false;
    } catch (Exception error) {
      // 尚未写任何字节：确定未执行；内核失败另行终止会话。
      notExecuted(frame, KERNEL_FAILURE_MESSAGE);
      requestStop(new IllegalStateException(KERNEL_FAILURE_MESSAGE));
      return false;
    }
    if (current != frame.revision()) {
      notExecuted(frame, STALE_MODE_MESSAGE);
      return false;
    }
    return true;
  }

  private void writeNative(Frame frame) {
    ScheduledFuture<?> deadline = scheduleDeadline(() -> onNativeTimeout(frame));
    if (deadline == null) {
      // 尚未进入 native 写：确定未执行；无法建立截止时间即终止会话。
      if (resolve(frame) != null) {
        frame.future.completeExceptionally(new IllegalStateException(DEADLINE_UNAVAILABLE_MESSAGE));
      }
      requestStop(new IllegalStateException(DEADLINE_UNAVAILABLE_MESSAGE));
      return;
    }
    if (!admitNative(frame)) {
      deadline.cancel(false);
      return;
    }
    Throwable failure = null;
    try {
      ptyOutput.write(frame.data());
      ptyOutput.flush();
    } catch (Throwable error) {
      failure = error;
    } finally {
      deadline.cancel(false);
    }
    finishNative(frame, failure);
  }

  private void resizeNative(ResizeOperation operation) {
    ScheduledFuture<?> deadline = scheduleDeadline(() -> onNativeTimeout(operation));
    if (deadline == null) {
      notExecuted(operation, DEADLINE_UNAVAILABLE_MESSAGE);
      requestStop(new IllegalStateException(DEADLINE_UNAVAILABLE_MESSAGE));
      return;
    }
    if (!admitNative(operation)) {
      deadline.cancel(false);
      return;
    }
    try {
      kernel
          .resize(operation.columns(), operation.rows())
          .get(KERNEL_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      boolean stopped;
      synchronized (queueLock) {
        stopped = operation.phase != Phase.WRITING || closing;
      }
      if (stopped) {
        finishNative(operation, new IllegalStateException(SESSION_CLOSED_MESSAGE));
        return;
      }
      scope.resize(operation.columns(), operation.rows());
    } catch (Exception error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      finishNative(operation, error);
      return;
    } finally {
      deadline.cancel(false);
    }
    finishNative(operation, null);
  }

  /**
   * native gate：在关闭仲裁下把操作从尚未进入 native 推进到 native 进行中。
   *
   * <p>返回 {@code false} 表示操作已被关闭或截止时间先行决议，调用方绝不能开始 native。
   */
  private boolean admitNative(Operation operation) {
    synchronized (queueLock) {
      if (operation.phase != Phase.PREPARING) {
        return false;
      }
      if (closing || writerShutdown || operation.future.isDone()) {
        operation.phase = Phase.DONE;
      } else {
        operation.phase = Phase.WRITING;
        return true;
      }
    }
    operation.future.completeExceptionally(new IllegalStateException(SESSION_CLOSED_MESSAGE));
    return false;
  }

  /** native 截止时间触发：只有正在 native 时获胜才致结果不确定；已完成则忽略迟到的截止。 */
  private void onNativeTimeout(Operation operation) {
    Phase previous = resolve(operation);
    if (previous == null) {
      return;
    }
    if (previous == Phase.WRITING) {
      operation.future.completeExceptionally(
          new OutcomeUnknownException(operation.uncertainMessage()));
    } else {
      operation.future.completeExceptionally(
          new IllegalStateException(DEADLINE_UNAVAILABLE_MESSAGE));
    }
    requestStop(new IllegalStateException(operation.uncertainMessage()));
  }

  private void finishNative(Operation operation, Throwable failure) {
    if (resolve(operation) == null) {
      return;
    }
    if (failure == null) {
      operation.future.complete(null);
      return;
    }
    operation.future.completeExceptionally(
        new OutcomeUnknownException(operation.uncertainMessage()));
    requestStop(new IllegalStateException(operation.uncertainMessage()));
  }

  /** 原子地把操作推进到终态；已被其他来源决议时返回 {@code null}。 */
  private Phase resolve(Operation operation) {
    synchronized (queueLock) {
      if (operation.phase == Phase.DONE) {
        return null;
      }
      Phase previous = operation.phase;
      operation.phase = Phase.DONE;
      return previous;
    }
  }

  private void notExecuted(Operation operation, String message) {
    if (resolve(operation) != null) {
      operation.future.completeExceptionally(new IllegalStateException(message));
    }
  }

  private ScheduledFuture<?> scheduleDeadline(Runnable task) {
    try {
      return scheduler.schedule(task, NATIVE_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (RejectedExecutionException rejected) {
      return null;
    }
  }

  // ------------------------------------------------------------------ 入队准入

  private Rejection offer(Operation operation) {
    synchronized (queueLock) {
      if (closing || writerShutdown) {
        return Rejection.CLOSED;
      }
      if (queue.size() + (activeOperation != null ? 1 : 0) >= WRITE_QUEUE_CAPACITY) {
        return Rejection.FULL;
      }
      long activeBytes = activeOperation != null ? activeOperation.bytes() : 0L;
      if (queuedBytes + activeBytes + operation.bytes() > WRITE_QUEUE_MAX_BYTES) {
        return Rejection.BUDGET;
      }
      queue.addLast(operation);
      queuedBytes += operation.bytes();
      queueLock.notifyAll();
      return Rejection.NONE;
    }
  }

  private static CompletableFuture<Void> failed(Rejection rejection) {
    return CompletableFuture.failedFuture(rejectionException(rejection));
  }

  private static IllegalStateException rejectionException(Rejection rejection) {
    return switch (rejection) {
      case FULL -> new IllegalStateException(QUEUE_FULL_MESSAGE);
      case BUDGET -> new IllegalStateException(QUEUE_BUDGET_MESSAGE);
      case CLOSED, NONE -> new IllegalStateException(SESSION_CLOSED_MESSAGE);
    };
  }

  /** 内核查询应答走同一写队列，但不等 VIEW_APPLIED，也不带用户模式；会话进行中入队失败即内核失败。 */
  void onKernelResponse(byte[] data) {
    if (data == null || data.length == 0) {
      return;
    }
    if (data.length > MAX_INTERNAL_FRAME_BYTES) {
      throw new IllegalStateException("terminal response frame exceeds the internal frame budget");
    }
    Rejection rejection = offer(new Frame(data.clone(), 0L));
    if (rejection == Rejection.CLOSED) {
      // 会话正在收敛：应答不再有意义，也不得因此把正常关闭变成内核失败。
      return;
    }
    if (rejection != Rejection.NONE) {
      throw new IllegalStateException("terminal response could not be enqueued");
    }
  }

  // ------------------------------------------------------------------ 收敛

  private void requestStop(Throwable failure) {
    synchronized (queueLock) {
      closing = true;
      if (failure != null) {
        failureRef.compareAndSet(null, failure);
      }
    }
    stopSignal.countDown();
  }

  private void converge() {
    closing = true;
    try {
      cancel(readerTask);
      cancel(writerTask);
      resolveNaturalExit();
      captureFinalView();
      failPending();
      boolean converged = terminateScope();
      closeKernel();
      awaitTask(readerTask, readerStarted, readerDone);
      awaitTask(writerTask, writerStarted, writerDone);
      completeTermination(converged);
    } finally {
      convergeDone.countDown();
    }
  }

  /** 真实 EOF 之后有界读取退出码；读不到即以固定失败收尾，不伪造自然退出，也不阻断其余清理。 */
  private void resolveNaturalExit() {
    if (!ptyEof) {
      return;
    }
    try {
      if (!scope.awaitNaturalExit(NATURAL_EXIT_BUDGET_MILLIS)) {
        failureRef.compareAndSet(null, new IllegalStateException(NATURAL_EXIT_MISSING_MESSAGE));
        return;
      }
      Integer code = scope.naturalExitCode();
      if (code == null) {
        failureRef.compareAndSet(null, new IllegalStateException(NATURAL_EXIT_MISSING_MESSAGE));
        return;
      }
      exitCode = code;
    } catch (RuntimeException error) {
      failureRef.compareAndSet(null, new IllegalStateException(NATURAL_EXIT_MISSING_MESSAGE));
    }
  }

  private void captureFinalView() {
    try {
      finalView = kernel.snapshot().get(KERNEL_OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception error) {
      if (error instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      failureRef.compareAndSet(null, new IllegalStateException(CAPTURE_FAILURE_MESSAGE));
    }
  }

  private void failPending() {
    List<Operation> pending = new ArrayList<>();
    synchronized (queueLock) {
      writerShutdown = true;
      Operation operation;
      while ((operation = queue.pollFirst()) != null) {
        queuedBytes -= operation.bytes();
        pending.add(operation);
      }
      if (activeOperation != null) {
        pending.add(activeOperation);
      }
      queueLock.notifyAll();
    }
    for (Operation operation : pending) {
      Phase previous = resolve(operation);
      if (previous == null) {
        continue;
      }
      if (previous == Phase.WRITING) {
        // 已进入 native：可能已有前缀或部分变更，结果不确定。
        operation.future.completeExceptionally(
            new OutcomeUnknownException(operation.uncertainMessage()));
      } else {
        operation.future.completeExceptionally(new IllegalStateException(SESSION_CLOSED_MESSAGE));
      }
    }
  }

  private boolean terminateScope() {
    boolean converged = false;
    try {
      converged = scope.terminate();
    } catch (RuntimeException error) {
      recordCleanupFailure(CONVERGENCE_FAILURE_MESSAGE);
    }
    try {
      scope.close();
    } catch (RuntimeException error) {
      recordCleanupFailure(CONVERGENCE_FAILURE_MESSAGE);
      converged = false;
    }
    return converged;
  }

  private void closeKernel() {
    try {
      kernel.close();
    } catch (RuntimeException error) {
      recordCleanupFailure(KERNEL_CLOSE_FAILURE_MESSAGE);
    }
  }

  private void cancel(Future<?> task) {
    if (task != null) {
      task.cancel(true);
    }
  }

  /** 等待一个已启动的读写任务真正结束；未启动（取消赢得竞争）的任务不必等待。 */
  private void awaitTask(Future<?> task, AtomicBoolean started, CountDownLatch done) {
    if (task == null || !started.get()) {
      return;
    }
    try {
      if (!done.await(TASK_JOIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        recordCleanupFailure(TASK_FAILURE_MESSAGE);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      recordCleanupFailure(TASK_FAILURE_MESSAGE);
    }
  }

  private void completeTermination(boolean converged) {
    if (!converged) {
      recordCleanupFailure(CONVERGENCE_FAILURE_MESSAGE);
    }
    Throwable failure = failureRef.get();
    if (failure != null) {
      terminated.completeExceptionally(failure);
    } else {
      terminated.complete(null);
    }
  }

  /** 统一有界等待本次收敛完成；被中断或超时都显式失败，绝不宣称已经收敛。 */
  private void awaitConvergence() {
    try {
      if (!convergeDone.await(CONVERGE_WAIT_SECONDS, TimeUnit.SECONDS)) {
        throw new IllegalStateException(CLOSE_TIMEOUT_MESSAGE);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(CLOSE_INTERRUPTED_MESSAGE);
    }
    Throwable failure = cleanupFailure.get();
    if (failure != null) {
      throw new IllegalStateException(failure.getMessage());
    }
  }

  /** 任务被调用方提前中断：发停止信号收敛；已在关闭中则无需额外失败。 */
  private void onTaskInterrupted() {
    if (!closing) {
      requestStop(new IllegalStateException(TASK_INTERRUPTED_MESSAGE));
    }
  }

  private void recordCleanupFailure(String message) {
    IllegalStateException error = new IllegalStateException(message);
    cleanupFailure.compareAndSet(null, error);
    failureRef.compareAndSet(null, error);
  }

  // ------------------------------------------------------------------ 校验

  private static void requireSize(int columns, int rows) {
    if (columns < TerminalLimits.MIN_COLUMNS
        || columns > TerminalLimits.MAX_COLUMNS
        || rows < TerminalLimits.MIN_ROWS
        || rows > TerminalLimits.MAX_ROWS) {
      throw new IllegalArgumentException(
          "terminal size must be "
              + TerminalLimits.MIN_COLUMNS
              + ".."
              + TerminalLimits.MAX_COLUMNS
              + " columns and "
              + TerminalLimits.MIN_ROWS
              + ".."
              + TerminalLimits.MAX_ROWS
              + " rows");
    }
  }

  private static void requireHistory(int maxHistoryLines) {
    if (maxHistoryLines < 0 || maxHistoryLines > TerminalLimits.MAX_HISTORY_LINES) {
      throw new IllegalArgumentException(
          "terminal history must be 0.." + TerminalLimits.MAX_HISTORY_LINES + " lines");
    }
  }

  // ------------------------------------------------------------------ 操作模型

  /** 单次 native 操作的阶段；除 QUEUED 外全部在 {@link #queueLock} 下推进。 */
  private enum Phase {
    QUEUED,
    PREPARING,
    WRITING,
    DONE
  }

  private enum Rejection {
    NONE,
    CLOSED,
    FULL,
    BUDGET
  }

  /** 写队列中的一项：用户/内部写入帧或尺寸调整；字节预算对 resize 记为 0。 */
  private abstract static class Operation {

    final CompletableFuture<Void> future = new CompletableFuture<>();

    Phase phase = Phase.QUEUED;

    abstract int bytes();

    abstract String uncertainMessage();
  }

  private static final class Frame extends Operation {

    private final byte[] data;
    private final long revision;

    Frame(byte[] data, long revision) {
      this.data = data;
      this.revision = revision;
    }

    byte[] data() {
      return data;
    }

    long revision() {
      return revision;
    }

    @Override
    int bytes() {
      return data.length;
    }

    @Override
    String uncertainMessage() {
      return WRITE_UNCERTAIN_MESSAGE;
    }
  }

  private static final class ResizeOperation extends Operation {

    private final int columns;
    private final int rows;

    ResizeOperation(int columns, int rows) {
      this.columns = columns;
      this.rows = rows;
    }

    int columns() {
      return columns;
    }

    int rows() {
      return rows;
    }

    @Override
    int bytes() {
      return 0;
    }

    @Override
    String uncertainMessage() {
      return RESIZE_UNCERTAIN_MESSAGE;
    }
  }

  /** 写入结果不确定：可能已有前缀进入 PTY，调用方不得重放。 */
  public static final class OutcomeUnknownException extends IllegalStateException {

    OutcomeUnknownException(String message) {
      super(message);
    }
  }
}
