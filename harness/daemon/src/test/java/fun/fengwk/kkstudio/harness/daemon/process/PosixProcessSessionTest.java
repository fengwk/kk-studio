package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.jna.Native;
import com.sun.jna.platform.mac.SystemB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * {@link PosixProcessSession} 的解析规则与会话枚举回归：它们是收敛判定唯一的事实来源，错一位就会把「还有后代」判成「已经收敛」。
 *
 * <p>解析用例喂的是 {@code /proc/<pid>/stat} 的真实格式（含带括号与空格的 {@code comm}）；会话枚举用例只在具备真实内核查询的平台上
 * 断言（Linux/WSL 用 {@code /proc}，macOS 用 libproc），没有枚举能力的平台必须如实回答「不可判定」而不是「没有成员」。
 */
class PosixProcessSessionTest {

  @TempDir Path workdir;

  /** 命令名带括号与空格时也必须只从最后一个 {@code ')'} 之后解析，否则会话字段会整体错位。 */
  @Test
  void parsesProcessStatWithHostileCommandNames() throws IOException {
    Path stat =
        statFile("1234 (my (weird) name) S 1 1234 1234 0 -1 4194560 1 0 0 0 0 0 0 0 20 0 1 0\n");
    PosixProcessSession.ProcessStat parsed = PosixProcessSession.readProcessStat(stat);
    assertNotNull(parsed);
    assertEquals('S', parsed.state());
    assertEquals(1234L, parsed.session());
  }

  /** 僵尸状态必须被如实读出来：调用方正是靠它把「只剩不会执行的成员」判成已经收敛。 */
  @Test
  void parsesZombieState() throws IOException {
    Path stat = statFile("42 (sh) Z 1 42 42 0 -1 4194560 0 0 0 0 0 0 0 0 20 0 1 0\n");
    PosixProcessSession.ProcessStat parsed = PosixProcessSession.readProcessStat(stat);
    assertNotNull(parsed);
    assertEquals('Z', parsed.state());
    assertEquals(42L, parsed.session());
  }

  /** 结构不完整的样本一律按「不可判定」返回 null，绝不猜出一个会话。 */
  @Test
  void refusesMalformedProcessStat() throws IOException {
    assertNull(PosixProcessSession.readProcessStat(statFile("no parentheses here\n")));
    assertNull(PosixProcessSession.readProcessStat(statFile("42 (sh)\n")));
    assertNull(PosixProcessSession.readProcessStat(statFile("42 (sh) S 1 not-a-group 42\n")));
    assertNull(PosixProcessSession.readProcessStat(workdir.resolve("missing-stat")));
  }

  /** {@code /proc} 目录项里的 pid 必须是十进制；别的名字只能被忽略，不能被当成 pid。 */
  @Test
  void parsesOnlyDecimalProcessIds() {
    assertEquals(4321L, PosixProcessSession.parseProcessId("4321"));
    assertEquals(-1L, PosixProcessSession.parseProcessId("self"));
    assertEquals(-1L, PosixProcessSession.parseProcessId("12a"));
  }

  /** 成员快照必须带身份（启动时刻）：只带 pid 的快照无法对抗 pid 复用，调用方要靠它重新核验。 */
  @Test
  void memberSnapshotCarriesIdentityAndHonoursExclusion() {
    assumeTrueLinux();
    long session = PosixProcessSession.currentSession();
    long self = ProcessHandle.current().pid();
    List<PosixProcessSession.GroupMember> withSelf =
        PosixProcessSession.membersSnapshot(session, PosixProcessSession.NO_PROCESS);
    assertNotNull(withSelf, "Linux 上必须能枚举会话成员");
    assertTrue(
        withSelf.stream().anyMatch(member -> member.pid() == self && member.start() != null),
        "当前进程必须出现在自己的会话里，并带上启动时刻");
    List<PosixProcessSession.GroupMember> withoutSelf =
        PosixProcessSession.membersSnapshot(session, self);
    assertNotNull(withoutSelf);
    assertFalse(withoutSelf.stream().anyMatch(member -> member.pid() == self), "被排除的进程绝不出现在快照里");
  }

  /** 不存在的会话没有成员，已经退出的进程也查不到会话。 */
  @Test
  void emptySessionHasNoMembersAndUnknownProcessHasNoSession() {
    assumeTrueLinux();
    List<PosixProcessSession.GroupMember> members =
        PosixProcessSession.membersSnapshot(goneSession(), PosixProcessSession.NO_PROCESS);
    assertNotNull(members);
    assertEquals(List.of(), members);
    assertEquals(-1L, PosixProcessSession.sessionOf(aProcessIdThatHasExited()));
    assertEquals(
        PosixProcessSession.currentSession(),
        PosixProcessSession.sessionOf(ProcessHandle.current().pid()));
  }

  /**
   * 有真实枚举能力的平台必须能枚举出自己；没有能力的 POSIX 平台必须如实回答「不可判定」。
   *
   * <p>「没有成员」会让收敛跳过强杀阶段并宣布已经收敛，所以能力缺口只能表现为不可判定（返回 {@code null}）。
   */
  @Test
  void memberEnumerationUsesRealKernelQueriesOrReportsUndecidable() {
    assumeFalse(isWindows(), "需要 POSIX 会话语义");
    long session = PosixProcessSession.currentSession();
    if (isLinux() || isMac()) {
      List<PosixProcessSession.GroupMember> members =
          PosixProcessSession.membersSnapshot(session, PosixProcessSession.NO_PROCESS);
      assertNotNull(members, () -> "具备内核查询能力的平台必须能枚举会话成员" + macEnumerationDiagnostics(session));
      assertTrue(
          members.stream().anyMatch(member -> member.pid() == ProcessHandle.current().pid()),
          "当前进程必须出现在自己的会话里");
    } else {
      assertNull(
          PosixProcessSession.membersSnapshot(session, PosixProcessSession.NO_PROCESS),
          "没有真实枚举能力就必须返回不可判定");
    }
  }

  /** pid 快照里的已退出项不污染真实成员；排除成员仍必须得到确定的空集合。 */
  @Test
  void macEnumerationIgnoresVanishedPidsAndPreservesMemberIdentity() {
    assumeTrue(isMac(), "需要真实 Darwin getsid/libproc");
    long self = ProcessHandle.current().pid();
    long session = PosixProcessSession.currentSession();
    int[] pids = {(int) self, Integer.MAX_VALUE};
    List<PosixProcessSession.GroupMember> members =
        PosixProcessSession.macMembers(session, PosixProcessSession.NO_PROCESS, pids);
    assertNotNull(members);
    assertEquals(1, members.size());
    assertEquals(self, members.getFirst().pid());
    assertEquals(PosixProcessSession.processStart(self), members.getFirst().start());
    assertNotNull(members.getFirst().start());
    assertEquals(List.of(), PosixProcessSession.macMembers(session, self, pids));
  }

  /** 枚举不可判定必须表现为「还有成员」：谎称已经收敛会让收尾跳过强杀阶段。 */
  @Test
  void undecidableEnumerationIsNeverReportedAsConverged() {
    assumeFalse(isWindows(), "需要 POSIX 会话语义");
    assumeFalse(isLinux() || isMac(), "这条断言描述的是没有真实枚举能力的平台");
    assertTrue(
        PosixProcessSession.hasLiveMember(
            PosixProcessSession.currentSession(), PosixProcessSession.NO_PROCESS),
        "枚举不可判定时绝不能报告已经收敛");
    assertEquals(
        -1L,
        PosixProcessSession.liveMemberCount(
            PosixProcessSession.currentSession(), PosixProcessSession.NO_PROCESS),
        "不可判定的成员数量就是 -1");
  }

  /** 建立 session 的失败必须显式报错：静默回退会让「整会话收敛」的承诺失真。 */
  @Test
  void createSessionSucceedsOnceAndFailsWhenAlreadyTheLeader() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX session 语义");
    Path marker = workdir.resolve("session.out");
    Process process =
        new ProcessBuilder(sessionFixtureCommand(marker)).redirectErrorStream(true).start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "夹具必须在预算内结束");
    List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
    assertEquals(List.of("first=ok", "group=leader", "second=failed"), lines);
  }

  private List<String> sessionFixtureCommand(Path marker) {
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("java.home") + "/bin/java");
    command.addAll(TestCoverageAgentArguments.forwarded());
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(PosixProcessSessionFixture.class.getName());
    command.add(marker.toString());
    return command;
  }

  /** 失败后重新采样内核查询，只输出数字；不读取命令、环境变量或终端流。 */
  private static String macEnumerationDiagnostics(long session) {
    if (!isMac()) {
      return "";
    }
    int requested = SystemB.INSTANCE.proc_listpids(SystemB.PROC_ALL_PIDS, 0, null, 0);
    if (requested <= 0 || requested % Integer.BYTES != 0) {
      return " (resample: requestedBytes=" + requested + ", errno=" + Native.getLastError() + ")";
    }
    int[] pids = new int[Math.min(8192, requested / Integer.BYTES + 256)];
    int written =
        SystemB.INSTANCE.proc_listpids(SystemB.PROC_ALL_PIDS, 0, pids, pids.length * Integer.BYTES);
    StringBuilder diagnostics =
        new StringBuilder(
            " (resample: sid="
                + session
                + ", requestedBytes="
                + requested
                + ", writtenBytes="
                + written);
    int faultCount = 0;
    int memberCount = 0;
    for (int index = 0; index < Math.min(pids.length, written / Integer.BYTES); index++) {
      int pid = pids[index];
      if (pid <= 0) {
        continue;
      }
      int sid = PosixProcessSession.LibC.INSTANCE.getsid(pid);
      int error = Native.getLastError();
      if (sid < 0) {
        if (faultCount++ < 8) {
          diagnostics
              .append(", getsid=")
              .append(pid)
              .append("/")
              .append(sid)
              .append("/")
              .append(error);
        }
        continue;
      }
      if (Integer.toUnsignedLong(sid) != session) {
        continue;
      }
      memberCount++;
      SystemB.ProcBsdInfo info = new SystemB.ProcBsdInfo();
      int bytes =
          SystemB.INSTANCE.proc_pidinfo(pid, SystemB.PROC_PIDTBSDINFO, 0, info, info.size());
      error = Native.getLastError();
      if (bytes != info.size() && faultCount++ < 8) {
        diagnostics
            .append(", bsdinfo=")
            .append(pid)
            .append("/")
            .append(bytes)
            .append("/")
            .append(error);
      }
    }
    return diagnostics
        .append(", members=")
        .append(memberCount)
        .append(", faults=")
        .append(faultCount)
        .append(")")
        .toString();
  }

  private Path statFile(String content) throws IOException {
    Path file = workdir.resolve("stat-" + content.hashCode());
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private static long aProcessIdThatHasExited() {
    try {
      Process process =
          new ProcessBuilder(
                  isWindows() ? List.of("cmd", "/c", "exit") : List.of("sh", "-c", "exit"))
              .start();
      process.waitFor();
      Thread.sleep(50);
      return process.pid();
    } catch (IOException | InterruptedException error) {
      throw new IllegalStateException("cannot create a short lived process for this case", error);
    }
  }

  /** 一个不可能被内核分配的会话 id：远大于 pid 上限，因此不可能是任何真实会话。 */
  private static long goneSession() {
    return 1L << 30;
  }

  /**
   * 成员枚举与「会话」查询都建立在真实内核查询上：Linux/WSL 用 {@code /proc}，macOS 用 libproc。
   *
   * <p>这条前置条件必须真的判定 Linux，而不是只排除 Windows：macOS 是 POSIX 但没有 {@code /proc}，因此它的枚举走 libproc， 不能与 Linux
   * 混为一谈。
   */
  private static void assumeTrueLinux() {
    assumeFalse(isWindows(), "需要 POSIX 会话语义");
    assumeTrue(
        System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"),
        "需要 /proc 才能枚举会话成员");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static boolean isLinux() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
  }

  private static boolean isMac() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
  }
}
