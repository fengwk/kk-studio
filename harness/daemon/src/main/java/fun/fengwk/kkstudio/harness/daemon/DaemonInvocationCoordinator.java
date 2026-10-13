package fun.fengwk.kkstudio.harness.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalEntry;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalStart;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityResultCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelopeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 本地 Environment Capability 的调用执行事实：Invocation journal、进程内执行句柄、deadline 与终态仲裁。
 *
 * <p>journal 是进程内执行事实源；本类只在 RUNNING 到终态的原子跃迁成功时发送终态报文，因此重连重放与迟到回调都不产生第二份终态。执行句柄的逐调用状态归 {@link
 * DaemonRunningInvocation}；本类不缓存这些事实，只按 invocationId 驱动。
 *
 * <p>本类只通过 {@link DaemonMessageSink} 出站、通过 {@link DaemonInvocationUploads}
 * 在终态编码时直传资源，因此不持有连接、资源或更新状态。
 */
final class DaemonInvocationCoordinator {

  private static final Logger LOG = System.getLogger(DaemonInvocationCoordinator.class.getName());

  private final DaemonCapabilityRegistry capabilityRegistry;
  private final DaemonInvocationJournal journal;
  private final ScheduledExecutorService scheduler;
  private final BooleanSupplier started;
  private final DaemonMessageSink sink;
  private final DaemonInvocationUploads uploads;
  private final DaemonEnvelopeCodec envelopeCodec = new DaemonEnvelopeCodec();
  private final DaemonCapabilityInvokeCodec capabilityInvokeCodec =
      new DaemonCapabilityInvokeCodec();
  private final DaemonCapabilityResultCodec resultCodec = new DaemonCapabilityResultCodec();
  private final ConcurrentHashMap<String, DaemonRunningInvocation> running =
      new ConcurrentHashMap<>();

  DaemonInvocationCoordinator(
      DaemonCapabilityRegistry capabilityRegistry,
      DaemonInvocationJournal journal,
      ScheduledExecutorService scheduler,
      BooleanSupplier started,
      DaemonMessageSink sink,
      DaemonInvocationUploads uploads) {
    this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry, "capabilityRegistry");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    this.started = Objects.requireNonNull(started, "started");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.uploads = Objects.requireNonNull(uploads, "uploads");
  }

  /** 处理一条 INVOKE：先做 journal 去重，再按 descriptor/参数预检后派发本地执行。 */
  void handleInvoke(DaemonEnvelope envelope) {
    // wire 的 timeoutMillis 是 Platform 已解析完成的唯一有效超时：Daemon 原样使用，不再回落或截断。
    InvokePayload payload = readInvokePayload(envelope);
    if (!started.getAsBoolean()) {
      return;
    }
    // 原子去重：若 journal 已存在该 invocationId，重放既有状态（RUNNING 重放 STARTED(replayed=true)，终态重放对应 terminal
    // 报文）。
    DaemonInvocationJournalStart start = journal.start(envelope.invocationId());
    if (!start.created()) {
      replay(start.entry(), envelope.invocationId());
      return;
    }

    try {
      EnvironmentCapability capability =
          capabilityRegistry
              .find(payload.capabilityId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "unknown Environment capability: " + payload.capabilityId()));
      EnvironmentCapabilityDescriptor descriptor = capability.descriptor();
      if (!descriptor.version().equals(payload.capabilityVersion())) {
        throw new IllegalArgumentException(
            "capabilityVersion does not match environment descriptor: "
                + payload.capabilityVersion());
      }
      Duration timeout = payload.timeout();
      EnvironmentCapabilityExecutionRequest request =
          new EnvironmentCapabilityExecutionRequest(
              descriptor,
              new EnvironmentCapabilityCall(envelope.invocationId(), payload.argumentsJson()),
              timeout);
      DaemonRunningInvocation invocation = new DaemonRunningInvocation(envelope.invocationId());
      running.put(envelope.invocationId(), invocation);
      if (!started.getAsBoolean() || !isRunning(envelope.invocationId()) || !invocation.begin()) {
        running.remove(envelope.invocationId(), invocation);
        if (!started.getAsBoolean()) {
          journal.complete(
              envelope.invocationId(),
              new DaemonTerminalMessage(
                  DaemonMessageType.CANCELLED, "{\"reason\":\"daemon stopped\"}"));
        }
        return;
      }
      // 仅当调用参数、能力元数据与运行时预检全部通过后才发出 STARTED。
      sink.send(DaemonMessageType.STARTED, envelope.invocationId(), "{}");
      EnvironmentCapabilityExecutionHandle handle =
          capability.execute(request, new InvocationListener(invocation));
      invocation.setHandle(Objects.requireNonNull(handle, "capability execution handle"));
      scheduleTimeout(invocation, timeout);
    } catch (RuntimeException error) {
      terminal(
          envelope.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.FAILED, DaemonFailurePayload.error(error)),
          null);
    }
  }

  /** 处理一条 CANCEL：已终态重放既有终态，活动调用走终态仲裁，未知调用不产生任何报文。 */
  void handleCancel(DaemonEnvelope envelope) {
    Optional<DaemonInvocationJournalEntry> entry = journal.find(envelope.invocationId());
    if (entry.isEmpty()) {
      return;
    }
    if (entry.get().state().isTerminal()) {
      replay(entry.get(), envelope.invocationId());
      return;
    }
    terminal(
        envelope.invocationId(),
        new DaemonTerminalMessage(DaemonMessageType.CANCELLED, "{\"reason\":\"cancelled\"}"),
        EnvironmentCapabilityTerminationCause.CANCELLED);
  }

  /**
   * 关闭：取消全部在途调用并为其记录可重放的 CANCELLED，绝不留下无终态的本地执行。
   *
   * <p>单个调用的收尾失败（RuntimeException）不得中断其余调用：每个调用独立收尾、独立写 journal 终态，失败只记录异常类型；进程级 Error 仍向上传播。
   */
  void shutdown() {
    for (DaemonRunningInvocation invocation : running.values()) {
      try {
        invocation.terminate(EnvironmentCapabilityTerminationCause.CANCELLED);
      } catch (RuntimeException error) {
        // 释放失败不阻断 shutdown，也不回显底层正文。
        LOG.log(
            Level.DEBUG, "daemon invocation terminate failed: " + error.getClass().getSimpleName());
      }
      journal.complete(
          invocation.invocationId(),
          new DaemonTerminalMessage(
              DaemonMessageType.CANCELLED, "{\"reason\":\"daemon shutdown\"}"));
    }
    running.clear();
  }

  /**
   * 在调用仍活动且终态未抢占时递交一条资源上传控制帧（{@code RESOURCE_UPLOAD_REQUEST}/{@code COMMIT}）。
   *
   * <p>控制帧的「检查调用仍活动」与「发送」必须对该 invocation 的终态抢占互斥：服务端只接受活动调用的上传控制帧，若控制帧排在终态之后发出，连接会被判定为协议违规而关闭。
   */
  boolean sendControlWhileActive(
      DaemonMessageType messageType, String invocationId, String payloadJson) {
    DaemonRunningInvocation invocation = running.get(invocationId);
    if (invocation == null) {
      return false;
    }
    return invocation.trySendControl(
        () -> isRunning(invocationId) && sink.send(messageType, invocationId, payloadJson));
  }

  private void replay(DaemonInvocationJournalEntry entry, String invocationId) {
    if (entry.state() == DaemonInvocationState.RUNNING) {
      sink.send(DaemonMessageType.STARTED, invocationId, "{\"replayed\":true}");
      return;
    }
    DaemonTerminalMessage terminal = entry.terminalMessage();
    sink.send(terminal.messageType(), invocationId, terminal.payloadJson());
  }

  /**
   * 原子终态仲裁与结果派发。
   *
   * <p>存在 {@link DaemonRunningInvocation} 时，正常完成、执行失败、调度器超时、主动取消与停机先通过 {@link
   * DaemonRunningInvocation#claimTerminal} 仲裁本地执行资源；预检失败等尚未建立本地执行的路径直接进入 journal。两类路径最终都以 {@link
   * DaemonInvocationJournal#complete(String, DaemonTerminalMessage)} 的 RUNNING
   * 到终态原子跃迁作为唯一提交点；只有提交成功者发送终态报文。
   *
   * @param cause 非空表示运行时基于 deadline 或取消裁决
   */
  private void terminal(
      String invocationId,
      DaemonTerminalMessage message,
      EnvironmentCapabilityTerminationCause cause) {
    DaemonRunningInvocation invocation = running.get(invocationId);
    if (invocation != null
        && !invocation.claimTerminal(
            scheduler, cause, message, () -> commitTerminal(invocationId, invocation, message))) {
      return;
    }
    commitTerminal(invocationId, invocation, message);
  }

  /** 单点提交终态：journal 的 RUNNING 到终态跃迁是唯一仲裁点，只有跃迁成功者发送终态报文。 */
  private void commitTerminal(
      String invocationId, DaemonRunningInvocation invocation, DaemonTerminalMessage message) {
    if (!journal.complete(invocationId, message)) {
      return;
    }
    if (invocation == null) {
      running.remove(invocationId);
    } else {
      running.remove(invocationId, invocation);
    }
    sink.send(message.messageType(), invocationId, message.payloadJson());
  }

  private boolean isRunning(String invocationId) {
    return journal
        .find(invocationId)
        .map(entry -> entry.state() == DaemonInvocationState.RUNNING)
        .orElse(false);
  }

  /**
   * 为调用登记执行 deadline。
   *
   * <p>timeout 为 0 表示「没有 deadline」：绝不能调度一个立即触发的定时终态，调用只由 CANCEL、连接停机或能力自身终态收敛。
   */
  private void scheduleTimeout(DaemonRunningInvocation invocation, Duration timeout) {
    if (!isRunning(invocation.invocationId()) || invocation.isTerminal()) {
      return;
    }
    if (timeout.isZero()) {
      invocation.setDeadlineNanos(Long.MAX_VALUE);
      return;
    }
    invocation.setDeadlineNanos(deadlineNanos(timeout));
    try {
      ScheduledFuture<?> deadline =
          scheduler.schedule(
              () ->
                  terminal(
                      invocation.invocationId(),
                      new DaemonTerminalMessage(
                          DaemonMessageType.FAILED,
                          "{\"message\":\"capability execution timed out after "
                              + timeout.toMillis()
                              + "ms\"}"),
                      EnvironmentCapabilityTerminationCause.TIMED_OUT),
              timeout.toMillis(),
              TimeUnit.MILLISECONDS);
      invocation.setDeadline(deadline);
    } catch (RuntimeException error) {
      ObjectNode payload = envelopeCodec.createPayload();
      payload.put("message", "cannot schedule capability timeout: " + error.getMessage());
      terminal(
          invocation.invocationId(),
          new DaemonTerminalMessage(DaemonMessageType.FAILED, payload.toString()),
          EnvironmentCapabilityTerminationCause.CANCELLED);
    }
  }

  private static long deadlineNanos(Duration timeout) {
    try {
      return Math.addExact(System.nanoTime(), Math.multiplyExact(timeout.toMillis(), 1_000_000L));
    } catch (ArithmeticException overflow) {
      // wire 允许的超长 timeout 只表示「实际上无 deadline」；调用仍可由 CANCEL、连接停机或正常终态收敛。
      return Long.MAX_VALUE;
    }
  }

  private InvokePayload readInvokePayload(DaemonEnvelope envelope) {
    DaemonCapabilityInvokeCodec.InvokeRequest request =
        capabilityInvokeCodec.decode(envelope.payloadJson());
    return new InvokePayload(
        request.capabilityId(),
        request.capabilityVersion(),
        request.argumentsJson(),
        request.timeout());
  }

  private void failResultEncoding(String invocationId, RuntimeException error, String phase) {
    String message =
        "cannot " + phase + " capability result: " + DaemonFailurePayload.safeMessage(error);
    terminal(
        invocationId,
        new DaemonTerminalMessage(
            DaemonMessageType.FAILED,
            DaemonFailurePayload.error(new IllegalStateException(message, error))),
        null);
  }

  /**
   * 用能力收尾事实替换运行时终态的正文，同时保留运行时的终态类型。
   *
   * <p>终态类型表达裁决原因（超时仍是 {@code FAILED}、取消仍是 {@code
   * CANCELLED}，平台据此走既有的失败/取消收敛路径），正文表达本地事实。这样已捕获输出无需协议变更即可到达调用方，也绝不会把一次取消报告成 {@code COMPLETED}。
   *
   * <p>注入的正文只来自能力结果的文本内容，与同一次调用原本会返回的工具结果正文完全一致：二进制/资源/JSON 内容不进入终态说明，因此不会出现 durable 日志之外的原始字节。超过单条
   * wire 文本上限的正文被整体丢弃（只保留运行时自己的裁决原因），绝不截断成半个字符，也绝不因此丢终态。
   */
  private DaemonTerminalMessage withCapabilityText(
      DaemonTerminalMessage base, String capabilityText) {
    String field = base.messageType() == DaemonMessageType.CANCELLED ? "reason" : "message";
    String baseText = payloadText(base.payloadJson(), field);
    String text =
        capabilityText == null
                || capabilityText.isBlank()
                || ResourceRef.utf8LengthUpTo(
                        capabilityText,
                        "capabilityText",
                        DaemonCapabilityResultCodec.MAX_PAYLOAD_UTF8_BYTES)
                    > DaemonCapabilityResultCodec.MAX_PAYLOAD_UTF8_BYTES
            ? baseText
            : baseText + "\n" + capabilityText;
    ObjectNode payload = envelopeCodec.createPayload();
    payload.put(field, text);
    return new DaemonTerminalMessage(base.messageType(), payload.toString());
  }

  private String payloadText(String payloadJson, String field) {
    try {
      JsonNode payload = envelopeCodec.readJson(payloadJson);
      JsonNode value = payload.isObject() ? payload.get(field) : null;
      return value == null || !value.isTextual() ? "" : value.textValue();
    } catch (RuntimeException error) {
      // 运行时自己的终态正文必定可解析；解析失败时退化为只携带能力文本，绝不因此丢终态。
      return "";
    }
  }

  /** 汇总能力结果中模型可见的文本正文；非文本内容不进入终态说明。 */
  private static String capturedText(EnvironmentCapabilityResult result) {
    StringBuilder text = new StringBuilder();
    for (ResultContent content : result.contents()) {
      if (content instanceof TextResultContent textContent) {
        if (text.length() > 0) {
          text.append('\n');
        }
        text.append(textContent.text());
      }
    }
    return text.toString();
  }

  /** 绑定一次调用的上传等待边界；deadline 与预算在创建时确定，重连不改变本次上传的等待语义。 */
  private DaemonInvocationUploads.Deadline uploadDeadline(DaemonRunningInvocation invocation) {
    long deadlineNanos = invocation.deadlineNanos();
    return new DaemonInvocationUploads.Deadline() {
      @Override
      public boolean canContinue() {
        return !invocation.isTerminal()
            && isRunning(invocation.invocationId())
            && System.nanoTime() < deadlineNanos;
      }

      @Override
      public long remainingMillis() {
        if (deadlineNanos == Long.MAX_VALUE) {
          return Long.MAX_VALUE;
        }
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
      }
    };
  }

  /** 调用执行事件到 wire 报文的适配：只转发与调用关联的正文，不转发原始字节。 */
  private final class InvocationListener implements EnvironmentCapabilityExecutionListener {

    private final DaemonRunningInvocation invocation;

    private InvocationListener(DaemonRunningInvocation invocation) {
      this.invocation = invocation;
    }

    private static boolean matchesInvocation(
        EnvironmentCapabilityResult result, String invocationId) {
      return result != null && invocationId.equals(result.callId());
    }

    @Override
    public void onPartial(EnvironmentCapabilityResult partial) {
      if (invocation.isTerminal() || !isRunning(invocation.invocationId())) {
        return;
      }
      if (!matchesInvocation(partial, invocation.invocationId())) {
        terminal(
            invocation.invocationId(),
            new DaemonTerminalMessage(
                DaemonMessageType.FAILED,
                "{\"message\":\"capability partial callId does not match invocationId\"}"),
            EnvironmentCapabilityTerminationCause.CANCELLED);
        return;
      }
      try {
        String payloadJson = resultCodec.encodeProgress(partial);
        sink.send(DaemonMessageType.PROGRESS, invocation.invocationId(), payloadJson);
      } catch (RuntimeException error) {
        failResultEncoding(invocation.invocationId(), error, "partial");
      }
    }

    @Override
    public void onComplete(EnvironmentCapabilityResult result) {
      if (!matchesInvocation(result, invocation.invocationId())) {
        terminal(
            invocation.invocationId(),
            new DaemonTerminalMessage(
                DaemonMessageType.FAILED,
                "{\"message\":\"capability result callId does not match invocationId\"}"),
            null);
        return;
      }
      try {
        String payloadJson =
            resultCodec.encodeCompleted(
                result,
                uploads.maxResourceBytes(),
                uploads.uploaderFor(uploadDeadline(invocation)));
        DaemonTerminalMessage completed =
            new DaemonTerminalMessage(DaemonMessageType.COMPLETED, payloadJson);
        // 处于超时/取消的延迟终态窗口时，用已捕获输出在同一裁决类型下收尾；否则按正常终态路径提交。
        if (!invocation.resolveHandoff(
            runtime -> withCapabilityText(runtime, capturedText(result)),
            resolved -> commitTerminal(invocation.invocationId(), invocation, resolved))) {
          terminal(invocation.invocationId(), completed, null);
        }
      } catch (RuntimeException error) {
        failResultEncoding(invocation.invocationId(), error, "complete");
      }
    }

    @Override
    public void onError(Throwable error) {
      DaemonTerminalMessage failed =
          new DaemonTerminalMessage(DaemonMessageType.FAILED, DaemonFailurePayload.error(error));
      if (!invocation.resolveHandoff(
          runtime -> withCapabilityText(runtime, DaemonFailurePayload.safeMessage(error)),
          resolved -> commitTerminal(invocation.invocationId(), invocation, resolved))) {
        terminal(invocation.invocationId(), failed, null);
      }
    }
  }

  private record InvokePayload(
      EnvironmentCapabilityId capabilityId,
      String capabilityVersion,
      String argumentsJson,
      Duration timeout) {}
}
