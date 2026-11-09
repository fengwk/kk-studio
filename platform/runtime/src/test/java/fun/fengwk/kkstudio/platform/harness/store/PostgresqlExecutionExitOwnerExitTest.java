package fun.fengwk.kkstudio.platform.harness.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.UUID;

/** 真实 PostgreSQL 上 {@code OWNER_EXITED} 的 Store 契约：本地可确认、未确认查询不再命中、REMOTE 拒绝。 */
class PostgresqlExecutionExitOwnerExitTest {

  private HarnessStore store;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
  }

  @Test
  void ownerExitIsDurableForALocalExecutionOnly() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID executionId = registerLocal(turn.threadId());

    ExecutionExitFact confirmed =
        store.transaction(
            tx -> {
              tx.threads().lock(turn.threadId()).orElseThrow();
              ExecutionExitFact stored = tx.exits().find(executionId).orElseThrow();
              return tx.exits()
                  .confirm(stored, ExecutionExitState.OWNER_EXITED, tx.now().plusMillis(10))
                  .orElseThrow();
            });

    assertEquals(ExecutionExitState.OWNER_EXITED, confirmed.state());
    assertTrue(confirmed.state().confirmed());
    assertNotNull(confirmed.confirmedAt());
    assertTrue(store.transaction(tx -> tx.exits().loadUnconfirmed(turn.threadId())).isEmpty());
  }

  @Test
  void remoteExecutionCannotClaimLocalOwnerExit() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID ownerLease = UUID.randomUUID();
    ExecutionExitFact remote =
        store.transaction(
            tx -> {
              tx.threads().lock(turn.threadId()).orElseThrow();
              UUID parentId = tx.nextId();
              ExecutionExitLedger.register(
                  tx,
                  parentId,
                  turn.threadId(),
                  turn.turnStartEntryId(),
                  1,
                  ownerLease,
                  ExecutionExitKind.TOOL,
                  null);
              UUID remoteId = tx.nextId();
              return ExecutionExitLedger.register(
                  tx,
                  remoteId,
                  turn.threadId(),
                  turn.turnStartEntryId(),
                  1,
                  ownerLease,
                  ExecutionExitKind.REMOTE,
                  parentId,
                  EnvironmentId.parse("11111111-1111-1111-1111-111111111111"));
            });

    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.transaction(
                tx -> {
                  tx.threads().lock(turn.threadId()).orElseThrow();
                  return tx.exits()
                      .confirm(remote, ExecutionExitState.OWNER_EXITED, tx.now().plusMillis(10));
                }));
    assertEquals(
        ExecutionExitState.UNCONFIRMED,
        store.transaction(tx -> tx.exits().find(remote.executionId()).orElseThrow()).state());
  }

  private UUID registerLocal(UUID threadId) {
    return store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          UUID executionId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              executionId,
              threadId,
              tx.nextId(),
              1,
              UUID.randomUUID(),
              ExecutionExitKind.TOOL,
              null);
          return executionId;
        });
  }
}
