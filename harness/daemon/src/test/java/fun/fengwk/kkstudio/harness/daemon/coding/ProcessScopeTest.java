package fun.fengwk.kkstudio.harness.daemon.coding;

import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.daemon.DaemonOperatingSystemDetector;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@link ProcessScope} 的确定性行为测试：OS 所有权先于命令、退出码保真、整组收敛与私有状态清理。
 *
 * <p>这些用例覆盖父进程一侧能独立观察的事实：命令的自然退出码来自 helper 的原子发布而不是 helper 自己的退出码；范围
 * 收敛判定直接问内核，因此既不会把已退出但尚未回收的僵尸当成存活，也不会把仍然活着的命令当成已收敛；私有状态目录在一 次调用结束时被删除。
 *
 * <p>进程内可见的用法与命令无关，因此这里用最小的跨平台命令构造事实（POSIX 用 {@code sh -c}，Windows 用 {@code cmd /c}）；需要 POSIX
 * 进程组语义的用例显式跳过 Windows。
 */
class ProcessScopeTest {

  @TempDir Path workdir;

  /**
   * 命令的自然退出码必须原样保真，且 helper 在退出之前已经把「整组已经收敛」这一事实发布出来。
   *
   * <p>helper 自己的退出码只是它自己的事：命令的事实来自原子发布的退出码，收敛的事实来自内核或 Job 的确认。
   */
  @Test
  void naturalExitKeepsTheExactCommandExitCode() throws Exception {
    ProcessScope scope = ProcessScope.start(workdir, exitCommand(3));
    try {
      scope.process().waitFor();
      assertTrue(scope.scopeEstablished(), "范围必须在用户命令启动之前建立");
      assertEquals(3, scope.naturalExitCode(), "命令退出码必须来自 helper 的原子发布");
      assertNull(scope.startFailure());
      String cleanup =
          ProcessScopeState.read(scope.stateDirectory(), ProcessScopeState.CLEANUP_FILE);
      if (isLinuxLike()) {
        // Linux/WSL 能在 /proc 里区分「只剩 helper 自己」与「还有后代」，因此这个事实必须为真。
        assertEquals("true", cleanup, "helper 退出之前必须自己确认整组收敛");
      }
      assertTrue(scope.terminate(), "自然退出后收敛必须已经由内核确认");
      assertTrue(scope.converged());
    } finally {
      scope.close();
    }
  }

  /**
   * 命令无法启动时失败关闭：发布失败原因、没有自然退出码、范围仍然收敛。
   *
   * <p>平台差异：POSIX 上命令进程在范围建立之后才 {@code exec}，因此失败发生在已建立的范围内；Windows 上首个进程必须
   * 先创建并归属，命令不存在意味着这一步无法完成，于是表现为范围建立失败。两条去向都是失败关闭且都带上命令名，调用方 据同样的失败终态处理。
   */
  @Test
  void missingExecutableIsReportedAsScopeFailure() throws Exception {
    ProcessScope scope;
    try {
      scope =
          ProcessScope.start(workdir, List.of("kk-studio-missing-command", "-lc", "echo never"));
    } catch (IllegalStateException failure) {
      assertTrue(failure.getMessage().contains("kk-studio-missing-command"), failure.getMessage());
      return;
    }
    try {
      readAll(scope.process().getInputStream());
      scope.process().waitFor();
      String failure = scope.startFailure();
      assertNotNull(failure, "无法启动命令时必须发布失败原因");
      assertTrue(failure.contains("kk-studio-missing-command"), failure);
      assertNull(scope.naturalExitCode(), "启动失败没有自然退出码");
      assertTrue(scope.converged(), "启动失败也必须收敛范围");
    } finally {
      scope.close();
    }
  }

  /** 收敛判定必须看见活着的命令：发过终止信号不等于已经收敛，更不允许把存活进程当成已结束。 */
  @Test
  void liveScopeIsNotReportedAsConverged() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX sleep");
    ProcessScope scope = ProcessScope.start(workdir, List.of("sh", "-c", "sleep 30"));
    try {
      assertTrue(scope.scopeEstablished());
      assertFalse(scope.converged(), "命令仍存活时绝不能报告已收敛");
      scope.terminate();
      assertTrue(scope.converged(), "终止后必须由内核确认范围消失");
      scope.terminate();
      assertTrue(scope.converged(), "重复终止是空操作");
    } finally {
      scope.close();
    }
  }

  /** 终止是幂等且并发安全的：多个调用者只执行一次收敛，且都等到范围消失之后才返回。 */
  @Test
  void concurrentTerminationIsIdempotentAndConverges() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX sleep");
    ProcessScope scope = ProcessScope.start(workdir, List.of("sh", "-c", "sleep 30"));
    int callers = 4;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    try {
      List<Future<?>> terminations = new ArrayList<>();
      for (int index = 0; index < callers; index++) {
        terminations.add(
            pool.submit(
                () -> {
                  start.await();
                  scope.terminate();
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> termination : terminations) {
        termination.get(15, TimeUnit.SECONDS);
      }
      assertTrue(scope.converged(), "并发终止之后范围必须收敛");
    } finally {
      pool.shutdownNow();
      scope.close();
    }
  }

  /** 私有状态目录只在调用期间存在：{@link ProcessScope#close()} 必须删除它。 */
  @Test
  void closeRemovesThePrivateStateDirectory() throws Exception {
    ProcessScope scope = ProcessScope.start(workdir, exitCommand(0));
    Path stateDir = scope.stateDirectory();
    scope.process().waitFor();
    assertTrue(Files.isDirectory(stateDir), "调用期间私有状态目录必须存在");
    scope.close();
    assertFalse(Files.exists(stateDir), "close 必须删除私有状态目录");
  }

  /** 输出原样穿过执行范围：不额外增加内容，也不截断。 */
  @Test
  void outputIsForwardedVerbatim() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX printf");
    ProcessScope scope = ProcessScope.start(workdir, List.of("sh", "-c", "printf 'a b\\nc\\n'"));
    try {
      String output = readAll(scope.process().getInputStream());
      scope.process().waitFor();
      assertEquals("a b\nc\n", output);
    } finally {
      scope.close();
    }
  }

  /** 无法定位 daemon 自身的类路径时必须立刻失败关闭，绝不启动一个无法承载 helper 的 JVM。 */
  @Test
  void missingDaemonClasspathFailsClosedWithoutLaunchingAHelper() {
    String classpath = System.getProperty("java.class.path");
    try {
      System.setProperty("java.class.path", " ");
      IOException failure =
          assertThrows(IOException.class, () -> ProcessScope.start(workdir, exitCommand(0)));
      assertTrue(failure.getMessage().contains("classpath"), failure.getMessage());
    } finally {
      System.setProperty("java.class.path", classpath);
    }
  }

  /**
   * helper 无法启动（这里用不可用的类路径复现，与「不支持的平台 / JNA 载入失败」同一条失败通道）时，父进程必须把 「范围没有建立」当成明确失败，而不是退化成没有范围的直接执行。
   */
  @Test
  void helperThatDiesBeforePublishingTheScopeIsReportedAsStartupFailure() {
    String classpath = System.getProperty("java.class.path");
    try {
      System.setProperty("java.class.path", workdir.resolve("missing-classpath").toString());
      IllegalStateException failure =
          assertThrows(
              IllegalStateException.class, () -> ProcessScope.start(workdir, exitCommand(0)));
      assertTrue(
          failure.getMessage().contains("before establishing the OS scope"), failure.getMessage());
    } finally {
      System.setProperty("java.class.path", classpath);
    }
  }

  /** 状态文件被外部破坏时必须显式失败，绝不把不可读内容当成命令的退出码。 */
  @Test
  void corruptedExitStateIsRejected() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX sleep");
    ProcessScope scope = ProcessScope.start(workdir, List.of("sh", "-c", "sleep 30"));
    try {
      Files.writeString(
          scope.stateDirectory().resolve(ProcessScopeState.EXIT_FILE), "not-a-number");
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, scope::naturalExitCode);
      assertTrue(failure.getMessage().contains("not-a-number"), failure.getMessage());
    } finally {
      scope.close();
    }
  }

  /** close 是幂等的：重复 close 不抛出，也不改变已经收敛的范围。 */
  @Test
  void closeIsIdempotent() throws Exception {
    ProcessScope scope = ProcessScope.start(workdir, exitCommand(0));
    scope.process().waitFor();
    scope.close();
    scope.close();
    assertTrue(scope.converged());
  }

  /**
   * 许可之前取消必须完全没有副作用：命令从未启动，因此不会留下命令的标记文件，也不会留下调用私有的状态目录。
   *
   * <p>这正是「父进程确认范围之后才放行用户命令」的直接后果，也是启动阶段取消与「先启动、再强杀」的区别。
   */
  @Test
  void cancelledBeforeThePermitLeavesNoSideEffect() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX touch");
    Path marker = workdir.resolve("cancelled-before-permit.marker");
    long stateDirectoriesBefore = countStateDirectories();
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                ProcessScope.start(
                    workdir, List.of("sh", "-c", "touch '" + marker + "'"), () -> false));
    assertTrue(
        failure.getMessage().contains("cancelled before the user command"), failure.getMessage());
    assertFalse(Files.exists(marker), "许可之前取消不得让命令产生任何副作用");
    assertEquals(stateDirectoriesBefore, countStateDirectories(), "许可之前失败不得留下调用私有的状态目录");
  }

  /**
   * 发布的 scope id 必须属于本次调用的 helper：POSIX 上它是 helper 自己的进程组，父进程只在此基础上发信号。
   *
   * <p>这是「不向陌生进程组发信号」这一保证的前提，因此单独用真实进程固定下来。
   */
  @Test
  void publishedScopeIdIsTheHelperProcessGroup() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 进程组语义");
    ProcessScope scope = ProcessScope.start(workdir, List.of("sh", "-c", "sleep 30"));
    try {
      String published =
          ProcessScopeState.read(scope.stateDirectory(), ProcessScopeState.SCOPE_FILE);
      assertNotNull(published);
      assertEquals(
          scope.process().pid(),
          Long.parseLong(published),
          "POSIX scope id 必须等于 helper 自己的进程组 id（= helper 的 pid）");
    } finally {
      scope.close();
    }
  }

  /**
   * helper 自己的诊断只进调用私有的诊断文件，命令输出一个字节都不多。
   *
   * <p>JVM 启动提示（例如 {@code JAVA_TOOL_OPTIONS}）与 helper 的报错都会写进这个文件，因此它绝不能是命令输出的一部分。
   */
  @Test
  void helperDiagnosticsStayOutOfTheCommandOutput() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX sleep");
    ProcessScope scope =
        ProcessScope.start(workdir, List.of("sh", "-c", "sleep 0.4; printf 'command-output\\n'"));
    try {
      Path diagnostics = scope.stateDirectory().resolve(ProcessScopeState.DIAGNOSTICS_FILE);
      Files.writeString(diagnostics, "helper-diagnostic-noise\n", StandardCharsets.UTF_8, APPEND);
      String output = readAll(scope.process().getInputStream());
      scope.process().waitFor();
      assertEquals("command-output\n", output, "helper 的诊断绝不能进入命令输出");
      assertTrue(Files.readString(diagnostics).contains("helper-diagnostic-noise"), "诊断内容属于诊断文件本身");
    } finally {
      scope.close();
    }
  }

  /**
   * 取消落在「helper 已经就位、但命令还没有派生」的位置时，命令绝不会被派生。
   *
   * <p>这是派发与收敛互斥的确定性断言：收敛一旦开始就再也不允许派生，因此这里既不会出现「收敛扫描过一次、命令随后才
   * 诞生」的成员（它会带着忽略状态活到天荒地老），也不会出现「收敛已经宣布清理完成、命令却又跑起来」。命令从未启动由 pid 文件不存在来证明，收敛结论由 helper
   * 自己发布（而不是退回父进程的内核复核）。
   */
  @Test
  void cancelBeforeTheReleaseKeepsTheCommandUnspawned() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 进程组与信号语义");
    Path latch = workdir.resolve("spawn-latch-held");
    Files.createDirectories(latch);
    Path pidFile = workdir.resolve("spawn-held.pids");
    System.setProperty(ProcessScope.SPAWN_LATCH_PROPERTY, latch.toString());
    ProcessScope scope;
    try {
      scope =
          ProcessScope.start(
              workdir,
              List.of(
                  "sh",
                  "-c",
                  "echo $$ >> '"
                      + pidFile
                      + "'; (trap '' TERM; sleep 60) & echo $! >> '"
                      + pidFile
                      + "'; wait"));
    } finally {
      System.clearProperty(ProcessScope.SPAWN_LATCH_PROPERTY);
    }
    try {
      // helper 停在派生之前，闸门一直不解开：取消必须在这里就收敛，而不是等命令跑起来再收尾。
      awaitFile(latch.resolve("spawn-ready"));
      assertTrue(scope.terminate(), "取消之后整组必须由内核确认收敛");
      assertTrue(scope.converged(), "收敛结论必须来自内核");
      assertEquals(
          "true",
          ProcessScopeState.read(scope.stateDirectory(), ProcessScopeState.CLEANUP_FILE),
          "helper 必须自己确认收敛，而不是把结论丢给父进程的内核复核");
      assertFalse(Files.exists(pidFile), "收敛开始之后命令绝不能被派生");
    } finally {
      Files.writeString(latch.resolve("spawn-go"), "go", StandardCharsets.UTF_8);
      scope.close();
    }
  }

  /**
   * 取消与派生真的交叉时，无论谁先拿到锁，根进程与忽略 TERM 的后代都必须收敛。
   *
   * <p>两种合法结局都要求收敛：命令在收敛开始之前完成 fork（pid 文件有内容，必须全部消失），或者收敛先拿到锁（命令根本 没有 fork）。这条用例不用 sleep
   * 去猜窗口，而是让闸门释放与整组信号同时发生，再由内核判定收口。
   */
  @Test
  void cancelRacingWithTheSpawnStillConvergesRootAndDescendants() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 进程组与信号语义");
    Path latch = workdir.resolve("spawn-latch-race");
    Files.createDirectories(latch);
    Path pidFile = workdir.resolve("spawn-window.pids");
    System.setProperty(ProcessScope.SPAWN_LATCH_PROPERTY, latch.toString());
    ProcessScope scope;
    try {
      scope =
          ProcessScope.start(
              workdir,
              List.of(
                  "sh",
                  "-c",
                  "echo $$ >> '"
                      + pidFile
                      + "'; (trap '' TERM; sleep 60) & echo $! >> '"
                      + pidFile
                      + "'; wait"));
    } finally {
      System.clearProperty(ProcessScope.SPAWN_LATCH_PROPERTY);
    }
    try {
      awaitFile(latch.resolve("spawn-ready"));
      CountDownLatch cancelled = new CountDownLatch(1);
      boolean[] converged = new boolean[1];
      Thread cancel =
          new Thread(
              () -> {
                converged[0] = scope.terminate();
                cancelled.countDown();
              },
              "spawn-window-cancel");
      cancel.start();
      Files.writeString(latch.resolve("spawn-go"), "go", StandardCharsets.UTF_8);
      assertTrue(cancelled.await(30, TimeUnit.SECONDS), "取消必须在预算内结束");
      cancel.join(Duration.ofSeconds(5).toMillis());
      assertTrue(converged[0], "取消之后整组必须由内核确认收敛");
      assertTrue(scope.converged(), "收敛结论必须来自内核，而不是调用方的乐观判断");
      for (long pid : recordedPids(pidFile)) {
        assertFalse(alive(pid), "取消窗口里启动的 " + pid + " 必须已经消失");
      }
    } finally {
      scope.close();
    }
  }

  /**
   * keeper 被杀之后父进程仍必须收敛剩下的后代，且只能强杀「身份被重新核验过」的成员。
   *
   * <p>这是「keeper 先死、后代还在」这条真实去向的确定性构造：直接强杀 helper（模拟它被外部杀掉，因此不会走它自己的收敛 hook），此时组 id 已经没有 helper
   * 可问，只能靠成员快照逐个确认「它此刻仍然属于本次范围、启动时刻与快照一致」之后再强杀。
   */
  @Test
  void terminateConvergesRemainingMembersAfterTheKeeperIsKilled() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 进程组语义");
    Path pidFile = workdir.resolve("keeper-dead.pids");
    ProcessScope scope =
        ProcessScope.start(
            workdir,
            List.of(
                "sh",
                "-c",
                "echo $$ >> '"
                    + pidFile
                    + "'; (trap '' TERM; sleep 60) & echo $! >> '"
                    + pidFile
                    + "'; wait"));
    try {
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
        Thread.sleep(20);
      }
      assertTrue(Files.exists(pidFile), "命令必须先真的跑起来");
      List<Long> recorded = recordedPids(pidFile);
      assertFalse(recorded.isEmpty(), "命令必须记下自己的 pid");

      // keeper 直接消失：没有温和信号、没有收敛 hook，只剩父进程自己收尾。
      scope.process().destroyForcibly();
      assertTrue(scope.process().waitFor(20, TimeUnit.SECONDS), "keeper 必须先消失");
      assertFalse(scope.process().isAlive(), "keeper 必须先消失");

      assertTrue(scope.terminate(), "keeper 消失后父进程仍必须自己收敛剩下的成员");
      assertTrue(scope.converged(), "收敛结论必须来自内核");
      for (long pid : recorded) {
        assertFalse(alive(pid), "keeper 死亡后 " + pid + " 必须被收敛");
      }
    } finally {
      scope.close();
    }
  }

  /**
   * 只有身份被核验过的 pid 才允许强杀：启动时刻不符或不属于本次范围的进程一律不动。
   *
   * <p>这里用 pid 1 做「不属于本次范围但确实存在的进程」：它永远活着、也永远不在本次调用新建的进程组里。任何一次误杀都会
   * 立刻表现为这个断言失败，因此这条用例把「绝不杀错进程」从注释变成事实。
   */
  @Test
  void killVerifiedMemberNeverSignalsAnUnverifiedProcess() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX 进程组语义");
    ProcessHandle foreign = ProcessHandle.of(1).orElse(null);
    assertNotNull(foreign, "需要 pid 1 作为「存在但不属于本次范围」的进程");
    Instant foreignStart = foreign.info().startInstant().orElse(null);
    assertNotNull(foreignStart, "需要能读到 pid 1 的启动时刻");

    ProcessScope scope = ProcessScope.start(workdir, List.of("sh", "-c", "sleep 30"));
    try {
      // 启动时刻不符：同一个 pid 但换了进程（pid 复用）时绝不能动手。
      scope.killVerifiedMember(new PosixProcessGroup.GroupMember(1, foreignStart.plusSeconds(60)));
      assertTrue(foreign.isAlive(), "启动时刻不符时绝不能发信号");
      // 身份对得上、但不属于本次调用的进程组：同样绝不能动手。
      scope.killVerifiedMember(new PosixProcessGroup.GroupMember(1, foreignStart));
      assertTrue(foreign.isAlive(), "不属于本次范围的进程绝不能发信号");
    } finally {
      scope.close();
    }
  }

  /** 等到文件出现：测试闸门的就绪标记由 helper 在另一个进程里写下。 */
  private static void awaitFile(Path file) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (!Files.exists(file)) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("等待超时：" + file);
      }
      Thread.sleep(20);
    }
  }

  /** 命令记录下来的 pid；命令根本没启动时为空。 */
  private static List<Long> recordedPids(Path file) throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<Long> pids = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        pids.add(Long.parseLong(trimmed));
      }
    }
    return pids;
  }

  private static boolean alive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  /** 统计临时目录里的进程范围状态目录数量，用于断言失败路径不留残留。 */
  private static long countStateDirectories() throws IOException {
    try (Stream<Path> entries = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
      return entries
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith(ProcessScope.STATE_DIR_PREFIX))
          .count();
    }
  }

  /** 读取到 EOF；helper 只在用户的文件描述符全部关闭之后才结束，因此这里同时验证「无遗留写端」。 */
  private static String readAll(InputStream input) throws IOException {
    return new String(input.readAllBytes(), StandardCharsets.UTF_8);
  }

  private static List<String> exitCommand(int code) {
    return isWindows() ? List.of("cmd", "/c", "exit " + code) : List.of("sh", "-c", "exit " + code);
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** helper 侧能排除自己的收敛判定只在 Linux/WSL 成立（非 Linux 只能由父进程的内核判定收口）。 */
  private static boolean isLinuxLike() {
    DaemonOperatingSystem operatingSystem = DaemonOperatingSystemDetector.detectCurrent();
    return operatingSystem == DaemonOperatingSystem.LINUX
        || operatingSystem == DaemonOperatingSystem.WSL;
  }
}
