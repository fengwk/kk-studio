package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import fun.fengwk.kkstudio.common.resource.ResourceRef;
import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.contributor.api.BoundEnvironment;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCancelledException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityFailedException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.runtime.admission.ConcurrencyAdmission;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.port.ToolGateway;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolResultSizeLimits;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalog;
import fun.fengwk.kkstudio.platform.catalog.tool.RuntimeToolCatalogSource;
import fun.fengwk.kkstudio.platform.catalog.tool.TestRuntimeToolCatalogSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * {@link ToolGateway.Run#execute}：阻塞调用工具并分类终态——结果外部化与校验、partial 校验与中断、异常到 Failed / Cancelled /
 * Unknown 的固定映射、 Environment 阻塞适配（中断即远端 cancel）与资源只由 {@code close} 释放。
 */
class ToolExecutionGatewayRunTest {

  private static final ToolDescriptor DESCRIPTOR = ToolGatewayTestSupport.hostDescriptor("demo");
  private static final EnvironmentCapabilityDescriptor REMOTE_CAPABILITY =
      new EnvironmentCapabilityDescriptor(
          new EnvironmentCapabilityId("remote.test"),
          "1",
          new InputSchema("arguments", Map.of(), Set.of(), false),
          Duration.ofSeconds(1));
  private static final byte[] BINARY_BYTES = new byte[] {1, 2, 3, 4, 5};

  private final ToolGatewayTestSupport.FakeResourceStager store =
      new ToolGatewayTestSupport.FakeResourceStager();

  private ToolGateway.Completion runHost(ToolGatewayTestSupport.ToolBody body) {
    return runHost(body, new ArrayList<>());
  }

  private ToolGateway.Completion runHost(
      ToolGatewayTestSupport.ToolBody body, List<ToolResult> progress) {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.body = body;
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway, ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR));
    try {
      return run.execute(progress::add);
    } finally {
      run.close();
    }
  }

  private ToolGateway.Completion runHostResult(ToolResult result) {
    return runHost((request, progress) -> ToolOutcome.withoutEffects(result));
  }

  private static ToolResult result(String callId, TextResultContent... contents) {
    return new ToolResult(callId, List.of(contents), false, "{}");
  }

  @Test
  void binaryContentIsExternalizedBeforeSuccess() {
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");

    ToolGateway.Succeeded succeeded =
        assertInstanceOf(ToolGateway.Succeeded.class, runHostResult(result));

    assertEquals(1, store.puts.size());
    ToolGatewayTestSupport.FakeResourceStager.PutRecord put = store.puts.get(0);
    assertEquals("image/png", put.mediaType());
    assertArrayEquals(BINARY_BYTES, put.content());
    ResourceResultContent externalized =
        assertInstanceOf(
            ResourceResultContent.class, succeeded.success().result().contents().get(0));
    assertEquals(ToolGatewayTestSupport.sha256(BINARY_BYTES), externalized.resource().sha256());
    assertFalse(succeeded.success().result().error());
  }

  @Test
  void largeTextIsExternalizedAndSmallTextStaysInline() {
    ToolResult large =
        result(
            "call-1",
            new TextResultContent("x".repeat(ToolResultFinalizer.INLINE_MAX_UTF8_BYTES + 1)));
    ToolGateway.Succeeded externalized =
        assertInstanceOf(ToolGateway.Succeeded.class, runHostResult(large));
    assertEquals(1, store.puts.size());
    assertInstanceOf(
        ResourceResultContent.class, externalized.success().result().contents().get(0));

    ToolGateway.Succeeded inline =
        assertInstanceOf(
            ToolGateway.Succeeded.class,
            runHostResult(result("call-1", new TextResultContent("small"))));
    assertEquals(1, store.puts.size(), "inline text must not be staged");
    assertInstanceOf(TextResultContent.class, inline.success().result().contents().get(0));
  }

  @Test
  void uploadedResourcePassesThroughAndExternalResourceIsInvalid() {
    byte[] existing = new byte[] {1, 2, 3};
    ResourceRef uploaded =
        new ResourceRef(
            ResourceRef.blobUploadUri(UUID.randomUUID()),
            "text/plain",
            "existing",
            (long) existing.length,
            ToolGatewayTestSupport.sha256(existing));
    ResourceResultContent content = new ResourceResultContent(uploaded, "preview");
    ToolGateway.Succeeded passed =
        assertInstanceOf(
            ToolGateway.Succeeded.class,
            runHostResult(new ToolResult("call-1", List.of(content), false, "{}")));
    assertEquals(content, passed.success().result().contents().get(0));
    assertTrue(store.puts.isEmpty());

    ResourceRef external =
        new ResourceRef("https://example.com/out.txt", "text/plain", null, 3L, "a".repeat(64));
    ToolGateway.Failed failed =
        assertInstanceOf(
            ToolGateway.Failed.class,
            runHostResult(
                new ToolResult(
                    "call-1", List.of(new ResourceResultContent(external)), false, "{}")));
    assertEquals("INVALID_RESULT", failed.error().kind());
    assertFalse(failed.retryable());
    assertTrue(store.puts.isEmpty());
  }

  @Test
  void storeFailureAfterExecutionIsUnknown() {
    store.putFailure = new IllegalStateException("object store down");
    ToolResult result =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");

    assertInstanceOf(ToolGateway.Unknown.class, runHostResult(result));
  }

  @Test
  void nullOrMismatchedTerminalResultIsInvalidResultWithoutStoreIo() {
    ToolGateway.Failed nullOutcome =
        assertInstanceOf(ToolGateway.Failed.class, runHost((request, progress) -> null));
    assertEquals("INVALID_RESULT", nullOutcome.error().kind());

    ToolGateway.Failed mismatched =
        assertInstanceOf(
            ToolGateway.Failed.class,
            runHostResult(
                new ToolResult(
                    "other-call",
                    List.of(new BinaryResultContent("image/png", BINARY_BYTES)),
                    false,
                    "{}")));
    assertEquals("INVALID_RESULT", mismatched.error().kind());
    assertTrue(store.puts.isEmpty());
  }

  @Test
  void semanticErrorResultIsPreservedAsSuccess() {
    ToolGateway.Succeeded succeeded =
        assertInstanceOf(
            ToolGateway.Succeeded.class,
            runHostResult(ToolResult.error("call-1", "the tool reported an error")));

    assertTrue(succeeded.success().result().error());
  }

  @Test
  void validPartialsAreForwardedInOrder() {
    List<ToolResult> progress = new ArrayList<>();

    ToolGateway.Completion completion =
        runHost(
            (request, sink) -> {
              sink.accept(result("call-1", new TextResultContent("p1")));
              sink.accept(result("call-1", new TextResultContent("p2")));
              return ToolOutcome.withoutEffects(result("call-1", new TextResultContent("done")));
            },
            progress);

    assertInstanceOf(ToolGateway.Succeeded.class, completion);
    assertEquals(2, progress.size());
    assertEquals("p1", ((TextResultContent) progress.get(0).contents().get(0)).text());
    assertEquals("p2", ((TextResultContent) progress.get(1).contents().get(0)).text());
  }

  /** 非法 partial（错误 callId / binary / 超限 / null）：不转发，工具线程被中断并以 INVALID_PARTIAL 失败收敛，且没有任何资源写入。 */
  @Test
  void invalidPartialInterruptsTheToolAndFailsWithInvalidPartial() {
    List<ToolResult> invalid =
        Arrays.asList(
            result("wrong-call", new TextResultContent("p")),
            new ToolResult(
                "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}"),
            result(
                "call-1",
                new TextResultContent(
                    "z".repeat(ToolResultSizeLimits.MAX_PARTIAL_RESULT_UTF8_BYTES + 1))),
            null);
    for (ToolResult partial : invalid) {
      List<ToolResult> forwarded = new ArrayList<>();
      ToolGateway.Completion completion =
          runHost(
              (request, sink) -> {
                sink.accept(partial);
                Thread.sleep(10_000);
                return ToolOutcome.withoutEffects(result("call-1", new TextResultContent("late")));
              },
              forwarded);

      ToolGateway.Failed failed = assertInstanceOf(ToolGateway.Failed.class, completion);
      assertEquals(ToolExecutionGateway.INVALID_PARTIAL_KIND, failed.error().kind());
      assertFalse(failed.retryable());
      assertTrue(forwarded.isEmpty());
    }
    assertTrue(store.puts.isEmpty());
  }

  /** 中断映射为取消：Cancelled（不声称未执行），且线程继续保持可用。 */
  @Test
  void interruptionIsReportedAsCancelled() throws Exception {
    CountDownLatch inBody = new CountDownLatch(1);
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.body =
        (request, progress) -> {
          inBody.countDown();
          Thread.sleep(60_000);
          return null;
        };
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway, ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR));
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));
    assertTrue(inBody.await(5, TimeUnit.SECONDS));

    worker.interrupt();
    worker.join(5000);
    run.close();

    ToolGateway.Cancelled cancelled =
        assertInstanceOf(ToolGateway.Cancelled.class, completion.get());
    assertEquals(ToolExecutionGateway.CANCELLED_KIND, cancelled.error().kind());
    assertTrue(cancelled.error().message().contains("cannot be confirmed"));
  }

  @Test
  void unclassifiedToolFailureIsUnknownWithoutLeakingTheOriginalMessage() {
    ToolGateway.Unknown unknown =
        assertInstanceOf(
            ToolGateway.Unknown.class,
            runHost(
                (request, progress) -> {
                  throw new IllegalStateException("secret internal detail");
                }));

    assertEquals(ToolExecutionGateway.EXECUTION_FAILED_KIND, unknown.error().kind());
    assertFalse(unknown.error().message().contains("secret internal detail"));
  }

  @Test
  void environmentFailuresAreClassifiedByTheirSendCertainty() {
    assertEnvironmentFailure(
        ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNAVAILABLE,
        ToolGateway.Failed.class,
        "UNAVAILABLE",
        true);
    assertEnvironmentFailure(
        ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_BUSY,
        ToolGateway.Failed.class,
        "UNAVAILABLE",
        true);
    assertEnvironmentFailure(
        ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNCERTAIN,
        ToolGateway.Unknown.class,
        "REMOTE_UNCERTAIN",
        false);
    assertEnvironmentFailure(
        ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_INVALID,
        ToolGateway.Unknown.class,
        "EXECUTION_FAILED",
        false);
    assertEnvironmentFailure(
        ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_GENERIC,
        ToolGateway.Unknown.class,
        "EXECUTION_FAILED",
        false);
  }

  /** Stop/失权先赢得登记时不得发送，也不得伪造 exit 或 not-started 确认。 */
  @Test
  void deniedRemoteRegistrationDoesNotSendOrFakeExit() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();
    remoteExecutions.allowStart = false;
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(ToolGatewayTestSupport.defaultCatalog(), transport, store);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway,
            ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A),
            remoteExecutions);

    ToolGateway.Completion completion;
    try {
      completion = run.execute(partial -> {});
    } finally {
      run.close();
    }

    assertInstanceOf(ToolGateway.Cancelled.class, completion);
    assertEquals(1, remoteExecutions.startAttempts.size());
    assertTrue(remoteExecutions.started.isEmpty());
    assertTrue(transport.invocations.isEmpty(), "denied registration must not send");
    assertTrue(remoteExecutions.notStartedConfirmed.isEmpty());
    assertTrue(remoteExecutions.exitConfirmed.isEmpty());
  }

  /** 同一 Tool 的多次远端 execute 各自在发送前登记独立 UUID，并分别映射回同一模型 call id。 */
  @Test
  void eachRemoteExecuteRegistersIndependentUuidBeforeSend() {
    ToolGatewayTestSupport.FakeTool tool =
        new ToolGatewayTestSupport.FakeTool(ToolGatewayTestSupport.hostDescriptor("remote-twice")) {
          @Override
          public ToolRequirements requirements() {
            return ToolRequirements.environment();
          }
        };
    tool.body =
        (request, progress) -> {
          BoundEnvironment environment = request.context().environment().orElseThrow();
          environment.execute(REMOTE_CAPABILITY, request, progress);
          return ToolOutcome.withoutEffects(
              environment.execute(REMOTE_CAPABILITY, request, progress));
        };
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.syncResult = result("ignored-by-transport", new TextResultContent("ok"));
    ToolInvocationRequest hostRequest =
        ToolGatewayTestSupport.hostRequest("call-twice", tool.descriptor());
    ToolBinding hostBinding = hostRequest.binding();
    ToolInvocationRequest environmentRequest =
        new ToolInvocationRequest(
            hostRequest.call(),
            new ToolBinding(
                hostBinding.definition(),
                hostBinding.contributor(),
                EnvironmentSupport.REQUIRED,
                ToolGatewayTestSupport.ENV_A,
                "test-env"));
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool), transport, store);
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();
    AtomicBoolean registrationOrderViolated = new AtomicBoolean();
    remoteExecutions.tryStartHook =
        remoteExecutionId -> {
          if (transport.invocations.size() != remoteExecutions.startAttempts.size() - 1) {
            registrationOrderViolated.set(true);
          }
        };

    ToolGateway.Completion completion;
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(gateway, environmentRequest, remoteExecutions);
    try {
      completion = run.execute(partial -> {});
    } finally {
      run.close();
    }

    ToolGateway.Succeeded succeeded = assertInstanceOf(ToolGateway.Succeeded.class, completion);
    assertEquals("call-twice", succeeded.success().result().toolCallId());
    assertEquals(2, transport.invocations.size());
    assertEquals(2, remoteExecutions.started.size());
    assertFalse(registrationOrderViolated.get(), "registration must precede each send");
    assertFalse(remoteExecutions.started.get(0).equals(remoteExecutions.started.get(1)));
    assertFalse(remoteExecutions.started.contains(ToolGatewayTestSupport.INVOCATION_ID));
    assertTrue(remoteExecutions.exitConfirmed.isEmpty(), "normal terminal is not an exit ACK");
    assertTrue(remoteExecutions.notStartedConfirmed.isEmpty());
  }

  /** 结果 future 已异常完成后到达的 exit ACK 仍独立确认该次远端执行。 */
  @Test
  void lateExitAckAfterExceptionalResultConfirmsTheRemoteExecution() throws Exception {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();

    ToolGateway.Completion completion =
        runEnvironmentThen(
            transport,
            remoteExecutions,
            listener -> {
              listener.onError(new EnvironmentCapabilityFailedException("exit 1"));
              listener.onExitConfirmed();
            });

    assertInstanceOf(ToolGateway.Failed.class, completion);
    UUID remoteExecutionId = UUID.fromString(transport.invocations.get(0).request().call().id());
    assertEquals(List.of(remoteExecutionId), remoteExecutions.exitConfirmed);
    assertTrue(remoteExecutions.notStartedConfirmed.isEmpty());
  }

  /** 发送后结果回调的 Busy 不是“未发送”证据：不得因此 confirmNotStarted。 */
  @Test
  void postSendBusyErrorDoesNotConfirmNotStarted() throws Exception {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();

    ToolGateway.Completion completion =
        runEnvironmentThen(
            transport,
            remoteExecutions,
            listener ->
                listener.onError(new EnvironmentCapabilityBusyException("busy after send")));

    ToolGateway.Failed failed = assertInstanceOf(ToolGateway.Failed.class, completion);
    assertTrue(failed.retryable());
    assertTrue(remoteExecutions.notStartedConfirmed.isEmpty());
    assertTrue(remoteExecutions.exitConfirmed.isEmpty());
  }

  /** 未启动确认自身持久化失败时不得被吞成“已确认未执行”，结果保持 Unknown。 */
  @Test
  void notStartedConfirmationFailureIsNotSwallowed() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNAVAILABLE;
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();
    String secret = "synthetic-secret-not-started-ledger-details";
    remoteExecutions.confirmNotStartedFailure = new IllegalStateException(secret);
    Logger logger = (Logger) LoggerFactory.getLogger(ToolExecutionGateway.class);
    Level previousLevel = logger.getLevel();
    boolean previousAdditivity = logger.isAdditive();
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.setContext(logger.getLoggerContext());
    logs.start();
    logger.addAppender(logs);
    logger.setLevel(Level.WARN);
    logger.setAdditive(false);
    try {
      ToolGateway.Completion completion = runEnvironment(transport, remoteExecutions);

      assertInstanceOf(ToolGateway.Unknown.class, completion);
      assertEquals(1, remoteExecutions.notStartedConfirmed.size());
      assertTrue(remoteExecutions.exitConfirmed.isEmpty());
      // 同步确认失败也会流入 Unknown 分类：日志不得回显持久化异常正文。
      assertEquals(1, logs.list.size(), "confirmation failure must remain diagnosable once");
      ILoggingEvent failure = logs.list.getFirst();
      assertFalse(failure.getFormattedMessage().contains(secret));
      assertTrue(
          failure
              .getFormattedMessage()
              .contains("phase=REMOTE_NOT_STARTED_CONFIRM; exception=IllegalStateException"));
      assertNull(failure.getThrowableProxy());
      for (Object argument : failure.getArgumentArray()) {
        assertFalse(String.valueOf(argument).contains(secret));
      }
    } finally {
      logger.detachAppender(logs);
      logs.stop();
      logger.setLevel(previousLevel);
      logger.setAdditive(previousAdditivity);
    }
  }

  private void assertEnvironmentFailure(
      ToolGatewayTestSupport.FakeTransport.InvokeAction action,
      Class<? extends ToolGateway.Completion> type,
      String kind,
      boolean retryable) {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = action;
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();
    ToolGateway.Completion completion = runEnvironment(transport, remoteExecutions);

    assertInstanceOf(type, completion);
    if (completion instanceof ToolGateway.Failed failed) {
      assertEquals(kind, failed.error().kind());
      assertEquals(retryable, failed.retryable());
    } else if (completion instanceof ToolGateway.Unknown unknown) {
      assertEquals(kind, unknown.error().kind());
    }
    UUID remoteExecutionId = UUID.fromString(transport.invocations.get(0).request().call().id());
    assertEquals(List.of(remoteExecutionId), remoteExecutions.started);
    assertTrue(remoteExecutions.exitConfirmed.isEmpty(), "result failure is not an exit ACK");
    if (action == ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_BUSY
        || action == ToolGatewayTestSupport.FakeTransport.InvokeAction.THROW_UNAVAILABLE) {
      assertEquals(
          List.of(remoteExecutionId),
          remoteExecutions.notStartedConfirmed,
          "definitely-unsent transport failure must confirm not started");
    } else {
      assertTrue(
          remoteExecutions.notStartedConfirmed.isEmpty(),
          "send-uncertain and generic failures must not confirm not started");
    }
  }

  private ToolGateway.Completion runEnvironment(ToolGatewayTestSupport.FakeTransport transport) {
    return runEnvironment(transport, new ToolGatewayTestSupport.RecordingRemoteExecutions());
  }

  private ToolGateway.Completion runEnvironment(
      ToolGatewayTestSupport.FakeTransport transport,
      ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions) {
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(ToolGatewayTestSupport.defaultCatalog(), transport, store);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway,
            ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A),
            remoteExecutions);
    try {
      return run.execute(partial -> {});
    } finally {
      run.close();
    }
  }

  /** 远端终态错误透传：FAILED 为不可重试的已知失败，CANCELLED 为取消且不声称未执行。 */
  @Test
  void remoteTerminalErrorsMapToFailedAndCancelled() throws Exception {
    ToolGatewayTestSupport.FakeTransport failing = new ToolGatewayTestSupport.FakeTransport();
    ToolGateway.Failed failed =
        assertInstanceOf(
            ToolGateway.Failed.class,
            runEnvironmentThen(
                failing,
                listener -> listener.onError(new EnvironmentCapabilityFailedException("exit 1"))));
    assertEquals("EXECUTION_FAILED", failed.error().kind());
    assertFalse(failed.retryable());

    ToolGatewayTestSupport.FakeTransport cancelling = new ToolGatewayTestSupport.FakeTransport();
    ToolGateway.Cancelled cancelled =
        assertInstanceOf(
            ToolGateway.Cancelled.class,
            runEnvironmentThen(
                cancelling,
                listener ->
                    listener.onError(new EnvironmentCapabilityCancelledException("stopped"))));
    assertEquals("CANCELLED", cancelled.error().kind());

    ToolGatewayTestSupport.FakeTransport busy = new ToolGatewayTestSupport.FakeTransport();
    ToolGateway.Failed busyFailure =
        assertInstanceOf(
            ToolGateway.Failed.class,
            runEnvironmentThen(
                busy,
                listener -> listener.onError(new EnvironmentCapabilityBusyException("busy"))));
    assertTrue(busyFailure.retryable());
  }

  /** transport 的异步终态：run 阻塞等待，终态由另一线程经 listener 交付；partial 经 progress 转发并还原模型 callId。 */
  @Test
  void blockingAdapterWaitsForTheAsyncTerminalAndRestoresTheModelCallId() throws Exception {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(ToolGatewayTestSupport.defaultCatalog(), transport, store);
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway,
            ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A),
            remoteExecutions);
    List<ToolResult> progress = new ArrayList<>();
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(progress::add)));
    try {
      awaitInvocation(transport);
      assertNull(completion.get(), "run must block until the terminal arrives");

      var listener = transport.invocations.get(0).listener();
      String capabilityCallId = transport.invocations.get(0).request().call().id();
      assertFalse(
          ToolGatewayTestSupport.INVOCATION_ID.toString().equals(capabilityCallId),
          "remote execution id must not reuse the ToolInvocation id");
      listener.onPartial(
          new EnvironmentCapabilityResult(
              capabilityCallId, List.of(new TextResultContent("step")), false, "{}"));
      listener.onComplete(
          new EnvironmentCapabilityResult(
              capabilityCallId, List.of(new TextResultContent("done")), false, "{}"));
      assertTrue(worker.join(Duration.ofSeconds(5)), "remote adapter must exit");

      ToolGateway.Succeeded succeeded =
          assertInstanceOf(ToolGateway.Succeeded.class, completion.get());
      assertEquals("call-1", succeeded.success().result().toolCallId());
      assertEquals(1, progress.size());
      assertEquals("call-1", progress.get(0).toolCallId());
      assertTrue(
          remoteExecutions.exitConfirmed.isEmpty(),
          "normal terminal completion must not synthesize an exit ACK");
    } finally {
      closeRunAfterWorkerExit(worker, run);
    }
  }

  /** 中断：向远端请求取消并立即返回 Cancelled，不等待远端确认。 */
  @Test
  void interruptionCancelsTheRemoteInvocationWithoutWaiting() throws Exception {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(ToolGatewayTestSupport.defaultCatalog(), transport, store);
    ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions =
        new ToolGatewayTestSupport.RecordingRemoteExecutions();
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway,
            ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A),
            remoteExecutions);
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));
    try {
      awaitInvocation(transport);
      worker.interrupt();
      assertTrue(worker.join(Duration.ofSeconds(5)), "interrupted remote adapter must exit");

      assertInstanceOf(ToolGateway.Cancelled.class, completion.get());
      assertEquals(1, transport.returnedHandles.get(0).cancelCount());
      assertEquals(
          List.of(EnvironmentCapabilityTerminationCause.CANCELLED),
          transport.returnedHandles.get(0).terminations());
      assertTrue(remoteExecutions.notStartedConfirmed.isEmpty());
      assertTrue(remoteExecutions.exitConfirmed.isEmpty(), "interrupt is not exit evidence");
    } finally {
      closeRunAfterWorkerExit(worker, run);
    }
  }

  /** 无 handle 视为契约违反：post-send 的 Unknown，不假称未执行。 */
  @Test
  void nullTransportHandleIsUnknown() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.returnNullHandle = true;

    assertInstanceOf(ToolGateway.Unknown.class, runEnvironment(transport));
  }

  /** wrong correlation：终态 callId 既不是 capability id 也不是模型 id，外部化之前即失败。 */
  @Test
  void wrongCapabilityCorrelationFailsBeforeExternalization() {
    ToolGatewayTestSupport.FakeTransport transport = new ToolGatewayTestSupport.FakeTransport();
    transport.action = ToolGatewayTestSupport.FakeTransport.InvokeAction.SYNC_COMPLETE;
    transport.callbackCallId = "unrelated-call";
    transport.syncResult =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", BINARY_BYTES)), false, "{}");

    ToolGateway.Failed failed =
        assertInstanceOf(ToolGateway.Failed.class, runEnvironment(transport));

    assertEquals("INVALID_RESULT", failed.error().kind());
    assertTrue(store.puts.isEmpty());
  }

  /** 资源只由 close 释放：execute 返回之后配额仍被持有，close 幂等恰好释放一次。 */
  @Test
  void quotaStaysHeldUntilCloseAndIsReleasedExactlyOnce() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.body =
        (request, progress) ->
            ToolOutcome.withoutEffects(result("call-1", new TextResultContent("ok")));
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            ToolGatewayTestSupport.defaultCatalog(tool),
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway, ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR));

    assertNotNull(run.execute(partial -> {}));
    assertTrue(admission.tryAcquire().isEmpty(), "quota is still pinned after the body exits");

    run.close();
    run.close();
    ConcurrencyAdmission.Lease lease = admission.tryAcquire().orElseThrow();
    assertTrue(admission.tryAcquire().isEmpty(), "close must release exactly one permit");
    lease.close();
  }

  /** 远端以空结果完成：结果无法确认，Unknown。 */
  @Test
  void remoteCompletionWithoutResultIsUnknown() throws Exception {
    assertInstanceOf(
        ToolGateway.Unknown.class,
        runEnvironmentThen(
            new ToolGatewayTestSupport.FakeTransport(), listener -> listener.onComplete(null)));
  }

  /** 远端以非运行时异常失败：结果无法确认，Unknown，且不泄露原始消息。 */
  @Test
  void remoteCheckedFailureIsUnknown() throws Exception {
    ToolGateway.Unknown unknown =
        assertInstanceOf(
            ToolGateway.Unknown.class,
            runEnvironmentThen(
                new ToolGatewayTestSupport.FakeTransport(),
                listener -> listener.onError(new Exception("checked sk-secret"))));
    assertFalse(unknown.error().message().contains("sk-secret"));
  }

  /** 远端回显的是模型调用 id 而不是本次执行 id：映射后与请求不符，确定性 INVALID_RESULT，不被当作合法结果。 */
  @Test
  void remoteEchoOfTheModelCallIdIsRejected() throws Exception {
    ToolGateway.Failed failed =
        assertInstanceOf(
            ToolGateway.Failed.class,
            runEnvironmentThen(
                new ToolGatewayTestSupport.FakeTransport(),
                listener ->
                    listener.onComplete(
                        ToolGatewayTestSupport.capabilityResult("call-1", "spoofed"))));
    assertEquals("INVALID_RESULT", failed.error().kind());
  }

  /** 目录租用关闭失败时并发配额仍被释放，关闭失败原样抛出。 */
  @Test
  void catalogCloseFailureStillReleasesTheQuota() {
    ToolGatewayTestSupport.FakeTool tool = new ToolGatewayTestSupport.FakeTool(DESCRIPTOR);
    tool.body = (request, progress) -> ToolOutcome.withoutEffects(result("call-1"));
    RuntimeToolCatalogSource delegate =
        TestRuntimeToolCatalogSource.of(ToolGatewayTestSupport.defaultCatalog(tool));
    IllegalStateException closeFailure = new IllegalStateException("catalog close failed");
    RuntimeToolCatalogSource failingClose =
        () -> {
          RuntimeToolCatalogSource.Lease lease = delegate.acquire();
          return new RuntimeToolCatalogSource.Lease() {
            @Override
            public HarnessCatalog harnessCatalog() {
              return lease.harnessCatalog();
            }

            @Override
            public RuntimeToolCatalog catalog() {
              return lease.catalog();
            }

            @Override
            public ToolContribution require(ToolBinding binding) {
              return lease.require(binding);
            }

            @Override
            public void close() {
              lease.close();
              throw closeFailure;
            }
          };
        };
    ConcurrencyAdmission admission = new ConcurrencyAdmission(1);
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(
            failingClose,
            new ToolGatewayTestSupport.FakeTransport(),
            store,
            ToolGatewayTestSupport.RESOURCE_MAX_BYTES,
            ToolGatewayTestSupport.settings(PermissionAction.ALLOW),
            admission);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway, ToolGatewayTestSupport.hostRequest("call-1", DESCRIPTOR));
    assertInstanceOf(ToolGateway.Succeeded.class, run.execute(partial -> {}));

    assertSame(closeFailure, assertThrows(IllegalStateException.class, run::close));
    admission.tryAcquire().orElseThrow().close();
  }

  private ToolGateway.Completion runEnvironmentThen(
      ToolGatewayTestSupport.FakeTransport transport,
      Consumer<EnvironmentCapabilityExecutionListener> terminal)
      throws Exception {
    return runEnvironmentThen(
        transport, new ToolGatewayTestSupport.RecordingRemoteExecutions(), terminal);
  }

  private ToolGateway.Completion runEnvironmentThen(
      ToolGatewayTestSupport.FakeTransport transport,
      ToolGatewayTestSupport.RecordingRemoteExecutions remoteExecutions,
      Consumer<EnvironmentCapabilityExecutionListener> terminal)
      throws Exception {
    ToolExecutionGateway gateway =
        ToolGatewayTestSupport.gateway(ToolGatewayTestSupport.defaultCatalog(), transport, store);
    ToolGateway.Run run =
        ToolGatewayTestSupport.admitted(
            gateway,
            ToolGatewayTestSupport.environmentRequest("call-1", ToolGatewayTestSupport.ENV_A),
            remoteExecutions);
    AtomicReference<ToolGateway.Completion> completion = new AtomicReference<>();
    Thread worker = Thread.ofVirtual().start(() -> completion.set(run.execute(partial -> {})));
    try {
      awaitInvocation(transport);
      terminal.accept(transport.invocations.get(0).listener());
      assertTrue(worker.join(Duration.ofSeconds(5)), "remote adapter must exit after its terminal");
      return completion.get();
    } finally {
      closeRunAfterWorkerExit(worker, run);
    }
  }

  /** 远端夹具在准备/回调/断言失败时也必须 join 真实 worker，且 join 失败不能跳过 run 资源释放。 */
  private static void closeRunAfterWorkerExit(Thread worker, ToolGateway.Run run)
      throws InterruptedException {
    worker.interrupt();
    try {
      assertTrue(worker.join(Duration.ofSeconds(5)), "remote adapter must exit in cleanup");
    } finally {
      run.close();
    }
  }

  private static void awaitInvocation(ToolGatewayTestSupport.FakeTransport transport)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (transport.invocations.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertFalse(transport.invocations.isEmpty(), "transport must have been invoked");
  }
}
