package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.harness.daemon.DaemonConfig;
import fun.fengwk.kkstudio.harness.daemon.DaemonRuntime;
import fun.fengwk.kkstudio.harness.daemon.DaemonToolRegistry;
import fun.fengwk.kkstudio.harness.daemon.coding.ArtifactSource;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.kernel.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.kernel.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationUpdateOutcome;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.web.WebPostgresTestSupport;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** End-to-end Daemon v1 WebSocket contract against the final Tool transaction port. */
class EnvironmentDaemonWebSocketFinalIntegrationTest extends WebPostgresTestSupport {
  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 99L;

  @LocalServerPort private int port;

  @MockitoBean private ToolInvocationTransactions transactions;
  @MockitoBean private ArtifactStore artifactStore;
  private final AtomicReference<ToolResult> completedResult = new AtomicReference<>();

  @Test
  void daemonHandshakeDispatchAndCompletionReachDurableGateway() {
    ToolDescriptor descriptor = descriptor();
    ClaimedToolInvocation claimed = configureClaimedInvocation(descriptor);
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(new CompletingTool(descriptor));
    DaemonRuntime runtime =
        new DaemonRuntime(daemonConfig(), registry, DaemonSkillRegistry.empty());
    try {
      runtime.start();

      verify(transactions, timeout(10_000))
          .claim(eq(INVOCATION_ID), anyString(), eq(Duration.ofSeconds(10)), any(), any());
      verify(transactions, timeout(10_000)).completeSuccess(eq(claimed), any(), any(), any());
      assertEquals("provider-call", completedResult.get().toolCallId());
      assertEquals(
          "daemon completed", ((TextToolContent) completedResult.get().contents().get(0)).text());
    } finally {
      runtime.close();
    }
  }

  @Test
  void daemonArtifactCompletionIsPersistedByGateway() {
    byte[] bytes = new byte[] {1, 2, 3};
    ArtifactRef local = new ArtifactRef("daemon-local", "application/octet-stream", bytes.length);
    ArtifactRef global = new ArtifactRef("global-artifact", local.mediaType(), bytes.length);
    ToolDescriptor descriptor = descriptor();
    ClaimedToolInvocation claimed = configureClaimedInvocation(descriptor);
    when(artifactStore.save(anyString(), anyString(), any())).thenReturn(global);
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(new ArtifactCompletingTool(descriptor, local));
    ArtifactSource source = ignored -> bytes;
    DaemonRuntime runtime =
        new DaemonRuntime(daemonConfig(), registry, DaemonSkillRegistry.empty(), source);
    try {
      runtime.start();

      ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
      verify(artifactStore, timeout(10_000))
          .save(eq(local.mediaType()), eq("identity"), contentCaptor.capture());
      assertArrayEquals(bytes, contentCaptor.getValue());
      verify(transactions, timeout(10_000)).completeSuccess(eq(claimed), any(), any(), any());
      ArtifactToolContent artifact = (ArtifactToolContent) completedResult.get().contents().get(0);
      assertEquals(global, artifact.artifact());
    } finally {
      runtime.close();
    }
  }

  @Test
  void daemonFailureUsesFencedFailureTransition() {
    ToolDescriptor descriptor = descriptor();
    ClaimedToolInvocation claimed = configureClaimedInvocation(descriptor);
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(new FailingTool(descriptor));
    DaemonRuntime runtime =
        new DaemonRuntime(daemonConfig(), registry, DaemonSkillRegistry.empty());
    try {
      runtime.start();

      ArgumentCaptor<ToolInvocationError> errorCaptor =
          ArgumentCaptor.forClass(ToolInvocationError.class);
      verify(transactions, timeout(10_000))
          .completeFailure(eq(claimed), errorCaptor.capture(), any(), any());
      assertEquals("EXECUTION_FAILED", errorCaptor.getValue().kind());
      assertEquals("daemon failed", errorCaptor.getValue().message());
    } finally {
      runtime.close();
    }
  }

  private ClaimedToolInvocation configureClaimedInvocation(ToolDescriptor descriptor) {
    ToolInvocation candidate = queued(descriptor);
    ToolInvocation running = running(descriptor);
    ClaimedToolInvocation claimed = new ClaimedToolInvocation(running, false);
    when(transactions.findNextClaimable(
            eq(ToolExecutionLocation.ENVIRONMENT), eq(ENVIRONMENT_NAME), any()))
        .thenReturn(Optional.of(candidate), Optional.empty());
    when(transactions.claim(eq(INVOCATION_ID), anyString(), any(), any(), any()))
        .thenReturn(Optional.of(claimed));
    when(transactions.completeSuccess(eq(claimed), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Supplier<ToolResult> resultSupplier = invocation.getArgument(1);
              completedResult.set(resultSupplier.get());
              return ToolInvocationUpdateOutcome.APPLIED;
            });
    when(transactions.completeFailure(eq(claimed), any(), any(), any()))
        .thenReturn(ToolInvocationUpdateOutcome.APPLIED);
    return claimed;
  }

  private DaemonConfig daemonConfig() {
    return new DaemonConfig(
        URI.create("ws://localhost:" + port + EnvironmentDaemonWebSocketHandler.PATH),
        ENVIRONMENT_NAME,
        "websocket-integration-daemon",
        Duration.ofSeconds(30),
        Duration.ofMillis(50),
        Duration.ofSeconds(1),
        Duration.ofSeconds(10),
        "test-daemon-token",
        List.of());
  }

  private static ToolDescriptor descriptor() {
    return new ToolDescriptor(
        "echo",
        "1",
        "echo",
        "echo",
        new ToolParamsSchema(null, Map.of(), Set.of(), false),
        ToolExecutionLocation.ENVIRONMENT,
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(10));
  }

  private static ToolInvocation queued(ToolDescriptor descriptor) {
    Instant now = Instant.now();
    return new ToolInvocation(
        INVOCATION_ID,
        101L,
        102L,
        0,
        "provider-call",
        descriptor,
        "{}",
        ToolExecutionLocation.ENVIRONMENT,
        ENVIRONMENT_NAME,
        1L,
        InvocationStatus.QUEUED,
        1,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        now,
        null,
        null);
  }

  private static ToolInvocation running(ToolDescriptor descriptor) {
    Instant now = Instant.now();
    return new ToolInvocation(
        INVOCATION_ID,
        101L,
        102L,
        0,
        "provider-call",
        descriptor,
        "{}",
        ToolExecutionLocation.ENVIRONMENT,
        ENVIRONMENT_NAME,
        1L,
        InvocationStatus.RUNNING,
        1,
        null,
        new Lease("gateway-owner", now.plusSeconds(15)),
        now.plusSeconds(30),
        now,
        null,
        null,
        null,
        now.minusMillis(1),
        now,
        null);
  }

  private static final class CompletingTool implements Tool {
    private final ToolDescriptor descriptor;

    private CompletingTool(ToolDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      listener.onComplete(
          new ToolResult(
              request.call().id(),
              List.of(new TextToolContent("daemon completed")),
              false,
              "{}",
              false));
      return CompletedHandle.INSTANCE;
    }
  }

  private static final class ArtifactCompletingTool implements Tool {
    private final ToolDescriptor descriptor;
    private final ArtifactRef artifact;

    private ArtifactCompletingTool(ToolDescriptor descriptor, ArtifactRef artifact) {
      this.descriptor = descriptor;
      this.artifact = artifact;
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      listener.onComplete(
          new ToolResult(
              request.call().id(), List.of(new ArtifactToolContent(artifact)), false, "{}", false));
      return CompletedHandle.INSTANCE;
    }
  }

  private static final class FailingTool implements Tool {
    private final ToolDescriptor descriptor;

    private FailingTool(ToolDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      listener.onError(new IllegalStateException("daemon failed"));
      return CompletedHandle.INSTANCE;
    }
  }

  private enum CompletedHandle implements ToolExecutionHandle {
    INSTANCE;

    @Override
    public void cancel() {}

    @Override
    public boolean isCancelled() {
      return false;
    }
  }
}
