package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次调用的操作系统级执行范围，父进程侧的唯一入口。
 *
 * <p>{@link #start(Path, List, StartGate)} 让独立的 scope helper JVM 先取得 OS 所有权（POSIX 新 session 与进程组、
 * Windows 命名 Job），再等父进程确认，然后才让用户命令运行。因此父进程不需要枚举后代，也不需要 {@code ProcessHandle.descendants()}
 * 这类「看运气」的收敛依据。「怎样把 helper 拉起来」（JVM 命令、标准流接线、覆盖率注入）属于 {@link ProcessScopeHelperLauncher}，本类只看启动结果。
 *
 * <p>顺序是这里唯一的安全性来源：范围确认 -> 父进程登记自己的收敛手段（POSIX 进程组 id / Windows Job 句柄）-> 放行用户
 * 命令。所以「许可之前失败」永远等价于「用户命令没有产生任何副作用」，此时直接强杀 helper 是安全的；许可之后则一律走完整 收敛。scope id 在使用前必须校验：POSIX 侧它必须等于
 * helper 自己的 pid，否则父进程宁可失败也不向一个不属于本次调用的 进程组发信号。
 *
 * <p>生命周期按平台分岔但语义一致。POSIX 侧一次调用就是一个新 session 里的进程组：scope id 是组长（= helper）的 pid， 父进程自己持有收敛手段就是这个组
 * id，收敛以「组里没有非僵尸成员」为准。Windows 侧一次调用就是一个命名 Job：scope id 只是 首个进程的 pid，收敛判定只看 Job
 * 是否报告没有活动进程，父进程为此独立持有第二个 Job 句柄。两者的句柄/所有权都在本类显式 生命周期内释放：父进程拿到「整组没有活着的成员」的证据之后才关闭自己的 Job
 * 句柄，句柄关闭只是兜底，不是收敛依据；helper 自己创建的挂起进程与 Job 句柄由 helper 的失败关闭与收尾释放。
 *
 * <p>标准流不经过本类：用户命令继承 helper 的 stdin/stdout（stdout 也是父进程排空的捕获管道），helper 自己的诊断只写调用 私有的诊断文件，因此 JVM
 * 启动噪声不会混进命令输出。自然退出码由 helper 原子发布，父进程据此恢复精确退出码——helper 自身 的退出码（收尾时通常被强杀）永远不是命令的事实。
 *
 * <p>收敛语义：POSIX 对整组先温和后强杀，并确认组里没有活着的成员（僵尸不算）；Windows 显式 {@code TerminateJobObject} 并等到 Job
 * 报告没有活动进程。收敛失败一律显式传播（{@link #terminate()} 返回 {@code false}），绝不声称已经收敛；未收敛时留下只报
 * 平台与状态字段的去敏诊断，不回显命令内容。
 *
 * <p>本类的状态目录是一次调用私有的临时目录，{@link #close()} 保证在调用结束时删除；删不干净时留下诊断，但不改变调用结果。
 */
final class ProcessScope implements AutoCloseable {

  /** 父进程私有状态目录前缀：定位残留时一眼可辨，且不落在数据目录内。 */
  static final String STATE_DIR_PREFIX = "kk-studio-daemon-process-scope-";

  /** 父进程侧的诊断 logger：收敛未能证明、状态目录删不干净时留去敏诊断；只报平台/状态字段，不回显命令内容。 */
  private static final Logger LOG = System.getLogger(ProcessScope.class.getName());

  /** 启动许可：父进程在确认范围之后、放行用户命令之前询问调用方是否仍然允许启动。 */
  @FunctionalInterface
  interface StartGate {

    boolean allowStart();
  }

  private static final StartGate ALWAYS_ALLOW = () -> true;

  /**
   * 启动失败清理没能证明收敛的固定边界。
   *
   * <p>终止返回 {@code false} 或抛出时，「资源可能还活着」不能被上层当成普通启动失败去重试；这个类型只承载固定文案， 原始失败作为 cause
   * 保留，绝不内联原始诊断。上层据此把它当成终结事实，而不是靠 suppressed 元数据（抑制可以被禁用）。
   */
  static final class StartupCleanupFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    StartupCleanupFailure(RuntimeException cause) {
      super("the process scope startup cleanup did not converge", cause);
    }
  }

  /** 等待 helper 发布 scope 的预算：覆盖 helper JVM 冷启动与 JNA 载入。 */
  private static final long PREPARE_BUDGET_MILLIS = 20_000;

  /** 温和信号之后的宽限窗口：短到不拖慢收尾，长到足以让命令自己的清理跑完。 */
  private static final long TERMINATION_GRACE_MILLIS = 150;

  /** 命令已经自然退出时，留给 helper 自己完成整组收敛的时间。 */
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

  private final Process helper;
  private final Path stateDir;
  private final boolean windows;
  private final AtomicBoolean terminationStarted = new AtomicBoolean();
  private final CountDownLatch terminationDone = new CountDownLatch(1);
  private volatile WindowsJobScope jobScope;
  private volatile boolean jobDrained;
  private volatile boolean convergenceProven;
  private volatile long scopeId = -1;
  private volatile boolean scopeEstablished;

  private ProcessScope(Process helper, Path stateDir, boolean windows) {
    this.helper = helper;
    this.stateDir = stateDir;
    this.windows = windows;
  }

  /**
   * 在给定 workdir 中启动 {@code command}，返回已经确认 OS 范围建立的范围对象。
   *
   * <p>范围未确认、helper 提前退出、平台不支持或调用方在启动阶段取消时失败关闭：用户命令不会启动，状态目录也不会残留。
   *
   * <p>启动失败一律先收敛整组再抛出：普通失败保持原身份，致命 {@link Error} 原样抛出；只有清理没能证明收敛时，普通失败才被固定的 {@link
   * StartupCleanupFailure} 包住，让上层把「资源结论不可靠」当成终结事实。
   */
  static ProcessScope start(Path workdir, List<String> command) throws IOException {
    return start(workdir, command, ALWAYS_ALLOW);
  }

  static ProcessScope start(Path workdir, List<String> command, StartGate gate) throws IOException {
    return start(workdir, command, gate, ProcessScopeHelperLauncher.Stdio.CAPTURE);
  }

  /**
   * 以双向标准流启动 {@code command}：命令的 stdin/stdout/stderr 继承本次调用持有的管道，供常驻协议收发消息。
   *
   * <p>其余语义与 {@link #start(Path, List)} 完全一致——许可之前失败仍然等价于「命令没有运行过」，收敛仍然覆盖整组（含后代）。
   */
  static ProcessScope startDuplex(Path workdir, List<String> command) throws IOException {
    return startDuplex(workdir, command, ALWAYS_ALLOW);
  }

  static ProcessScope startDuplex(Path workdir, List<String> command, StartGate gate)
      throws IOException {
    return start(workdir, command, gate, ProcessScopeHelperLauncher.Stdio.DUPLEX);
  }

  private static ProcessScope start(
      Path workdir, List<String> command, StartGate gate, ProcessScopeHelperLauncher.Stdio stdio)
      throws IOException {
    Objects.requireNonNull(gate, "gate");
    Objects.requireNonNull(stdio, "stdio");
    boolean windows =
        DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS;
    Path stateDir = Files.createTempDirectory(STATE_DIR_PREFIX);
    Process helper;
    try {
      helper = ProcessScopeHelperLauncher.launch(stateDir, workdir, command, windows, stdio);
    } catch (IOException | RuntimeException error) {
      deleteStateDirectory(stateDir);
      throw error;
    }
    ProcessScope scope = new ProcessScope(helper, stateDir, windows);
    try {
      scope.awaitScope(gate);
    } catch (RuntimeException | Error error) {
      // 范围可能已经建立（命令可能已经运行），因此统一走收敛路径；未建立时它只是强杀 keeper。
      throw failStart(error, scope);
    }
    return scope;
  }

  /**
   * 启动失败后的清理：收敛整组并删除调用私有的状态目录，把「没能证明收敛」这一事实显式交付给调用方。
   *
   * <p>清理动作必须做完：终止失败时状态目录的删除仍然尝试。首个失败保持身份，后到的 {@link Error} 提升到普通异常之上， 其余作为 {@code suppressed}
   * 附着（抑制被禁用时也不出错，因此结论绝不依赖 suppressed 元数据）。最终是 {@link Error} 时原样抛出、绝不包装；普通启动失败一旦清理没能证明收敛，就用固定的
   * {@link StartupCleanupFailure} 承载「资源结论不可靠」。
   */
  private static RuntimeException failStart(Throwable original, ProcessScope scope) {
    boolean converged = false;
    Throwable terminationFailure = null;
    Throwable directoryFailure = null;
    try {
      converged = scope.terminate();
    } catch (RuntimeException | Error error) {
      terminationFailure = error;
    }
    try {
      deleteStateDirectory(scope.stateDir);
    } catch (RuntimeException | Error error) {
      directoryFailure = error;
    }
    Throwable primary = original;
    if (terminationFailure != null) {
      primary = mergeStartFailure(primary, terminationFailure);
    }
    if (directoryFailure != null) {
      primary = mergeStartFailure(primary, directoryFailure);
    }
    if (primary instanceof Error fatal) {
      throw fatal;
    }
    if (!converged) {
      throw new StartupCleanupFailure((RuntimeException) primary);
    }
    return (RuntimeException) primary;
  }

  /** 两步收尾共用同一优先级：首个 Error 保持身份，后到的 Error 优先于普通异常。 */
  private static Throwable mergeStartFailure(Throwable primary, Throwable later) {
    if (primary == later) {
      return primary;
    }
    if (later instanceof Error && !(primary instanceof Error)) {
      later.addSuppressed(primary);
      return later;
    }
    primary.addSuppressed(later);
    return primary;
  }

  /**
   * 删除调用私有的状态目录；删不干净时留下「没有清理干净」这一去敏诊断，但不改变调用结果。
   *
   * <p>目录只承载调用私有的握手文件，删除失败不影响命令事实；不过残留会一直占着临时目录，所以要让收尾的失败可见。
   */
  private static void deleteStateDirectory(Path stateDir) {
    if (!ProcessScopeState.deleteQuietly(stateDir)) {
      // 只报「删不干净」这一事实：诊断绝不回显私有路径或命令参数。
      LOG.log(Level.WARNING, "cannot fully remove the process scope state directory");
    }
  }

  /** helper 的进程对象：父进程只从它读取用户命令的输出并等待收敛。 */
  Process process() {
    return helper;
  }

  /** 用户命令的自然退出码；没有发布过就返回 {@code null}（超时、取消或 helper 异常）。 */
  Integer naturalExitCode() {
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
  boolean awaitNaturalExit(long millis) {
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
  String startFailure() {
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

  /** 范围建立（helper 冷启动与确认）的等待预算：调用方据此把「等待启动真正结束」的边界设在启动预算之上。 */
  static long startBudgetMillis() {
    return PREPARE_BUDGET_MILLIS;
  }

  /** 收敛整组的等待预算：调用方据此把「等待停止真正结束」的边界设在真实收敛之上，而不是猜一个更短的宽限。 */
  static long terminationBudgetMillis() {
    return TERMINATION_WAIT_MILLIS;
  }

  /**
   * 收敛整个范围，并返回「范围里不再有任何活着的成员」这一事实是否已经被证明。
   *
   * <p>幂等且有界；并发调用者等待同一次收敛，等待超时或被中断时同样返回 {@code false}——收敛结果绝不因为「等不到」而被当成 成功。
   */
  boolean terminate() {
    if (terminationStarted.compareAndSet(false, true)) {
      try {
        convergenceProven = stop();
      } finally {
        terminationDone.countDown();
      }
      if (!convergenceProven) {
        LOG.log(Level.WARNING, "the process scope did not converge: " + scopeStateSummary());
      }
      return convergenceProven;
    }
    if (!awaitTerminationDone()) {
      return false;
    }
    return convergenceProven;
  }

  /** 收敛失败时的去敏诊断上下文：只报平台与范围状态字段，绝不回显私有路径、命令参数或命令内容。 */
  private String scopeStateSummary() {
    return "windows="
        + windows
        + ", scopeEstablished="
        + scopeEstablished
        + ", helperAlive="
        + helper.isAlive();
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
    return !PosixProcessGroup.hasLiveMember(scopeId, PosixProcessGroup.NO_PROCESS);
  }

  @Override
  public void close() {
    terminate();
    ProcessScopeHelperLauncher.collectCoverage(stateDir);
    deleteStateDirectory(stateDir);
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
   * <p>状态文件被破坏时不能变成向一个陌生进程组发信号的依据：POSIX 上 scope id 就是组长 pid，只有等于 helper 的 pid 才可能 是本次调用刚建立的组。
   */
  private void validateScopeId(long published) {
    if (published <= 0) {
      throw new IllegalStateException(
          "the process scope helper published an invalid scope id: " + published);
    }
    if (!windows && published != helper.pid()) {
      throw new IllegalStateException(
          "the process scope helper published a scope id that is not its own process group: "
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
    boolean drained = windows ? drainJob() : drainGroup();
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
   * keeper 已经退出之后的收敛：组 id 与 pid 都可能已经被系统复用，因此绝不广播，而是「重新核验身份之后逐个强杀」，直到没有 活着的成员。
   *
   * <p>只做只读确认会把「keeper 先死、后代还在」当成失败并留下资源；但照着快照直接发信号又可能杀到一个复用了同一个 pid 的无关
   * 进程。这里两者都不做：每个成员在发信号之前都要重新确认「它还活着、它仍然属于本次范围、它的启动时刻与快照一致」，任何一条 不成立就不发信号。不能枚举的平台（非
   * Linux）与不可判定的扫描都退回过读确认：组不存在即收敛，否则如实报告未收敛。
   */
  private boolean convergedAfterHelperExit() {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONVERGENCE_BUDGET_MILLIS);
    while (true) {
      List<PosixProcessGroup.GroupMember> members =
          PosixProcessGroup.membersSnapshot(scopeId, PosixProcessGroup.NO_PROCESS);
      if (members == null) {
        return !PosixProcessGroup.hasLiveMember(scopeId, PosixProcessGroup.NO_PROCESS);
      }
      if (members.isEmpty()) {
        return true;
      }
      for (PosixProcessGroup.GroupMember member : members) {
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
   * <p>快照与强杀之间存在时间差，pid 可能已经被回收并分配给别的进程；因此这里同时核验「所属进程组仍然是本次范围」与 「启动时刻与快照一致」——前者排除同 pid 换了组的情况，后者排除同
   * pid 换了进程的情况。核验不通过就不发信号：宁可报告未收敛， 也绝不杀错进程。
   */
  void killVerifiedMember(PosixProcessGroup.GroupMember member) {
    ProcessHandle handle = ProcessHandle.of(member.pid()).orElse(null);
    if (handle == null) {
      return;
    }
    Instant start = handle.info().startInstant().orElse(null);
    if (start == null || !start.equals(member.start())) {
      return;
    }
    if (PosixProcessGroup.processGroupOf(member.pid()) != scopeId) {
      return;
    }
    handle.destroyForcibly();
  }

  /**
   * POSIX 整组收敛：命令已经自然退出时先让 helper 完成它自己的收尾，否则由父进程对整组发温和信号并在宽限后升级。
   *
   * <p>helper 存活时父进程始终可以广播整组信号（keeper 自己会活到 shutdown hook 收尾完成）；helper 一旦退出就改走 {@link
   * #convergedAfterHelperExit()}。
   */
  private boolean drainGroup() {
    if (ProcessScopeState.read(stateDir, ProcessScopeState.EXIT_FILE) != null) {
      awaitHelperExit(HELPER_CONVERGENCE_BUDGET_MILLIS);
    }
    if (!helper.isAlive()) {
      return convergedAfterHelperExit();
    }
    if (!PosixProcessGroup.signalGroup(scopeId, PosixProcessGroup.SIGTERM)) {
      return false;
    }
    if (!awaitConvergence(TERMINATION_GRACE_MILLIS) && helper.isAlive()) {
      PosixProcessGroup.signalGroup(scopeId, PosixProcessGroup.SIGKILL);
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

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }
}
