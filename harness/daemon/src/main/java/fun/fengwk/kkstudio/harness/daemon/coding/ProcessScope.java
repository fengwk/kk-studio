package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次调用的操作系统级执行范围，父进程侧的唯一入口。
 *
 * <p>{@link #start(Path, List, StartGate)} 启动独立的 scope helper JVM：helper 先取得 OS 所有权（POSIX 新 session
 * 与进程组、Windows 命名 Job），再等父进程确认，然后才让用户命令运行。因此父进程不需要枚举后代，也不需要 {@code ProcessHandle.descendants()}
 * 这类「看运气」的收敛依据。
 *
 * <p>顺序是这里唯一的安全性来源：范围确认 -> 父进程登记自己的收敛手段（POSIX 进程组 id / Windows Job 句柄）-> 放行用户
 * 命令。所以「许可之前失败」永远等价于「用户命令没有产生任何副作用」，此时直接强杀 helper 是安全的；许可之后则一律走完整 收敛。scope id 在使用前必须校验：POSIX 侧它必须等于
 * helper 自己的 pid，否则父进程宁可失败也不向一个不属于本次调用的 进程组发信号。
 *
 * <p>标准流不经过本类：用户命令继承 helper 的 stdin/stdout（stdout 也是父进程排空的捕获管道），helper 自己的诊断只写调用 私有的诊断文件，因此 JVM
 * 启动噪声不会混进命令输出。自然退出码由 helper 原子发布，父进程据此恢复精确退出码——helper 自身 的退出码（收尾时通常被强杀）永远不是命令的事实。
 *
 * <p>收敛语义：POSIX 对整组先温和后强杀，并确认组里没有活着的成员（僵尸不算）；Windows 显式 {@code TerminateJobObject} 并等到 Job
 * 报告没有活动进程。收敛失败一律显式传播（{@link #terminate()} 返回 {@code false}），绝不声称已经收敛。
 *
 * <p>本类的状态目录是一次调用私有的临时目录，{@link #close()} 保证在调用结束时删除。
 */
final class ProcessScope implements AutoCloseable {

  /** 父进程私有状态目录前缀：定位残留时一眼可辨，且不落在数据目录内。 */
  static final String STATE_DIR_PREFIX = "kk-studio-daemon-process-scope-";

  /** 启动许可：父进程在确认范围之后、放行用户命令之前询问调用方是否仍然允许启动。 */
  @FunctionalInterface
  interface StartGate {

    boolean allowStart();
  }

  private static final StartGate ALWAYS_ALLOW = () -> true;

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
   */
  static ProcessScope start(Path workdir, List<String> command) throws IOException {
    return start(workdir, command, ALWAYS_ALLOW);
  }

  static ProcessScope start(Path workdir, List<String> command, StartGate gate) throws IOException {
    Objects.requireNonNull(gate, "gate");
    boolean windows =
        DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS;
    Path stateDir = Files.createTempDirectory(STATE_DIR_PREFIX);
    Process helper;
    try {
      helper = spawnHelper(stateDir, workdir, command, windows);
    } catch (IOException | RuntimeException error) {
      ProcessScopeState.deleteQuietly(stateDir);
      throw error;
    }
    ProcessScope scope = new ProcessScope(helper, stateDir, windows);
    try {
      scope.awaitScope(gate);
    } catch (RuntimeException error) {
      // 范围可能已经建立（命令可能已经运行），因此统一走收敛路径；未建立时它只是强杀 keeper。
      scope.terminate();
      ProcessScopeState.deleteQuietly(stateDir);
      throw error;
    }
    return scope;
  }

  private static Process spawnHelper(
      Path stateDir, Path workdir, List<String> command, boolean windows) throws IOException {
    String classpath = System.getProperty("java.class.path");
    if (classpath == null || classpath.isBlank()) {
      throw new IOException(
          "cannot locate the daemon classpath required to launch the process scope helper");
    }
    List<String> argv = new ArrayList<>();
    argv.add(javaBinary(windows));
    // helper 的输出必须只有用户命令的输出：关掉 JVM 统一日志，并容忍陌生 VM 选项。
    argv.add("-XX:+IgnoreUnrecognizedVMOptions");
    argv.add("-XX:TieredStopAtLevel=1");
    argv.add("-Xlog:disable");
    argv.addAll(helperCoverageArguments(stateDir));
    argv.addAll(spawnLatchArguments());
    argv.add("-cp");
    argv.add(classpath);
    argv.add(ProcessScopeHelper.class.getName());
    argv.add(stateDir.toString());
    argv.add(workdir.toString());
    argv.addAll(command);
    return new ProcessBuilder(argv)
        // helper 自己的 stderr（JVM 启动提示、诊断）只进调用私有的诊断文件，绝不混进用户命令的输出。
        .redirectError(stateDir.resolve(ProcessScopeState.DIAGNOSTICS_FILE).toFile())
        .start();
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
    return !PosixProcessGroup.hasLiveMember(scopeId, PosixProcessGroup.NO_PROCESS);
  }

  @Override
  public void close() {
    terminate();
    collectHelperCoverage();
    ProcessScopeState.deleteQuietly(stateDir);
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
   * keeper 已经退出之后的收敛：组 id 可能已经被复用，因此绝不广播，而是「先确认此刻属于本组、再逐个强杀」，直到没有活着的成员。
   *
   * <p>只做只读确认会把「keeper 先死、后代还在」当成失败并留下资源；这里把这类成员也收敛掉。没有枚举能力的平台（非 Linux）只能 退回过读确认：组不存在即收敛，否则如实报告未收敛。
   */
  private boolean convergedAfterHelperExit() {
    if (!PosixProcessGroup.canEnumerateMembers()) {
      return !PosixProcessGroup.hasLiveMember(scopeId, PosixProcessGroup.NO_PROCESS);
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONVERGENCE_BUDGET_MILLIS);
    while (true) {
      long[] members = PosixProcessGroup.liveMembers(scopeId, PosixProcessGroup.NO_PROCESS);
      if (members.length == 0) {
        return true;
      }
      if (members[0] < 0) {
        // 枚举不可判定：不向任何 pid 发信号，也绝不当成收敛。
        return false;
      }
      for (long member : members) {
        PosixProcessGroup.signalProcess(member, PosixProcessGroup.SIGKILL);
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      sleepQuietly(POLL_INTERVAL_MILLIS);
    }
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
}
