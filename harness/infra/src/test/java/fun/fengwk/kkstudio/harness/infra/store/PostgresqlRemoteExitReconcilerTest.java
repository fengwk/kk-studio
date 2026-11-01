package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTerminationCause;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityTransport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.runtime.execution.RemoteExitReconciler;
import fun.fengwk.kkstudio.harness.runtime.execution.TestExecutionExitConfirmer;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实 PostgreSQL 上验证 {@code loadUnconfirmedRemote} 与 {@link RemoteExitReconciler} 的 RUNNING 过滤和退出确认。
 */
class PostgresqlRemoteExitReconcilerTest {

  private static final EnvironmentId ENV_A =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId ENV_B =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

  private HarnessStore store;
  private FakeTransport transport;
  private ExecutionExitConfirmer confirmer;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    transport = new FakeTransport();
    confirmer = TestExecutionExitConfirmer.create(store, Clock.systemUTC());
  }

  @Test
  void loadUnconfirmedRemoteFiltersCorrectlyInRealPostgresql() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID assistantId = seedAssistantEntry(turn, "call-0", "call-1");

    UUID toolId1 =
        insertTool(
            turn.threadId(), turn.turnStartEntryId(), assistantId, 0, ToolInvocationStatus.READY);
    UUID toolId2 =
        insertTool(
            turn.threadId(), turn.turnStartEntryId(), assistantId, 1, ToolInvocationStatus.READY);

    UUID execUnconfirmedA = registerRemoteFact(turn.threadId(), toolId1, ENV_A);
    UUID execConfirmedA = registerRemoteFact(turn.threadId(), toolId2, ENV_A);
    UUID execOtherEnv = registerRemoteFact(turn.threadId(), toolId1, ENV_B);
    UUID execLocal = registerLocalFact(turn.threadId(), toolId2);

    // 把 execConfirmedA 置为 REMOTE_ACKNOWLEDGED
    ExecutionExitFact confirmedFact =
        store.transaction(tx -> tx.exits().find(execConfirmedA).orElseThrow());
    confirmer.confirm(confirmedFact, ExecutionExitState.REMOTE_ACKNOWLEDGED);

    // 查询 ENV_A 的未确认远端事实：应该只包含 execUnconfirmedA
    List<ExecutionExitFact> results =
        store.transaction(tx -> tx.exits().loadUnconfirmedRemote(ENV_A));
    assertEquals(1, results.size());
    assertEquals(execUnconfirmedA, results.getFirst().executionId());
    assertEquals(ExecutionExitState.UNCONFIRMED, results.getFirst().state());
    assertEquals(ENV_A, results.getFirst().environmentId());

    // 查询 ENV_B：应该只包含 execOtherEnv
    List<ExecutionExitFact> resultsB =
        store.transaction(tx -> tx.exits().loadUnconfirmedRemote(ENV_B));
    assertEquals(1, resultsB.size());
    assertEquals(execOtherEnv, resultsB.getFirst().executionId());
  }

  @Test
  void reconcilesAgainstRealPostgresqlSkippingRunningAndConfirmingNonRunning() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID assistantId = seedAssistantEntry(turn, "call-0", "call-1", "call-2");

    UUID runningToolId =
        insertTool(
            turn.threadId(), turn.turnStartEntryId(), assistantId, 0, ToolInvocationStatus.RUNNING);
    UUID succeededToolId =
        insertTool(
            turn.threadId(),
            turn.turnStartEntryId(),
            assistantId,
            1,
            ToolInvocationStatus.SUCCEEDED);
    UUID deletedToolId =
        insertTool(
            turn.threadId(),
            turn.turnStartEntryId(),
            assistantId,
            2,
            ToolInvocationStatus.SUCCEEDED);

    // 删除 deletedToolId 模拟 batch apply 后的物理清理
    store.transaction(
        tx -> {
          tx.threads().lock(turn.threadId()).orElseThrow();
          tx.tools().lock(deletedToolId).orElseThrow();
          tx.tools().deleteByIds(List.of(deletedToolId));
          return null;
        });

    UUID runningExecId = registerRemoteFact(turn.threadId(), runningToolId, ENV_A);
    UUID succeededExecId = registerRemoteFact(turn.threadId(), succeededToolId, ENV_A);
    UUID deletedExecId = registerRemoteFact(turn.threadId(), deletedToolId, ENV_A);

    RemoteExitReconciler reconciler =
        new RemoteExitReconciler(store, transport, confirmer, Runnable::run);
    reconciler.onEnvironmentReady(ENV_A);

    // RUNNING 的 tool 事实必须跳过，非 RUNNING 和已删除的 tool 事实必须挂接
    assertFalse(transport.attached.containsKey(runningExecId));
    assertTrue(transport.attached.containsKey(succeededExecId));
    assertTrue(transport.attached.containsKey(deletedExecId));

    // 触发退出确认
    transport.attached.get(succeededExecId).onExitConfirmed();
    transport.attached.get(deletedExecId).onExitConfirmed();

    // 验证数据库状态已推进为 REMOTE_ACKNOWLEDGED
    assertEquals(
        ExecutionExitState.REMOTE_ACKNOWLEDGED,
        store.transaction(tx -> tx.exits().find(succeededExecId).orElseThrow().state()));
    assertEquals(
        ExecutionExitState.REMOTE_ACKNOWLEDGED,
        store.transaction(tx -> tx.exits().find(deletedExecId).orElseThrow().state()));
    assertEquals(
        ExecutionExitState.UNCONFIRMED,
        store.transaction(tx -> tx.exits().find(runningExecId).orElseThrow().state()));

    // 再次触发对账：已确认的事实不再加载，RUNNING 事实依然被跳过
    transport.attached.clear();
    reconciler.onEnvironmentReady(ENV_A);
    assertTrue(transport.attached.isEmpty());
  }

  private UUID seedAssistantEntry(StoreTestSupport.TurnBaseline turn, String... toolCallIds) {
    return store.transaction(
        tx -> {
          var thread = tx.threads().lock(turn.threadId()).orElseThrow();
          UUID userId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      userId,
                      turn.sessionId(),
                      turn.turnStartEntryId(),
                      StoreTestSupport.userMessagePayload(),
                      T1));
          UUID assistantId = tx.nextId();
          tx.entries()
              .insert(
                  new Entry(
                      assistantId,
                      turn.sessionId(),
                      userId,
                      StoreTestSupport.assistantPayload(toolCallIds),
                      T2));
          tx.threads().update(thread.advanceHead(assistantId, T2));
          return assistantId;
        });
  }

  private UUID insertTool(
      UUID threadId,
      UUID turnStartEntryId,
      UUID assistantId,
      int callIndex,
      ToolInvocationStatus status) {
    return store.transaction(
        tx -> {
          UUID toolId = tx.nextId();
          ToolInvocation ready =
              new ToolInvocation(
                  toolId,
                  threadId,
                  turnStartEntryId,
                  assistantId,
                  callIndex,
                  StoreTestSupport.toolCall("call-" + callIndex),
                  StoreTestSupport.hostBinding(),
                  ToolInvocationStatus.READY,
                  0,
                  null,
                  null,
                  null,
                  T0,
                  T0);
          tx.tools().insert(List.of(ready));
          if (status == ToolInvocationStatus.READY) {
            return toolId;
          }
          ToolInvocation approved =
              tx.tools().lock(toolId).orElseThrow().markApprovalNotRequired(T1);
          tx.tools().update(List.of(approved));
          ToolInvocation running = approved.markRunning(T2);
          tx.tools().update(List.of(running));
          if (status == ToolInvocationStatus.RUNNING) {
            return toolId;
          }
          if (status == ToolInvocationStatus.SUCCEEDED) {
            ToolInvocation succeeded =
                running.succeed(new ToolResult("call-" + callIndex, List.of(), false, "{}"), T3);
            tx.tools().update(List.of(succeeded));
            return toolId;
          }
          return toolId;
        });
  }

  private UUID registerRemoteFact(UUID threadId, UUID invocationId, EnvironmentId environmentId) {
    return store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          UUID toolExecId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              toolExecId,
              threadId,
              invocationId,
              1,
              "lease-token",
              ExecutionExitKind.TOOL,
              null);
          UUID remoteExecId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              remoteExecId,
              threadId,
              invocationId,
              1,
              "lease-token",
              ExecutionExitKind.REMOTE,
              toolExecId,
              environmentId);
          return remoteExecId;
        });
  }

  private UUID registerLocalFact(UUID threadId, UUID invocationId) {
    return store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          UUID executionId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              executionId,
              threadId,
              invocationId,
              1,
              "lease-token",
              ExecutionExitKind.TOOL,
              null,
              null);
          return executionId;
        });
  }

  private static final class FakeTransport implements EnvironmentCapabilityTransport {
    private final Map<UUID, EnvironmentCapabilityExecutionListener> attached =
        new ConcurrentHashMap<>();

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
        UUID executionId,
        EnvironmentCapabilityExecutionListener listener) {
      attached.put(executionId, listener);
      return new EnvironmentCapabilityExecutionHandle() {
        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
          return false;
        }

        @Override
        public void terminate(EnvironmentCapabilityTerminationCause cause) {}

        @Override
        public Duration terminalDeliveryGrace() {
          return Duration.ZERO;
        }
      };
    }
  }
}
