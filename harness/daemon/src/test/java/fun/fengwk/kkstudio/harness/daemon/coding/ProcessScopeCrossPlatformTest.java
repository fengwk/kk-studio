package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 执行范围的跨平台真实进程验收：用 {@link ProcessScopeFixtureMain} 造出真实的父子/孙进程层级，并用原生 PID 断言收敛。
 *
 * <p>这些用例刻意不使用任何平台 shell（Linux/macOS 与 Windows 都不需要 bash、MSYS 或 POSIX 工具），因此在三个平台上都会真的 执行，不会被
 * {@code assumeFalse} 跳过——Windows 的 Job 语义与 POSIX 的进程组语义由此得到同一份事实检验：
 *
 * <ul>
 *   <li>根进程自然退出时，它留下的活着的子进程必须在范围完结时已经消失；
 *   <li>终止（超时/取消走的是同一条路径）必须覆盖嵌套后代，而不只是根进程；
 *   <li>许可之前的取消不得让夹具（以及它的子进程）执行过哪怕一条指令。
 * </ul>
 *
 * <p>「子进程曾经真的活着」由它自己写下的 pid 文件固定：夹具的存活时间是 600 秒，远长于测试窗口，因此「pid 不再存活」只能由 执行范围的收敛解释，而不是子进程自己退出。
 */
class ProcessScopeCrossPlatformTest {

  private static final Duration PID_BUDGET = Duration.ofSeconds(20);
  private static final Duration GONE_BUDGET = Duration.ofSeconds(20);
  private static final long POLL_INTERVAL_MILLIS = 20;

  @TempDir Path workdir;

  /**
   * 根进程自然退出、子进程仍在运行时，范围完结后子进程必须已经消失。
   *
   * <p>这正是 C01 的核心事实：自然退出不靠「根进程死了」结案，而是靠整组收敛结案。
   */
  @Test
  void naturalExitConvergesLiveChildren() throws Exception {
    Path rootPid = workdir.resolve("root.pid");
    Path childPid = workdir.resolve("child.pid");
    ProcessScope scope = ProcessScope.start(workdir, fixture("fork-exit", rootPid, childPid));
    try {
      // 读到 EOF 才结束：捕获管道只有在范围内所有进程都不再持有写端时才会关闭。
      readAll(scope.process().getInputStream());
      scope.process().waitFor();
      long child = awaitPid(childPid, PID_BUDGET);
      assertTrue(awaitPid(rootPid, PID_BUDGET) > 0, "根进程必须真的运行过");
      assertTrue(scope.terminate(), "范围必须由内核或 Job 确认为已经收敛");
      assertTrue(awaitGone(child, GONE_BUDGET), "自然退出后子进程 " + child + " 必须已经被收敛");
    } finally {
      scope.close();
    }
  }

  /** 终止必须覆盖嵌套后代：孙进程用原生 PID 观察，且它也不能在范围收敛后继续存活。 */
  @Test
  void terminateConvergesNestedProcesses() throws Exception {
    Path rootPid = workdir.resolve("root.pid");
    Path childPid = workdir.resolve("child.pid");
    Path grandPid = workdir.resolve("grand.pid");
    ProcessScope scope =
        ProcessScope.start(workdir, fixture("nest-hold", rootPid, childPid, grandPid));
    try {
      long root = awaitPid(rootPid, PID_BUDGET);
      long child = awaitPid(childPid, PID_BUDGET);
      long grand = awaitPid(grandPid, PID_BUDGET);
      assertTrue(isAlive(root), "根进程必须仍然存活：" + root);
      assertTrue(isAlive(child), "子进程必须仍然存活：" + child);
      assertTrue(isAlive(grand), "孙进程必须仍然存活：" + grand);
      assertTrue(scope.terminate(), "终止后必须由内核或 Job 确认整组结束");
      for (long pid : List.of(root, child, grand)) {
        assertTrue(awaitGone(pid, GONE_BUDGET), "终止后 " + pid + " 必须已经消失");
      }
    } finally {
      scope.close();
    }
  }

  /**
   * 命令从不写 stdin 的调用方那里拿到的是确定性的 EOF：夹具读 {@code System.in} 直到 EOF，再用给定退出码自然退出。
   *
   * <p>这条事实不经过任何平台的 shell，因此「stdin 管道是否还有别的写端持有者」会被真正检验：只要还有任何一个写端活着， 命令就永远读不到 EOF，只能等超时。
   */
  @Test
  void stdinEofLetsTheFixtureExitNaturally() throws Exception {
    Path rootPid = workdir.resolve("root.pid");
    ProcessScope scope = ProcessScope.start(workdir, fixture("eof", rootPid, "42"));
    try {
      awaitPid(rootPid, PID_BUDGET);
      // 调用方从不向命令写入数据，只关闭自己那一侧的写端。
      scope.process().getOutputStream().close();
      assertTrue(scope.process().waitFor(30, TimeUnit.SECONDS), "命令必须在读到 EOF 后自然退出，而不是阻塞到超时");
      Integer exitCode = scope.naturalExitCode();
      assertNotNull(exitCode, "helper 必须发布命令的自然退出码");
      assertEquals(42, exitCode.intValue(), "自然退出码必须来自命令自身");
      assertTrue(scope.terminate(), "收敛必须由内核或 Job 确认");
    } finally {
      scope.close();
    }
  }

  /**
   * 双向标准流：命令的 stdin/stdout 是调用方直接持有的管道，而 stderr 仍然走自己的通道。
   *
   * <p>这条用例同时固定三件事：写进 stdin 的字节真的到达命令（而不是像捕获模式那样被一条立刻关闭的空管道顶替）；命令的输出可以按行读回；命令 的 stderr 没有被合并进
   * stdout。捕获模式会把命令的 stderr 指向 stdout 的捕获句柄，因此这里的 stderr 断言正是两种模式的判别事实。
   *
   * <p>退出方式与捕获模式一致：调用方关闭自己那一侧的写端，命令读到 EOF 后自然退出，退出码由命令自己发布。
   */
  @Test
  void duplexStdioCarriesInputAndKeepsStderrSeparate() throws Exception {
    Path rootPid = workdir.resolve("root.pid");
    ProcessScope scope = ProcessScope.startDuplex(workdir, fixture("duplex-echo", rootPid, "42"));
    try {
      awaitPid(rootPid, PID_BUDGET);
      OutputStream stdin = scope.process().getOutputStream();
      stdin.write("ping-42\n".getBytes(StandardCharsets.UTF_8));
      stdin.flush();
      BufferedReader stdout =
          new BufferedReader(
              new InputStreamReader(scope.process().getInputStream(), StandardCharsets.UTF_8));
      assertEquals("ping-42", stdout.readLine(), "双向模式下命令必须收到调用方写进 stdin 的字节");

      // 关闭调用方这一侧的写端：命令因此读到 EOF，并用自己的退出码自然退出。
      stdin.close();
      assertTrue(scope.awaitNaturalExit(PID_BUDGET.toMillis()), "命令必须在读到 EOF 后自然退出，而不是阻塞到超时");
      Integer exitCode = scope.naturalExitCode();
      assertNotNull(exitCode, "命令的退出码必须来自它自己的发布");
      assertEquals(42, exitCode.intValue(), "自然退出码必须来自命令自身");
      assertTrue(scope.process().waitFor(30, TimeUnit.SECONDS), "keeper 必须在命令退出后收敛并结束");

      String restOfStdout = readAll(scope.process().getInputStream());
      String stderr = readAll(scope.process().getErrorStream());
      assertTrue(
          stderr.contains(ProcessScopeFixtureMain.STDERR_MARKER), "命令的 stderr 必须走它自己的通道：" + stderr);
      assertFalse(
          restOfStdout.contains(ProcessScopeFixtureMain.STDERR_MARKER),
          "双向模式绝不能把命令的 stderr 合并进 stdout：" + restOfStdout);
      assertTrue(scope.terminate(), "收敛必须由内核或 Job 确认");
    } finally {
      scope.close();
    }
  }

  /**
   * 双向标准流下根进程自然退出：它留下的活着的子进程同样必须被收敛。
   *
   * <p>双向模式没有「捕获管道读到 EOF」这个信号可用，因此范围完结只能靠命令自己的退出事实与整组收敛来判定；这条用例确认 收敛并不依赖捕获管道。
   */
  @Test
  void duplexStdioConvergesLiveChildrenAfterNaturalExit() throws Exception {
    Path rootPid = workdir.resolve("root.pid");
    Path childPid = workdir.resolve("child.pid");
    ProcessScope scope =
        ProcessScope.startDuplex(workdir, fixture("duplex-fork-exit", rootPid, childPid));
    try {
      long child = awaitPid(childPid, PID_BUDGET);
      assertTrue(isAlive(child), "子进程必须仍然存活：" + child);
      assertTrue(scope.awaitNaturalExit(PID_BUDGET.toMillis()), "根进程必须在自己派生子进程之后自然退出");
      assertTrue(scope.terminate(), "范围必须由内核或 Job 确认为已经收敛");
      assertTrue(awaitGone(child, GONE_BUDGET), "自然退出后子进程 " + child + " 必须已经被收敛");
    } finally {
      scope.close();
    }
  }

  /** 许可之前的取消：夹具从未运行，因此连自己的 pid 文件都不会出现（Windows 上它可能已被创建但绝不会被执行）。 */
  @Test
  void unpermittedStartNeverRunsTheFixture() throws Exception {
    Path rootPid = workdir.resolve("root.pid");
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> ProcessScope.start(workdir, fixture("hold", rootPid), () -> false));
    assertTrue(
        failure.getMessage().contains("cancelled before the user command"), failure.getMessage());
    assertFalse(Files.exists(rootPid), "许可之前取消不得让命令产生任何副作用");
  }

  /**
   * 夹具命令：只把夹具自己所在的代码位置交给子 JVM。
   *
   * <p>刻意不用 {@code java.class.path}：surefire 可能用清单 JAR 启动测试 JVM，那时这个属性并不等于测试类路径；夹具只依赖 JDK，
   * 因此它需要的类路径就只有它自己所在的位置，嵌套子进程再原样传递下去。
   */
  private static List<String> fixture(String mode, Object... arguments) {
    List<String> command =
        new ArrayList<>(
            List.of(
                javaBinary(),
                "-cp",
                fixtureClasspath(),
                ProcessScopeFixtureMain.class.getName(),
                mode));
    for (Object argument : arguments) {
      command.add(String.valueOf(argument));
    }
    return command;
  }

  private static String javaBinary() {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
        .toString();
  }

  private static String fixtureClasspath() {
    try {
      return Path.of(
              ProcessScopeFixtureMain.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI())
          .toString();
    } catch (URISyntaxException error) {
      throw new IllegalStateException("cannot locate the process scope fixture classpath", error);
    }
  }

  private static long awaitPid(Path pidFile, Duration budget) throws IOException {
    long deadline = System.nanoTime() + budget.toNanos();
    while (true) {
      long pid = readPid(pidFile);
      if (pid > 0) {
        return pid;
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException("no pid published in " + pidFile + " within " + budget);
      }
      sleepQuietly();
    }
  }

  private static long readPid(Path pidFile) throws IOException {
    if (!Files.isRegularFile(pidFile)) {
      return -1;
    }
    try {
      return Long.parseLong(Files.readString(pidFile).trim());
    } catch (NumberFormatException error) {
      return -1;
    }
  }

  private static boolean awaitGone(long pid, Duration budget) {
    long deadline = System.nanoTime() + budget.toNanos();
    while (true) {
      if (!isAlive(pid)) {
        return true;
      }
      if (System.nanoTime() >= deadline) {
        return false;
      }
      sleepQuietly();
    }
  }

  private static boolean isAlive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  private static void sleepQuietly() {
    try {
      Thread.sleep(POLL_INTERVAL_MILLIS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for the process fixture", error);
    }
  }

  private static String readAll(InputStream input) throws IOException {
    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
  }
}
