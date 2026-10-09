package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pty4j.windows.conpty.WinConPtyProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Windows ConPTY 验收：命令必须真的附着到 ConPTY 控制台，窗口尺寸必须能通过原生 API 读回，子进程必须随 Job 一起收敛。
 *
 * <p>这些事实在 Linux/macOS 上无法构造（同一段代码在那里走 pty4j 的 unix 实现），因此非 Windows 平台整体跳过；在 Windows 上它们
 * 是必跑的核心验收：一旦 pty4j 退回 WinPTY，{@code startPty} 会直接失败，{@link
 * #conPtyIsUsedAndNativeWindowSizeFollowsResize} 还会显式断言拿到的是 {@link WinConPtyProcess}。
 */
class ProcessScopeConPtyIntegrationTest {

  @TempDir Path workdir;

  /**
   * 必须拿到 ConPTY 对象，且 {@code resize} 之后命令自己读回的原生窗口尺寸是新值。
   *
   * <p>夹具在自己的 ConPTY 控制台里用原生 {@code GetConsoleScreenBufferInfo} 读回可见视口，因此这里的断言是「命令自身看到的窗口」， 而不是
   * pty4j 缓存的上次请求值：初始必须读到 80x24，父进程 {@code resize(100,40)} 并放行后必须读到 100x40。
   */
  @Test
  void conPtyIsUsedAndNativeWindowSizeFollowsResize() throws Exception {
    assumeTrue(isWindows(), "需要 Windows ConPTY");
    Path pidFile = workdir.resolve("conpty-size.pid");
    Path initialSizeFile = workdir.resolve("conpty-size.initial");
    Path resizePermitFile = workdir.resolve("conpty-size.permit");
    Path resizedSizeFile = workdir.resolve("conpty-size.resized");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            ProcessScopeFixtureMain.fixtureCommand(
                "conpty-size",
                pidFile.toString(),
                initialSizeFile.toString(),
                resizePermitFile.toString(),
                resizedSizeFile.toString(),
                "100",
                "40"),
            80,
            24,
            System.getenv(),
            () -> true);
    try {
      assertTrue(
          scope.process() instanceof WinConPtyProcess,
          "必须得到 ConPTY，绝不接受 WinPTY 回退：" + scope.process().getClass().getName());
      Viewport initial = awaitViewport(initialSizeFile);
      assertEquals(80, initial.columns(), "初始列数必须是命令自己读到的原生窗口");
      assertEquals(24, initial.rows(), "初始行数必须是命令自己读到的原生窗口");

      scope.resize(100, 40);
      Files.writeString(resizePermitFile, "go", StandardCharsets.UTF_8);
      Viewport resized = awaitViewport(resizedSizeFile);
      assertEquals(100, resized.columns(), "resize 之后命令自己读到的原生列数必须是新值");
      assertEquals(40, resized.rows(), "resize 之后命令自己读到的原生行数必须是新值");

      assertTrue(scope.awaitNaturalExit(30_000), "命令必须在预算内自然退出");
      assertEquals(0, scope.naturalExitCode(), "原生探测必须正常退出");
      assertTrue(scope.terminate(), "ConPTY 会话必须在终止后收敛");
      assertTrue(scope.converged());
    } finally {
      scope.close();
    }
  }

  /** 有界等待夹具发布原生窗口尺寸，并解析出列/行（文件由夹具原子改名，父进程不会读到半截数值）。 */
  private static Viewport awaitViewport(Path sizeFile) throws Exception {
    long deadline = System.nanoTime() + 30_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(sizeFile)) {
        String[] parts = Files.readString(sizeFile, StandardCharsets.UTF_8).trim().split("\\s+");
        assertEquals(2, parts.length, "原子发布的尺寸文件必须恰好包含列数与行数");
        return new Viewport(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
      }
      Thread.sleep(20);
    }
    throw new AssertionError("命令没有在预算内发布原生窗口尺寸：" + sizeFile);
  }

  /** 命令自己读到的原生控制台可见视口尺寸。 */
  private record Viewport(int columns, int rows) {}

  /**
   * 命令自然退出后留下的子进程必须随 Job 一起被清掉：ConPTY 模式下命令同样归属到命名 Job，收敛覆盖整个 Job。
   *
   * <p>夹具先派生子进程并等许可文件，命令再退出，因此「子进程原本活着」由调用方掌握；收敛之后它必须消失。
   */
  @Test
  void conPtySessionConvergesAChildThatOutlivesTheCommand() throws Exception {
    assumeTrue(isWindows(), "需要 Windows Job Object 收敛");
    Path pidFile = workdir.resolve("conpty.pid");
    Path childPidFile = workdir.resolve("conpty-child.pid");
    Path permitFile = workdir.resolve("conpty.permit");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            ProcessScopeFixtureMain.fixtureCommand(
                "fork-exit", pidFile.toString(), childPidFile.toString(), permitFile.toString()),
            80,
            24,
            System.getenv(),
            () -> true);
    try {
      long childPid = awaitPid(childPidFile);
      Files.writeString(permitFile, "go", StandardCharsets.UTF_8);
      assertTrue(scope.awaitNaturalExit(30_000), "命令必须在预算内自然退出");
      assertTrue(scope.terminate(), "Job 必须被显式终止并确认没有活动进程");
      assertTrue(scope.converged());
      assertFalse(ProcessHandle.of(childPid).isPresent(), "子进程必须随 Job 一起被清掉");
    } finally {
      scope.close();
    }
  }

  private static long awaitPid(Path pidFile) throws Exception {
    long deadline = System.nanoTime() + 30_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (Files.isRegularFile(pidFile)) {
        String value = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
        if (!value.isEmpty()) {
          return Long.parseLong(value);
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("子进程没有在预算内发布 pid");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
