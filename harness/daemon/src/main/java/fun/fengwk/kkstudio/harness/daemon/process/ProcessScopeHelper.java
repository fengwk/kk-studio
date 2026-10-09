package fun.fengwk.kkstudio.harness.daemon.process;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程 scope helper：以独立 JVM 建立操作系统级执行范围，然后在范围内启动用户命令。
 *
 * <p>daemon 不是用户命令的 OS 所有者：它无法把 {@code ProcessBuilder} 直接放进一个新的 session/Job。helper 因此先取得 所有权（POSIX
 * 管道模式用 {@code setsid} 建立新 session 与进程组；PTY 模式由 pty4j 原生 {@code login_tty} 建立，本类只验证 身份；Windows 建立带
 * {@code KILL_ON_JOB_CLOSE} 的命名 Job 并把首个进程挂起后归属进去），再等父进程确认，最后才让用户命令运行。
 *
 * <p>helper 的命令行只携带固定入口与调用私有的状态目录；启动规格（workdir 与 argv）通过 {@link ProcessScopeState#LAUNCH_FILE}
 * 交接、读取后立即删除，因此命令行、pty4j 线程名与诊断都不会带上启动参数。
 *
 * <p>标准流由 stdio 属性区分：捕获模式把命令的 stderr 合并进 stdout 并关闭 stdin 写端；双向与 PTY 模式让命令继承 helper 的三条流 （PTY
 * 模式下它们就是伪终端从端），供常驻协议或交互终端使用。
 *
 * <p>「父进程确认之后才启动命令」是本类的核心约定：许可之前失败（取消、超时、父进程退出）因此永远等价于「用户命令没有产生 任何副作用」。命令自然退出时先把退出码原子发布，再收敛整个会话（交互
 * shell 会建立多个作业进程组，单组收敛会漏组）并把收敛事实发布 到 {@link ProcessScopeState#CLEANUP_FILE}。
 *
 * <p>本类只作为 helper 入口存在：它不是能力、不注册新工具，也不读取任何新配置；它自己的话只写调用私有的诊断文件，标准输出完全属于 用户命令。
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

  private static final String SPAWN_LATCH_READY_FILE = "spawn-ready";
  private static final String SPAWN_LATCH_RELEASE_FILE = "spawn-go";

  private ProcessScopeHelper() {}

  /**
   * helper 入口：{@code <state-dir>}。启动规格从状态目录读取，不再出现在命令行。
   *
   * <p>失败一律写入失败原因文件并以非零状态退出：范围建立失败、JNA 载入失败与不支持的平台都必须显式失败，绝不静默回退到 「没有范围」的直接执行。
   */
  public static void main(String[] args) {
    // 入口只接受恰好一个参数（调用私有的状态目录）：多给参数说明调用方走错了入口，绝不忽略它们继续。
    if (args.length != 1) {
      System.exit(2);
      return;
    }
    Path stateDir = Path.of(args[0]);
    redirectDiagnostics(stateDir);
    int exitCode;
    try {
      exitCode = new Helper(stateDir).run();
    } catch (Throwable error) {
      // 失败原因必须独立于 Helper 是否构造成功：启动规格缺失等失败恰恰发生在构造阶段。
      ProcessScopeState.publishQuietly(stateDir, ProcessScopeState.ERROR_FILE, describe(error));
      exitCode = 1;
    }
    System.exit(exitCode);
  }

  /**
   * helper 自己的输出（异常、诊断）写调用私有的诊断文件。
   *
   * <p>标准输出只承载用户命令的输出，因此 JVM 启动噪声与 helper 的报错都不能出现在那里；这里同时重定向 Java 侧的两条流，底层
   * 文件描述符不变，命令仍继承原始的标准流（捕获管道、双向管道或伪终端）。
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
      System.setOut(diagnostics);
    } catch (IOException ignored) {
      // 诊断文件不可写只影响 helper 报错的可见性；父进程仍会以退出码与状态文件收敛为失败。
    }
  }

  /**
   * 失败原因的可读化。
   *
   * <p>POSIX 命令启动失败的 {@link IOException} 可能带着可执行文件与工作目录，只报告固定类型；Windows 创建失败在原生边界直接生成不含 argv
   * 的说明。其余失败都是 helper 自己的状态描述（会话/权限/errno）。
   */
  private static String describe(Throwable error) {
    if (error instanceof IOException) {
      return "the command could not be started";
    }
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  /** 单次调用的 helper 状态机；不含任何静态可变状态。 */
  private static final class Helper {

    private final Path stateDir;
    private final ProcessScopeState.LaunchSpec spec;

    /** 命令的 stdin/stdout/stderr 是否继承 helper 自己的三条流（双向与 PTY 模式）。 */
    private final boolean inheritStreams;

    /** 是否运行在伪终端中（PTY/ConPTY 模式）。 */
    private final boolean pty;

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
     * <p>取值只在 {@code spawnLock} 内改变，因此「{@code stopping} 已经成立」配上「{@code spawned} 仍为 false」是一条
     * 确定性的「本次调用没有任何成员」证明：锁内一旦声明不再派生，就不可能有新成员诞生。
     */
    private volatile boolean spawned;

    /** 收敛只执行一次：main 的自然退出路径与 shutdown hook 用它同步。 */
    private final AtomicBoolean convergenceStarted = new AtomicBoolean();

    /** 收敛完成的闩：谁发现收敛已经在执行，就必须等它真正结束，不能提前返回把 JVM 放走。 */
    private final CountDownLatch convergenceFinished = new CountDownLatch(1);

    private volatile boolean convergenceResult;

    private Helper(Path stateDir) {
      this.stateDir = stateDir;
      this.spec = ProcessScopeState.readLaunch(stateDir);
      if (spec == null) {
        throw new IllegalStateException("the helper launch spec is missing");
      }
      String stdio = System.getProperty(ProcessScope.STDIO_PROPERTY);
      if (stdio != null && !"pty".equals(stdio) && !"duplex".equals(stdio)) {
        throw new IllegalStateException("unsupported process scope stdio mode");
      }
      this.pty = "pty".equals(stdio);
      this.inheritStreams = "duplex".equals(stdio) || pty;
    }

    private int run() throws Exception {
      if (DaemonOperatingSystemDetector.detectCurrent() == DaemonOperatingSystem.WINDOWS) {
        return runWindows();
      }
      return runPosix();
    }

    /** POSIX：先建立或验证 session/进程组并公开范围，等父进程确认，然后启动用户命令。 */
    private int runPosix() throws Exception {
      long processGroup;
      if (pty) {
        // PTY 模式下 pty4j 的原生 login_tty 已经建立 session 与控制终端：只验证身份，绝不再次 setsid。
        long session = PosixProcessSession.currentSession();
        processGroup = PosixProcessSession.currentGroup();
        long pid = ProcessHandle.current().pid();
        if (session != pid || processGroup != pid) {
          throw new IllegalStateException(
              "the pty scope helper is not the leader of its session: sid="
                  + session
                  + " pgrp="
                  + processGroup
                  + " pid="
                  + pid);
        }
        if (!PosixProcessSession.isTerminal(0)
            || !PosixProcessSession.isTerminal(1)
            || !PosixProcessSession.isTerminal(2)) {
          throw new IllegalStateException(
              "the pty scope helper did not inherit a TTY on fds 0/1/2");
        }
      } else {
        PosixProcessSession.createSession();
        processGroup = PosixProcessSession.currentGroup();
        if (processGroup != ProcessHandle.current().pid()) {
          throw new IllegalStateException(
              "the scope helper is not the leader of its process group: " + processGroup);
        }
      }
      long session = PosixProcessSession.currentSession();
      // 收敛的唯一执行者是 convergePosixSession：它在两处被调用——命令自然退出后的这里，以及 JVM 默认 SIGTERM 处置触发的
      // shutdown hook。JVM 的信号处置是「捕获」而不是「忽略」，而 exec 会把被捕获的信号复位成默认处置，因此命令可以注册自己
      // 的 trap，helper 也不必在启动命令的瞬间改动任何信号处置（忽略状态会被 fork 继承，非交互 shell 无法覆盖）。
      // hook 必须在范围发布之前注册：发布之后父进程随时可能对会话广播温和信号。
      installConvergenceHook(session);
      PosixProcessSession.becomeChildSubreaper();
      ProcessScopeState.publish(stateDir, ProcessScopeState.SCOPE_FILE, Long.toString(session));
      awaitPermit();
      awaitSpawnRelease();
      // 派生与收敛在同一把锁上互斥：收敛开始之后绝不会有新成员诞生，命令 fork 出来的那一刻收敛也还没有开始。
      Process child = null;
      synchronized (spawnLock) {
        if (!stopping) {
          // 捕获模式把命令的 stderr 合并进 stdout（helper 的诊断已经指向诊断文件，不会被继承）；双向与 PTY 模式必须让
          // 三条流保持独立（PTY 下它们就是伪终端从端），父进程才能像对待一个直接启动的进程那样分别读它们。
          if (!inheritStreams) {
            PosixProcessSession.mergeStandardErrorIntoStandardOutput();
          }
          ProcessBuilder builder =
              new ProcessBuilder(spec.command())
                  .directory(Path.of(spec.workdir()).toFile())
                  .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                  .redirectError(ProcessBuilder.Redirect.INHERIT);
          if (inheritStreams) {
            // 命令的 stdin 就是 helper 自己的 stdin（父进程持有的双向管道，或伪终端从端）：既不新建空管道，也不关闭它。
            builder.redirectInput(ProcessBuilder.Redirect.INHERIT);
          } else {
            builder.redirectInput(ProcessBuilder.Redirect.PIPE);
          }
          child = builder.start();
          spawned = true;
          // 命令已经 fork 完成，此刻才开始忽略温和信号：收敛自己的会话信号会打到组长身上，helper 必须活到强杀与结论发布完成。
          // 放在 fork 之后是为了不让忽略状态被命令继承——非交互 shell 无法为「进入时已被忽略」的信号注册 trap。
          PosixProcessSession.ignoreTerminationSignal();
        }
      }
      if (child == null) {
        awaitConvergenceFinished();
        return convergenceResult ? 0 : 1;
      }
      // 捕获模式下命令的 stdin 是一条只有 helper 持有写端的空管道：立刻关闭写端，于是「等待 EOF 的命令自然退出」只取决于这里。
      if (!inheritStreams) {
        closeQuietly(child.getOutputStream());
      }
      int exitCode = child.waitFor();
      ProcessScopeState.publish(stateDir, ProcessScopeState.EXIT_FILE, Integer.toString(exitCode));
      // 命令的退出码已经回收，此后 waitpid(-1) 只会回收被收养的孤儿，不会偷走命令的状态。
      startOrphanReaper();
      return convergePosixSession(session) ? 0 : 1;
    }

    /** 注册收敛 hook：必须在范围发布之前，否则父进程可能先看到范围再对会话发信号，而那时还没有人能接住温和信号。 */
    private void installConvergenceHook(long session) {
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    // 顺序不能颠倒：先在锁内声明「不再派生」，再忽略温和信号。反过来会让一个尚未派生的命令继承忽略状态，
                    // 也会让收敛扫描与派生互相穿过。收尾期间屏蔽重复的温和信号也是这里的目的之一。
                    synchronized (spawnLock) {
                      stopping = true;
                      PosixProcessSession.ignoreTerminationSignal();
                    }
                    convergePosixSession(session);
                  },
                  "process-scope-convergence"));
    }

    /**
     * 收敛整个会话并把结果发布到 {@code cleanup}；同一次调用的收敛只执行一次——命令自然退出的 main 路径与 shutdown hook
     * 会竞争，先到者执行，后到者直接复用它的结论。
     */
    private boolean convergePosixSession(long session) {
      if (!convergenceStarted.compareAndSet(false, true)) {
        awaitConvergenceFinished();
        return convergenceResult;
      }
      try {
        boolean drained = convergeSession(session);
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

    /** 有界等待正在执行的收敛结束；等不到就显式失败：报告「还没收敛」比让调用方以为已经收敛安全得多。 */
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
          pty
              ? WindowsJobScope.createSuspendedForConsole(
                  WindowsJobScope.jobNameFor(stateDir), spec.command(), Path.of(spec.workdir()))
              : WindowsJobScope.createSuspended(
                  WindowsJobScope.jobNameFor(stateDir),
                  spec.command(),
                  Path.of(spec.workdir()),
                  inheritStreams);
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
     * 收缩整个会话；返回是否已经确认「除 helper 自己之外没有活着的成员」。
     *
     * <p>成员枚举按平台使用真实内核查询（Linux {@code /proc}、macOS libproc）；快照不可判定时不能发信号或宣称收敛。
     */
    private boolean convergeSession(long session) {
      if (!spawned) {
        // 从未派生过命令：stopping 已经在同一把锁内成立，因此不可能再有成员诞生。这是一条确定性的「没有成员」证明。
        return true;
      }
      long self = ProcessHandle.current().pid();
      PosixProcessSession.signalSession(session, PosixProcessSession.SIGTERM, self);
      if (awaitSessionGone(session, self, TERMINATION_GRACE)) {
        return true;
      }
      // 宽限窗口内没有收敛：对会话成员强杀（排除 helper 自己），覆盖连温和信号都不理的后代。
      PosixProcessSession.signalSession(session, PosixProcessSession.SIGKILL, self);
      return awaitSessionGone(session, self, TERMINATION_GRACE);
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
                  PosixProcessSession.reapOrphans();
                  sleepQuietly();
                }
              },
              "process-scope-orphan-reaper");
      reaper.setDaemon(true);
      reaper.start();
    }

    /** 等到会话里只剩 helper 自己；{@code hasLiveMember} 在 Linux 上按状态排除僵尸。 */
    private boolean awaitSessionGone(long session, long self, Duration budget) {
      long deadline = System.nanoTime() + budget.toNanos();
      while (PosixProcessSession.hasLiveMember(session, self)) {
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
