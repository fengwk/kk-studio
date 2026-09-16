package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 针对 {@link ChildProcessRunner} 与 {@link ProcessTree} 的行为断言测试。
 *
 * <p>被冻结的产品语义决定本测试的重点：stdout 是调用方载荷必须完整保留，stderr 只保留有界诊断尾部；两路输出必须并发排空，否则任一路写满管道就会 死锁；deadline
 * 绑定调用方有效超时，超时或取消都必须终止整棵进程树（含后代），而不是只杀主进程。
 */
class ChildProcessRunnerTest {

  @TempDir Path workdir;

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /** stdout 是调用方载荷：超过管道缓冲与诊断尾部上限时仍必须完整返回。 */
  @Test
  void keepsStdoutPayloadCompleteBeyondPipeAndDiagnosticBuffers() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 构造并发大输出");
    int bytes = 300_000;

    ChildProcessRunner.Result result =
        ChildProcessRunner.run(
            List.of(
                "sh", "-c", "yes A | head -c " + bytes + "; yes B | head -c " + bytes + " 1>&2"),
            workdir,
            null,
            Duration.ofSeconds(30),
            () -> false);

    assertEquals(0, result.exitCode());
    assertEquals(
        bytes,
        result.stdout().getBytes(StandardCharsets.UTF_8).length,
        "stdout 载荷必须完整，不能被诊断尾部上限截断");
    assertEquals("A\n", result.stdout().substring(0, 2));
    // stderr 只保留有界尾部，但必须包含最新内容。
    assertTrue(
        result.stderr().getBytes(StandardCharsets.UTF_8).length
            <= ChildProcessRunner.DIAGNOSTIC_TAIL_BYTES,
        "stderr 诊断必须保持有界");
    assertTrue(result.stderr().endsWith("B\n"), "诊断尾部必须是最新内容");
    assertTrue(result.merged().contains("A\n"), "合并诊断必须包含 stdout 载荷");
  }

  /** stderr 与 stdout 同时写满管道时不得死锁：并发排空是唯一正确实现。 */
  @Test
  void drainsBothStreamsConcurrentlyWithoutDeadlock() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 构造并发大输出");
    int bytes = 512 * 1024;
    long started = System.nanoTime();

    ChildProcessRunner.Result result =
        ChildProcessRunner.run(
            List.of(
                "sh",
                "-c",
                "(yes O | head -c " + bytes + ") & (yes E | head -c " + bytes + " 1>&2) & wait"),
            workdir,
            null,
            Duration.ofSeconds(30),
            () -> false);

    long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
    assertEquals(0, result.exitCode());
    assertEquals(bytes, result.stdout().getBytes(StandardCharsets.UTF_8).length);
    assertTrue(elapsedMillis < 25_000, "并发排空必须在 deadline 内完成，实际 " + elapsedMillis + "ms");
  }

  /** 非零退出码是权威事实，调用方据此判定失败。 */
  @Test
  void reportsNonZeroExitCode() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell");

    ChildProcessRunner.Result result =
        ChildProcessRunner.run(
            List.of("sh", "-c", "echo boom 1>&2; exit 3"),
            workdir,
            null,
            Duration.ofSeconds(10),
            () -> false);

    assertEquals(3, result.exitCode());
    assertTrue(result.stderr().contains("boom"));
  }

  /** deadline 到点必须终止整棵进程树并以 TIMED_OUT 失败，而不是让调用方永久等待。 */
  @Test
  void timeoutTerminatesTheWholeProcessTree() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与进程树语义");
    Path marker = workdir.resolve("ticks.log");
    CountDownLatch started = new CountDownLatch(1);

    ChildProcessRunner.ChildProcessException error =
        assertThrows(
            ChildProcessRunner.ChildProcessException.class,
            () ->
                ChildProcessRunner.run(
                    List.of(
                        "sh",
                        "-c",
                        "(while true; do echo child >> "
                            + marker
                            + "; sleep 0.05; done) & "
                            + "echo started >> "
                            + marker
                            + "; "
                            + "while true; do echo parent >> "
                            + marker
                            + "; sleep 0.05; done"),
                    workdir,
                    null,
                    Duration.ofMillis(700),
                    () -> {
                      if (Files.exists(marker)) {
                        started.countDown();
                      }
                      return false;
                    }));

    assertEquals(ChildProcessRunner.Cancellation.TIMED_OUT, error.cancellation());
    long ticksAtDeath = Files.readAllLines(marker).size();
    assertTrue(ticksAtDeath > 0, "超时前进程树必须已经在产出输出");

    // 宽限期后仍不得有新的 tick：主进程与后代都必须已终止。
    Thread.sleep(700);
    assertEquals(ticksAtDeath, Files.readAllLines(marker).size(), "超时必须终止整棵进程树，后代不得继续写入");
  }

  /** 取消信号必须终止整棵进程树并以 CANCELLED 失败，且失败原因可被调用方区分。 */
  @Test
  void cancellationTerminatesTheProcessTree() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell 与进程树语义");
    Path marker = workdir.resolve("cancel-ticks.log");
    AtomicBoolean cancelled = new AtomicBoolean(false);

    ChildProcessRunner.ChildProcessException error =
        assertThrows(
            ChildProcessRunner.ChildProcessException.class,
            () ->
                ChildProcessRunner.run(
                    List.of(
                        "sh",
                        "-c",
                        "(while true; do echo child >> "
                            + marker
                            + "; sleep 0.05; done) & "
                            + "while true; do echo parent >> "
                            + marker
                            + "; sleep 0.05; done"),
                    workdir,
                    null,
                    Duration.ofSeconds(30),
                    () -> {
                      if (cancelled.get()) {
                        return true;
                      }
                      // 取消检查接口不允许抛出受检异常；这里只做尽力而为的观测。
                      if (lineCount(marker) > 3) {
                        cancelled.set(true);
                      }
                      return false;
                    }));

    assertEquals(ChildProcessRunner.Cancellation.CANCELLED, error.cancellation());
    long ticksAtDeath = lineCount(marker);
    Thread.sleep(500);
    assertEquals(ticksAtDeath, lineCount(marker), "取消必须终止整棵进程树，后代不得继续写入");
  }

  /** 取消检查可能在任意时刻调用，这里把读取失败折叠为 0，避免受检异常逃出 lambda。 */
  private static long lineCount(Path file) {
    try {
      return Files.readAllLines(file).size();
    } catch (IOException error) {
      return 0;
    }
  }

  /** 已经取消时不得再启动子进程：取消是 fail-closed 的前置检查。 */
  @Test
  void alreadyCancelledCallDoesNotStartAProcess() {
    ChildProcessRunner.ChildProcessException error =
        assertThrows(
            ChildProcessRunner.ChildProcessException.class,
            () ->
                ChildProcessRunner.run(
                    List.of("sh", "-c", "echo should-not-run"),
                    workdir,
                    null,
                    Duration.ofSeconds(5),
                    () -> true));

    assertEquals(ChildProcessRunner.Cancellation.CANCELLED, error.cancellation());
    assertTrue(error.diagnostics().isEmpty());
  }

  /** 启动失败必须给出可判定的 START_FAILED，而不是让 IOException 逃逸到调用方。 */
  @Test
  void startFailureIsReportedAsStartFailed() {
    ChildProcessRunner.ChildProcessException error =
        assertThrows(
            ChildProcessRunner.ChildProcessException.class,
            () ->
                ChildProcessRunner.run(
                    List.of("kk-studio-missing-executable"),
                    workdir,
                    null,
                    Duration.ofSeconds(5),
                    () -> false));

    assertEquals(ChildProcessRunner.Cancellation.START_FAILED, error.cancellation());
    assertTrue(error.getMessage().contains("cannot start process"), error.getMessage());
  }

  /** stdin 会写入并在其后关闭；子进程提前退出导致的写失败不得变成调用失败。 */
  @Test
  void writesAndClosesStdinWithoutFailingWhenTheChildExitsEarly() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell");

    ChildProcessRunner.Result echoed =
        ChildProcessRunner.run(
            List.of("sh", "-c", "cat"),
            workdir,
            "hello\n".getBytes(StandardCharsets.UTF_8),
            Duration.ofSeconds(10),
            () -> false);
    assertEquals(0, echoed.exitCode());
    assertEquals("hello\n", echoed.stdout());

    // 子进程完全不读 stdin 时写入会失败，但这不影响最终结果的判定。
    ChildProcessRunner.Result ignoring =
        ChildProcessRunner.run(
            List.of("sh", "-c", "echo done"),
            workdir,
            new byte[256 * 1024],
            Duration.ofSeconds(10),
            () -> false);
    assertEquals(0, ignoring.exitCode());
    assertTrue(ignoring.stdout().contains("done"));
  }

  /** 进程树终止是幂等的：对已退出或 null 句柄再次调用都是空操作。 */
  @Test
  void processTreeTerminationIsIdempotent() throws Exception {
    assumeFalse(isWindows(), "需要 POSIX shell");
    Process process = new ProcessBuilder("sh", "-c", "exit 0").start();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS));

    ProcessTree.terminate(process);
    ProcessTree.terminate(process);
    ProcessTree.terminate(null);

    assertFalse(ProcessTree.isAlive(process), "已退出进程不得被报告为存活");
  }
}
