package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在本次调用显式提供的 workdir 中执行 Platform 授权的 shell 命令。
 *
 * <p>静态解析无法沙箱化 shell 内部行为；命令授权属于 Platform permission。Daemon 只按本次 arguments 的 {@code workdir}
 * 解析执行目录（必须显式提供、绝对、现存且为目录），并使用运行时注入的共享 scheduler 在超时或取消时终止完整 process tree。
 *
 * <p>stdout/stderr 合并后持续排空：读取永不因输出体积停止，因此子进程不会因为管道写满而阻塞或被杀。输出超过内联阈值时落本地文件，超出捕获预算时只停止文件捕获并继续计数；
 * 两种情况都不终止进程，子进程自然退出后的退出码始终是权威事实。
 *
 * <p>live 阶段按块或按时间合并发出 {@code APPEND} partial，携带精确的字节区间与已观测总量；捕获被截断时补发一条 {@code SNAPSHOT}
 * partial，让调用方尽早知道全文不完整。终态结果的预览与本地路径才是权威内容。
 */
public final class BashCapability implements EnvironmentCapability {

  static final int DEFAULT_TIMEOUT_SECONDS = 120;
  static final int MAX_TIMEOUT_SECONDS = 3600;

  /** 单条 live partial 的文本上界：远低于 256 KiB 的单条 partial 协议上限。 */
  static final int LIVE_PARTIAL_UTF8_BYTES = 64 * 1024;

  /** 慢速输出下的合并间隔：小于该间隔的多次读取合并为一条 partial。 */
  static final long LIVE_FLUSH_INTERVAL_MILLIS = 150;

  static final String PARTIAL_KIND = "process.output";
  static final String PARTIAL_MODE_APPEND = "APPEND";
  static final String PARTIAL_MODE_SNAPSHOT = "SNAPSHOT";

  private final CodingToolsConfig config;
  private final ExecutorService executor;
  private final ScheduledExecutorService scheduler;
  private final EnvironmentCapabilityDescriptor descriptor;

  public BashCapability(
      CodingToolsConfig config, ExecutorService executor, ScheduledExecutorService scheduler) {
    this.config = Objects.requireNonNull(config, "config");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    descriptor = EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.PROCESS_EXEC);
  }

  @Override
  public EnvironmentCapabilityDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public EnvironmentCapabilityExecutionHandle execute(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener) {
    if (!descriptor.equals(request.descriptor())) {
      throw new IllegalArgumentException("request descriptor does not match tool descriptor");
    }
    Objects.requireNonNull(listener, "listener");
    BashHandle handle = new BashHandle(request.call().id(), listener);
    handle.worker = executor.submit(() -> run(request, listener, handle));
    return handle;
  }

  private void run(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener,
      BashHandle handle) {
    try {
      JsonNode args = AbstractCodingCapability.arguments(request);
      String command = AbstractCodingCapability.string(args, "command");
      Path workdir = EnvironmentPaths.workdir(AbstractCodingCapability.string(args, "workdir"));
      int timeoutSeconds = requestedTimeoutSeconds(args);
      Duration processTimeout =
          effectiveProcessTimeout(request.effectiveTimeout(), Duration.ofSeconds(timeoutSeconds));
      Process process =
          new ProcessBuilder(config.bashExecutable(), "-lc", command)
              .directory(workdir.toFile())
              .redirectErrorStream(true)
              .start();
      handle.process = process;
      if (handle.cancelled.get()) {
        handle.stopProcessTree();
      }
      handle.timeoutFuture =
          scheduler.schedule(
              () -> {
                if (handle.timedOut.compareAndSet(false, true)) {
                  try {
                    executor.execute(handle::stopProcessTree);
                  } catch (RejectedExecutionException ignored) {
                    // runtime shutdown 会同步 cancel handle 并终止进程；scheduler 不执行阻塞等待。
                  }
                }
              },
              processTimeout.toMillis(),
              TimeUnit.MILLISECONDS);
      if (handle.terminal.get()) {
        handle.timeoutFuture.cancel(false);
      }
      EnvironmentCapabilityResult res = drain(request, listener, handle, process);
      handle.complete(listener, res);
    } catch (Exception error) {
      handle.complete(
          listener, AbstractCodingCapability.error(request.call().id(), error.getMessage()));
    }
  }

  /** 持续排空合并输出：读取永不停止，输出体积与本地磁盘状态都不构成终止进程的理由。 */
  private EnvironmentCapabilityResult drain(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener,
      BashHandle handle,
      Process process)
      throws Exception {
    try (OutputSpool output = new OutputSpool(config.textOutputStore(), request.call().id())) {
      Utf8StreamDecoder streamDecoder = new Utf8StreamDecoder();
      LiveEmitter emitter = new LiveEmitter(request.call().id(), listener, handle);
      byte[] buffer = new byte[4096];
      try (InputStream input = process.getInputStream()) {
        int count;
        while ((count = input.read(buffer)) >= 0) {
          output.write(buffer, 0, count);
          emitter.accept(streamDecoder.decode(buffer, count));
          if (output.isCaptureTruncated() && !handle.truncationReported.get()) {
            handle.truncationReported.set(true);
            emitter.snapshot(output);
          }
        }
        emitter.accept(streamDecoder.finish());
      }
      emitter.flush();
      int exitCode = process.waitFor();
      if (handle.cancelled.get()) {
        return AbstractCodingCapability.error(request.call().id(), "Operation cancelled");
      }
      if (handle.timedOut.get()) {
        return AbstractCodingCapability.error(request.call().id(), "Command timed out");
      }
      boolean failed = exitCode != 0;
      if (failed) {
        output.write(("\nCommand exited with code " + exitCode).getBytes(StandardCharsets.UTF_8));
      }
      return output.finish(failed);
    }
  }

  static int requestedTimeoutSeconds(JsonNode args) {
    return AbstractCodingCapability.optionalPositiveInt(
        args, "timeout_seconds", DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
  }

  static Duration effectiveProcessTimeout(Duration invocationTimeout, Duration requestedTimeout) {
    Objects.requireNonNull(invocationTimeout, "invocationTimeout");
    Objects.requireNonNull(requestedTimeout, "requestedTimeout");
    return invocationTimeout.compareTo(requestedTimeout) <= 0
        ? invocationTimeout
        : requestedTimeout;
  }

  /** live partial 的 details 形状：模式、精确字节区间与已观测总量。 */
  static String partialDetailsJson(
      String mode, long startOffset, long endOffset, long observedBytes) {
    ObjectNode details = AbstractCodingCapability.OBJECT_MAPPER.createObjectNode();
    details.put("kind", PARTIAL_KIND);
    details.put("mode", mode);
    details.put("startOffset", startOffset);
    details.put("endOffset", endOffset);
    details.put("observedBytes", observedBytes);
    return details.toString();
  }

  /**
   * live partial 合并器：按时间与块大小两个维度合并，保证单条 partial 有界、区间精确且不丢字节。
   *
   * <p>{@code APPEND} 模式承载连续新区间；捕获被截断时用 {@code SNAPSHOT} 携带最新有界尾部，明确提示全文不完整。
   */
  private static final class LiveEmitter {

    private final String callId;
    private final EnvironmentCapabilityExecutionListener listener;
    private final BashHandle handle;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private long observedBytes;
    private long nextOffset;
    private long lastFlushNanos = System.nanoTime();

    private LiveEmitter(
        String callId, EnvironmentCapabilityExecutionListener listener, BashHandle handle) {
      this.callId = callId;
      this.listener = listener;
      this.handle = handle;
    }

    private void accept(String text) {
      if (text.isEmpty()) {
        return;
      }
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      observedBytes += bytes.length;
      pending.write(bytes, 0, bytes.length);
      if (pending.size() >= LIVE_PARTIAL_UTF8_BYTES
          || System.nanoTime() - lastFlushNanos >= LIVE_FLUSH_INTERVAL_MILLIS * 1_000_000L) {
        flush();
      }
    }

    /** 发出已合并的连续区间；文本为多字节字符时按解码后的字符边界切分，不产生孤立代理项。 */
    private void flush() {
      while (pending.size() > 0) {
        byte[] all = pending.toByteArray();
        int take = Math.min(all.length, LIVE_PARTIAL_UTF8_BYTES);
        take = alignToCharacterBoundary(all, take);
        if (take <= 0) {
          return;
        }
        String text = new String(all, 0, take, StandardCharsets.UTF_8);
        long start = nextOffset;
        long end = nextOffset + take;
        nextOffset = end;
        pending.reset();
        if (take < all.length) {
          pending.write(all, take, all.length - take);
        }
        emit(text, PARTIAL_MODE_APPEND, start, end, observedBytes);
      }
      lastFlushNanos = System.nanoTime();
    }

    /** 截断恢复快照：最新有界尾部加显式提示，说明全文已被本地捕获预算截断。 */
    private void snapshot(OutputSpool output) {
      flush();
      emit(
          output.captureTruncatedTailPreview(),
          PARTIAL_MODE_SNAPSHOT,
          nextOffset,
          nextOffset,
          observedBytes);
    }

    private void emit(String text, String mode, long start, long end, long observed) {
      if (text.isEmpty() || handle.cancelled.get() || handle.timedOut.get()) {
        return;
      }
      listener.onPartial(
          new EnvironmentCapabilityResult(
              callId,
              List.of(new TextResultContent(text)),
              false,
              partialDetailsJson(mode, start, end, observed)));
    }

    /** 不切断 UTF-8 多字节字符：回退到最后一个完整字符边界。 */
    private static int alignToCharacterBoundary(byte[] bytes, int limit) {
      int index = limit;
      while (index > 0 && (bytes[index - 1] & 0xC0) == 0x80) {
        index--;
      }
      if (index == 0) {
        // 整个前缀都是续字节（合法 UTF-8 中不可能出现）：只能按原样切分。
        return limit;
      }
      int lead = index - 1;
      int sequenceLength = sequenceLength(bytes[lead]);
      return lead + sequenceLength <= limit ? limit : lead;
    }

    private static int sequenceLength(byte value) {
      int unsigned = value & 0xFF;
      if ((unsigned & 0x80) == 0) {
        return 1;
      }
      if ((unsigned & 0xE0) == 0xC0) {
        return 2;
      }
      if ((unsigned & 0xF0) == 0xE0) {
        return 3;
      }
      if ((unsigned & 0xF8) == 0xF0) {
        return 4;
      }
      return 1;
    }
  }

  private static final class BashHandle implements EnvironmentCapabilityExecutionHandle {
    private final String callId;
    private final EnvironmentCapabilityExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean truncationReported = new AtomicBoolean();
    private volatile Process process;
    private volatile Future<?> worker;
    private volatile ScheduledFuture<?> timeoutFuture;

    private BashHandle(String callId, EnvironmentCapabilityExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        Future<?> current = worker;
        if (current != null) {
          current.cancel(true);
        }
        stopProcessTree();
        complete(listener, AbstractCodingCapability.error(callId, "Operation cancelled"));
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    private void stopProcessTree() {
      ProcessTree.terminate(process);
    }

    private void complete(
        EnvironmentCapabilityExecutionListener listener, EnvironmentCapabilityResult result) {
      if (terminal.compareAndSet(false, true)) {
        ScheduledFuture<?> timeout = timeoutFuture;
        if (timeout != null) {
          timeout.cancel(false);
        }
        listener.onComplete(result);
      }
    }
  }
}
