package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link ProcessScope#startPty} 的真实终端验收：命令必须真的在伪终端里跑，而不是「看起来像终端」。
 *
 * <p>这里断言的是父进程一侧能独立观察的事实：命令的输出经由 PTY 主端回传（Windows 上这条路径就是 ConPTY 的控制台继承， 一旦 pty4j 退回 WinPTY，{@code
 * startPty} 会直接失败）、窗口尺寸调整真的到达命令、命令与 helper 同属一个会话、以及命令自然退出 或后台作业留下时整会话都被收敛。只有需要 POSIX {@code
 * isatty}/{@code stty}/后台作业语义的用例才显式跳过 Windows。
 */
class ProcessScopePtyIntegrationTest {

  /** 命令输出里必须出现的标记：它证明输出真的穿过了伪终端。 */
  private static final String MARKER = "__PTY_MARKER__";

  @TempDir Path workdir;

  /**
   * PTY 必须把命令的输出原样回传，并且退出码与收敛事实与其它模式一致。
   *
   * <p>Windows 上这条路径就是 ConPTY：命令附着到 helper 的伪控制台；如果 pty4j 退回 WinPTY，{@code startPty} 会在启动阶段显式
   * 失败，因此这条用例在 Windows 上同时是「绝不接受 WinPTY 回退」的验收。
   */
  @Test
  void ptyCarriesCommandOutputAndKeepsTheExactExitCode() throws Exception {
    ProcessScope scope =
        ProcessScope.startPty(workdir, markerCommand(), 80, 24, System.getenv(), () -> true);
    try {
      assertTrue(scope.scopeEstablished(), "PTY 范围必须在命令启动之前建立");
      String output = readUntil(scope.process().getInputStream(), MARKER, 30_000);
      assertTrue(output.contains(MARKER), "命令输出必须经由伪终端回传，实际为：" + output);
      assertTrue(scope.awaitNaturalExit(30_000), "命令必须在预算内自然退出");
      assertEquals(0, scope.naturalExitCode(), "命令退出码必须原样保真");
      assertTrue(scope.terminate(), "PTY 会话必须在终止后收敛");
      assertTrue(scope.converged());
    } finally {
      scope.close();
    }
  }

  /**
   * POSIX：命令必须真的拿到终端（fd 0/1/2 都是 TTY），并与 helper 同属一个会话、一个前台进程组。
   *
   * <p>会话归属来自内核查询：helper 是 PTY 的会话 leader（pty4j 的 {@code login_tty} 建立），命令继承同一会话，因此命令报告 的 {@code
   * sid}/{@code pgrp} 都等于 helper 的 pid——这正是「整会话收敛」可以成立的依据。
   */
  @Test
  void ptyGivesTheCommandARealTerminalInTheHelperSession() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX isatty 与 getsid 语义");
    Path pidFile = workdir.resolve("pty-probe.pid");
    Path resultFile = workdir.resolve("pty-probe.result");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            ProcessScopeFixtureMain.fixtureCommand(
                "pty-probe", pidFile.toString(), resultFile.toString()),
            80,
            24,
            System.getenv(),
            () -> true);
    try {
      assertTrue(scope.awaitNaturalExit(30_000), "pty-probe 必须在预算内结束");
      Map<String, String> facts = readFacts(resultFile);
      assertEquals("true", facts.get("tty0"), "命令的 stdin 必须是终端");
      assertEquals("true", facts.get("tty1"), "命令的 stdout 必须是终端");
      assertEquals("true", facts.get("tty2"), "命令的 stderr 必须是终端");
      long helperPid = scope.process().pid();
      assertEquals(
          helperPid, Long.parseLong(facts.get("sid")), "命令必须与 helper 同属一个会话（会话 id 等于 helper pid）");
      assertEquals(helperPid, Long.parseLong(facts.get("pgrp")), "命令必须继承 helper 的前台进程组");
      assertTrue(scope.terminate(), "PTY 会话必须在终止后收敛");
    } finally {
      scope.close();
    }
  }

  /** POSIX：窗口尺寸调整必须真的到达命令（命令用 {@code stty size} 回读内核里的尺寸）。 */
  @Test
  void ptyResizeDeliversTheNewWindowSizeToTheCommand() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX stty 读取窗口尺寸");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            List.of("sh", "-c", "read line; stty size; echo __SIZE_DONE__"),
            80,
            24,
            System.getenv(),
            () -> true);
    try {
      scope.resize(100, 40);
      scope.process().getOutputStream().write("go\n".getBytes(StandardCharsets.UTF_8));
      scope.process().getOutputStream().flush();
      String output = readUntil(scope.process().getInputStream(), "__SIZE_DONE__", 30_000);
      assertTrue(output.contains("40 100"), "调整后的尺寸必须被命令读到（行 列），实际为：" + output);
      assertTrue(scope.awaitNaturalExit(30_000), "命令必须在预算内自然退出");
      assertTrue(scope.terminate(), "PTY 会话必须在终止后收敛");
    } finally {
      scope.close();
    }
  }

  /**
   * POSIX：命令自然退出后留下的后台作业必须被整会话收敛，而不是只收敛命令自己所在的进程组。
   *
   * <p>非交互 {@code sh} 的后台作业与 shell 同属一个进程组；交互式 shell 会建立多个作业进程组，这正是收敛必须按会话（而不是单组） 进行的原因。
   */
  @Test
  void ptySessionConvergesBackgroundJobsAfterNaturalExit() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 后台作业语义");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            List.of("sh", "-c", "sleep 300 & sleep 300 & exit 0"),
            80,
            24,
            System.getenv(),
            () -> true);
    try {
      assertTrue(scope.awaitNaturalExit(30_000), "sh 必须在预算内自然退出");
      assertEquals(0, scope.naturalExitCode());
      assertTrue(scope.terminate(), "会话必须在终止后收敛（含后台作业）");
      assertTrue(scope.converged());
    } finally {
      scope.close();
    }
  }

  /** 未获许可的 PTY 启动必须失败关闭：命令一次都不该执行，状态目录也不残留。 */
  @Test
  void ptyUnpermittedStartNeverRunsTheCommand() throws Exception {
    Path marker = workdir.resolve("never-created");
    assertThrows(
        IllegalStateException.class,
        () ->
            ProcessScope.startPty(
                workdir, touchCommand(marker), 80, 24, System.getenv(), () -> false));
    assertFalse(Files.exists(marker), "没有得到许可的命令一次都不该执行");
  }

  /** 尺寸必须是正数：非法的 PTY 尺寸在启动之前就显式失败。 */
  @Test
  void ptyRejectsNonPositiveInitialSize() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProcessScope.startPty(workdir, markerCommand(), 0, 24, System.getenv(), () -> true));
  }

  /**
   * 跨平台：PTY 里跑一个只依赖 JDK 的夹具（不经过任何 shell），标记必须原样从伪终端回传，退出码必须保真。
   *
   * <p>这条用例在三平台都真跑：Windows 上它就是 ConPTY 的控制台继承实证；一旦 pty4j 退回 WinPTY，{@code startPty} 会直接失败。
   */
  @Test
  void ptyRunsAJvmFixtureAndKeepsItsExitCode() throws Exception {
    Path pidFile = workdir.resolve("pty-exit.pid");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            ProcessScopeFixtureMain.fixtureCommand("pty-exit", pidFile.toString(), "7"),
            80,
            24,
            System.getenv(),
            () -> true);
    try {
      String output = readUntil(scope.process().getInputStream(), "__PTY_FIXTURE_OK__", 30_000);
      assertTrue(output.contains("__PTY_FIXTURE_OK__"), "夹具标记必须经由伪终端回传，实际为：" + output);
      assertTrue(scope.awaitNaturalExit(30_000), "夹具必须在预算内自然退出");
      assertEquals(7, scope.naturalExitCode(), "退出码必须原样保真");
      assertTrue(scope.terminate(), "PTY 会话必须在终止后收敛");
      assertTrue(scope.converged());
    } finally {
      scope.close();
    }
  }

  /** Linux：自然退出不能代替 PTY 主端释放，close 必须交还本次打开的终端描述符。 */
  @Test
  void ptyCloseReleasesMasterDescriptorsAfterNaturalExit() throws Exception {
    assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"));
    Set<String> before = ptyDescriptors();
    for (int iteration = 0; iteration < 3; iteration++) {
      try (ProcessScope scope =
          ProcessScope.startPty(
              workdir, List.of("sh", "-c", "exit 0"), 80, 24, System.getenv(), () -> true)) {
        assertTrue(scope.awaitNaturalExit(30_000));
        assertEquals(0, scope.naturalExitCode());
      }
      assertEquals(before, ptyDescriptors(), "close 后不得残留 PTY 主端描述符");
    }
  }

  private static Set<String> ptyDescriptors() throws IOException {
    Set<String> descriptors = new HashSet<>();
    try (Stream<Path> entries = Files.list(Path.of("/proc/self/fd"))) {
      for (Path descriptor : entries.toList()) {
        try {
          String target = Files.readSymbolicLink(descriptor).toString();
          if (target.startsWith("/dev/pts/")) {
            descriptors.add(descriptor.getFileName() + ":" + target);
          }
        } catch (NoSuchFileException disappeared) {
          // 枚举期间已关闭的描述符不再占用资源。
        }
      }
    }
    return descriptors;
  }

  private static List<String> markerCommand() {
    return isWindows()
        ? List.of("cmd", "/c", "echo " + MARKER)
        : List.of("sh", "-c", "printf '" + MARKER + "\\n'");
  }

  private static List<String> touchCommand(Path marker) {
    return isWindows()
        ? List.of("cmd", "/c", "echo never > \"" + marker + "\"")
        : List.of("sh", "-c", "touch '" + marker + "'");
  }

  private static Map<String, String> readFacts(Path file) throws IOException {
    Map<String, String> facts = new HashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      int separator = line.indexOf('=');
      if (separator > 0) {
        facts.put(line.substring(0, separator), line.substring(separator + 1));
      }
    }
    return facts;
  }

  /**
   * 读到标记、EOF 或超时为止。
   *
   * <p>用 {@link InputStreamReader} + UTF-8 增量解码器连续解码，而不是对每个字节块做 {@code new String}：多字节字符会被跨块切开，逐块
   * 解码会把它变成替换字符。读取放在守护线程里并在 finally 里取消、关闭并汇合，因此命令挂住时用例仍能收敛，也不会在后台留下读取线程。
   */
  private static String readUntil(InputStream input, String marker, long millis) throws Exception {
    StringBuilder collected = new StringBuilder();
    Thread reader =
        new Thread(
            () -> {
              try (InputStreamReader decoder =
                  new InputStreamReader(input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[512];
                int read;
                while ((read = decoder.read(buffer)) >= 0) {
                  synchronized (collected) {
                    collected.append(buffer, 0, read);
                    if (collected.indexOf(marker) >= 0) {
                      return;
                    }
                  }
                }
              } catch (IOException error) {
                // 命令退出后主端被关闭：这是预期的结束条件，不是失败。
              }
            },
            "pty-output-reader");
    reader.setDaemon(true);
    reader.start();
    reader.join(millis);
    try {
      synchronized (collected) {
        return collected.toString();
      }
    } finally {
      // 取消并关闭读取端，再汇合守护线程：无论读到标记、EOF 还是超时，都不留下后台读取。
      closeQuietly(input);
      reader.interrupt();
      reader.join(1_000);
    }
  }

  private static void closeQuietly(InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      // 已经关闭或对端消失都等价于「不需要再关闭」。
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
