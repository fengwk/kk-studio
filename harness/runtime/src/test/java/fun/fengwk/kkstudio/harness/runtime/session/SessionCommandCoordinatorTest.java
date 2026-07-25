package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSource;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ReconcileTestSupport;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** Session bootstrap freezes the supplied agent definition before createSession. */
class SessionCommandCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-07-24T12:00:00Z");

  @Test
  void createSessionResolvesBootstrapConfigThenPersists() {
    FakeConfigSource configSource = new FakeConfigSource();
    FakeTransactions transactions = new FakeTransactions();
    SessionCommandCoordinator coordinator =
        new SessionCommandCoordinator(transactions, configSource, Clock.fixed(NOW, ZoneOffset.UTC));

    ThreadCommandTransactions.SessionCreation creation = coordinator.createSession("t", 1L, true);

    assertEquals(1L, configSource.lastDefinitionId);
    assertEquals(true, configSource.lastYolo);
    assertSame(configSource.resolved, transactions.lastConfig);
    assertEquals("t", transactions.lastTitle);
    assertEquals(NOW, transactions.lastNow);
    assertSame(transactions.creation, creation);
  }

  private static final class FakeConfigSource implements RuntimeConfigSource {
    long lastDefinitionId;
    boolean lastYolo;
    final RuntimeConfigSnapshot resolved =
        ReconcileTestSupport.configSnapshot().withYoloEnabled(true);

    @Override
    public RuntimeConfigSnapshot resolveAgent(long definitionId, boolean yoloEnabled) {
      lastDefinitionId = definitionId;
      lastYolo = yoloEnabled;
      return resolved;
    }

    @Override
    public RuntimeConfigSnapshot replaceModel(
        RuntimeConfigSnapshot current, long modelId, String requestedVariant) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class FakeTransactions implements ThreadCommandTransactions {
    String lastTitle;
    RuntimeConfigSnapshot lastConfig;
    Instant lastNow;
    SessionCreation creation;

    @Override
    public SessionCreation createSession(
        String title, RuntimeConfigSnapshot initialConfig, Instant now) {
      lastTitle = title;
      lastConfig = initialConfig;
      lastNow = now;
      Session session = new Session(10L, 11L, title, null, null, now, now);
      creation = new SessionCreation(session, null, null);
      return creation;
    }

    @Override
    public HarnessThread createBranch(long sessionId, long fromEntryId, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<RuntimeConfigSnapshot> lockAndFindCurrentConfig(long threadId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EnqueueResult enqueue(
        long threadId, ThreadInputPayload payload, String idempotencyKey, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StopResult stop(long threadId, Instant now) {
      throw new UnsupportedOperationException();
    }
  }
}
