package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * helper 自身的失败去向：参数不足、工作目录不存在、父进程从未确认范围。
 *
 * <p>这些去向只能在真正的 helper 进程里成立（它们是 JVM 入口与启动预算的行为），因此这里直接启动 {@link ProcessScopeHelper} 的 {@code
 * main}，用状态目录里的握手文件说话——不给生产代码加任何测试专用分支。
 *
 * <p>「父进程从未确认范围」必须等满 helper 自己的许可预算（20 秒），因此这一条显式保留为一次性慢用例，而不是靠缩短预算或 循环猜时间来加快。
 */
class ProcessScopeHelperFailureTest {

  @TempDir Path workdir;

  /** 参数不足时 helper 必须立刻以 2 退出，且不创建任何状态文件。 */
  @Test
  void helperRejectsTooFewArguments() throws Exception {
    Path stateDir = Files.createDirectories(workdir.resolve("state-args"));
    List<Integer> exitCodes = new ArrayList<>();
    List<List<String>> argumentSets =
        List.of(
            List.of(),
            List.of(stateDir.toString()),
            List.of(stateDir.toString(), workdir.toString()));
    for (List<String> arguments : argumentSets) {
      exitCodes.add(runHelper(arguments, Duration.ofSeconds(30)));
      assertEquals(List.of(), filesIn(stateDir), "参数不足时不得产生任何握手文件");
    }
    assertEquals(List.of(2, 2, 2), exitCodes, "helper 的入口约定必须显式失败");
  }

  /** 工作目录不存在时命令无法启动：helper 必须显式失败并留下原因，而不是当作「命令跑过了」。 */
  @Test
  void helperFailsClosedWhenTheWorkdirDoesNotExist() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 命令与工作目录语义");
    Path stateDir = Files.createDirectories(workdir.resolve("state-workdir"));
    Path missingWorkdir = workdir.resolve("missing-workdir");
    Path marker = workdir.resolve("never-created");
    int exitCode =
        runHelperWithPermit(
            stateDir,
            missingWorkdir,
            List.of("sh", "-c", "touch '" + marker + "'"),
            Duration.ofSeconds(30));
    assertEquals(1, exitCode, "命令无法启动时 helper 必须失败关闭");
    String error = ProcessScopeState.read(stateDir, ProcessScopeState.ERROR_FILE);
    assertNotNull(error, "失败原因必须发布给父进程");
    assertTrue(error.contains(missingWorkdir.getFileName().toString()), error);
    assertFalse(Files.exists(marker), "命令一次都不该执行");
    assertNull(ProcessScopeState.read(stateDir, ProcessScopeState.EXIT_FILE), "没有命令退出码可发布");
  }

  /**
   * 父进程从未确认范围时，helper 必须在自己的许可预算耗尽后失败关闭，并且一次都不启动命令。
   *
   * <p>这条用例刻意等满 helper 的 20 秒预算：缩短预算会改变生产行为，循环探测则会把「什么时候放弃」变成猜测。
   */
  @Test
  void helperFailsClosedWhenThePermitNeverArrives() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 命令语义");
    Path stateDir = Files.createDirectories(workdir.resolve("state-permit"));
    Path marker = workdir.resolve("never-started");
    long started = System.nanoTime();
    int exitCode =
        runHelper(
            List.of(stateDir.toString(), workdir.toString(), "sh", "-c", "touch '" + marker + "'"),
            Duration.ofSeconds(90));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertEquals(1, exitCode, "没有许可就必须失败关闭");
    assertTrue(elapsedMillis >= 20_000, "必须等满 helper 自己的许可预算，而不是提前放弃：" + elapsedMillis);
    String error = ProcessScopeState.read(stateDir, ProcessScopeState.ERROR_FILE);
    assertNotNull(error);
    assertTrue(error.contains("did not confirm the process scope"), error);
    assertFalse(Files.exists(marker), "没有得到许可的命令一次都不该执行");
  }

  /**
   * helper 只以编译期常量引用启动契约，绝不加载父进程侧的 {@link ProcessScopeHelperLauncher}。
   *
   * <p>这个类带父进程的 logger：helper 是执行范围的核心，任何在 helper 里意外的 JUL 初始化都可能把日志绑到命令的 stderr （双向模式下正是命令的 stderr
   * 管道）。因此属性名必须是编译期内联的字符串常量，而不是对 launcher 的运行时引用。
   *
   * <p>用一个全新 JVM 打开 {@code -verbose:class} 直接跑 helper 入口：类加载日志重定向到本用例私有的文件，既证明 helper 入口真的被
   * 加载，又能逐行证明 launcher 从未出现在日志里。
   *
   * <p>命令退出码来自握手文件；macOS 的整组强杀会同时结束 helper，因此 helper 退出码按平台收敛语义独立断言。
   */
  @Test
  void helperNeverLoadsTheParentSideLauncher() throws Exception {
    Path stateDir = Files.createDirectories(workdir.resolve("state-class-load"));
    Path verboseLog = workdir.resolve("state-class-load-verbose.log");
    List<String> command = new ArrayList<>();
    command.add(javaBinary());
    command.add("-verbose:class");
    command.addAll(TestCoverageAgentArguments.forwarded());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(ProcessScopeHelper.class.getName());
    command.add(stateDir.toString());
    command.add(workdir.toString());
    command.addAll(exitCommand());
    Process helper =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(verboseLog.toFile())
            .start();
    boolean exited;
    try {
      // 父进程放行；helper 读到许可后启动命令并自然收尾。
      ProcessScopeState.publish(stateDir, ProcessScopeState.PERMIT_FILE, "permit");
      // 日志进文件，因此这里只做有界等待，不会因为输出超过管道缓冲而互相阻塞。
      exited = helper.waitFor(60, TimeUnit.SECONDS);
    } finally {
      // 用例失败或 helper 卡住时都必须有界收尾：既不把 helper 留在后台，也不做无界读取。
      if (helper.isAlive()) {
        helper.destroyForcibly();
        helper.waitFor(10, TimeUnit.SECONDS);
      }
    }
    assertTrue(exited, "helper 必须在预算内结束");
    assertEquals(expectedHelperExitCode(), helper.exitValue(), "helper 自己的去向必须符合平台的收敛约定");
    String verbose = Files.readString(verboseLog, StandardCharsets.UTF_8);
    assertTrue(verbose.contains("ProcessScopeHelper source"), "verbose:class 必须先证明 helper 入口已被加载");
    assertFalse(
        verbose.contains("ProcessScopeHelperLauncher"),
        "helper 绝不能加载父进程侧的 launcher（启动契约常量必须编译期内联）");
    // 命令的结局只由原子发布的握手文件说明：成功就是「退出码 0」加上「没有失败原因」。
    assertEquals(
        "0", ProcessScopeState.read(stateDir, ProcessScopeState.EXIT_FILE), "命令自然退出必须发布退出码 0");
    assertNull(ProcessScopeState.read(stateDir, ProcessScopeState.ERROR_FILE), "自然退出不得发布失败原因");
    if (isWindows()) {
      assertEquals(
          "true",
          ProcessScopeState.read(stateDir, ProcessScopeState.CLEANUP_FILE),
          "Windows Job 必须报告没有活动进程");
    } else {
      String scope = ProcessScopeState.read(stateDir, ProcessScopeState.SCOPE_FILE);
      assertNotNull(scope, "helper 必须在放行之前发布自己的进程组");
      awaitScopeConverged(Long.parseLong(scope));
    }
  }

  private int runHelperWithPermit(
      Path stateDir, Path commandWorkdir, List<String> command, Duration budget) throws Exception {
    Process helper = startHelper(stateDir, commandWorkdir, command);
    // 父进程放行的唯一证据就是这个文件：写下去之后 helper 才会尝试启动命令。
    ProcessScopeState.publish(stateDir, ProcessScopeState.PERMIT_FILE, "permit");
    return awaitExit(helper, budget);
  }

  private int runHelper(List<String> arguments, Duration budget) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(javaBinary());
    command.addAll(TestCoverageAgentArguments.forwarded());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(ProcessScopeHelper.class.getName());
    command.addAll(arguments);
    Process helper = new ProcessBuilder(command).redirectErrorStream(true).start();
    return awaitExit(helper, budget);
  }

  private Process startHelper(Path stateDir, Path commandWorkdir, List<String> command)
      throws IOException {
    List<String> arguments = new ArrayList<>();
    arguments.add(stateDir.toString());
    arguments.add(commandWorkdir.toString());
    arguments.addAll(command);
    List<String> helperCommand = new ArrayList<>();
    helperCommand.add(javaBinary());
    helperCommand.addAll(TestCoverageAgentArguments.forwarded());
    helperCommand.add("-cp");
    helperCommand.add(System.getProperty("java.class.path"));
    helperCommand.add(ProcessScopeHelper.class.getName());
    helperCommand.addAll(arguments);
    return new ProcessBuilder(helperCommand).redirectErrorStream(true).start();
  }

  private static int awaitExit(Process helper, Duration budget) throws Exception {
    if (!helper.waitFor(budget.toMillis(), TimeUnit.MILLISECONDS)) {
      helper.destroyForcibly();
      throw new AssertionError("helper 必须在预算内结束");
    }
    return helper.exitValue();
  }

  private static List<String> filesIn(Path directory) throws IOException {
    try (var entries = Files.list(directory)) {
      return entries.map(path -> path.getFileName().toString()).sorted().toList();
    }
  }

  /**
   * helper 自身退出码的平台约定：只有 Linux/WSL 与 Windows 能让 helper 明确收尾并以 0 结束；其它 POSIX 平台（macOS）只能向整组广播
   * 强杀，helper 自己也被一并终结，因此是 128 + SIGKILL。
   */
  private static int expectedHelperExitCode() {
    if (isWindows() || isLinuxLike()) {
      return 0;
    }
    return 128 + PosixProcessGroup.SIGKILL;
  }

  /** 确认 helper 专有进程组已经收敛：helper 已退出并被回收，组里不该再有任何活着的成员；等待有界，等不到就显式失败。 */
  private static void awaitScopeConverged(long processGroup) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (PosixProcessGroup.hasLiveMember(processGroup, PosixProcessGroup.NO_PROCESS)) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("helper 专有进程组必须已经收敛：" + processGroup);
      }
      Thread.sleep(20);
    }
  }

  /** helper 侧能排除自己的收敛判定只在 Linux/WSL 成立。 */
  private static boolean isLinuxLike() {
    DaemonOperatingSystem operatingSystem = DaemonOperatingSystemDetector.detectCurrent();
    return operatingSystem == DaemonOperatingSystem.LINUX
        || operatingSystem == DaemonOperatingSystem.WSL;
  }

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"))
        .resolve("bin")
        .resolve(isWindows() ? "java.exe" : "java")
        .toString();
  }

  /** 立刻以 0 退出的最小跨平台命令：只用来让 helper 走完整的「派生 -> 自然退出 -> 收敛」路径。 */
  private static List<String> exitCommand() {
    return isWindows() ? List.of("cmd", "/c", "exit 0") : List.of("sh", "-c", "exit 0");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
