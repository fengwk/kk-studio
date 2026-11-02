package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.approveTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.markRunningTool;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedToolBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.succeedTool;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilitySendUncertainException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.MultiToolBaseline;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.ToolBaseline;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.RemoteExitReconciler;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跨实例远端退出对账器单元测试。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>RUNNING 工具的事实被跳过；
 *   <li>非 RUNNING（包括 Tool 行已删除）的事实被 attach；
 *   <li>EXECUTION_EXITED 后事实变为 REMOTE_ACKNOWLEDGED，且确认使用库中身份；
 *   <li>NOT_FOUND 时保持 UNCONFIRMED；
 *   <li>IllegalArgumentException 时跳过并继续处理下一条；
 *   <li>Unavailable 时停止本轮；
 *   <li>onEnvironmentReady 不在调用线程上访问 store。
 * </ul>
 */
class RemoteExitReconcilerTest {

  private static final EnvironmentId ENV_ID = EnvironmentId.of(UUID.randomUUID());
  private static final String OWNER_LEASE = "lease-token-1";

  private InMemoryHarnessStore store;
  private ExecutionExitConfirmer confirmer;
  private RecordingTransport transport;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    confirmer = TestExecutionExitConfirmer.create(store, Clock.fixed(T2, ZoneOffset.UTC));
    transport = new RecordingTransport();
  }

  @Test
  void skipsRunningToolFacts() {
    ToolBaseline baseline = seedToolBaseline(store);
    approveTool(store, baseline.toolId());
    markRunningTool(store, baseline.toolId());
    UUID remoteExecId = registerRemoteFact(baseline.threadId(), baseline.toolId());

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, Runnable::run);
    reconciler.onEnvironmentReady(ENV_ID);

    assertTrue(transport.attachedInvocations.isEmpty(), "RUNNING tool fact must be skipped");
    ExecutionExitFact stored = store.transaction(tx -> tx.exits().find(remoteExecId).orElseThrow());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored.state());
  }

  @Test
  void skipsRemoteFactsWhoseParentIsAgentExecution() {
    ToolBaseline baseline = seedToolBaseline(store);
    UUID remoteExecId = registerAgentRemoteFact(baseline.threadId(), baseline.toolId());

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, Runnable::run);
    reconciler.onEnvironmentReady(ENV_ID);

    assertTrue(
        transport.attachedInvocations.isEmpty(),
        "Remote exit with AGENT parent must be skipped by reconciler");
    ExecutionExitFact stored = store.transaction(tx -> tx.exits().find(remoteExecId).orElseThrow());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored.state());
  }

  @Test
  void attachesNonRunningAndMissingToolFacts() {
    MultiToolBaseline baseline = seedToolBaseline(store, 2);
    UUID succeededToolId = baseline.toolIds().get(0);
    UUID deletedToolId = baseline.toolIds().get(1);

    approveTool(store, succeededToolId);
    markRunningTool(store, succeededToolId);
    succeedTool(store, succeededToolId);

    // 删除第二个 tool 模拟 terminal apply 后的清理
    store.transaction(
        tx -> {
          tx.threads().lock(baseline.threadId()).orElseThrow();
          tx.tools().lock(deletedToolId).orElseThrow();
          tx.tools().deleteByIds(List.of(deletedToolId));
          return null;
        });

    UUID succeededRemoteExecId = registerRemoteFact(baseline.threadId(), succeededToolId);
    UUID deletedRemoteExecId = registerRemoteFact(baseline.threadId(), deletedToolId);

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, Runnable::run);
    reconciler.onEnvironmentReady(ENV_ID);

    assertEquals(2, transport.attachedInvocations.size());
    assertTrue(transport.attachedInvocations.containsKey(succeededRemoteExecId));
    assertTrue(transport.attachedInvocations.containsKey(deletedRemoteExecId));
  }

  @Test
  void confirmsRemoteAcknowledgedOnExitConfirmedUsingInStoreIdentity() {
    ToolBaseline baseline = seedToolBaseline(store);
    approveTool(store, baseline.toolId());
    markRunningTool(store, baseline.toolId());
    succeedTool(store, baseline.toolId());
    UUID remoteExecId = registerRemoteFact(baseline.threadId(), baseline.toolId());

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, Runnable::run);
    reconciler.onEnvironmentReady(ENV_ID);

    EnvironmentCapabilityExecutionListener listener =
        transport.attachedInvocations.get(remoteExecId);
    listener.onExitConfirmed();

    ExecutionExitFact confirmed =
        store.transaction(tx -> tx.exits().find(remoteExecId).orElseThrow());
    assertEquals(ExecutionExitState.REMOTE_ACKNOWLEDGED, confirmed.state());
    assertEquals(baseline.threadId(), confirmed.threadId());
    assertEquals(baseline.toolId(), confirmed.invocationId());
    assertEquals(1, confirmed.attempt());
    assertEquals(OWNER_LEASE, confirmed.ownerLeaseToken());
    assertEquals(ENV_ID, confirmed.environmentId());
  }

  @Test
  void remainsUnconfirmedOnNotFound() {
    ToolBaseline baseline = seedToolBaseline(store);
    approveTool(store, baseline.toolId());
    markRunningTool(store, baseline.toolId());
    succeedTool(store, baseline.toolId());
    UUID remoteExecId = registerRemoteFact(baseline.threadId(), baseline.toolId());

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, Runnable::run);
    reconciler.onEnvironmentReady(ENV_ID);

    EnvironmentCapabilityExecutionListener listener =
        transport.attachedInvocations.get(remoteExecId);
    listener.onError(new EnvironmentCapabilitySendUncertainException("RECOVER_NOT_FOUND"));

    ExecutionExitFact stored = store.transaction(tx -> tx.exits().find(remoteExecId).orElseThrow());
    assertEquals(ExecutionExitState.UNCONFIRMED, stored.state());
  }

  @Test
  void skipsFactAndContinuesWhenAttachThrowsIllegalArgumentException() {
    MultiToolBaseline baseline = seedToolBaseline(store, 2);
    UUID toolId1 = baseline.toolIds().get(0);
    UUID toolId2 = baseline.toolIds().get(1);

    approveTool(store, toolId1);
    markRunningTool(store, toolId1);
    succeedTool(store, toolId1);
    approveTool(store, toolId2);
    markRunningTool(store, toolId2);
    succeedTool(store, toolId2);

    UUID remoteExecId1 = registerRemoteFact(baseline.threadId(), toolId1);
    UUID remoteExecId2 = registerRemoteFact(baseline.threadId(), toolId2);

    transport.throwOnAttach.put(
        remoteExecId1, new IllegalArgumentException("already registered on this node"));

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, Runnable::run);
    reconciler.onEnvironmentReady(ENV_ID);

    assertEquals(1, transport.attachedInvocations.size());
    assertTrue(transport.attachedInvocations.containsKey(remoteExecId2));

    transport.attachedInvocations.get(remoteExecId2).onExitConfirmed();
    ExecutionExitFact confirmed2 =
        store.transaction(tx -> tx.exits().find(remoteExecId2).orElseThrow());
    assertEquals(ExecutionExitState.REMOTE_ACKNOWLEDGED, confirmed2.state());
  }

  @Test
  void stopsRoundWhenAttachThrowsUnavailable() {
    MultiToolBaseline baseline = seedToolBaseline(store, 2);
    UUID toolId1 = baseline.toolIds().get(0);
    UUID toolId2 = baseline.toolIds().get(1);

    approveTool(store, toolId1);
    markRunningTool(store, toolId1);
    succeedTool(store, toolId1);
    approveTool(store, toolId2);
    markRunningTool(store, toolId2);
    succeedTool(store, toolId2);

    UUID remoteExecId1 = registerRemoteFact(baseline.threadId(), toolId1);
    registerRemoteFact(baseline.threadId(), toolId2);

    transport.throwOnAttach.put(
        remoteExecId1, new EnvironmentCapabilityUnavailableException("route unavailable"));

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, Runnable::run);
    reconciler.onEnvironmentReady(ENV_ID);

    assertEquals(1, transport.attachAttempts.size());
    assertEquals(remoteExecId1, transport.attachAttempts.getFirst());
    assertTrue(transport.attachedInvocations.isEmpty());
  }

  @Test
  void onEnvironmentReadyDoesNotAccessStoreOnCallingThread() {
    ToolBaseline baseline = seedToolBaseline(store);
    registerRemoteFact(baseline.threadId(), baseline.toolId());

    List<Runnable> queuedTasks = new ArrayList<>();
    Executor queuingExecutor = queuedTasks::add;

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, queuingExecutor);

    int beforeTxCount = store.transactionCount();
    reconciler.onEnvironmentReady(ENV_ID);

    assertEquals(
        beforeTxCount, store.transactionCount(), "store must not be accessed on calling thread");
    assertEquals(1, queuedTasks.size(), "task must be submitted to executor");

    queuedTasks.getFirst().run();
    assertEquals(beforeTxCount + 1, store.transactionCount(), "store accessed only when task runs");
    assertEquals(1, transport.attachedInvocations.size());
  }

  private UUID registerRemoteFact(UUID threadId, UUID toolId) {
    return store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          UUID toolExecId = tx.nextId();
          ExecutionExitLedger.register(
              tx, toolExecId, threadId, toolId, 1, OWNER_LEASE, ExecutionExitKind.TOOL, null);
          UUID remoteExecId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              remoteExecId,
              threadId,
              toolId,
              1,
              OWNER_LEASE,
              ExecutionExitKind.REMOTE,
              toolExecId,
              ENV_ID);
          return remoteExecId;
        });
  }

  private UUID registerAgentRemoteFact(UUID threadId, UUID invocationId) {
    return store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          UUID agentExecId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              agentExecId,
              threadId,
              invocationId,
              1,
              OWNER_LEASE,
              ExecutionExitKind.AGENT,
              null);
          UUID remoteExecId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              remoteExecId,
              threadId,
              invocationId,
              1,
              OWNER_LEASE,
              ExecutionExitKind.REMOTE,
              agentExecId,
              ENV_ID);
          return remoteExecId;
        });
  }

  private static final class RecordingTransport implements EnvironmentCapabilityTransport {
    final Map<UUID, EnvironmentCapabilityExecutionListener> attachedInvocations =
        new LinkedHashMap<>();
    final List<UUID> attachAttempts = new ArrayList<>();
    final Map<UUID, RuntimeException> throwOnAttach = new LinkedHashMap<>();

    @Override
    public EnvironmentCapabilityExecutionHandle invoke(
        EnvironmentId environmentId,
        EnvironmentCapabilityExecutionRequest request,
        EnvironmentCapabilityExecutionListener listener) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EnvironmentCapabilityExecutionHandle attach(
        EnvironmentId environmentId,
        UUID invocationId,
        EnvironmentCapabilityExecutionListener listener) {
      attachAttempts.add(invocationId);
      RuntimeException failure = throwOnAttach.get(invocationId);
      if (failure != null) {
        throw failure;
      }
      attachedInvocations.put(invocationId, listener);
      return new NoopHandle();
    }
  }

  private static final class NoopHandle implements EnvironmentCapabilityExecutionHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public void terminate(EnvironmentCapabilityTerminationCause cause) {
      cancel();
    }

    @Override
    public Duration terminalDeliveryGrace() {
      return Duration.ZERO;
    }
  }
}
