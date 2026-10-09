package fun.fengwk.kkstudio.harness.daemon.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.terminal.TerminalView;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link TerminalRuntime} 的真实跨平台 PTY 验收：命令输出经伪终端进入唯一内核、用户写入到达命令、自然退出 保留末屏与退出码、终端声明 TERM/COLORTERM。
 *
 * <p>全部使用自己的 JDK 夹具 argv（不经过 shell、不设置平台 assume），在 Linux/macOS/Windows 上使用相同断言验证。
 */
class TerminalRuntimeRealPtyTest {

  private static final long WAIT_SECONDS = 30L;
  private static final int COLUMNS = 80;
  private static final int ROWS = 24;
  private static final int HISTORY = 64;

  @TempDir Path workdir;

  private final List<ExecutorService> executors = new ArrayList<>();
  private final List<ScheduledExecutorService> schedulers = new ArrayList<>();

  @AfterEach
  void shutDownExecutors() {
    for (ExecutorService executor : executors) {
      executor.shutdownNow();
    }
    for (ScheduledExecutorService scheduler : schedulers) {
      scheduler.shutdownNow();
    }
  }

  @Test
  void userWriteReachesTheCommandAndItsOutputReturnsThroughTheKernel() throws Exception {
    TerminalRuntime runtime = start(TerminalRuntimeFixtureMain.fixtureCommand("echo"));
    try {
      // 终端 Enter 发 CR；ConPTY 的行输入不把单独 LF 当作按下 Enter。
      runtime
          .writeInput("hello\r".getBytes(StandardCharsets.UTF_8), 1L)
          .get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(awaitProjected(runtime, "ECHO:hello"), "命令必须读到写入的字节并把回显经 PTY 传回内核画面");
    } finally {
      runtime.close();
    }
  }

  @Test
  void naturalExitPreservesFinalViewAndExitCode() throws Exception {
    TerminalRuntime runtime = start(TerminalRuntimeFixtureMain.fixtureCommand("exit-code", "7"));
    try {
      runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertEquals(7, runtime.exitCode(), "自然退出的退出码必须原样保留");
      TerminalView finalView = runtime.finalView();
      assertNotNull(finalView, "自然退出必须保留一份末屏");
      assertTrue(projected(finalView).contains(TerminalRuntimeFixtureMain.MARKER));
      assertEquals(
          finalView, runtime.snapshot().get(WAIT_SECONDS, TimeUnit.SECONDS), "退出后 snapshot 仍返回末屏");
    } finally {
      runtime.close();
    }
  }

  @Test
  void startDeclaresTermAndTrueColorToTheCommand() throws Exception {
    Path resultFile = workdir.resolve("env-probe.txt");
    TerminalRuntime runtime =
        start(TerminalRuntimeFixtureMain.fixtureCommand("env-probe", resultFile.toString()));
    try {
      runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS);
      assertTrue(Files.isRegularFile(resultFile), "命令必须发布自身环境");
      String facts = Files.readString(resultFile, StandardCharsets.UTF_8);
      assertTrue(facts.contains("TERM=xterm-256color"), "必须声明 xterm-256color，实际为：" + facts);
      assertTrue(facts.contains("COLORTERM=truecolor"), "必须声明 truecolor，实际为：" + facts);
      assertTrue(projected(runtime.finalView()).contains(TerminalRuntimeFixtureMain.MARKER));
    } finally {
      runtime.close();
    }
  }

  @Test
  void repeatedCloseIsIdempotentAfterNaturalExit() throws Exception {
    TerminalRuntime runtime = start(TerminalRuntimeFixtureMain.fixtureCommand("exit-code", "0"));
    runtime.termination().get(WAIT_SECONDS, TimeUnit.SECONDS);
    runtime.close();
    runtime.close();
    assertTrue(runtime.termination().isDone());
    assertEquals(0, runtime.exitCode());
  }

  private TerminalRuntime start(List<String> command) throws Exception {
    TerminalLaunchSpec spec =
        new TerminalLaunchSpec(
            command.get(0), command.subList(1, command.size()), workdir.toAbsolutePath());
    return TerminalRuntime.start(
        spec,
        COLUMNS,
        ROWS,
        HISTORY,
        System.getenv(),
        () -> true,
        vtExecutor(),
        ioExecutor(),
        scheduler());
  }

  private static boolean awaitProjected(TerminalRuntime runtime, String expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
    while (System.nanoTime() < deadline) {
      if (projected(runtime.snapshot().get(WAIT_SECONDS, TimeUnit.SECONDS)).contains(expected)) {
        return true;
      }
      Thread.sleep(20L);
    }
    return false;
  }

  private static String projected(TerminalView view) {
    if (view == null) {
      return "";
    }
    StringBuilder text = new StringBuilder();
    for (TerminalView.Line line : view.lines()) {
      for (TerminalView.Slot slot : line.slots()) {
        text.append((char) slot.code());
      }
      text.append('\n');
    }
    return text.toString();
  }

  private ExecutorService vtExecutor() {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    executors.add(executor);
    return executor;
  }

  private ExecutorService ioExecutor() {
    ExecutorService executor = Executors.newCachedThreadPool();
    executors.add(executor);
    return executor;
  }

  private ScheduledExecutorService scheduler() {
    ScheduledExecutorService scheduled = Executors.newSingleThreadScheduledExecutor();
    schedulers.add(scheduled);
    return scheduled;
  }
}
