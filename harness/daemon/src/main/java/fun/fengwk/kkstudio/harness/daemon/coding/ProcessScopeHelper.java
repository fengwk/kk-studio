package fun.fengwk.kkstudio.harness.daemon.coding;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程 scope helper：以独立 JVM 建立操作系统级执行范围，然后在范围内启动用户命令。
 *
 * <p>daemon 不是用户命令的 OS 所有者：它无法把 {@code ProcessBuilder} 直接放进一个新的 session/Job。helper 因此先取得 所有权（POSIX
 * 用 {@code setsid} 建立新 session 与进程组，Windows 建立带 {@code KILL_ON_JOB_CLOSE} 的命名 Job 并把首个进程
 * 挂起后归属进去），再等父进程确认，最后才让用户命令运行。
 *
 * <p>「父进程确认之后才启动命令」是本类的核心约定：许可之前失败（取消、超时、父进程退出）因此永远等价于「用户命令没有产生
 * 任何副作用」。命令自然退出时先把退出码原子发布，再收敛整个范围并把收敛事实发布到 {@link ProcessScopeState#CLEANUP_FILE}。
 *
 * <p>本类只作为 helper 入口存在：它不是能力、不注册新工具，也不读取任何新配置；参数全部来自父进程本次调用。它自己的话只写 调用私有的诊断文件，标准输出完全属于用户命令。
 */
public final class ProcessScopeHelper {

  /** 范围收敛的宽限窗口：温和信号之后最多等这么久，再升级为强制信号。 */
  private static final Duration TERMINATION_GRACE = Duration.ofMillis(150);

  /** 等待父进程许可的预算；父进程的启动预算与它一致。 */
  private static final Duration PERMIT_BUDGET = Duration.ofSeconds(20);

  /** Windows Job 收敛的等待预算。 */
  private static final Duration JOB_DRAIN_BUDGET = Duration.ofSeconds(2);

  private static final long POLL_INTERVAL_MILLIS = 5;

  /** 收敛已经在别处执行时的等待预算：等不到就显式失败，绝不提前放走 JVM。 */
  private static final Duration CONVERGENCE_WAIT_BUDGET = Duration.ofSeconds(10);

  /** 测试闸门的等待预算：没有释放就必须显式失败，绝不静默继续。 */
  private static final Duration SPAWN_LATCH_BUDGET = Duration.ofSeconds(20);

  /** 双向标准流模式的属性值；属性名与父进程 {@code ProcessScope} 的约定一致。 */
  private static final String STDIO_PROPERTY = "kk-studio.process-scope.stdio";

  private static final String SPAWN_LATCH_READY_FILE = "spawn-ready";
  private static final String SPAWN_LATCH_RELEASE_FILE = "spawn-go";

  private ProcessScopeHelper() {}

  /**
   * helper 入口：{@code <state-dir> <workdir> <command...>}。
   *
   * <p>失败一律写入失败原因文件并以非零状态退出：范围建立失败、JNA 载入失败与不支持的平台都必须显式失败，绝不静默回退到 「没有范围」的直接执行。
   */
  public static void main(String[] args) {
    if (args.length < 3) {
      System.exit(2);
      return;
    }
    Path stateDir = Path.of(args[0]);
    redirectDiagnostics(stateDir);
    Helper helper = new Helper(stateDir, Path.of(args[1]), List.of(args).subList(2, args.length));
    int exitCode;
    try {
      exitCode = helper.run();
    } catch (Throwable error) {
      helper.publishFailure(error);
      exitCode = 1;
    }
    System.exit(exitCode);
  }

  /**
   * helper 自己的输出（异常、诊断）写调用私有的诊断文件。
   *
   * <p>标准输出只承载用户命令的输出，因此 JVM 启动噪声（例如 {@code JAVA_TOOL_OPTIONS} 的提示）与 helper 的报错都不能出现
   * 在那里；父进程把这些内容重定向到同一个诊断文件，只在需要解释失败原因时读取它。
   */
  private static void redirectDiagnostics(Path stateDir) {
    try {
      PrintStream diagnostics =
          new PrintStream(
              Files.newOutputStream(
                  stateDir.resolve(ProcessScopeState.DIAGNOSTICS_FILE),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.APPEND),
              true,
              StandardCharsets.UTF_8);
      System.setErr(diagnostics);
    } catch (IOException ignored) {
      // 诊断文件不可写只影响 helper 报错的可见性；父进程仍会以退出码与状态文件收敛为失败。
    }
  }

  /** 单次调用的 helper 状态机；不含任何静态可变状态。 */
  private static final class Helper {

    private final Path stateDir;
    private final Path workdir;
    private final List<String> command;

    /** 双向标准流：命令的 stdin/stdout/stderr 直接继承 keeper 的三条流（供常驻协议收发消息）。 */
    private final boolean duplex;

    /**
     * 派生与收敛的互斥锁：{@code stopping} 一旦在锁内成立，就不再有任何命令被派生。
     *
     * <p>这把锁同时消掉两个方向的竞态：收敛开始之后诞生的后代不可能存在（所以收敛的扫描之后不会再出现新成员），而命令 fork 的那一刻收敛还没开始（所以它的信号处置不会被收敛改动）。
     */
    private final Object spawnLock = new Object();

    private boolean stopping;

    /**
     * 命令是否真的被派生过。
     *
     * <p>取值只在 {@code spawnLock} 内改变，因此「{@code stopping} 已经成立」配上「{@code spawned} 仍为 false」是一条确定性的「
     * 本次调用没有任何成员」证明：锁内一旦声明不再派生，就不可能有新成员诞生；此前也没派生过，就没有任何后代需要收敛。收敛因此 不必扫描、更不必向整组广播强杀（那一条会打到 helper
     * 自己），permit/工作目录这类启动失败也就不会把父进程看到的事实变成 「被强杀」。
     */
    private volatile boolean spawned;

    /** 收敛只执行一次：main 的自然退出路径与 shutdown hook 用它同步。 */
    private final AtomicBoolean convergenceStarted = new AtomicBoolean();

    /** 收敛完成的闩：谁发现收敛已经在执行，就必须等它真正结束，不能提前返回把 JVM 放走。 */
    private final CountDownLatch convergenceFinished = new CountDownLatch(1);

    private volatile boolean convergenceResult;

    private Helper(Path stateDir, Path workdir, List<String> command) {
      this.stateDir = stateDir;
      this.workdir = workdir;
      this.command = command;
      this.duplex = "duplex".equalsIgnoreCase(System.getProperty(STDIO_PROPERTY));
    }

    private int run() throws Exception {
      if (DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS) {
        return runWindows();
      }
      return runPosix();
    }

    /** POSIX：先建立 session/进程组并公开范围，等父进程确认，然后启动用户命令。 */
    private int runPosix() throws Exception {
      PosixProcessGroup.createSession();
      long processGroup = PosixProcessGroup.currentGroup();
      if (processGroup != ProcessHandle.current().pid()) {
        throw new IllegalStateException(
            "the scope helper is not the leader of its process group: " + processGroup);
      }
      // 收敛的唯一执行者是 convergePosixGroup：它在两处被调用——命令自然退出后的这里，以及 JVM 默认 SIGTERM 处置触发的 shutdown
      // hook。JVM 的信号处置是「捕获」而不是「忽略」，而 exec 会把被捕获的信号复位成默认处置，因此命令可以注册自己的 trap，
      // helper 也不必在启动命令的瞬间改动任何信号处置（忽略状态会被 fork 继承，非交互 shell 无法覆盖）。
      // hook 必须在范围发布之前注册：发布之后父进程随时可能对整组广播温和信号。
      installConvergenceHook(processGroup);
      PosixProcessGroup.becomeChildSubreaper();
      ProcessScopeState.publish(
          stateDir, ProcessScopeState.SCOPE_FILE, Long.toString(processGroup));
      awaitPermit();
      awaitSpawnRelease();
      // 派生与收敛在同一把锁上互斥：收敛开始之后绝不会有新成员诞生，命令 fork 出来的那一刻收敛也还没有开始。整个
      // ProcessBuilder.start()（含 fork 与 exec 之间的窗口）都在锁内，收敛不可能穿过它扫描一次「看起来已经收敛」的组。
      Process child = null;
      synchronized (spawnLock) {
        if (!stopping) {
          // 捕获模式把命令的 stderr 合并进 stdout（helper 的诊断已经指向诊断文件，不会被继承）；双向模式必须让 stderr 保持
          // 独立，父进程才能像对待一个直接启动的进程那样分别读它。
          if (!duplex) {
            PosixProcessGroup.mergeStandardErrorIntoStandardOutput();
          }
          ProcessBuilder builder =
              new ProcessBuilder(command)
                  .directory(workdir.toFile())
                  .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                  .redirectError(ProcessBuilder.Redirect.INHERIT);
          if (duplex) {
            // 命令的 stdin 就是 keeper 自己的 stdin（父进程持有的管道）：既不新建空管道，也不关闭它，EOF 只在父进程关闭
            // 自己那一端时到达。
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
          } else {
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
          }
          child = builder.start();
          // 派生事实在同一把锁内成立：收敛若在锁内看到 stopping，就一定也看到「有没有派生过」的最终值。
          spawned = true;
          // 命令已经 fork 完成，此刻才开始忽略温和信号：收敛自己的整组信号会打到组长身上，helper 必须活到强杀与结论发布
          // 完成。放在 fork 之后是为了不让忽略状态被命令继承——非交互 shell 无法为「进入时已被忽略」的信号注册 trap。
          PosixProcessGroup.ignoreTerminationSignal();
        }
      }
      if (child == null) {
        // 收敛已经接管，命令绝不会被派生：这里只对齐它的结论，既不制造第二个事实，也不让 JVM 提前退出。
        awaitConvergenceFinished();
        return convergenceResult ? 0 : 1;
      }
      // 捕获模式下命令的 stdin 是一条只有 helper 持有写端的空管道：立刻关闭写端，于是「等待 EOF 的命令自然退出」只取决于
      // 这里，而不是调用方何时关闭自己的写端，也不是是否有别的进程持有了写端的副本。双向模式没有这条管道，也不去动它。
      if (!duplex) {
        closeQuietly(child.getOutputStream());
      }
      int exitCode = child.waitFor();
      ProcessScopeState.publish(stateDir, ProcessScopeState.EXIT_FILE, Integer.toString(exitCode));
      // 命令的退出码已经回收，此后 waitpid(-1) 只会回收被收养的孤儿，不会偷走命令的状态。
      startOrphanReaper();
      return convergePosixGroup(processGroup) ? 0 : 1;
    }

    /** 注册收敛 hook：必须在范围发布之前，否则父进程可能先看到范围再对整组发信号，而那时还没有人能接住温和信号。 */
    private void installConvergenceHook(long processGroup) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    // 顺序不能颠倒：先在锁内声明「不再派生」，再忽略温和信号。反过来（先忽略、后加锁）会让一个尚未派生的
                    // 命令继承忽略状态，也会让收敛扫描与派生互相穿过。收尾期间屏蔽重复的温和信号也是这里的目的之一。
                    synchronized (spawnLock) {
                      stopping = true;
                      PosixProcessGroup.ignoreTerminationSignal();
                    }
                    convergePosixGroup(processGroup);
                  },
                  "process-scope-convergence"));
    }

    /**
     * 收敛整组并把结果发布到 {@code cleanup}；同一次调用的收敛只执行一次——命令自然退出的 main 路径与 shutdown hook 会竞争，
     * 先到者执行，后到者直接复用它的结论。
     */
    private boolean convergePosixGroup(long processGroup) {
      if (!convergenceStarted.compareAndSet(false, true)) {
        // 收敛已经在别处执行：必须等它结束再返回。直接返回 false 会让 JVM 在收敛完成之前退出，把「后代还在」留在系统里。
        awaitConvergenceFinished();
        return convergenceResult;
      }
      try {
        boolean drained = convergeGroup(processGroup);
        ProcessScopeState.publish(
            stateDir, ProcessScopeState.CLEANUP_FILE, Boolean.toString(drained));
        convergenceResult = drained;
        return drained;
      } catch (RuntimeException | Error failure) {
        publishFailure(failure);
        ProcessScopeState.publishQuietly(stateDir, ProcessScopeState.CLEANUP_FILE, "false");
        convergenceResult = false;
        return false;
      } finally {
        convergenceFinished.countDown();
      }
    }

    /**
     * 有界等待正在执行的收敛结束。
     *
     * <p>等不到就显式失败：报告「还没收敛」比让调用方以为已经收敛安全得多。
     */
    private void awaitConvergenceFinished() {
      try {
        if (!convergenceFinished.await(CONVERGENCE_WAIT_BUDGET.toMillis(), TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException(
              "another convergence was still running after "
                  + CONVERGENCE_WAIT_BUDGET.toMillis()
                  + " ms");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "interrupted while waiting for the running convergence", interrupted);
      }
    }

    /**
     * 仅测试使用的派生闸门：显式设置 {@link ProcessScope#SPAWN_LATCH_PROPERTY} 时，启动命令之前先写下就绪标记并等待释放
     * 标记，用来确定性地构造「取消与派生同时发生」。生产路径不设置该属性，因此这里直接返回。
     */
    private void awaitSpawnRelease() throws IOException {
      String latch = System.getProperty(ProcessScope.SPAWN_LATCH_PROPERTY);
      if (latch == null || latch.isBlank()) {
        return;
      }
      Path directory = Path.of(latch);
      Files.writeString(
          directory.resolve(SPAWN_LATCH_READY_FILE),
          Long.toString(ProcessHandle.current().pid()),
          StandardCharsets.UTF_8);
      long deadline = System.nanoTime() + SPAWN_LATCH_BUDGET.toNanos();
      while (!Files.exists(directory.resolve(SPAWN_LATCH_RELEASE_FILE))) {
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException(
              "the spawn latch was not released within " + SPAWN_LATCH_BUDGET.toMillis() + " ms");
        }
        sleepQuietly();
      }
    }

    /**
     * 等到父进程确认范围。
     *
     * <p>许可之前不启动任何进程，因此取消或超时总能赶在用户命令产生副作用之前生效；父进程若放弃本次调用，会直接结束 helper 而不是让这里等到预算耗尽。
     */
    private void awaitPermit() {
      long deadline = System.nanoTime() + PERMIT_BUDGET.toNanos();
      while (ProcessScopeState.read(stateDir, ProcessScopeState.PERMIT_FILE) == null) {
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException(
              "the parent did not confirm the process scope within "
                  + PERMIT_BUDGET.toMillis()
                  + " ms; the command was not started");
        }
        sleepQuietly();
      }
    }

    /**
     * Windows：建立命名 Job 并让首个进程保持挂起，等父进程持有同一个 Job 的句柄之后再恢复执行。
     *
     * <p>命令自然退出后先发布退出码，再显式终止整组并等 Job 报告没有活动进程，最后才发布收敛完成的事实。
     */
    private int runWindows() throws Exception {
      WindowsJobScope scope =
          WindowsJobScope.createSuspended(
              WindowsJobScope.jobNameFor(stateDir), command, workdir, duplex);
      try {
        ProcessScopeState.publish(
            stateDir, ProcessScopeState.SCOPE_FILE, Long.toString(scope.processId()));
        awaitPermit();
        scope.resume();
        int exitCode = scope.awaitExit();
        ProcessScopeState.publish(
            stateDir, ProcessScopeState.EXIT_FILE, Integer.toString(exitCode));
        boolean drained = scope.terminateAndDrain(JOB_DRAIN_BUDGET.toMillis());
        ProcessScopeState.publish(
            stateDir, ProcessScopeState.CLEANUP_FILE, Boolean.toString(drained));
        return drained ? 0 : 1;
      } finally {
        scope.close();
      }
    }

    /**
     * 收敛整个 POSIX 进程组；返回是否已经确认「除 helper 自己之外没有活着的成员」。
     *
     * <p>只有 Linux/WSL 能在 {@code /proc} 里区分「只剩 helper 自己」与「还有后代」；其它 POSIX 平台只能广播强杀（这一条包含 helper
     * 自身），因此收敛的最终判定由父进程的内核检查收口。
     */
    private boolean convergeGroup(long processGroup) {
      if (!spawned) {
        // 从未派生过命令：stopping 已经在同一把锁内成立，因此不可能再有成员诞生。这是一条确定性的「没有成员」证明，不需要
        // 扫描，更不需要向整组广播强杀——那一条会把 helper 自己也带走，让父进程只能看到「被信号杀掉」而不是真实的失败原因。
        return true;
      }
      PosixProcessGroup.signalGroup(processGroup, PosixProcessGroup.SIGTERM);
      if (awaitGroupGone(processGroup, TERMINATION_GRACE)) {
        return true;
      }
      // 宽限窗口内没有收敛：广播强杀，这一条路径包含 helper 自身——只有它才能覆盖连温和信号都不理的后代。
      PosixProcessGroup.signalGroup(processGroup, PosixProcessGroup.SIGKILL);
      awaitGroupGone(processGroup, TERMINATION_GRACE);
      return false;
    }

    /**
     * 后台回收被收养的孤儿，一直持续到本进程结束。
     *
     * <p>不能只扫一遍：被强杀的后代可能在回收线程退出之后才变成僵尸，那样它会一直挂在进程表里，直到 helper 自己也消失。
     */
    private void startOrphanReaper() {
      Thread reaper =
          new Thread(
              () -> {
                while (true) {
                  PosixProcessGroup.reapOrphans();
                  sleepQuietly();
                }
              },
              "process-scope-orphan-reaper");
      reaper.setDaemon(true);
      reaper.start();
    }

    /** 等到范围内只剩 helper 自己；{@code hasLiveMember} 在 Linux 上按状态排除僵尸。 */
    private boolean awaitGroupGone(long processGroup, Duration budget) {
      long deadline = System.nanoTime() + budget.toNanos();
      while (PosixProcessGroup.hasLiveMember(processGroup, ProcessHandle.current().pid())) {
        if (System.nanoTime() >= deadline) {
          return false;
        }
        sleepQuietly();
      }
      return true;
    }

    /** 尽力报告失败原因；报告本身失败时只能放弃，父进程会以「没有状态文件」收敛为失败。 */
    private void publishFailure(Throwable error) {
      ProcessScopeState.publishQuietly(stateDir, ProcessScopeState.ERROR_FILE, describe(error));
    }

    private static String describe(Throwable error) {
      String message = error.getMessage();
      return message == null || message.isBlank() ? error.toString() : message;
    }

    private static void closeQuietly(OutputStream stream) {
      try {
        stream.close();
      } catch (IOException ignored) {
        // 关闭失败只影响命令能否读到 EOF：命令自身的去向仍由退出码与收敛结论决定。
      }
    }

    private static void sleepQuietly() {
      try {
        Thread.sleep(POLL_INTERVAL_MILLIS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
