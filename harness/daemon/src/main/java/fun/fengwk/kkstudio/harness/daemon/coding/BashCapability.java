package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在本次调用显式提供的 workdir 中执行 Platform 授权的 shell 命令。
 *
 * <p>静态解析无法沙箱化 shell 内部行为；命令授权属于 Platform permission。Daemon 只按本次 arguments 的 {@code workdir}
 * 解析执行目录（必须显式提供、绝对、现存且为目录），并让 {@link ProcessScope} 在执行之前先取得 OS 所有权：POSIX 用新 session/进程组、Windows 用
 * Job Object 覆盖命令与它的普通后代，因此自然退出、超时与取消都能一次收敛整组，而不依赖枚举 后代。
 *
 * <p>显式 {@code setsid}/{@code set -m} 之类的自我再分组属于本能力明确不退让的边界：本能力的目标是普通同组后台后代，不是
 * 恶意命令沙箱，因此不承诺阻止命令主动逃离执行范围。
 *
 * <p>stdout/stderr 合并后持续排空：读取永不因输出体积停止，因此子进程不会因为管道写满而阻塞或被杀。输出超过内联阈值时落本地文件，超出捕获预算时只停止文件捕获并继续计数；
 * 两种情况都不终止进程，子进程自然退出后的退出码始终是权威事实。
 *
 * <p>成功、非零退出、超时和取消都收尾并保留已捕获输出：唯一的中转文件始终被发布或有界预览替代，绝不因收尾路径被删除。终态用 {@code
 * detailsJson.process.outcome}（{@code EXITED}/{@code TIMED_OUT}/{@code
 * CANCELLED}）与文本说明区分三种失败；取消或失败只说明输出可能不完整， 不声称已执行的副作用被回滚。
 *
 * <p>所有终态通知都发生在执行范围收敛之后：自然退出与终止收尾都在提交结果前完成整组终止与内核/Job 侧确认（已收敛时是幂等
 * 空操作），因此调用方一旦收到终态，就不再有进程会写输出或改副作用；收敛没有被确认时本次调用显式失败，绝不报告成自然退出。
 *
 * <p>live 阶段按块或按时间合并发出 {@code APPEND} partial，携带精确的字节区间与已观测总量；捕获被截断时补发一条 {@code SNAPSHOT}
 * partial，让调用方尽早知道全文不完整。终态结果的预览与本地路径才是权威内容。
 */
public final class BashCapability implements EnvironmentCapability {

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
    BashHandle handle = new BashHandle(listener);
    executor.submit(() -> run(request, listener, handle));
    return handle;
  }

  private void run(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener,
      BashHandle handle) {
    OutputSpool output = null;
    ProcessScope scope = null;
    Duration processTimeout = Duration.ZERO;
    long deadlineNanos = Long.MAX_VALUE;
    try {
      JsonNode args = AbstractCodingCapability.arguments(request);
      String command = AbstractCodingCapability.string(args, "command");
      Path workdir = EnvironmentPaths.workdir(AbstractCodingCapability.string(args, "workdir"));
      // 有效超时在 Platform 侧解析完成；这里只消费 request.timeout()，0 表示不设 deadline。
      processTimeout = request.timeout();
      // helper 的冷启动属于本次调用：deadline 从执行线程开始计时，而不是从命令启动之后才开始计时。
      long effectiveDeadline = deadlineNanos(processTimeout);
      deadlineNanos = effectiveDeadline;
      ProcessOutcome early = earlyOutcome(handle, effectiveDeadline);
      if (early != null) {
        // 取消或超时可能早于执行线程开始：此时不启动 helper、不产生任何本地文件，直接给出明确去向。
        handle.complete(terminatedBeforeStart(request.call().id(), early, processTimeout));
        return;
      }
      // OS 所有权先于用户命令：helper 建立执行范围、父进程登记收敛手段之后才放行 shell。
      // 启动阶段的取消与超时同样在放行之前生效，因此命令不会先产生副作用再被杀。
      scope =
          ProcessScope.start(
              workdir,
              List.of(config.bashExecutable(), "-lc", command),
              () -> earlyOutcome(handle, effectiveDeadline) == null);
      handle.scope = scope;
      // 命令以参数传入，进程不会从 stdin 读取；立刻关闭写端，等待 EOF 的命令才能自然退出而不是阻塞到超时。
      closeStdin(scope.process());
      // 0 表示没有执行 deadline：绝不能退化为立即超时。
      if (!processTimeout.isZero()) {
        long delayNanos = Math.max(0, effectiveDeadline - System.nanoTime());
        handle.timeoutFuture =
            scheduler.schedule(
                () -> {
                  if (handle.timedOut.compareAndSet(false, true)) {
                    try {
                      executor.execute(handle::stopScope);
                    } catch (RejectedExecutionException ignored) {
                      // runtime shutdown 会同步 cancel handle 并终止范围；scheduler 不执行阻塞等待。
                    }
                  }
                },
                delayNanos,
                TimeUnit.NANOSECONDS);
        if (handle.terminal.get()) {
          handle.timeoutFuture.cancel(false);
        }
      }
      output = new OutputSpool(config.textOutputStore(), request.call().id());
      handle.complete(drain(request, listener, handle, scope, processTimeout, output));
    } catch (Exception failure) {
      // 终态通知必须在范围清理与收敛完成之后：callback 一旦触发，调用方就据此认为调用已结束，
      // 此后不得再有进程在写输出或改副作用；因此这里先终止范围，再收尾并提交终态。
      boolean commandStarted = handle.scope != null;
      handle.stopScope();
      ProcessOutcome early = commandStarted ? null : earlyOutcome(handle, deadlineNanos);
      if (early != null) {
        // 命令从未启动（许可之前失败）：取消与超时可以直接给出明确去向，而不是一条含混的失败说明。
        handle.complete(terminatedBeforeStart(request.call().id(), early, processTimeout));
        return;
      }
      handle.complete(captureFailure(request, handle, output, failure));
    } finally {
      // 兜底：参数校验失败、资源只创建了一部分等未走到上面两条路径的情况都不能遗留进程或未收尾的中转文件。
      // 范围终止是幂等的，已收敛时只是空操作，也不会触发第二次终态回调。
      handle.stopScope();
      closeQuietly(output);
      closeQuietly(scope);
    }
  }

  /** 持续排空合并输出：读取永不停止，输出体积与本地磁盘状态都不构成终止进程的理由。 */
  private EnvironmentCapabilityResult drain(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener,
      BashHandle handle,
      ProcessScope scope,
      Duration processTimeout,
      OutputSpool output)
      throws Exception {
    Utf8StreamDecoder streamDecoder = new Utf8StreamDecoder();
    LiveEmitter emitter = new LiveEmitter(request.call().id(), listener, handle);
    byte[] buffer = new byte[4096];
    InputStream input = scope.process().getInputStream();
    try {
      try {
        int count;
        while ((count = input.read(buffer)) >= 0) {
          output.write(buffer, 0, count);
          emitter.accept(streamDecoder.decode(buffer, count));
          if (output.isCaptureTruncated() && !handle.truncationReported.get()) {
            handle.truncationReported.set(true);
            emitter.snapshot(output);
          }
        }
      } catch (IOException ignored) {
        // 终止导致的流异常关闭：已读取的字节仍然有效，不能因此丢弃已捕获输出。
      }
    } catch (RuntimeException | Error failure) {
      // 排空期间的异常（监听器抛错等）必须先收敛执行范围，再关闭 stdout：读端一旦提前关闭，
      // 主进程会因为 SIGPIPE 自行退出，而它当时已经 fork 的后台后代就此脱离可达范围，收尾再也覆盖不到。
      handle.stopScope();
      throw failure;
    } finally {
      closeQuietly(input);
    }
    emitter.accept(streamDecoder.finish());
    emitter.flush();
    // 到这里范围一定已经收敛：自然退出，或在超时/取消时被终止。helper 的退出码只用于诊断，命令的事实来自状态文件。
    int helperExitCode = scope.process().waitFor();
    ProcessOutcome termination = terminatedOutcome(handle);
    // 终态通知只能发生在范围收敛之后：自然退出时这里是空操作（范围已收敛），
    // 超时/取消时这里等到已发出的终止完全收敛，避免还有进程存活时就回调。
    if (!handle.stopScope()) {
      // 收敛没有被内核或 Job 确认：绝不能把这次调用报告成自然退出，也不能谎称副作用已经停止。
      throw new IllegalStateException(
          "the process scope did not converge; processes may still be running");
    }
    String scopeFailure = scope.startFailure();
    if (scopeFailure != null) {
      throw new IllegalStateException(scopeFailure);
    }
    Integer naturalExitCode = scope.naturalExitCode();
    if (termination == null && naturalExitCode == null) {
      throw new IllegalStateException(
          "the command scope ended without reporting the command exit status (helper exit code "
              + helperExitCode
              + ")");
    }
    ProcessOutcome outcome = termination == null ? ProcessOutcome.EXITED : termination;
    int exitCode = naturalExitCode == null ? 0 : naturalExitCode;
    boolean failed = outcome != ProcessOutcome.EXITED || exitCode != 0;
    // 任何退出方式都先收尾并发布已捕获输出：这是唯一副本，收尾只会在本地存储失败时降级为有界预览。
    return withProcessOutcome(
        output.finish(failed, terminationNote(outcome, exitCode, processTimeout)),
        outcome,
        exitCode);
  }

  /** 已知的终止去向；没有取消或超时信号时返回 {@code null}，表示进程去向由自然退出决定。 */
  private static ProcessOutcome terminatedOutcome(BashHandle handle) {
    if (handle.cancelled.get()) {
      return ProcessOutcome.CANCELLED;
    }
    return handle.timedOut.get() ? ProcessOutcome.TIMED_OUT : null;
  }

  /**
   * 本次调用的有效 deadline；{@code 0} 超时表示没有 deadline，此时返回 {@link Long#MAX_VALUE}。
   *
   * <p>deadline 从执行线程开始计时：helper 的冷启动也占用调用方给的时间预算。
   */
  private static long deadlineNanos(Duration processTimeout) {
    if (processTimeout.isZero()) {
      return Long.MAX_VALUE;
    }
    long budget;
    try {
      budget = processTimeout.toNanos();
    } catch (ArithmeticException error) {
      // 超出可表示范围等价于没有 deadline：绝不能退化成立即超时。
      return Long.MAX_VALUE;
    }
    long now = System.nanoTime();
    return budget > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + budget;
  }

  /**
   * 启动阶段的去向判定：取消、超时或本次调用已经超过有效 deadline。
   *
   * <p>在没有 deadline 时只有显式的取消/超时信号算数，因此 {@link Long#MAX_VALUE} 不会被当成过期。
   */
  private static ProcessOutcome earlyOutcome(BashHandle handle, long deadlineNanos) {
    ProcessOutcome terminated = terminatedOutcome(handle);
    if (terminated != null) {
      return terminated;
    }
    return System.nanoTime() >= deadlineNanos ? ProcessOutcome.TIMED_OUT : null;
  }

  /**
   * 启动前已被取消或超时的收尾：不启动 shell、不创建任何本地文件，直接给出明确的进程去向。
   *
   * <p>此处没有任何输出可保留，正文只有与其它终态一致的说明：输出可能不完整，已执行的副作用不回滚。
   */
  private static EnvironmentCapabilityResult terminatedBeforeStart(
      String callId, ProcessOutcome outcome, Duration processTimeout) {
    ObjectNode details = AbstractCodingCapability.OBJECT_MAPPER.createObjectNode();
    details.putObject("process").put("outcome", outcome.name());
    return new EnvironmentCapabilityResult(
        callId,
        List.of(new TextResultContent(terminationNote(outcome, 0, processTimeout))),
        true,
        details.toString());
  }

  /**
   * 异常路径的收尾：进程树终止与 spool 关闭已由 {@link #run} 的 {@code finally} 保证，这里只决定终态内容。
   *
   * <p>已捕获输出优先保留：有内容时按捕获事实收尾，失败原因只作为终态说明追加；完全没有输出时才退回纯错误结果，绝不谎称捕获过内容。
   */
  private EnvironmentCapabilityResult captureFailure(
      EnvironmentCapabilityExecutionRequest request,
      BashHandle handle,
      OutputSpool output,
      Exception error) {
    String reason = error.getMessage() == null ? error.toString() : error.getMessage();
    if (output == null || output.totalBytes() == 0) {
      // 没有捕获到任何输出时的纯错误结果：命令尚未启动（scope 未建立）说明校验/启动前失败、未执行；
      // 已经启动则结果无法确认，绝不能声称未执行。
      ExecutionFact fact =
          handle.scope == null ? ExecutionFact.NOT_EXECUTED : ExecutionFact.UNCERTAIN;
      return AbstractCodingCapability.error(
          request.call().id(),
          ToolErrorGuidance.message(
              failureReason(handle, error),
              fact,
              "Review the command and the reported process state before deciding what to do next"));
    }
    EnvironmentCapabilityResult captured = output.finish(true, "[" + reason + "]");
    ObjectNode details = parseDetails(captured.detailsJson());
    ProcessOutcome outcome = terminatedOutcome(handle);
    if (outcome != null) {
      details.putObject("process").put("outcome", outcome.name());
    }
    return new EnvironmentCapabilityResult(
        captured.callId(), captured.contents(), true, details.toString());
  }

  /**
   * 终态说明只取受控来源：派发前参数/路径拒绝的字段说明，或配置里显式的 shell 可执行文件。已启动后的异常不回显其 message（进程启动与读取的 IO 异常可能内联可执行文件与参数）。
   */
  private String failureReason(BashHandle handle, Exception error) {
    if (error instanceof ToolInputRejectedException) {
      return error.getMessage();
    }
    if (handle.scope == null) {
      return "the shell could not be started: " + config.bashExecutable();
    }
    // 已启动后的异常不回显其 message（进程启动与读取的 IO 异常可能内联可执行文件与参数）：诊断改用显式的 shell 名。
    return "the command failed after it started (shell: " + config.bashExecutable() + ")";
  }

  /** 关闭子进程 stdin：命令以参数传入、不读 stdin，写端未关闭会让等待 EOF 的命令一直阻塞到超时。 */
  private static void closeStdin(Process process) {
    try {
      process.getOutputStream().close();
    } catch (IOException ignored) {
      // 关闭失败只影响子进程能否读到 EOF：超时与取消仍会收敛执行范围。
    }
  }

  /** 关闭捕获中转：{@link OutputSpool#close()} 只删除未发布的中转文件，已发布全文与已提交终态不受影响。 */
  private static void closeQuietly(OutputSpool output) {
    if (output == null) {
      return;
    }
    try {
      output.close();
    } catch (Exception ignored) {
      // 关闭失败只影响未发布中转文件的清理。
    }
  }

  /** 释放执行范围：删除调用私有的临时状态目录；终止本身是幂等且有界的。 */
  private static void closeQuietly(ProcessScope scope) {
    if (scope == null) {
      return;
    }
    try {
      scope.close();
    } catch (Exception ignored) {
      // 关闭失败只影响私有状态目录的清理，命令结果与该事实无关。
    }
  }

  /** 关闭子进程 stdout：关闭失败只影响后续读取，已捕获的字节仍然有效。 */
  private static void closeQuietly(InputStream input) {
    try {
      input.close();
    } catch (IOException ignored) {
      // 关闭失败只影响后续读取。
    }
  }

  /**
   * 终态说明：区分成功、非零退出、超时与取消，并明确取消或失败不代表副作用回滚。
   *
   * <p>说明只追加到返回文本；它不写入已发布的 durable 全文，也不计入捕获总量。
   */
  private static String terminationNote(
      ProcessOutcome outcome, int exitCode, Duration processTimeout) {
    switch (outcome) {
      case CANCELLED:
        return "[Operation cancelled by the caller. Captured output above may be incomplete; side"
            + " effects already performed are not rolled back.]";
      case TIMED_OUT:
        return "[Command timed out after "
            + processTimeout.toMillis()
            + " ms and was terminated. Captured output above may be incomplete; side effects"
            + " already performed are not rolled back.]";
      case EXITED:
        return exitCode == 0 ? "" : "[Command exited with code " + exitCode + ".]";
      default:
        throw new IllegalStateException("unhandled process outcome: " + outcome);
    }
  }

  /** 在捕获事实之上补充机器可判定的进程收尾结果；退出码只在自然退出时报告。 */
  private static EnvironmentCapabilityResult withProcessOutcome(
      EnvironmentCapabilityResult captured, ProcessOutcome outcome, int exitCode) {
    ObjectNode details = parseDetails(captured.detailsJson());
    ObjectNode process = details.putObject("process");
    process.put("outcome", outcome.name());
    if (outcome == ProcessOutcome.EXITED) {
      process.put("exitCode", exitCode);
    }
    return new EnvironmentCapabilityResult(
        captured.callId(), captured.contents(), captured.error(), details.toString());
  }

  private static ObjectNode parseDetails(String detailsJson) {
    try {
      return (ObjectNode) AbstractCodingCapability.OBJECT_MAPPER.readTree(detailsJson);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("capability detailsJson must be a JSON object", error);
    }
  }

  /** 进程的最终去向：只有 {@link #EXITED} 时退出码才是命令的事实。 */
  private enum ProcessOutcome {
    EXITED,
    TIMED_OUT,
    CANCELLED
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
    private final EnvironmentCapabilityExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean timedOut = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private final AtomicBoolean truncationReported = new AtomicBoolean();
    private volatile ProcessScope scope;
    private volatile ScheduledFuture<?> timeoutFuture;

    private BashHandle(EnvironmentCapabilityExecutionListener listener) {
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        // 只标记取消并终止执行范围：终态由执行线程在排空并收尾已捕获输出后统一提交，
        // 因此取消不会丢弃捕获结果，也不会让 close 删除唯一的中转文件。
        stopScope();
      }
    }

    /**
     * 按运行时的明确原因收尾：原因决定终态如何描述进程去向，因此超时绝不会被报告成调用方取消。
     *
     * <p>运行时的超时与调用方取消都只终止执行范围，终态仍由执行线程在排空并收尾已捕获输出后提交。
     */
    @Override
    public void terminate(EnvironmentCapabilityTerminationCause cause) {
      if (cause == EnvironmentCapabilityTerminationCause.TIMED_OUT) {
        if (timedOut.compareAndSet(false, true)) {
          stopScope();
        }
        return;
      }
      cancel();
    }

    @Override
    public boolean isCancelled() {
      // 超时收尾与取消一样要求进程停止，因此这里表达「已被请求终止」，与其它能力的协作式中断信号一致。
      return cancelled.get() || timedOut.get();
    }

    /**
     * 收尾预算：运行时不再以「超时/取消」立即收敛终态，而是最多等这么久让本能力提交携带已捕获输出的终态。
     *
     * <p>预算只需覆盖执行范围的终止、排空、收尾与中转文件发布；运行时的终止请求会同步终止范围，执行线程在提交终态前还会等待内核确认收敛， 两者合计上限是温和信号的宽限窗口、helper
     * 退出与范围收敛预算之和，因此秒级预算对同步收敛、排空与发布仍然充裕，不构成无界等待。
     */
    @Override
    public Duration terminationGrace() {
      return Duration.ofSeconds(2);
    }

    /**
     * 收敛执行范围，并返回范围是否已经由内核或 Job 证明没有活着的成员。
     *
     * <p>没有建立范围（命令从未启动）时视为已经收敛；并发调用等待同一次收敛，等待失败同样返回 {@code false}， 绝不把「等不到结果」当成收敛。
     */
    private boolean stopScope() {
      ProcessScope current = scope;
      return current == null || current.terminate();
    }

    /** 提交唯一终态；终态一旦提交就不再调度超时，也不再触发第二次回调。 */
    private void complete(EnvironmentCapabilityResult result) {
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
