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

    private Helper(Path stateDir, Path workdir, List<String> command) {
      this.stateDir = stateDir;
      this.workdir = workdir;
      this.command = command;
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
      // 从这一刻起忽略温和信号：父进程对整组广播温和信号时，helper 必须活到强杀阶段。
      PosixProcessGroup.ignoreTerminationSignal();
      PosixProcessGroup.becomeChildSubreaper();
      ProcessScopeState.publish(
          stateDir, ProcessScopeState.SCOPE_FILE, Long.toString(processGroup));
      awaitPermit();
      // 命令的 stderr 与 stdout 合并进捕获管道；helper 的诊断已经指向诊断文件，不会被继承。
      PosixProcessGroup.mergeStandardErrorIntoStandardOutput();
      Process child;
      // 信号处置随 fork 继承：helper 忽略 SIGTERM 是为了不被父进程的整组温和信号打死，但命令不能带着这个忽略状态开始——
      // POSIX 规定非交互 shell 无法注册「进入时已被忽略」的信号，脚本里的 trap 会静默失效、只剩强杀。因此只在 fork 的瞬间
      // 恢复默认处置，命令一启动就重新忽略；这个窗口里若真的收到整组温和信号，父进程自己的内核检查仍是唯一结论来源。
      PosixProcessGroup.restoreDefaultTerminationSignal();
      try {
        child =
            new ProcessBuilder(command)
                .directory(workdir.toFile())
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
      } finally {
        PosixProcessGroup.ignoreTerminationSignal();
      }
      // 命令的 stdin 是一条只有 helper 持有写端的空管道：立刻关闭写端，于是「等待 EOF 的命令自然退出」只取决于这里，
      // 而不是调用方何时关闭自己的写端，也不是是否有别的进程持有了写端的副本。
      closeQuietly(child.getOutputStream());
      int exitCode = child.waitFor();
      ProcessScopeState.publish(stateDir, ProcessScopeState.EXIT_FILE, Integer.toString(exitCode));
      // 命令的退出码已经回收，此后 waitpid(-1) 只会回收被收养的孤儿，不会偷走命令的状态。
      startOrphanReaper();
      boolean drained = convergeGroup(processGroup);
      ProcessScopeState.publish(
          stateDir, ProcessScopeState.CLEANUP_FILE, Boolean.toString(drained));
      return drained ? 0 : 1;
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
          WindowsJobScope.createSuspended(WindowsJobScope.jobNameFor(stateDir), command, workdir);
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
