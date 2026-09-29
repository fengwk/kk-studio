package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@link PosixProcessGroup} 的解析规则与 errno 语义回归：它们是收敛判定唯一的事实来源，错一位就会把「还有后代」判成 「已经收敛」。
 *
 * <p>解析用例喂的是 {@code /proc/<pid>/stat} 的真实格式（含带括号与空格的 {@code comm}）；errno 用例只在真实内核上构造
 * 可安全表达的拒绝（不存在的进程组、没有权限的进程），不注入假的 native 绑定，也不靠破坏系统来制造失败。
 */
class PosixProcessGroupTest {

  @TempDir Path workdir;

  /** 命令名带括号与空格时也必须只从最后一个 {@code ')'} 之后解析，否则进程组字段会整体错位。 */
  @Test
  void parsesProcessStatWithHostileCommandNames() throws IOException {
    Path stat =
        statFile("1234 (my (weird) name) S 1 1234 1234 0 -1 4194560 1 0 0 0 0 0 0 0 20 0 1 0\n");
    PosixProcessGroup.ProcessStat parsed = PosixProcessGroup.readProcessStat(stat);
    assertNotNull(parsed);
    assertEquals('S', parsed.state());
    assertEquals(1234L, parsed.group());
  }

  /** 僵尸状态必须被如实读出来：调用方正是靠它把「只剩不会执行的成员」判成已经收敛。 */
  @Test
  void parsesZombieState() throws IOException {
    Path stat = statFile("42 (sh) Z 1 42 42 0 -1 4194560 0 0 0 0 0 0 0 0 20 0 1 0\n");
    PosixProcessGroup.ProcessStat parsed = PosixProcessGroup.readProcessStat(stat);
    assertNotNull(parsed);
    assertEquals('Z', parsed.state());
    assertEquals(42L, parsed.group());
  }

  /** 结构不完整的样本一律按「不可判定」返回 null，绝不猜出一个进程组。 */
  @Test
  void refusesMalformedProcessStat() throws IOException {
    assertNull(PosixProcessGroup.readProcessStat(statFile("no parentheses here\n")));
    assertNull(PosixProcessGroup.readProcessStat(statFile("42 (sh)\n")));
    assertNull(PosixProcessGroup.readProcessStat(statFile("42 (sh) S 1 not-a-group 42\n")));
    assertNull(PosixProcessGroup.readProcessStat(workdir.resolve("missing-stat")));
  }

  /** {@code /proc} 目录项里的 pid 必须是十进制；别的名字只能被忽略，不能被当成 pid。 */
  @Test
  void parsesOnlyDecimalProcessIds() {
    assertEquals(4321L, PosixProcessGroup.parseProcessId("4321"));
    assertEquals(-1L, PosixProcessGroup.parseProcessId("self"));
    assertEquals(-1L, PosixProcessGroup.parseProcessId("12a"));
  }

  /** 已经退出的进程按「信号已经交付」处理：收敛判定不该因为回收时序而失败。 */
  @Test
  void signalProcessTreatsAGoneProcessAsDelivered() {
    assumeFalse(isWindows(), "需要 POSIX 信号语义");
    assertTrue(PosixProcessGroup.signalProcess(ProcessHandle.current().pid(), 0));
    long gone = aProcessIdThatHasExited();
    assertTrue(PosixProcessGroup.signalProcess(gone, PosixProcessGroup.SIGTERM));
  }

  /** 被内核拒绝（不是 ESRCH）时绝不能报成成功：那会把「还在跑的后代」判成已经收敛。 */
  @Test
  void signalProcessReportsRefusalInsteadOfSuccess() {
    assumeFalse(isWindows(), "需要 POSIX 信号语义");
    assumeFalse(isRoot(), "root 身份不会被内核拒绝，无法表达这条失败");
    assertFalse(PosixProcessGroup.signalProcess(1, PosixProcessGroup.SIGTERM));
    assertTrue(PosixProcessGroup.groupExists(1), "被拒绝的组必须按「仍然存在」处理");
  }

  /** 不存在的进程组对组信号来说是「已经收敛」，这是收敛判定最常见的正常结局。 */
  @Test
  void signalGroupTreatsAMissingGroupAsDelivered() {
    assumeFalse(isWindows(), "需要 POSIX 进程组语义");
    assertTrue(PosixProcessGroup.signalGroup(goneGroup(), PosixProcessGroup.SIGTERM));
    assertFalse(PosixProcessGroup.groupExists(goneGroup()));
    assertFalse(PosixProcessGroup.hasLiveMember(goneGroup(), PosixProcessGroup.NO_PROCESS));
  }

  /** 成员枚举必须只看本次范围：自己可以被排除，别的组不会被算进来。 */
  @Test
  void enumeratesOnlyMembersOfTheGivenGroup() {
    assumeTrueLinux();
    long group = PosixProcessGroup.currentGroup();
    long self = ProcessHandle.current().pid();
    List<Long> withSelf =
        asList(PosixProcessGroup.liveMembers(group, PosixProcessGroup.NO_PROCESS));
    List<Long> withoutSelf = asList(PosixProcessGroup.liveMembers(group, self));
    assertTrue(withSelf.contains(self) || withoutSelf.isEmpty(), "当前进程组至少包含自己");
    assertFalse(withoutSelf.contains(self), "被排除的进程绝不出现在结果里");
    assertTrue(withSelf.stream().anyMatch(pid -> pid > 0), "枚举结果只包含真实 pid");
  }

  /** 建立 session 的失败必须显式报错：静默回退会让「整组收敛」的承诺失真。 */
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
    return List.of(
        System.getProperty("java.home") + "/bin/java",
        "-cp",
        System.getProperty("java.class.path"),
        PosixProcessGroupSessionFixture.class.getName(),
        marker.toString());
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

  private static long goneGroup() {
    return 1L << 30;
  }

  private static List<Long> asList(long[] values) {
    return Arrays.stream(values).boxed().collect(Collectors.toList());
  }

  private static void assumeTrueLinux() {
    assumeFalse(isWindows(), "需要 /proc 才能枚举进程组成员");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static boolean isRoot() {
    return "root".equals(System.getProperty("user.name"));
  }
}
