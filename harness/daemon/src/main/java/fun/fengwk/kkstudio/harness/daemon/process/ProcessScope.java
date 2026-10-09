package fun.fengwk.kkstudio.harness.daemon.process;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import com.pty4j.windows.conpty.WinConPtyProcess;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次调用的操作系统级执行范围，父进程侧的唯一入口。
 *
 * <p>{@link #start(Path, List, StartGate)} 启动独立的 scope helper JVM：helper 先取得 OS 所有权（POSIX 新 session
 * 与进程组、 Windows 命名 Job），再等父进程确认，然后才让用户命令运行。因此父进程不需要枚举后代，也不需要 {@code ProcessHandle.descendants()}
 * 这类「看运气」的收敛依据。
 *
 * <p>顺序是这里唯一的安全性来源：范围确认 -> 父进程登记自己的收敛手段（POSIX 会话 id / Windows Job 句柄）-> 放行用户命令。所以
 * 「许可之前失败」永远等价于「用户命令没有产生任何副作用」，此时直接强杀 helper 是安全的；许可之后则一律走完整收敛。scope id 在使用前必须校验：POSIX 侧它必须等于
 * helper 自己的 pid（helper 是会话 leader，因此 scope id 同时是会话 id），否则父进程 宁可失败也不向一个不属于本次调用的会话发信号。
 *
 * <p>标准流有三种明确模式：{@link Stdio#CAPTURE}（命令 stdin 是空管道、stderr 合并进 stdout，父进程按捕获管道排空）、{@link
 * Stdio#DUPLEX}（命令 stdin/stdout/stderr 继承 helper 的三条流，供常驻双向协议）与 {@link Stdio#PTY}（命令在 pty4j
 * 伪终端中运行，父进程持有 {@link PtyProcess} 主端并可 {@link #resize(int, int)}）。三者共用同一条启动准入、退出、终止与收敛链路。
 *
 * <p>helper 命令行只携带固定入口与调用私有的状态目录；启动规格（argv 与 workdir）通过状态目录里的私有控制文件交接、读取后立即 删除，因此命令行、pty4j
 * 线程名与诊断都不会带上启动参数。状态目录由父进程独占创建。
 *
 * <p>收敛语义：POSIX 对**整个会话**先温和后强杀（交互 shell 会建立多个作业进程组，单组收敛会漏组），并确认会话里没有活着的成员 （僵尸不算）；Windows 显式 {@code
 * TerminateJobObject} 并等到 Job 报告没有活动进程。收敛失败一律显式传播（{@link #terminate()} 返回 {@code false}），绝不声称已经收敛。
 */
public final class ProcessScope implements AutoCloseable {

  /** 父进程私有状态目录前缀：定位残留时一眼可辨，且不落在数据目录内。 */
  public static final String STATE_DIR_PREFIX = "kk-studio-daemon-process-scope-";

  /**
   * 标准流模式：用户命令的 stdin/stdout/stderr 接到哪里。
   *
   * <p>{@link #CAPTURE} 是默认：命令的 stdin 是一条只有 helper 持有写端的空管道（命令因此读到确定性 EOF），stderr 合并进
   * stdout，父进程按捕获管道排空——「命令的输出就是调用结果」的模式。
   *
   * <p>{@link #DUPLEX} 供常驻双向协议（LSP）：命令的 stdin/stdout/stderr 直接继承 helper 自己的三条流，stderr 保持独立，
   * 父进程因此可以像直接 {@code ProcessBuilder.start()} 那样与命令对话。
   *
   * <p>{@link #PTY} 供交互终端：helper JVM 由 pty4j 放入伪终端，命令继承同一终端；父进程持有 {@link PtyProcess} 主端，输入/
   * 输出/resize 直接作用于该 PTY。
   */
  enum Stdio {
    CAPTURE,
    DUPLEX,
    PTY
  }

  /** 把 stdio 模式转发给 helper 的属性名。 */
  static final String STDIO_PROPERTY = "kk-studio.process-scope.stdio";

  /** 启动许可：父进程在确认范围之后、放行用户命令之前询问调用方是否仍然允许启动。 */
  public interface StartGate {

    boolean allowStart();
  }

  private static final StartGate ALWAYS_ALLOW = () -> true;

  /** 等待 helper 发布 scope 的预算：覆盖 helper JVM 冷启动与 JNA 载入。 */
  private static final long PREPARE_BUDGET_MILLIS = 20_000;

  /** 温和信号之后的宽限窗口：短到不拖慢收尾，长到足以让命令自己的清理跑完。 */
  private static final long TERMINATION_GRACE_MILLIS = 150;

  /** 命令已经自然退出时，留给 helper 自己完成整会话收敛的时间。 */
  private static final long HELPER_CONVERGENCE_BUDGET_MILLIS = 1_000;

  /** helper 退出与范围收敛的预算。 */
  private static final long CONVERGENCE_BUDGET_MILLIS = 2_000;

  /** 还没有用户命令时强杀 keeper 的等待预算：它已经忽略温和信号，不需要为它保留收敛时间。 */
  private static final long HELPER_FORCE_EXIT_BUDGET_MILLIS = 500;

  /** 轮询步长：足够细，使正常收敛不必白等整个预算。 */
  private static final long POLL_INTERVAL_MILLIS = 5;

  /** 并发终止等待同一次收敛的上限。 */
  private static final long TERMINATION_WAIT_MILLIS =
      PREPARE_BUDGET_MILLIS + TERMINATION_GRACE_MILLIS + 4 * CONVERGENCE_BUDGET_MILLIS;

  /** 失败说明里附带的 helper 诊断长度上限。 */
  private static final int DIAGNOSTICS_LIMIT = 400;

  /** 显式要求给 helper 挂覆盖率代理的开关：默认关闭，避免改变正常调用与测试的启动开销。 */
  private static final String HELPER_COVERAGE_PROPERTY = "kk-studio.process-scope.helper-coverage";

  /**
   * 仅测试使用的派生闸门：显式设置时转发给 helper，让它在启动命令之前等待释放文件。
   *
   * <p>用来确定性地构造「父进程取消与 helper 派生命令同时发生」这一窗口，生产路径不设置该属性。
   */
  static final String SPAWN_LATCH_PROPERTY = "kk-studio.process-scope.spawn-latch";

  private static final String HELPER_COVERAGE_FILE = "jacoco-helper.exec";

  private final Process helper;
  private final Path stateDir;
  private final boolean windows;
  private final PtyProcess ptyProcess;
  private final AtomicBoolean terminationStarted = new AtomicBoolean();
  private final CountDownLatch terminationDone = new CountDownLatch(1);
  private volatile WindowsJobScope jobScope;
  private volatile boolean jobDrained;
  private volatile boolean convergenceProven;
  private volatile long scopeId = -1;
  private volatile boolean scopeEstablished;

  private ProcessScope(Process helper, Path stateDir, boolean windows, PtyProcess ptyProcess) {
    this.helper = helper;
    this.stateDir = stateDir;
    this.windows = windows;
    this.ptyProcess = ptyProcess;
  }

  /**
   * 在给定 workdir 中启动 {@code command}，返回已经确认 OS 范围建立的范围对象。
   *
   * <p>范围未确认、helper 提前退出、平台不支持或调用方在启动阶段取消时失败关闭：用户命令不会启动，状态目录也不会残留。
   */
  public static ProcessScope start(Path workdir, List<String> command) throws IOException {
    return start(workdir, command, ALWAYS_ALLOW);
  }

  public static ProcessScope start(Path workdir, List<String> command, StartGate gate)
      throws IOException {
    return start(workdir, command, gate, Stdio.CAPTURE);
  }

  /**
   * 以双向标准流启动 {@code command}：命令的 stdin/stdout/stderr 继承本次调用持有的管道，供常驻协议收发消息。
   *
   * <p>其余语义与 {@link #start(Path, List)} 完全一致——许可之前失败仍然等价于「命令没有运行过」，收敛仍然覆盖整个会话（含后代）。
   */
  public static ProcessScope startDuplex(Path workdir, List<String> command) throws IOException {
    return startDuplex(workdir, command, ALWAYS_ALLOW);
  }

  public static ProcessScope startDuplex(Path workdir, List<String> command, StartGate gate)
      throws IOException {
    return start(workdir, command, gate, Stdio.DUPLEX);
  }

  /**
   * 以伪终端启动 {@code command}：helper JVM 由 pty4j 放入 PTY，命令继承同一终端；父进程持有 {@link PtyProcess} 主端。
   *
   * <p>{@code environment} 作为 helper（进而命令）继承的环境；{@code initialColumns}/{@code initialRows}
   * 是初始窗口尺寸。其余 语义与捕获模式一致：许可之前失败等价于「命令没有运行过」，收敛覆盖整个会话。仅 Linux/macOS/Windows 支持；Windows 必须得到
   * ConPTY，绝不接受 pty4j 静默退回的 WinPTY。
   */
  public static ProcessScope startPty(
      Path workdir,
      List<String> command,
      int initialColumns,
      int initialRows,
      Map<String, String> environment,
      StartGate gate)
      throws IOException {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(environment, "environment");
    if (initialColumns <= 0 || initialRows <= 0) {
      throw new IllegalArgumentException(
          "initial PTY size must be positive: " + initialColumns + "x" + initialRows);
    }
    boolean windows =
        DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS;
    Path stateDir = Files.createTempDirectory(STATE_DIR_PREFIX);
    PtyProcess pty;
    try {
      // 启动规格（workdir 与 argv）只经状态目录交接，绝不进入 pty4j 的线程名或命令行。
      ProcessScopeState.publishLaunch(stateDir, workdir, command);
      PtyProcessBuilder builder =
          new PtyProcessBuilder(helperArgumentVector(stateDir, Stdio.PTY).toArray(String[]::new))
              .setEnvironment(environment)
              .setInitialColumns(initialColumns)
              .setInitialRows(initialRows)
              // 单一终端：stderr 与 stdout 落在同一条伪终端上，命令只看到一个控制终端。
              .setRedirectErrorStream(true)
              .setConPtyInheritCursor(false);
      if (windows) {
        // 明确请求 ConPTY；不允许 pty4j 静默退回 WinPTY。
        builder.setUseWinConPty(true);
      }
      pty = builder.start();
    } catch (IOException | RuntimeException error) {
      ProcessScopeState.deleteQuietly(stateDir);
      throw error;
    }
    return finishStart(stateDir, pty, pty, windows, gate);
  }

  private static ProcessScope start(Path workdir, List<String> command, StartGate gate, Stdio stdio)
      throws IOException {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stdio, "stdio");
    boolean windows =
        DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS;
    Path stateDir = Files.createTempDirectory(STATE_DIR_PREFIX);
    Process helper;
    try {
      helper = spawnHelper(stateDir, workdir, command, windows, stdio);
    } catch (IOException | RuntimeException error) {
      ProcessScopeState.deleteQuietly(stateDir);
      throw error;
    }
    return finishStart(stateDir, helper, null, windows, gate);
  }

  /**
   * 启动准入与清理的唯一收口：两条启动路径（管道/双向、PTY）只在「如何取得 helper 进程」上不同。
   *
   * <p>顺序与清理只在这里实现一次：先校验 PTY 平台对象（Windows 必须是 ConPTY），再确认范围并放行命令；任何失败都走同一套收敛 （终止 helper、释放 PTY
   * 主端描述符、删除状态目录），绝不留下半个调用，也绝不 destroy 之后立刻删目录而跳过等待。
   */
  private static ProcessScope finishStart(
      Path stateDir, Process helper, PtyProcess pty, boolean windows, StartGate gate)
      throws IOException {
    ProcessScope scope = new ProcessScope(helper, stateDir, windows, pty);
    try {
      if (pty != null && windows && !(pty instanceof WinConPtyProcess)) {
        // 退回对象已经创建了一个从未获得许可的 helper：显式报告不支持，绝不让 WinPTY 实现投入服务。
        throw new IOException(
            "the platform returned "
                + pty.getClass().getName()
                + " instead of ConPTY; refusing the WinPTY fallback");
      }
      scope.awaitScope(gate);
    } catch (RuntimeException | IOException error) {
      // 范围可能已经建立（命令可能已经运行），因此统一走收敛路径；未建立时它只是强杀 keeper。
      scope.close();
      throw error;
    }
    return scope;
  }

  /**
   * 父进程侧唯一的 helper 命令行构造：只有固定入口与调用私有的状态目录，启动规格不进入命令行。
   *
   * <p>拿不到 daemon 类路径时以 {@link IOException} 失败关闭（与「helper 无法启动」同一条通道），绝不启动一个无法承载 helper 的 JVM。
   */
  private static List<String> helperArgumentVector(Path stateDir, Stdio stdio) throws IOException {
    String classpath = System.getProperty("java.class.path");
    if (classpath == null || classpath.isBlank()) {
      throw new IOException(
          "cannot locate the daemon classpath required to launch the process scope helper");
    }
    boolean windows =
        DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS;
    List<String> argv = new ArrayList<>();
    argv.add(javaBinary(windows));
    // helper 的输出必须只有用户命令的输出：关掉 JVM 统一日志，并容忍陌生 VM 选项。
    argv.add("-XX:+IgnoreUnrecognizedVMOptions");
    argv.add("-XX:TieredStopAtLevel=1");
    argv.add("-Xlog:disable");
    argv.addAll(helperCoverageArguments(stateDir));
    argv.addAll(spawnLatchArguments());
    argv.addAll(stdioArguments(stdio));
    argv.add("-cp");
    argv.add(classpath);
    argv.add(ProcessScopeHelper.class.getName());
    argv.add(stateDir.toString());
    return argv;
  }

  private static Process spawnHelper(
      Path stateDir, Path workdir, List<String> command, boolean windows, Stdio stdio)
      throws IOException {
    ProcessScopeState.publishLaunch(stateDir, workdir, command);
    List<String> argv = helperArgumentVector(stateDir, stdio);
    ProcessBuilder builder = new ProcessBuilder(argv);
    if (stdio == Stdio.DUPLEX) {
      // 双向模式下 helper 的 stderr 必须是管道：命令继承它，父进程按需排空（helper 自己的 Java 输出仍然写调用私有的诊断
      // 文件，因此不会混进命令的 stderr）。
      builder.redirectError(ProcessBuilder.Redirect.PIPE);
    } else {
      // 捕获模式下 helper 的 stderr（JVM 启动提示、诊断）只进调用私有的诊断文件，绝不混进用户命令的输出。
      builder.redirectError(stateDir.resolve(ProcessScopeState.DIAGNOSTICS_FILE).toFile());
    }
    return builder.start();
  }

  /** 把 stdio 模式转发给 helper；捕获模式（默认）不传参数。 */
  private static List<String> stdioArguments(Stdio stdio) {
    return switch (stdio) {
      case DUPLEX -> List.of("-D" + STDIO_PROPERTY + "=duplex");
      case PTY -> List.of("-D" + STDIO_PROPERTY + "=pty");
      case CAPTURE -> List.of();
    };
  }

  private static String javaBinary(boolean windows) {
    return Path.of(System.getProperty("java.home"))
        .resolve("bin")
        .resolve(windows ? "java.exe" : "java")
        .toString();
  }

  /** {@link #SPAWN_LATCH_PROPERTY} 设置时把闸门目录转发给 helper；未设置时参数里什么都不加。 */
  private static List<String> spawnLatchArguments() {
    String latch = System.getProperty(SPAWN_LATCH_PROPERTY);
    return latch == null || latch.isBlank()
        ? List.of()
        : List.of("-D" + SPAWN_LATCH_PROPERTY + "=" + latch);
  }

  /** helper 的进程对象：父进程只从它读取用户命令的输出并等待收敛。 */
  public Process process() {
    return helper;
  }

  /**
   * 调整 PTY 窗口尺寸；只对 {@link Stdio#PTY} 调用有效。
   *
   * <p>PTY 的从端会因此收到 {@code SIGWINCH}，交互进程据此更新行列。非 PTY 模式调用会显式失败。
   */
  public void resize(int columns, int rows) {
    if (ptyProcess == null) {
      throw new IllegalStateException("resize is only available for the PTY stdio mode");
    }
    if (columns <= 0 || rows <= 0) {
      throw new IllegalArgumentException("PTY size must be positive: " + columns + "x" + rows);
    }
    ptyProcess.setWinSize(new WinSize(columns, rows));
  }

  /** 用户命令的自然退出码；没有发布过就返回 {@code null}（超时、取消或 helper 异常）。 */
  public Integer naturalExitCode() {
    String value = ProcessScopeState.read(stateDir, ProcessScopeState.EXIT_FILE);
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException error) {
      throw new IllegalStateException(
          "process scope helper published an unreadable exit code: " + value, error);
    }
  }

  /**
   * 有界等待用户命令自己发布退出码；返回它是否已经退出。
   *
   * <p>命令退出不等于 keeper 退出：keeper 还要收敛范围（含后代）才会结束，因此「命令是否已经退出」只能看命令自己发布的退出码， 不能拿 helper 的 {@code
   * isAlive()} 当依据。keeper 已经结束时同样返回 {@code true}——那时命令不可能还在运行。
   */
  public boolean awaitNaturalExit(long millis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
    while (true) {
      if (naturalExitCode() != null || !helper.isAlive()) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      sleepQuietly(POLL_INTERVAL_MILLIS);
    }
  }

  /** helper 报告的失败原因；没有失败时返回 {@code null}。 */
  public String startFailure() {
    return ProcessScopeState.read(stateDir, ProcessScopeState.ERROR_FILE);
  }

  /** 范围是否已经确认建立；未确认时一定没有任何用户命令被启动。 */
  boolean scopeEstablished() {
    return scopeEstablished;
  }

  /** 调用私有的状态目录；{@link #close()} 之后必须不存在。 */
  Path stateDirectory() {
    return stateDir;
  }

  /**
   * 收敛整个范围，并返回「范围里不再有任何活着的成员」这一事实是否已经被证明。
   *
   * <p>幂等且有界；并发调用者等待同一次收敛，等待超时或被中断时同样返回 {@code false}——收敛结果绝不因为「等不到」而被当成 成功。
   */
  public boolean terminate() {
    if (terminationStarted.compareAndSet(false, true)) {
      try {
        convergenceProven = stop();
      } finally {
        terminationDone.countDown();
      }
      return convergenceProven;
    }
    if (!awaitTerminationDone()) {
      return false;
    }
    return convergenceProven;
  }

  /** 范围是否已经收敛；已建立的范围绝不因为「发过信号」或「首个进程退出」就算收敛。 */
  boolean converged() {
    if (!scopeEstablished) {
      return true;
    }
    if (windows) {
      WindowsJobScope job = jobScope;
      return job != null ? job.awaitEmpty(0) : jobDrained;
    }
    return !PosixProcessSession.hasLiveMember(scopeId, PosixProcessSession.NO_PROCESS);
  }

  @Override
  public void close() {
    try {
      terminate();
    } finally {
      try {
        releasePty();
      } finally {
        collectHelperCoverage();
        ProcessScopeState.deleteQuietly(stateDir);
      }
    }
  }

  /**
   * 释放 PTY 主端资源。
   *
   * <p>pty4j 明确要求即使命令已经结束也要调用 {@code destroy()} 来释放主端文件描述符；三条流也在这里关闭，因此 PTY 模式的描述符 与流生命周期都有确定的
   * finally 收口。非 PTY 模式是空操作。
   */
  private void releasePty() {
    if (ptyProcess == null) {
      return;
    }
    closeQuietly(ptyProcess.getInputStream());
    closeQuietly(ptyProcess.getOutputStream());
    closeQuietly(ptyProcess.getErrorStream());
    ptyProcess.destroy();
  }

  /**
   * 确认范围、登记父进程自己的收敛手段，然后放行用户命令。
   *
   * <p>每一步的顺序都有意义：id 校验与 Windows 句柄登记都在许可之前，因此父进程拿到许可时一定已经拥有「终止并证明整组 结束」的能力。
   */
  private void awaitScope(StartGate gate) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PREPARE_BUDGET_MILLIS);
    while (true) {
      Long published = readScopeId();
      if (published != null) {
        validateScopeId(published);
        if (windows) {
          jobScope = WindowsJobScope.attach(WindowsJobScope.jobNameFor(stateDir));
        }
        scopeId = published;
        scopeEstablished = true;
        if (!gate.allowStart()) {
          throw new IllegalStateException(
              "the process scope was cancelled before the user command was started");
        }
        ProcessScopeState.publish(
            stateDir, ProcessScopeState.PERMIT_FILE, Long.toString(published));
        return;
      }
      if (!helper.isAlive()) {
        throw new IllegalStateException(startupFailure());
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "the process scope helper did not establish the OS scope within "
                + PREPARE_BUDGET_MILLIS
                + " ms");
      }
      sleepQuietly(POLL_INTERVAL_MILLIS);
    }
  }

  /**
   * scope id 的正数校验，以及 POSIX 侧的「必须是 helper 自己」校验。
   *
   * <p>状态文件被破坏时不能变成向一个陌生会话发信号的依据：POSIX 上 scope id 就是 helper 的 pid（helper 是会话 leader， 因此它同时是会话
   * id），只有等于 helper 的 pid 才可能是本次调用刚建立的会话。
   */
  private void validateScopeId(long published) {
    if (published <= 0) {
      throw new IllegalStateException(
          "the process scope helper published an invalid scope id: " + published);
    }
    if (!windows && published != helper.pid()) {
      throw new IllegalStateException(
          "the process scope helper published a scope id that is not its own session: "
              + published);
    }
  }

  private String startupFailure() {
    String reported = startFailure();
    if (reported != null) {
      return reported;
    }
    String message =
        "the process scope helper exited with code "
            + helper.exitValue()
            + " before establishing the OS scope";
    String diagnostics = ProcessScopeState.read(stateDir, ProcessScopeState.DIAGNOSTICS_FILE);
    if (diagnostics == null || diagnostics.isBlank()) {
      return message;
    }
    return message
        + ": "
        + (diagnostics.length() > DIAGNOSTICS_LIMIT
            ? diagnostics.substring(0, DIAGNOSTICS_LIMIT) + "..."
            : diagnostics);
  }

  /** 终止范围的唯一实现；返回范围是否已经收敛。 */
  private boolean stop() {
    if (!scopeEstablished) {
      // 没有放行就没有用户命令，helper 也没有需要收敛的后代：直接强杀，不为它等满整个收敛预算。
      // Windows 上此时 Job 里只可能有一个从未恢复执行的挂起进程，helper 失败关闭时会自己结束它。
      return forceKillHelper();
    }
    boolean drained = windows ? drainJob() : drainSession();
    if (!awaitHelperExit(CONVERGENCE_BUDGET_MILLIS)) {
      helper.destroyForcibly();
      awaitHelperExit(CONVERGENCE_BUDGET_MILLIS);
    }
    return drained && awaitConvergence(CONVERGENCE_BUDGET_MILLIS);
  }

  /** 强杀 keeper 并等它消失：进程已经不在时是空操作，重复调用不会延长等待。 */
  private boolean forceKillHelper() {
    helper.destroyForcibly();
    if (awaitHelperExit(HELPER_FORCE_EXIT_BUDGET_MILLIS)) {
      return true;
    }
    helper.destroyForcibly();
    return awaitHelperExit(HELPER_FORCE_EXIT_BUDGET_MILLIS);
  }

  /**
   * keeper 已经退出之后的收敛：会话 id 与 pid 都可能已经被系统复用，因此绝不盲广播，而是「重新核验身份之后逐个强杀」，直到没有 活着的成员。
   *
   * <p>只做只读确认会把「keeper 先死、后代还在」当成失败并留下资源；但照着快照直接发信号又可能杀到一个复用了同一个 pid 的无关
   * 进程。这里两者都不做：每个成员在发信号之前都要重新确认「它还活着、它仍然属于本次会话、它的启动时刻与快照一致」。
   */
  private boolean convergedAfterHelperExit() {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONVERGENCE_BUDGET_MILLIS);
    while (true) {
      List<PosixProcessSession.GroupMember> members =
          PosixProcessSession.membersSnapshot(scopeId, PosixProcessSession.NO_PROCESS);
      if (members == null) {
        return !PosixProcessSession.hasLiveMember(scopeId, PosixProcessSession.NO_PROCESS);
      }
      if (members.isEmpty()) {
        return true;
      }
      for (PosixProcessSession.GroupMember member : members) {
        killVerifiedMember(member);
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      sleepQuietly(POLL_INTERVAL_MILLIS);
    }
  }

  /**
   * 强制结束一个成员，但只在它的身份被重新核验通过时动手。
   *
   * <p>快照与强杀之间存在时间差，pid 可能已经被回收并分配给别的进程；因此这里同时核验「所属会话仍然是本次范围」与 「启动时刻与快照一致」——前者排除同 pid 换了会话的情况，后者排除同
   * pid 换了进程的情况。核验不通过就不发信号：宁可报告未收敛，也绝不杀错进程。
   */
  void killVerifiedMember(PosixProcessSession.GroupMember member) {
    ProcessHandle handle = ProcessHandle.of(member.pid()).orElse(null);
    if (handle == null) {
      return;
    }
    Instant start = PosixProcessSession.processStart(member.pid());
    if (start == null || !start.equals(member.start())) {
      return;
    }
    if (PosixProcessSession.sessionOf(member.pid()) != scopeId) {
      return;
    }
    handle.destroyForcibly();
  }

  /**
   * POSIX 整会话收敛：命令已经自然退出时先让 helper 完成它自己的收尾，否则由父进程对会话发温和信号并在宽限后升级。
   *
   * <p>helper 存活时父进程始终可以广播会话信号（keeper 自己会活到 shutdown hook 收尾完成）；helper 一旦退出就改走 {@link
   * #convergedAfterHelperExit()}。
   */
  private boolean drainSession() {
    if (ProcessScopeState.read(stateDir, ProcessScopeState.EXIT_FILE) != null) {
      awaitHelperExit(HELPER_CONVERGENCE_BUDGET_MILLIS);
    }
    if (!helper.isAlive()) {
      return convergedAfterHelperExit();
    }
    PosixProcessSession.signalSession(scopeId, PosixProcessSession.SIGTERM);
    if (!awaitConvergence(TERMINATION_GRACE_MILLIS) && helper.isAlive()) {
      PosixProcessSession.signalSession(scopeId, PosixProcessSession.SIGKILL);
    }
    return true;
  }

  /**
   * Windows 整组收敛：显式终止 Job 并等到它报告没有活动进程，只有拿到这个证据才关闭父进程这一侧的句柄。
   *
   * <p>「首个进程退出」或「helper 退出」都不是整组结束的证明，因此 Job 句柄不能被句柄关闭语义代替。
   */
  private boolean drainJob() {
    WindowsJobScope job = jobScope;
    if (job == null) {
      return false;
    }
    jobDrained = job.terminateAndDrain(CONVERGENCE_BUDGET_MILLIS);
    job.close();
    jobScope = null;
    return jobDrained;
  }

  private boolean awaitTerminationDone() {
    try {
      return terminationDone.await(TERMINATION_WAIT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private boolean awaitHelperExit(long budgetMillis) {
    try {
      return helper.waitFor(budgetMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return !helper.isAlive();
    }
  }

  private boolean awaitConvergence(long budgetMillis) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    while (!converged()) {
      if (System.nanoTime() >= deadline) {
        return false;
      }
      sleepQuietly(POLL_INTERVAL_MILLIS);
    }
    return true;
  }

  private Long readScopeId() {
    String value = ProcessScopeState.read(stateDir, ProcessScopeState.SCOPE_FILE);
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException error) {
      throw new IllegalStateException(
          "process scope helper published an unreadable scope id: " + value, error);
    }
  }

  /**
   * 父进程自己带覆盖率代理且显式打开 helper 覆盖率时，给 helper 挂同一个代理并写到调用私有的文件。
   *
   * <p>helper 的逻辑是执行范围的核心，不能因为「在另一个 JVM 里」就没有覆盖数据；默认关闭是为了不让正常调用承担插桩开销。
   */
  private static List<String> helperCoverageArguments(Path stateDir) {
    if (!Boolean.getBoolean(HELPER_COVERAGE_PROPERTY)) {
      return List.of();
    }
    String argument = jacocoAgentArgument();
    if (argument == null) {
      return List.of();
    }
    String agent = argument.substring("-javaagent:".length());
    int options = agent.indexOf('=');
    String jar = options < 0 ? agent : agent.substring(0, options);
    return List.of(
        "-javaagent:"
            + jar
            + "=destfile="
            + stateDir.resolve(HELPER_COVERAGE_FILE)
            + ",append=false,dumponexit=true");
  }

  /** 把 helper 的覆盖数据搬到父进程覆盖数据旁边，便于合并报告；任何失败都不影响调用结果。 */
  private void collectHelperCoverage() {
    Path exec = stateDir.resolve(HELPER_COVERAGE_FILE);
    if (!Files.isRegularFile(exec)) {
      return;
    }
    Path directory = helperCoverageDirectory();
    if (directory == null) {
      return;
    }
    try {
      Files.createDirectories(directory);
      Files.copy(
          exec,
          directory.resolve(stateDir.getFileName() + ".exec"),
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException ignored) {
      // 覆盖数据只是度量工具：收集失败不影响调用结果。
    }
  }

  /** 父进程自己的 JaCoCo 代理参数；没有代理时返回 {@code null}。 */
  private static String jacocoAgentArgument() {
    for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      if (argument.startsWith("-javaagent:") && argument.contains("jacoco")) {
        return argument;
      }
    }
    return null;
  }

  /** helper 覆盖数据的落地目录：父进程覆盖数据文件旁边的 {@code jacoco-helper/}。 */
  private static Path helperCoverageDirectory() {
    String argument = jacocoAgentArgument();
    if (argument == null) {
      return null;
    }
    int destfile = argument.indexOf("destfile=");
    if (destfile < 0) {
      return null;
    }
    String value = argument.substring(destfile + "destfile=".length());
    int end = value.indexOf(',');
    Path parent = Path.of(end < 0 ? value : value.substring(0, end)).toAbsolutePath().getParent();
    return parent == null ? null : parent.resolve("jacoco-helper");
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  /** 关闭流时忽略失败：收尾阶段不能因为「已经关闭」而中断资源释放。 */
  private static void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // 已经关闭或对端已消失都等价于「不需要再关闭」。
    }
  }

  private static void closeQuietly(OutputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
      // 同上：释放描述符的失败不改变调用结果。
    }
  }
}
