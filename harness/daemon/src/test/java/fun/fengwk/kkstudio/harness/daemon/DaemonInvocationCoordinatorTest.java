package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationJournalEntry;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonInvocationState;
import fun.fengwk.kkstudio.harness.daemon.journal.DaemonTerminalMessage;
import fun.fengwk.kkstudio.harness.daemon.journal.InMemoryDaemonInvocationJournal;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilityInvokeCodec;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvelope;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMessageType;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonProtocol;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonResourceUploader;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 意图：调用编排的必要核心路径都有确定结论——停机期间不再接受调用且绝不留下无终态的本地执行、已终态调用只重放、未知调用不产生报文、
 * 超时无法登记时立即失败、不匹配的能力结果立即失败、以及收尾预算内的能力正文替换裁决正文但绝不改写裁决类型。
 */
class DaemonInvocationCoordinatorTest {

  private static final long AWAIT_TIMEOUT_SECONDS = 10L;
  private static final String CAPABILITY_ID = "test-capability";
  private static final String CAPABILITY_VERSION = "1.0.0";
  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private final List<Harness> harnesses = new ArrayList<>();

  @AfterEach
  void closeHarnesses() {
    harnesses.forEach(Harness::close);
  }

  /** 停机后到达的 INVOKE 被忽略：不发送 STARTED，也不建立任何 invocation 事实。 */
  @Test
  void ignoresInvokeAfterTheRuntimeStopped() {
    Harness harness = harness(() -> false);

    harness.coordinator.handleInvoke(invoke("call-stopped", Duration.ofSeconds(30)));

    assertTrue(harness.sink.isEmpty(), "a stopped runtime must not signal STARTED");
    assertTrue(
        harness.journal.find("call-stopped").isEmpty(),
        "a stopped runtime must not open an invocation fact");
  }

  /** 接受过程中停机：登记后立即取消本地执行并写入可重放的 CANCELLED，绝不留下无终态的本地执行。 */
  @Test
  void recordsCancelledWhenTheRuntimeStopsWhileAccepting() {
    AtomicInteger starts = new AtomicInteger();
    Harness harness = harness(() -> starts.incrementAndGet() == 1);

    harness.coordinator.handleInvoke(invoke("call-stopping", Duration.ofSeconds(30)));

    assertTrue(harness.sink.isEmpty(), "no wire terminal may be sent for a stopped runtime");
    DaemonInvocationJournalEntry entry = harness.journal.find("call-stopping").orElseThrow();
    assertEquals(DaemonInvocationState.CANCELLED, entry.state());
    assertEquals("{\"reason\":\"daemon stopped\"}", entry.terminalMessage().payloadJson());
  }

  /** 已终态调用的 CANCEL 只重放既有终态，重复取消仍然只有一份终态事实。 */
  @Test
  void replaysAnAlreadyTerminalCancelWithoutCreatingANewFact() throws InterruptedException {
    Harness harness = harness(() -> true);
    harness.journal.start("call-terminal");
    assertTrue(
        harness.journal.complete(
            "call-terminal",
            new DaemonTerminalMessage(DaemonMessageType.CANCELLED, "{\"reason\":\"cancelled\"}")));

    harness.coordinator.handleCancel(cancel("call-terminal"));
    harness.coordinator.handleCancel(cancel("call-terminal"));

    List<Outbound> replayed = harness.sink.drain(2);
    replayed.forEach(
        outbound -> {
          assertEquals(DaemonMessageType.CANCELLED, outbound.type());
          assertEquals("call-terminal", outbound.invocationId());
          assertEquals("{\"reason\":\"cancelled\"}", outbound.payload());
        });
    DaemonInvocationJournalEntry entry = harness.journal.find("call-terminal").orElseThrow();
    assertEquals(DaemonInvocationState.CANCELLED, entry.state());
  }

  /** 未知调用的资源上传控制帧确定不发送。 */
  @Test
  void refusesControlFramesForUnknownInvocations() {
    Harness harness = harness(() -> true);

    assertFalse(
        harness.coordinator.sendControlWhileActive(
            DaemonMessageType.RESOURCE_UPLOAD_REQUEST, "call-missing", "{}"));
    assertTrue(harness.sink.isEmpty());
  }

  /** 执行器停机导致 deadline 无法登记：调用立即以 FAILED 收敛，而不是悬挂到永远。 */
  @Test
  void failsTheInvocationWhenTheDeadlineCannotBeScheduled() throws InterruptedException {
    Harness harness = harness(() -> true);
    harness.scheduler.shutdownNow();

    harness.coordinator.handleInvoke(invoke("call-unschedulable", Duration.ofSeconds(30)));

    List<Outbound> outbound = harness.sink.drain(2);
    assertEquals(DaemonMessageType.STARTED, outbound.get(0).type());
    assertEquals(DaemonMessageType.FAILED, outbound.get(1).type());
    assertTrue(
        outbound.get(1).payload().contains("cannot schedule capability timeout"),
        outbound.get(1).payload());
    assertEquals(
        DaemonInvocationState.FAILED,
        harness.journal.find("call-unschedulable").orElseThrow().state());
    assertFalse(
        harness.coordinator.sendControlWhileActive(
            DaemonMessageType.RESOURCE_UPLOAD_REQUEST, "call-unschedulable", "{}"),
        "a failed invocation no longer accepts control frames");
  }

  /** 能力回报的 callId 与 invocationId 不符是本地执行事实冲突：立即以 FAILED 收敛，绝不把他人结果当成自己的终态。 */
  @Test
  void failsTheInvocationWhenTheCapabilityResultBelongsToAnotherCall() throws InterruptedException {
    Harness harness = harness(() -> true);
    harness.coordinator.handleInvoke(invoke("call-mismatch", Duration.ofSeconds(30)));
    assertEquals(DaemonMessageType.STARTED, harness.sink.await().type());

    harness.capability.partial(EnvironmentCapabilityResult.text("someone-else", "hello"));

    Outbound failed = harness.sink.await();
    assertEquals(DaemonMessageType.FAILED, failed.type());
    assertEquals("call-mismatch", failed.invocationId());
    assertTrue(
        failed.payload().contains("capability partial callId does not match invocationId"),
        failed.payload());
    assertEquals(
        DaemonInvocationState.FAILED, harness.journal.find("call-mismatch").orElseThrow().state());
  }

  /** 裁决后能力在收尾预算内提交结果：终态类型保持运行时裁决，正文换成能力已捕获输出，绝不把超时报告成 COMPLETED； 空正文只保留裁决说明。 */
  @Test
  void wrapsUpWithCapabilityTextInsideTheTerminationGrace() throws Exception {
    Harness harness = harness(() -> true, Duration.ofSeconds(5));

    harness.coordinator.handleInvoke(invoke("call-grace-text", Duration.ofMillis(30)));
    assertEquals(DaemonMessageType.STARTED, harness.sink.await().type());
    // 运行时裁决（超时）已抢占终态并请求能力收尾：此刻处在延迟终态窗口内。
    assertTrue(
        harness.capability.awaitTermination(), "the runtime must request capability wrap-up");

    harness.capability.complete(
        new EnvironmentCapabilityResult(
            "call-grace-text",
            List.of(new TextResultContent("captured-a"), new TextResultContent("captured-b")),
            false,
            "{}"));

    Outbound terminal = harness.sink.await();
    assertEquals(
        DaemonMessageType.FAILED, terminal.type(), "a timeout must never be reported as COMPLETED");
    assertEquals(
        "{\"message\":\"capability execution timed out after 30ms\\ncaptured-a\\ncaptured-b\"}",
        terminal.payload());
    assertEquals(
        DaemonInvocationState.FAILED,
        harness.journal.find("call-grace-text").orElseThrow().state());
  }

  /** 收尾预算内能力未捕获任何正文：终态只保留运行时裁决说明，不追加空行。 */
  @Test
  void keepsTheRuntimeVerdictWhenNoCapabilityTextWasCaptured() throws Exception {
    Harness harness = harness(() -> true, Duration.ofSeconds(5));

    harness.coordinator.handleInvoke(invoke("call-grace-blank", Duration.ofMillis(30)));
    assertEquals(DaemonMessageType.STARTED, harness.sink.await().type());
    assertTrue(harness.capability.awaitTermination());

    harness.capability.complete(
        new EnvironmentCapabilityResult("call-grace-blank", List.of(), false, "{}"));

    Outbound terminal = harness.sink.await();
    assertEquals(DaemonMessageType.FAILED, terminal.type());
    assertEquals(
        "{\"message\":\"capability execution timed out after 30ms\"}",
        terminal.payload(),
        "an empty capability text must not append anything to the runtime verdict");
  }

  private Harness harness(BooleanSupplier started) {
    return harness(started, Duration.ZERO);
  }

  private Harness harness(BooleanSupplier started, Duration terminationGrace) {
    Harness harness = new Harness(started, terminationGrace);
    harnesses.add(harness);
    return harness;
  }

  private static DaemonEnvelope invoke(String invocationId, Duration timeout) {
    return envelope(
        DaemonMessageType.INVOKE,
        invocationId,
        new DaemonCapabilityInvokeCodec()
            .encode(
                new DaemonCapabilityInvokeCodec.InvokeRequest(
                    new EnvironmentCapabilityId(CAPABILITY_ID),
                    CAPABILITY_VERSION,
                    "{}",
                    timeout)));
  }

  private static DaemonEnvelope cancel(String invocationId) {
    return envelope(DaemonMessageType.CANCEL, invocationId, "{}");
  }

  private static DaemonEnvelope envelope(
      DaemonMessageType messageType, String invocationId, String payloadJson) {
    return new DaemonEnvelope(
        DaemonProtocol.VERSION, messageType, ENVIRONMENT_ID, invocationId, payloadJson);
  }

  /** 协调器、journal 与受控调度的最小组合，避免真实网络与真实进程。 */
  private static final class Harness {

    private final DaemonCapabilityRegistry registry = new DaemonCapabilityRegistry();
    private final DaemonInvocationJournal journal = new InMemoryDaemonInvocationJournal();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final RecordingSink sink = new RecordingSink();
    private final StubCapability capability;
    private final DaemonInvocationCoordinator coordinator;

    private Harness(BooleanSupplier started, Duration terminationGrace) {
      this.capability = new StubCapability(terminationGrace);
      registry.register(capability);
      this.coordinator =
          new DaemonInvocationCoordinator(registry, journal, scheduler, started, sink, NO_UPLOADS);
    }

    private void close() {
      scheduler.shutdownNow();
    }
  }

  /** 这些用例都不产生需要上传的结果；端口存在只是为了证明编排不依赖资源传输。 */
  private static final DaemonInvocationUploads NO_UPLOADS =
      new DaemonInvocationUploads() {
        @Override
        public long maxResourceBytes() {
          return 1024L;
        }

        @Override
        public DaemonResourceUploader uploaderFor(Deadline deadline) {
          return NO_UPLOADER;
        }
      };

  /** 任何真实上传都说明用例前提被破坏：只有文本结果，绝不接触对象存储。 */
  private static final DaemonResourceUploader NO_UPLOADER =
      (invocationId, mediaType, name, bytes) -> {
        throw new AssertionError("no resource upload is expected in these cases");
      };

  /** 出站记录：断言类型、调用关联与正文。 */
  private static final class RecordingSink implements DaemonMessageSink {

    private final BlockingQueue<Outbound> outbound = new LinkedBlockingQueue<>();

    @Override
    public boolean send(DaemonMessageType messageType, String invocationId, String payloadJson) {
      outbound.add(new Outbound(messageType, invocationId, payloadJson));
      return true;
    }

    private Outbound await() throws InterruptedException {
      Outbound message = outbound.poll(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertTrue(message != null, "no outbound message within " + AWAIT_TIMEOUT_SECONDS + "s");
      return message;
    }

    private List<Outbound> drain(int count) throws InterruptedException {
      List<Outbound> messages = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        messages.add(await());
      }
      return messages;
    }

    private boolean isEmpty() {
      return outbound.isEmpty();
    }
  }

  private record Outbound(DaemonMessageType type, String invocationId, String payload) {}

  /** 最小能力实现：可注入收尾预算，并按测试需要回报部分/终止结果。 */
  private static final class StubCapability implements EnvironmentCapability {

    private final EnvironmentCapabilityDescriptor descriptor =
        new EnvironmentCapabilityDescriptor(
            new EnvironmentCapabilityId(CAPABILITY_ID),
            CAPABILITY_VERSION,
            new InputSchema("test arguments", Map.of(), Set.of(), false),
            Duration.ofSeconds(30));
    private final Duration terminationGrace;

    private volatile EnvironmentCapabilityExecutionListener listener;
    private volatile StubHandle handle;

    private StubCapability(Duration terminationGrace) {
      this.terminationGrace = terminationGrace;
      this.handle = new StubHandle(terminationGrace);
    }

    @Override
    public EnvironmentCapabilityDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public EnvironmentCapabilityExecutionHandle execute(
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener executionListener) {
      this.listener = executionListener;
      this.handle = new StubHandle(terminationGrace);
      return handle;
    }

    private void partial(EnvironmentCapabilityResult result) {
      listener.onPartial(result);
    }

    private void complete(EnvironmentCapabilityResult result) {
      listener.onComplete(result);
    }

    private boolean awaitTermination() throws InterruptedException {
      return handle.terminated.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** 按注入的收尾预算声明能力可自收敛；`terminate` 用于让测试确定地进入延迟终态窗口。 */
    private static final class StubHandle implements EnvironmentCapabilityExecutionHandle {

      private final CountDownLatch terminated = new CountDownLatch(1);
      private final Duration grace;

      private StubHandle(Duration grace) {
        this.grace = grace;
      }

      @Override
      public void cancel() {
        terminated.countDown();
      }

      @Override
      public boolean isCancelled() {
        return terminated.getCount() == 0L;
      }

      @Override
      public void terminate(EnvironmentCapabilityTerminationCause cause) {
        terminated.countDown();
      }

      @Override
      public Duration terminationGrace() {
        return grace;
      }
    }
  }
}
