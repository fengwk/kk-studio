package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import com.pty4j.windows.conpty.WinConPtyProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
   * 必须拿到 ConPTY 对象，且 {@code resize} 之后原生窗口尺寸读回的是新值。
   *
   * <p>{@code cmd /c pause} 会一直等按键，正好提供一个稳定存活的控制台进程；{@code getWinSize()} 是真实的原生调用（ConPTY 屏幕
   * 缓冲区），因此它同时证明「命令确实在一个可调整尺寸的控制台里」。
   */
  @Test
  void conPtyIsUsedAndNativeWindowSizeFollowsResize() throws Exception {
    assumeTrue(isWindows(), "需要 Windows ConPTY");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir, List.of("cmd", "/c", "pause"), 80, 24, System.getenv(), () -> true);
    try {
      assertTrue(
          scope.process() instanceof WinConPtyProcess,
          "必须得到 ConPTY，绝不接受 WinPTY 回退：" + scope.process().getClass().getName());
      PtyProcess pty = (PtyProcess) scope.process();
      WinSize initial = pty.getWinSize();
      assertEquals(80, initial.getColumns(), "初始列数必须来自请求值");
      assertEquals(24, initial.getRows(), "初始行数必须来自请求值");

      scope.resize(100, 40);
      WinSize resized = pty.getWinSize();
      assertEquals(100, resized.getColumns(), "resize 之后原生列数必须变化");
      assertEquals(40, resized.getRows(), "resize 之后原生行数必须变化");
    } finally {
      assertTrue(scope.terminate(), "ConPTY 会话必须在终止后收敛");
      scope.close();
    }
  }

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
