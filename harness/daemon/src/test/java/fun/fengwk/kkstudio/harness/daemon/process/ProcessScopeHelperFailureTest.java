package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * helper 自身的失败去向：参数不足、缺少启动规格、工作目录不存在、父进程从未确认范围。
 *
 * <p>这些去向只能在真正的 helper 进程里成立（它们是 JVM 入口与启动预算的行为），因此这里直接启动 {@link ProcessScopeHelper} 的 {@code
 * main}，用状态目录里的握手文件说话——不给生产代码加任何测试专用分支。helper 的命令行只携带状态目录，启动规格（workdir 与 argv） 通过 {@link
 * ProcessScopeState#LAUNCH_FILE} 交接，因此这些用例也顺带回归「启动规格不进入命令行」的约定。
 *
 * <p>「父进程从未确认范围」必须等满 helper 自己的许可预算（20 秒），因此这一条显式保留为一次性慢用例，而不是靠缩短预算或循环猜时间来加快。
 */
class ProcessScopeHelperFailureTest {

  @TempDir Path workdir;

  /** 完全没有参数时 helper 必须立刻以 2 退出，且不创建任何状态文件。 */
  @Test
  void helperRejectsMissingArguments() throws Exception {
    Path stateDir = Files.createDirectories(workdir.resolve("state-args"));
    assertEquals(2, runHelper(List.of(), Duration.ofSeconds(30)), "helper 的入口约定必须显式失败");
    assertEquals(List.of(), filesIn(stateDir), "参数不足时不得产生任何握手文件");
  }

  /** 有状态目录但没有启动规格时必须显式失败并留下原因，绝不当作「命令跑过了」。 */
  @Test
  void helperFailsClosedWithoutALaunchSpec() throws Exception {
    Path stateDir = Files.createDirectories(workdir.resolve("state-launch"));
    assertEquals(1, runHelper(List.of(stateDir.toString()), Duration.ofSeconds(30)));
    String error = ProcessScopeState.read(stateDir, ProcessScopeState.ERROR_FILE);
    assertNotNull(error, "缺少启动规格时必须发布失败原因");
    assertTrue(error.contains("launch spec"), error);
    assertNull(ProcessScopeState.read(stateDir, ProcessScopeState.EXIT_FILE), "没有命令退出码可发布");
  }

  /** 错写 stdio 属性不得静默变成捕获模式，也不得把大小写别名当成有效模式。 */
  @Test
  void helperRejectsAnUnknownStdioMode() throws Exception {
    Path stateDir = Files.createDirectories(workdir.resolve("state-stdio"));
    ProcessScopeState.publishLaunch(stateDir, workdir, List.of("never-started"));
    assertEquals(
        1,
        runHelper(
            List.of(stateDir.toString()),
            Duration.ofSeconds(30),
            "-D" + ProcessScope.STDIO_PROPERTY + "=PTY"));
    assertEquals(
        "unsupported process scope stdio mode",
        ProcessScopeState.read(stateDir, ProcessScopeState.ERROR_FILE));
    assertNull(ProcessScopeState.read(stateDir, ProcessScopeState.SCOPE_FILE));
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
    assertEquals("the command could not be started", error, "命令启动失败只报告固定类型，不回显命令与工作目录");
    assertFalse(error.contains(missingWorkdir.getFileName().toString()), error);
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
    ProcessScopeState.publishLaunch(
        stateDir, workdir, List.of("sh", "-c", "touch '" + marker + "'"));
    long started = System.nanoTime();
    int exitCode = runHelper(List.of(stateDir.toString()), Duration.ofSeconds(90));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    assertEquals(1, exitCode, "没有许可就必须失败关闭");
    assertTrue(elapsedMillis >= 20_000, "必须等满 helper 自己的许可预算，而不是提前放弃：" + elapsedMillis);
    String error = ProcessScopeState.read(stateDir, ProcessScopeState.ERROR_FILE);
    assertNotNull(error);
    assertTrue(error.contains("did not confirm the process scope"), error);
    assertFalse(Files.exists(marker), "没有得到许可的命令一次都不该执行");
  }

  private int runHelperWithPermit(
      Path stateDir, Path commandWorkdir, List<String> command, Duration budget) throws Exception {
    ProcessScopeState.publishLaunch(stateDir, commandWorkdir, command);
    Process helper = startHelper(stateDir);
    // 父进程放行的唯一证据就是这个文件：写下去之后 helper 才会尝试启动命令。
    ProcessScopeState.publish(stateDir, ProcessScopeState.PERMIT_FILE, "permit");
    return awaitExit(helper, budget);
  }

  private int runHelper(List<String> arguments, Duration budget, String... jvmArguments)
      throws Exception {
    List<String> command = new ArrayList<>();
    command.add(javaBinary());
    command.addAll(TestCoverageAgentArguments.forwarded());
    command.addAll(List.of(jvmArguments));
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(ProcessScopeHelper.class.getName());
    command.addAll(arguments);
    Process helper = new ProcessBuilder(command).redirectErrorStream(true).start();
    return awaitExit(helper, budget);
  }

  private Process startHelper(Path stateDir) throws IOException {
    List<String> helperCommand = new ArrayList<>();
    helperCommand.add(javaBinary());
    helperCommand.addAll(TestCoverageAgentArguments.forwarded());
    helperCommand.add("-cp");
    helperCommand.add(System.getProperty("java.class.path"));
    helperCommand.add(ProcessScopeHelper.class.getName());
    helperCommand.add(stateDir.toString());
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

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"))
        .resolve("bin")
        .resolve(isWindows() ? "java.exe" : "java")
        .toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
