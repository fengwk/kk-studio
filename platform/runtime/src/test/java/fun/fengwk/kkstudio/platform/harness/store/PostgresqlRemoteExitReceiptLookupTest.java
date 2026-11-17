package fun.fengwk.kkstudio.platform.harness.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitKind;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitState;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionExitLedger;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.platform.environment.server.PostgresqlExecutionExitReceiptLookup;

import java.util.UUID;

/**
 * Verifies that restart ACK recovery reads only durable REMOTE_ACKNOWLEDGED rows from PostgreSQL.
 */
class PostgresqlRemoteExitReceiptLookupTest {

  private static final EnvironmentId ENVIRONMENT_ID =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  private static final EnvironmentId OTHER_ENVIRONMENT_ID =
      EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

  private HarnessStore store;
  private PostgresqlExecutionExitReceiptLookup lookup;

  @BeforeEach
  void setUp() {
    store = PostgresqlHarnessStoreFixture.resetAndCreate();
    lookup =
        new PostgresqlExecutionExitReceiptLookup(
            new JdbcTemplate(PostgresqlHarnessStoreFixture.dataSource()));
  }

  @Test
  void acknowledgesOnlyMatchingDurableRemoteReceipt() {
    StoreTestSupport.TurnBaseline turn = StoreTestSupport.seedTurnBaseline(store);
    UUID acknowledged = registerRemote(turn.threadId(), ENVIRONMENT_ID);
    UUID unconfirmed = registerRemote(turn.threadId(), ENVIRONMENT_ID);
    UUID abandoned = registerRemote(turn.threadId(), ENVIRONMENT_ID);
    UUID otherEnvironment = registerRemote(turn.threadId(), OTHER_ENVIRONMENT_ID);
    UUID local = registerLocal(turn.threadId());
    confirm(turn.threadId(), acknowledged, ExecutionExitState.REMOTE_ACKNOWLEDGED);
    abandon(turn.threadId(), abandoned);
    confirm(turn.threadId(), otherEnvironment, ExecutionExitState.REMOTE_ACKNOWLEDGED);
    confirm(turn.threadId(), local, ExecutionExitState.STUDIO_EXITED);

    assertTrue(lookup.isAcknowledged(ENVIRONMENT_ID, acknowledged));
    assertFalse(lookup.isAcknowledged(ENVIRONMENT_ID, unconfirmed));
    assertFalse(lookup.isAcknowledged(ENVIRONMENT_ID, abandoned));
    assertFalse(lookup.isAcknowledged(ENVIRONMENT_ID, otherEnvironment));
    assertFalse(lookup.isAcknowledged(ENVIRONMENT_ID, local));
    assertFalse(lookup.isAcknowledged(ENVIRONMENT_ID, UUID.randomUUID()));
  }

  private UUID registerRemote(UUID threadId, EnvironmentId environmentId) {
    return store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          UUID invocationId = tx.nextId();
          UUID ownerLeaseToken = UUID.randomUUID();
          UUID parentId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              parentId,
              threadId,
              invocationId,
              1,
              ownerLeaseToken,
              ExecutionExitKind.TOOL,
              null);
          UUID remoteExecutionId = tx.nextId();
          ExecutionExitLedger.register(
              tx,
              remoteExecutionId,
              threadId,
              invocationId,
              1,
              ownerLeaseToken,
              ExecutionExitKind.REMOTE,
              parentId,
              environmentId);
          return remoteExecutionId;
        });
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

  private void abandon(UUID threadId, UUID executionId) {
    store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          tx.exits()
              .abandon(tx.exits().find(executionId).orElseThrow(), tx.now().plusMillis(10))
              .orElseThrow();
          return null;
        });
  }

  private void confirm(UUID threadId, UUID executionId, ExecutionExitState state) {
    store.transaction(
        tx -> {
          tx.threads().lock(threadId).orElseThrow();
          tx.exits()
              .confirm(tx.exits().find(executionId).orElseThrow(), state, tx.now().plusMillis(10))
              .orElseThrow();
          return null;
        });
  }
}
