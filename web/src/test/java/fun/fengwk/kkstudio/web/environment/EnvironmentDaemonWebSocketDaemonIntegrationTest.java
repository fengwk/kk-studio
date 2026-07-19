package fun.fengwk.kkstudio.web.environment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonGateway;
import fun.fengwk.kkstudio.core.harness.tool.worker.DatabaseEnvironmentToolInvocationWorkerStore;
import fun.fengwk.kkstudio.harness.daemon.DaemonConfig;
import fun.fengwk.kkstudio.harness.daemon.DaemonRuntime;
import fun.fengwk.kkstudio.harness.daemon.DaemonToolRegistry;
import fun.fengwk.kkstudio.harness.daemon.coding.ArtifactSource;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.web.WebTestApplication;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** End-to-end Daemon v1 network contract through the WebSocket adapter and durable gateway. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = WebTestApplication.class)
class EnvironmentDaemonWebSocketDaemonIntegrationTest {

  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 99L;

  @LocalServerPort private int port;

  @Autowired private EnvironmentDaemonGateway gateway;

  @MockitoBean private DatabaseEnvironmentToolInvocationWorkerStore invocationStore;
  @MockitoBean private ToolInvocationTransactions transactions;
  @MockitoBean private ArtifactStore artifactStore;

  /** A real Daemon completes one pulled invocation across the actual WebSocket server boundary. */
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

      verify(transactions, timeout(10_000)).start(eq(claimed), any());
      ArgumentCaptor<ToolResult> resultCaptor = ArgumentCaptor.forClass(ToolResult.class);
      verify(transactions, timeout(10_000))
          .terminate(
              eq(claimed),
              eq(ToolInvocationStatus.SUCCEEDED),
              resultCaptor.capture(),
              isNull(),
              any());
      assertEquals(
          "daemon completed", ((TextToolContent) resultCaptor.getValue().contents().get(0)).text());
    } finally {
      runtime.close();
    }
  }

  /** A daemon-local artifact crosses the actual WebSocket boundary and is persisted globally. */
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
      ArgumentCaptor<ToolResult> resultCaptor = ArgumentCaptor.forClass(ToolResult.class);
      verify(transactions, timeout(10_000))
          .terminate(
              eq(claimed),
              eq(ToolInvocationStatus.SUCCEEDED),
              resultCaptor.capture(),
              isNull(),
              any());
      ArtifactToolContent artifact =
          (ArtifactToolContent) resultCaptor.getValue().contents().get(0);
      assertEquals(global, artifact.artifact());
    } finally {
      runtime.close();
    }
  }

  /** An active persistent cancellation is delivered to a real daemon and returns CANCELLED. */
  @Test
  void daemonCancellationRoundTripsThroughWebSocket() throws InterruptedException {
    ToolDescriptor descriptor = descriptor();
    ClaimedToolInvocation claimed = configureClaimedInvocation(descriptor);
    BlockingTool tool = new BlockingTool(descriptor);
    DaemonToolRegistry registry = new DaemonToolRegistry();
    registry.register(tool);
    DaemonRuntime runtime =
        new DaemonRuntime(daemonConfig(), registry, DaemonSkillRegistry.empty());
    try {
      runtime.start();
      assertTrue(tool.started.await(10, TimeUnit.SECONDS));
      when(invocationStore.find(INVOCATION_ID))
          .thenReturn(Optional.of(invocation(ToolInvocationStatus.CANCEL_REQUESTED)));
      when(invocationStore.heartbeat(any(), any(), any())).thenReturn(true);

      gateway.pollOnce();

      verify(transactions, timeout(10_000))
          .terminate(
              eq(claimed),
              eq(ToolInvocationStatus.CANCELLED),
              any(ToolResult.class),
              anyString(),
              any());
      assertEquals(1, tool.handle.cancelCalls.get());
    } finally {
      runtime.close();
    }
  }

  private ClaimedToolInvocation configureClaimedInvocation(ToolDescriptor descriptor) {
    ToolInvocation invocation = invocation();
    ClaimedToolInvocation claimed = new ClaimedToolInvocation(invocation, false);
    when(invocationStore.claimDue(eq(ENVIRONMENT_NAME), anyString(), any(), any()))
        .thenReturn(Optional.of(claimed), Optional.empty());
    when(invocationStore.listDueEnvironmentCandidates(any(), anyInt())).thenReturn(List.of());
    when(transactions.start(eq(claimed), any())).thenReturn(true);
    when(transactions.terminate(eq(claimed), any(), any(), any(), any())).thenReturn(true);
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
        ToolExecutionMode.ENVIRONMENT,
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(10));
  }

  private static ToolInvocation invocation() {
    return invocation(ToolInvocationStatus.RUNNING);
  }

  private static ToolInvocation invocation(ToolInvocationStatus status) {
    Instant now = Instant.now();
    return new ToolInvocation(
        INVOCATION_ID,
        101L,
        102L,
        0,
        "provider-call",
        "echo",
        "1",
        ToolTargetType.ENVIRONMENT,
        ENVIRONMENT_NAME,
        "{}",
        status,
        PermissionAction.ALLOW,
        null,
        ToolSideEffect.READ_ONLY,
        now.plusSeconds(30),
        "gateway-test-environment-42",
        now.plusSeconds(15),
        null,
        null,
        null,
        now,
        now,
        null,
        now);
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
      return new CompletedHandle();
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
      return new CompletedHandle();
    }
  }

  private static final class BlockingTool implements Tool {

    private final ToolDescriptor descriptor;
    private final CountDownLatch started = new CountDownLatch(1);
    private final BlockingHandle handle = new BlockingHandle();

    private BlockingTool(ToolDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public ToolDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public ToolExecutionHandle execute(
        ToolExecutionRequest request, ToolExecutionListener listener) {
      started.countDown();
      return handle;
    }
  }

  private static final class CompletedHandle implements ToolExecutionHandle {

    @Override
    public void cancel() {}

    @Override
    public boolean isCancelled() {
      return false;
    }
  }

  private static final class BlockingHandle implements ToolExecutionHandle {

    private final AtomicInteger cancelCalls = new AtomicInteger();

    @Override
    public void cancel() {
      cancelCalls.incrementAndGet();
    }

    @Override
    public boolean isCancelled() {
      return cancelCalls.get() > 0;
    }
  }
}
