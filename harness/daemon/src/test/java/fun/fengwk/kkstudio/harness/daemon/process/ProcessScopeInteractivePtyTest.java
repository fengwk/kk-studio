package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 真正的交互式 shell 验收：在 PTY 里运行 {@code bash --noprofile --norc -i}，验证产品要用的 job control 语义。
 *
 * <p>与 {@link ProcessScopePtyIntegrationTest} 的差异正是本类存在的理由：那边只跑非交互命令，证明不了「交互 shell 的 monitor
 * 模式、作业控制、多进程组会话」。这里用受控环境（{@code PS1}/{@code TERM}/{@code LC_ALL}，清掉 {@code BASH_ENV}/ {@code
 * ENV}/{@code PROMPT_COMMAND}）与哨兵字符串同步：命令里把哨兵拆成两段（{@code "__P""ID__"}），因此回显的输入行不会命中
 * 哨兵，只有命令的输出会命中——同步不依赖时间猜测。
 *
 * <p>平台前提：需要 POSIX 的 {@code bash}、{@code ps -o pgid=}、{@code jobs} 与终端信号语义，因此 Windows 上整体跳过（这些用例在
 * Linux/macOS 上是必跑的核心验收）。
 */
class ProcessScopeInteractivePtyTest {

  /** 后台作业的存活时间：远大于用例窗口，使「作业消失」只能由收敛解释。 */
  private static final String LONG_SLEEP = "sleep 300";

  @TempDir Path workdir;

  /**
   * 交互式 bash 必须真的开启 monitor 模式，并且作业控制会为前台/后台作业建立新的进程组——这些进程组都在 helper 的同一个会话里。
   *
   * <p>这是「按会话收敛而不是按单组收敛」的实证：后台作业的 {@code pgid} 等于它自己的 pid（独立作业组），而它的 {@code sid} 等于 helper 的 pid。
   */
  @Test
  void interactiveBashRunsWithJobControlAndMultiProcessGroupSession() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 交互式 bash 与 ps/jobs");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            List.of("/bin/bash", "--noprofile", "--norc", "-i"),
            80,
            24,
            consoleEnvironment(),
            () -> true);
    try (PtyConsole console = new PtyConsole(scope.process())) {
      console.write("echo \"__M\"\"ON__$-\"\n");
      String flags = console.awaitToken("__MON__", 20_000);
      assertTrue(flags.contains("m"), "交互式 bash 必须处于 monitor 模式，$-=" + flags);
      console.write("echo \"__P\"\"ID__$$\"\n");
      long shellPid = Long.parseLong(console.awaitToken("__PID__", 20_000));

      console.write(LONG_SLEEP + " & echo \"__J\"\"OB__$!\"\n");
      long jobPid = Long.parseLong(console.awaitToken("__JOB__", 20_000));
      console.write("echo \"__P\"\"GID__$(ps -o pgid= -p " + jobPid + ")\"\n");
      long jobPgid = Long.parseLong(console.awaitToken("__PGID__", 20_000));

      assertNotEquals(shellPid, jobPid, "后台作业必须是独立进程");
      assertEquals(jobPid, jobPgid, "作业控制必须让后台作业拥有自己的进程组（pgid == 作业 pid）");
      assertEquals(
          scope.process().pid(),
          PosixProcessSession.sessionOf(jobPid),
          "作业组必须仍在 helper 的会话里（sid == helper pid）——这正是整会话收敛的依据");

      console.write("kill %1\n");
    } finally {
      assertTrue(scope.terminate(), "会话必须在终止后收敛");
      assertTrue(scope.converged());
      assertEquals(
          0,
          PosixProcessSession.liveMemberCount(
              scope.process().pid(), PosixProcessSession.NO_PROCESS),
          "终止后会话里不得留下活着的成员");
      scope.close();
    }
  }

  /** Ctrl+C 只能结束前台作业，交互式 shell 与 helper 必须存活并回到可用状态。 */
  @Test
  void controlCTerminatesOnlyTheForegroundJob() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 终端信号语义");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            List.of("/bin/bash", "--noprofile", "--norc", "-i"),
            80,
            24,
            consoleEnvironment(),
            () -> true);
    try (PtyConsole console = new PtyConsole(scope.process())) {
      console.write("echo \"__P\"\"ID__$$\"\n");
      long shellPid = Long.parseLong(console.awaitToken("__PID__", 20_000));
      // 前台作业：先启动它，再发 Ctrl+C。
      console.write(LONG_SLEEP + "\n");
      awaitForegroundJob(scope.process().pid());
      console.write("\u0003");
      console.write("echo \"__A\"\"LIVE__$?\"\n");
      String alive = console.awaitToken("__ALIVE__", 20_000);
      assertTrue(alive.contains("130"), "前台作业必须被信号结束（$?=130），实际：" + alive);
      assertTrue(scope.process().isAlive(), "shell 与 helper 必须存活");
      assertEquals(
          scope.process().pid(), PosixProcessSession.sessionOf(shellPid), "shell 必须仍在原会话里运行");
    } finally {
      assertTrue(scope.terminate(), "会话必须在终止后收敛");
      scope.close();
    }
  }

  /** Ctrl+Z 停止前台作业后，bg 必须恢复原作业进程组，且它仍与 shell 同属一个会话。 */
  @Test
  void controlZThenBackgroundResumesTheJobProcessGroup() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 作业控制信号语义");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            List.of("/bin/bash", "--noprofile", "--norc", "-i"),
            80,
            24,
            consoleEnvironment(),
            () -> true);
    try (PtyConsole console = new PtyConsole(scope.process())) {
      console.write("echo \"__P\"\"ID__$$\"\n");
      long shellPid = Long.parseLong(console.awaitToken("__PID__", 20_000));
      console.write(LONG_SLEEP + "\n");
      awaitForegroundJob(scope.process().pid());
      console.write("\u001a");
      console.write("echo \"__S\"\"TOP__$(jobs | grep -c Stopped)\"\n");
      assertEquals("1", console.awaitToken("__STOP__", 20_000), "Ctrl+Z 必须把前台作业标成 Stopped");
      console.write("bg\n");
      console.write("echo \"__JOBP\"\"ID__$(jobs -p)\"\n");
      long jobPid = Long.parseLong(console.awaitToken("__JOBPID__", 20_000));
      console.write("echo \"__JOBP\"\"GID__$(ps -o pgid= -p " + jobPid + ")\"\n");
      long jobPgid = Long.parseLong(console.awaitToken("__JOBPGID__", 20_000));
      assertNotEquals(shellPid, jobPgid, "恢复后的作业必须在自己的进程组里，而不是 shell 自己的组");
      assertEquals(jobPid, jobPgid, "作业组的 pgid 必须等于作业 pid");
      assertEquals(
          scope.process().pid(), PosixProcessSession.sessionOf(jobPid), "作业进程组必须仍在 helper 的会话里");
      console.write("kill %1\n");
    } finally {
      assertTrue(scope.terminate(), "会话必须在终止后收敛");
      scope.close();
    }
  }

  /**
   * 交互式 shell 自然 {@code exit} 后，会话里的后台作业（含忽略 {@code SIGTERM} 的作业）必须被整会话收敛：
   * 温和信号杀不掉的成员由升级的强杀清除，收敛必须被内核确认。
   */
  @Test
  void naturalExitConvergesBackgroundJobsIncludingTermIgnoringOnes() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 作业控制与信号语义");
    ProcessScope scope =
        ProcessScope.startPty(
            workdir,
            List.of("/bin/bash", "--noprofile", "--norc", "-i"),
            80,
            24,
            consoleEnvironment(),
            () -> true);
    try (PtyConsole console = new PtyConsole(scope.process())) {
      console.write("echo \"__P\"\"ID__$$\"\n");
      assertTrue(Long.parseLong(console.awaitToken("__PID__", 20_000)) > 0);
      console.write(LONG_SLEEP + " & echo \"__A\"\"__$!\"\n");
      long plainJob = Long.parseLong(console.awaitToken("__A__", 20_000));
      // 子 shell 里忽略 SIGTERM：忽略状态被 exec 继承，因此这个作业连温和信号都不理会。
      console.write(
          "(trap '' TERM; printf ready > term-ready; exec "
              + LONG_SLEEP
              + ") & echo \"__B\"\"__$!\"\n");
      long termIgnoringJob = Long.parseLong(console.awaitToken("__B__", 20_000));
      assertTrue(plainJob > 0 && termIgnoringJob > 0);
      long deadline = System.nanoTime() + 10_000_000_000L;
      while (!Files.exists(workdir.resolve("term-ready")) && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertTrue(Files.exists(workdir.resolve("term-ready")), "退出前必须确认后台作业已经安装 TERM 忽略状态");
      assertEquals(
          scope.process().pid(),
          PosixProcessSession.sessionOf(termIgnoringJob),
          "忽略 SIGTERM 的作业也必须在 helper 的会话里");

      console.write("exit\n");
      assertTrue(scope.awaitNaturalExit(30_000), "交互式 shell 必须在预算内自然退出");
      assertEquals(0, scope.naturalExitCode(), "shell 的退出码必须原样保真");
      assertTrue(scope.terminate(), "自然退出后会话（含忽略 TERM 的作业）必须被收敛");
      assertTrue(scope.converged());
      assertFalse(
          PosixProcessSession.hasLiveMember(scope.process().pid(), PosixProcessSession.NO_PROCESS),
          "收敛后不能还有活着的会话成员");
    } finally {
      scope.close();
    }
  }

  /**
   * 有界等待「前台作业已经跑起来」。
   *
   * <p>交互式 shell 在前台作业运行期间不会再接受命令，因此没有可用的输出哨兵；这里用有界轮询确认会话里除了 helper 与 shell 之外
   * 又多了一个成员（即前台作业），而不是盲等固定时长。
   */
  private static void awaitForegroundJob(long helperPid) throws Exception {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (PosixProcessSession.liveMemberCount(helperPid, PosixProcessSession.NO_PROCESS) >= 3) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("前台作业没有在预算内建立");
  }

  private static Map<String, String> consoleEnvironment() {
    Map<String, String> environment = new HashMap<>(System.getenv());
    environment.remove("BASH_ENV");
    environment.remove("ENV");
    environment.remove("PROMPT_COMMAND");
    environment.put("TERM", "dumb");
    environment.put("LC_ALL", "C");
    environment.put("PS1", "__KSPS1__ ");
    return environment;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /**
   * PTY 控制台：后台解码主端输出（UTF-8 增量解码）并按哨兵同步。
   *
   * <p>命令里把哨兵拆成两段（{@code "__P""ID__"}），回显的输入行因此不含完整哨兵；只有命令输出会命中，同步不靠时间猜测。
   */
  private static final class PtyConsole implements AutoCloseable {

    private final StringBuilder buffer = new StringBuilder();
    private final OutputStream input;
    private final Thread reader;
    private int scanIndex;

    PtyConsole(Process process) {
      this.input = process.getOutputStream();
      Thread thread =
          new Thread(
              () -> {
                try (InputStreamReader decoder =
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                  char[] chunk = new char[512];
                  int read;
                  while ((read = decoder.read(chunk)) >= 0) {
                    synchronized (buffer) {
                      buffer.append(chunk, 0, read);
                      buffer.notifyAll();
                    }
                  }
                } catch (IOException ignored) {
                  // 主端关闭是预期的结束条件。
                }
              },
              "interactive-pty-reader");
      thread.setDaemon(true);
      thread.start();
      this.reader = thread;
    }

    void write(String text) throws IOException {
      input.write(text.getBytes(StandardCharsets.UTF_8));
      input.flush();
    }

    /**
     * 等待哨兵后的第一个完整 token（以空白终止）。
     *
     * <p>只有出现终止空白才认为 token 完整，避免在数字被逐字符回传时提前返回半截值。
     */
    String awaitToken(String sentinel, long millis) throws InterruptedException {
      long deadline = System.nanoTime() + millis * 1_000_000L;
      synchronized (buffer) {
        while (true) {
          int index = buffer.indexOf(sentinel, scanIndex);
          if (index >= 0) {
            int cursor = index + sentinel.length();
            while (cursor < buffer.length() && buffer.charAt(cursor) == ' ') {
              cursor++;
            }
            int end = cursor;
            while (end < buffer.length() && !Character.isWhitespace(buffer.charAt(end))) {
              end++;
            }
            if (end < buffer.length()) {
              scanIndex = end;
              return buffer.substring(cursor, end);
            }
          }
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            throw new AssertionError(
                "did not observe " + sentinel + " within " + millis + "ms; output=" + buffer);
          }
          buffer.wait(Math.max(1, remaining / 1_000_000L));
        }
      }
    }

    @Override
    public void close() {
      try {
        input.close();
      } catch (IOException ignored) {
        // 已经关闭等价于无需再关闭。
      }
      reader.interrupt();
      try {
        reader.join(1_000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
