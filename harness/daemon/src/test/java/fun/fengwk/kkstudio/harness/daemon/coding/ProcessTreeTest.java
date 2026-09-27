package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 针对 {@link ProcessTree} 的确定性行为测试。
 *
 * <p>覆盖两类事实：真实进程的存活判断与已退出进程的空操作；平台异常状态（进程句柄不可用、后代枚举失败、终止信号被拒绝、 存活查询失败）下终止流程仍然不抛出并继续收敛。异常状态用可编排的
 * {@link ProcessHandle} 探针构造，避免依赖特定平台的偶发行为。
 *
 * <p>真实进程用例锁住终止顺序本身：温和信号必须先于强制收敛（能处理 SIGTERM 的进程有机会执行自己的清理），忽略温和信号的成员以及 leader 退出后仍存活的后代
 * 都必须被预先快照的强制阶段收敛。
 */
class ProcessTreeTest {

  @TempDir Path workdir;

  /** 真实进程仍存活时必须被判定为存活，终止后必须收敛为不可存活。 */
  @Test
  void reportsLiveProcessAndConvergesAfterTermination() throws Exception {
    Process process = new ProcessBuilder("sh", "-c", "sleep 30").start();
    try {
      assertTrue(ProcessTree.isAlive(process), "尚未终止的进程必须被判定为存活");

      ProcessTree.terminate(process);

      assertFalse(process.isAlive(), "终止后主进程必须已退出");
      assertFalse(ProcessTree.isAlive(process), "终止后进程树必须收敛");
    } finally {
      process.destroyForcibly();
    }
  }

  /** 已退出的进程不再收到任何信号：终止是空操作，不会向平台发多余的 kill。 */
  @Test
  void doesNotSignalAlreadyExitedProcess() throws Exception {
    Process exited = new ProcessBuilder("sh", "-c", "exit 0").start();
    assertEquals(0, exited.waitFor());
    assertFalse(ProcessTree.isAlive(exited), "已退出进程不得被报告为存活");
    ProcessTree.terminate(exited);
    assertFalse(ProcessTree.isAlive(exited));

    FakeProcess probe = new FakeProcess(new FakeHandle(false));
    ProcessTree.terminate(probe);
    assertEquals(0, probe.handle.destroyCalls.get(), "已退出进程不得收到温和终止信号");
    assertEquals(0, probe.handle.forceDestroyCalls.get(), "已退出进程不得收到强制终止信号");
  }

  /** 后代枚举失败不得阻止主进程终止：至少收敛已快照的成员。 */
  @Test
  void stillTerminatesRootWhenDescendantEnumerationFails() {
    FakeProcess probe = new FakeProcess(new FakeHandle(true).descendantsFail());

    ProcessTree.terminate(probe);

    assertEquals(1, probe.handle.destroyCalls.get(), "后代枚举失败时仍必须温和终止主进程");
    assertFalse(probe.handle.alive, "主进程必须已收敛");
  }

  /** 存活判断在后代枚举失败时按不可判定处理，不能把异常泄漏给调用方。 */
  @Test
  void reportsNotAliveWhenDescendantQueryFails() {
    FakeProcess probe = new FakeProcess(new FakeHandle(false).descendantsFail());

    assertFalse(ProcessTree.isAlive(probe), "后代枚举失败时死亡进程仍必须被判定为不可存活");
  }

  /** 平台不支持转换进程句柄时直接强制终止，绝不静默放弃收敛。 */
  @Test
  void fallsBackToDirectForceKillWhenHandleIsUnsupported() {
    FakeProcess probe = new FakeProcess(null);

    ProcessTree.terminate(probe);

    assertEquals(1, probe.directForceKills.get(), "句柄不可用时必须退化为直接强制终止");
  }

  /** 终止信号被平台拒绝时不得抛出：终止是尽力而为的收敛动作。 */
  @Test
  void convergesWhenTerminationSignalIsRejected() {
    FakeProcess probe = new FakeProcess(new FakeHandle(true).signalFail());

    ProcessTree.terminate(probe);

    assertTrue(probe.handle.destroyCalls.get() >= 1, "信号被拒绝也必须留下终止尝试，而不是跳过该成员");
    assertTrue(probe.handle.forceDestroyCalls.get() >= 1, "温和阶段未收敛必须进入强制阶段");
  }

  /** 存活查询失败按不可判定处理：既不抛出，也不阻止其它成员收敛。 */
  @Test
  void convergesWhenLivenessQueryFails() {
    FakeHandle faultyDescendant = new FakeHandle(true).queryFail();
    FakeHandle root = new FakeHandle(true).descendants(faultyDescendant);
    FakeProcess probe = new FakeProcess(root);

    ProcessTree.terminate(probe);

    assertEquals(1, root.destroyCalls.get(), "根进程必须收到终止信号");
    assertFalse(root.alive, "根进程必须收敛，存活查询失败的成员不得影响其它成员");
  }

  /** 终止是幂等的：对已退出进程或 null 句柄重复调用都只是空操作，不抛出也不产生多余信号。 */
  @Test
  void repeatedTerminationOfExitedAndNullHandlesIsNoOp() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell");
    Process process = new ProcessBuilder("sh", "-c", "exit 0").start();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));

    ProcessTree.terminate(process);
    ProcessTree.terminate(process);
    ProcessTree.terminate(null);

    assertFalse(ProcessTree.isAlive(process), "已退出进程不得被报告为存活");
  }

  /**
   * 温和停止必须先于强制收敛：能处理 SIGTERM 的进程在宽限窗口内完成自己的清理，而不是被立即 SIGKILL。
   *
   * <p>这是对「先强制收敛」实现的显式反向断言：若实现跳过温和阶段，trap 不会执行，标记文件不会出现。
   */
  @Test
  void terminatesGracefullyBeforeForcing() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与 SIGTERM trap");
    Path marker = workdir.resolve("term-marker.txt");
    Process process =
        new ProcessBuilder(
                "sh",
                "-c",
                "trap 'printf term > \""
                    + marker
                    + "\"; exit 0' TERM; while true; do sleep 0.05; done")
            .start();
    // 等进程真正进入事件循环，避免 TERM 早于 trap 安装而走默认处理。
    Thread.sleep(300);

    ProcessTree.terminate(process);

    assertTrue(Files.exists(marker), "进程必须先收到 SIGTERM 并执行自己的清理");
    assertEquals("term", Files.readString(marker));
    assertFalse(ProcessTree.isAlive(process), "温和阶段未能收敛时必须由强制阶段兜底");
  }

  /** 忽略温和信号的进程树不得永久存活：后代会继续写入，终止后必须停止；这是对「只杀主进程」实现的显式反向断言。 */
  @Test
  void terminatesDescendantsThatIgnoreSoftTermination() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与进程树语义");
    Path marker = workdir.resolve("ignore-term-ticks.log");
    Process process =
        new ProcessBuilder(
                "sh",
                "-c",
                "(trap '' TERM; while true; do printf 'child\\n' >> \""
                    + marker
                    + "\"; sleep 0.05; done) & "
                    + "trap '' TERM; while true; do sleep 0.05; done")
            .start();
    awaitTicks(marker);

    ProcessTree.terminate(process);

    long ticksAtTermination = lineCount(marker);
    assertTrue(ticksAtTermination > 0, "终止前后代必须已经在产出输出");
    Thread.sleep(700);
    assertEquals(ticksAtTermination, lineCount(marker), "忽略温和信号的后代也必须被强制收敛，不得继续写入");
    assertFalse(ProcessTree.isAlive(process));
  }

  /**
   * 父进程在宽限窗口内自行退出、后代继续存活时，强制阶段仍必须覆盖预先快照的后代。
   *
   * <p>主进程退出后 Java 无法再枚举它原来的后代，因此实现必须在发信号前快照成员；若改为退出后重新枚举，后代将逃逸且持续写入。
   */
  @Test
  void forceConvergesDescendantsThatOutliveTheirLeader() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与进程树语义");
    Path marker = workdir.resolve("orphan-ticks.log");
    Process process =
        new ProcessBuilder(
                "sh",
                "-c",
                "(trap '' TERM; while true; do printf 'child\\n' >> \""
                    + marker
                    + "\"; sleep 0.05; done) & "
                    + "trap 'exit 0' TERM; while true; do sleep 0.05; done")
            .start();
    awaitTicks(marker);

    ProcessTree.terminate(process);

    long ticksAtTermination = lineCount(marker);
    Thread.sleep(700);
    assertEquals(ticksAtTermination, lineCount(marker), "父进程退出后，忽略温和信号的后代仍必须被预先快照的强制阶段收敛");
    assertFalse(ProcessTree.isAlive(process));
  }

  /**
   * 跨平台（含 Windows）可执行的基础保证：并发调用终止与重复终止都必须幂等，并让进程在有限时间内收敛。
   *
   * <p>Windows 上不存在 taskkill 分支：{@code ProcessHandle.destroy()} 后接 {@code
   * ProcessHandle.destroyForcibly()} 就是平台的温和到强制收敛路径，因此本用例在两个平台上都真实执行，不用跳过代替验证。
   */
  @Test
  void concurrentTerminationIsIdempotentAndConverges() throws Exception {
    Process process = new ProcessBuilder(longRunningCommand()).start();
    assertTrue(process.isAlive(), "长驻测试进程必须处于运行状态");

    ExecutorService racers = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<?>> tasks = new ArrayList<>();
      for (int index = 0; index < 2; index++) {
        tasks.add(
            racers.submit(
                () -> {
                  start.await();
                  ProcessTree.terminate(process);
                  return null;
                }));
      }
      start.countDown();
      for (Future<?> task : tasks) {
        task.get(10, TimeUnit.SECONDS);
      }
    } finally {
      racers.shutdownNow();
    }
    // 终态之后再调用一次：已退出进程上的终止与 null 句柄都必须是空操作。
    ProcessTree.terminate(process);
    ProcessTree.terminate(null);

    assertFalse(ProcessTree.isAlive(process), "并发终止后不得有存活成员");
    assertTrue(process.waitFor(5, TimeUnit.SECONDS), "终止后进程必须可被回收");
  }

  /** 等待命令进入可观测阶段；失败时快速暴露而不是让测试挂起。 */
  private static void awaitTicks(Path marker) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (lineCount(marker) == 0 && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(lineCount(marker) > 0, "终止前进程树必须已经在产出输出：" + marker);
  }

  private static long lineCount(Path file) {
    try {
      return Files.readAllLines(file).size();
    } catch (IOException error) {
      return 0;
    }
  }

  /** 平台无关的长驻命令：Windows 用 ping，POSIX 用 sleep，二者都无需 shell 包装。 */
  private static List<String> longRunningCommand() {
    return isWindows()
        ? List.of("cmd", "/c", "ping -n 30 127.0.0.1 > nul")
        : List.of("sleep", "30");
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** 可编排的进程探针：{@code handle} 为 null 表示平台不支持转换句柄。 */
  private static final class FakeProcess extends Process {

    private final FakeHandle handle;
    private final AtomicInteger directForceKills = new AtomicInteger();

    private FakeProcess(FakeHandle handle) {
      this.handle = handle;
    }

    @Override
    public ProcessHandle toHandle() {
      if (handle == null) {
        throw new UnsupportedOperationException("handle is unsupported on this platform");
      }
      return handle;
    }

    @Override
    public Process destroyForcibly() {
      directForceKills.incrementAndGet();
      return this;
    }

    @Override
    public boolean isAlive() {
      return handle != null && handle.isAlive();
    }

    @Override
    public void destroy() {
      if (handle != null) {
        handle.destroy();
      }
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }
  }

  /** 可编排的进程句柄：确定性构造已退出、后代枚举失败、信号被拒绝与存活查询失败四种状态。 */
  private static final class FakeHandle implements ProcessHandle {

    private final AtomicInteger destroyCalls = new AtomicInteger();
    private final AtomicInteger forceDestroyCalls = new AtomicInteger();
    private volatile boolean alive;
    private volatile boolean descendantsFail;
    private volatile boolean signalFail;
    private volatile boolean queryFail;
    private volatile List<ProcessHandle> descendants = List.of();

    private FakeHandle(boolean alive) {
      this.alive = alive;
    }

    private FakeHandle descendantsFail() {
      descendantsFail = true;
      return this;
    }

    private FakeHandle signalFail() {
      signalFail = true;
      return this;
    }

    private FakeHandle queryFail() {
      queryFail = true;
      return this;
    }

    private FakeHandle descendants(ProcessHandle... members) {
      descendants = List.of(members);
      return this;
    }

    @Override
    public long pid() {
      return 4242L;
    }

    @Override
    public Optional<ProcessHandle> parent() {
      return Optional.empty();
    }

    @Override
    public Stream<ProcessHandle> children() {
      return Stream.empty();
    }

    @Override
    public Stream<ProcessHandle> descendants() {
      if (descendantsFail) {
        throw new IllegalStateException("cannot enumerate descendants");
      }
      return descendants.stream();
    }

    @Override
    public Info info() {
      return new Info() {
        @Override
        public Optional<String> command() {
          return Optional.empty();
        }

        @Override
        public Optional<String> commandLine() {
          return Optional.empty();
        }

        @Override
        public Optional<String[]> arguments() {
          return Optional.empty();
        }

        @Override
        public Optional<Instant> startInstant() {
          return Optional.empty();
        }

        @Override
        public Optional<Duration> totalCpuDuration() {
          return Optional.empty();
        }

        @Override
        public Optional<String> user() {
          return Optional.empty();
        }
      };
    }

    @Override
    public CompletableFuture<ProcessHandle> onExit() {
      return CompletableFuture.completedFuture(this);
    }

    @Override
    public boolean supportsNormalTermination() {
      return true;
    }

    @Override
    public boolean destroy() {
      destroyCalls.incrementAndGet();
      if (signalFail) {
        throw new IllegalStateException("termination signal rejected");
      }
      alive = false;
      return true;
    }

    @Override
    public boolean destroyForcibly() {
      forceDestroyCalls.incrementAndGet();
      if (signalFail) {
        throw new IllegalStateException("termination signal rejected");
      }
      alive = false;
      return true;
    }

    @Override
    public boolean isAlive() {
      if (queryFail) {
        throw new IllegalStateException("liveness query failed");
      }
      return alive;
    }

    @Override
    public int compareTo(ProcessHandle other) {
      return Long.compare(pid(), other.pid());
    }
  }
}
