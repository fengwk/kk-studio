package fun.fengwk.kkstudio.harness.daemon.coding;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LSP bridge 与 {@code javap} 共享的子进程执行边界：并发排空输出，deadline 绑定调用超时，取消/超时终止进程树。
 *
 * <p>旧实现先 {@code readFully} 再 {@code waitFor}，输出管道写满时双方互相等待，必然死锁；同时用隐藏的 30
 * 秒常量而不是调用方的有效超时。本实现改为：启动即并发排空 stdout 与 stderr，在调用方 deadline 内 {@code waitFor}，超时或取消时终止整个进程树。
 *
 * <p>stdout 是调用方的权威载荷（{@code javap} 反编译文本、LSP JSON 响应），因此完整保留：它与输出体积成正比，但这里的调用者本身就以完整文本为结果。 stderr
 * 只是诊断：保留有界尾部即可定位失败原因，不需要无界内存。
 */
final class ChildProcessRunner {

  /** stderr 诊断尾部上限：足够定位失败原因，又不会让诊断本身变成新的无界内存。 */
  static final int DIAGNOSTIC_TAIL_BYTES = 8 * 1024;

  private static final int DRAIN_BUFFER_BYTES = 4096;
  private static final Duration DRAIN_JOIN_GRACE = Duration.ofSeconds(2);

  private ChildProcessRunner() {}

  /**
   * 运行命令并返回其结果。
   *
   * @param command argv 形式的命令
   * @param workdir 子进程工作目录
   * @param stdin 写入子进程 stdin 的内容；{@code null} 表示不写
   * @param timeout 调用方有效超时；非正数视为“不设 deadline”
   * @param cancelled 取消检查；返回 true 时终止进程树并以取消失败结束
   * @throws InterruptedException 调用被中断
   * @throws ChildProcessException 启动失败、超时、取消或非零退出
   */
  static Result run(
      List<String> command,
      Path workdir,
      byte[] stdin,
      Duration timeout,
      CancellationCheck cancelled)
      throws InterruptedException {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(workdir, "workdir");
    Objects.requireNonNull(timeout, "timeout");
    Objects.requireNonNull(cancelled, "cancelled");

    if (cancelled.isCancelled()) {
      throw new ChildProcessException(Cancellation.CANCELLED, "Operation cancelled", List.of());
    }

    Process process =
        start(new ProcessBuilder(command).directory(workdir.toFile()).redirectErrorStream(false));
    StreamDrain stdout = StreamDrain.payload(process.getInputStream());
    StreamDrain stderr = StreamDrain.diagnostic(process.getErrorStream());

    if (stdin != null) {
      writeStdin(process, stdin);
    }

    boolean finished;
    try {
      finished = await(process, timeout, cancelled);
    } catch (InterruptedException error) {
      stop(process, stdout, stderr);
      throw error;
    }
    if (finished) {
      int exitCode = process.exitValue();
      join(stdout);
      join(stderr);
      return new Result(exitCode, stdout.text(), stderr.text());
    }

    // 超时或取消：先终止整棵进程树，再收集已经排空的诊断尾部。
    stop(process, stdout, stderr);
    if (cancelled.isCancelled()) {
      throw new ChildProcessException(Cancellation.CANCELLED, "Operation cancelled", List.of());
    }
    throw new ChildProcessException(
        Cancellation.TIMED_OUT,
        "process timed out after " + timeout.toMillis() + " milliseconds",
        List.of(stdout.text(), stderr.text()));
  }

  /** 终止进程树并收敛两条排空线程。 */
  private static void stop(Process process, StreamDrain stdout, StreamDrain stderr)
      throws InterruptedException {
    ProcessTree.terminate(process);
    join(stdout);
    join(stderr);
  }

  /** 启动进程；失败时暴露可读原因而不再重试。 */
  private static Process start(ProcessBuilder builder) {
    try {
      return builder.start();
    } catch (IOException error) {
      throw new ChildProcessException(
          Cancellation.START_FAILED, "cannot start process: " + error.getMessage(), List.of());
    }
  }

  /** 写 stdin 后立即关闭；子进程提前退出导致的写失败不是调用失败。 */
  private static void writeStdin(Process process, byte[] stdin) {
    try (var output = process.getOutputStream()) {
      output.write(stdin);
      output.flush();
    } catch (IOException ignored) {
      // 子进程不读 stdin（例如 javap）或提前退出：输入内容不影响最终结果判定。
    }
  }

  /**
   * 在 deadline 内等待进程退出，并持续检查取消。
   *
   * @return 进程是否已自然退出
   */
  private static boolean await(Process process, Duration timeout, CancellationCheck cancelled)
      throws InterruptedException {
    long deadlineNanos =
        timeout.isZero() || timeout.isNegative()
            ? Long.MAX_VALUE
            : System.nanoTime() + timeout.toNanos();
    while (true) {
      if (cancelled.isCancelled()) {
        return false;
      }
      long remainingNanos =
          deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : deadlineNanos - System.nanoTime();
      if (remainingNanos <= 0) {
        return false;
      }
      long waitMillis =
          remainingNanos == Long.MAX_VALUE
              ? 200
              : Math.max(1, Math.min(200, remainingNanos / 1_000_000L));
      if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
        return true;
      }
    }
  }

  private static void join(StreamDrain drain) throws InterruptedException {
    if (!drain.latch.await(DRAIN_JOIN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
      // 进程已终止但读取线程仍未收敛（例如句柄被后代继承）：关闭流让读取线程退出。
      drain.closeQuietly();
      drain.latch.await(DRAIN_JOIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /** 调用方的取消信号来源。 */
  @FunctionalInterface
  interface CancellationCheck {
    boolean isCancelled();
  }

  /** 子进程的终止原因。 */
  enum Cancellation {
    CANCELLED,
    TIMED_OUT,
    START_FAILED
  }

  /** 子进程成功结果：退出码与两路有界输出。 */
  record Result(int exitCode, String stdout, String stderr) {

    /** 合并诊断文本：stdout 在前，stderr 非空时追加，便于错误信息统一展示。 */
    String merged() {
      if (stderr.isBlank()) {
        return stdout;
      }
      if (stdout.isBlank()) {
        return stderr;
      }
      return stdout + "\n" + stderr;
    }
  }

  /** 子进程终止异常：携带可判定原因与有界诊断尾部。 */
  static final class ChildProcessException extends RuntimeException {

    private final transient Cancellation cancellation;
    private final transient List<String> diagnostics;

    ChildProcessException(Cancellation cancellation, String message, List<String> diagnostics) {
      super(message);
      this.cancellation = cancellation;
      this.diagnostics = List.copyOf(diagnostics);
    }

    Cancellation cancellation() {
      return cancellation;
    }

    /** 有界诊断尾部，非空项按 stdout、stderr 顺序排列。 */
    List<String> diagnostics() {
      return diagnostics;
    }
  }

  /** 单条流的并发排空线程：持续读取；stdout 完整保留，stderr 只保留有界诊断尾部。 */
  private static final class StreamDrain {

    private final InputStream input;
    private final ByteTailBuffer tail;
    private final ByteArrayOutputStream payload;
    private final CountDownLatch latch = new CountDownLatch(1);

    private StreamDrain(InputStream input, ByteTailBuffer tail, ByteArrayOutputStream payload) {
      this.input = input;
      this.tail = tail;
      this.payload = payload;
    }

    /** stdout：调用方载荷，必须完整保留。 */
    private static StreamDrain payload(InputStream input) {
      return start(new StreamDrain(input, null, new ByteArrayOutputStream()));
    }

    /** stderr：只保留有界诊断尾部。 */
    private static StreamDrain diagnostic(InputStream input) {
      return start(new StreamDrain(input, new ByteTailBuffer(DIAGNOSTIC_TAIL_BYTES), null));
    }

    private static StreamDrain start(StreamDrain drain) {
      Thread worker = new Thread(drain::drain, "daemon-child-stream");
      worker.setDaemon(true);
      worker.start();
      return drain;
    }

    private void drain() {
      byte[] buffer = new byte[DRAIN_BUFFER_BYTES];
      try (input) {
        int count;
        while ((count = input.read(buffer)) >= 0) {
          if (payload != null) {
            payload.write(buffer, 0, count);
          } else {
            tail.append(buffer, 0, count);
          }
        }
      } catch (IOException ignored) {
        // 进程终止导致流关闭：已读取的部分仍然有效。
      } finally {
        latch.countDown();
      }
    }

    private String text() {
      byte[] bytes = payload != null ? payload.toByteArray() : tail.toByteArray();
      return new String(bytes, StandardCharsets.UTF_8);
    }

    private void closeQuietly() {
      try {
        input.close();
      } catch (IOException ignored) {
        // 关闭失败不影响收敛：读取线程最多再等一个 join 宽限。
      }
    }
  }
}
