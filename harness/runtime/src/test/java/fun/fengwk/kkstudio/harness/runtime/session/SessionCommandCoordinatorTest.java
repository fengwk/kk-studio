package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSource;
import fun.fengwk.kkstudio.harness.runtime.entry.RootEntryPayload;
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

    Session session = coordinator.createSession("t", 1L, true);

    assertEquals(1L, configSource.lastDefinitionId);
    assertEquals(true, configSource.lastYolo);
    assertSame(configSource.resolved, transactions.lastConfig);
    assertEquals("t", transactions.lastTitle);
    assertEquals(NOW, transactions.lastNow);
    assertSame(transactions.session, session);
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
    Session session;

    @Override
    public SessionCreation createSession(
        String title, RuntimeConfigSnapshot initialConfig, Instant now) {
      lastTitle = title;
      lastConfig = initialConfig;
      lastNow = now;
      session = new Session(10L, title, now);
      SessionEntry root = new SessionEntry(11L, null, new RootEntryPayload());
      SessionEntry config = new SessionEntry(12L, root.id(), initialConfig);
      return new SessionCreation(session, root, config);
    }

    @Override
    public HarnessThread createThread(Instant now) {
      return null;
    }

    @Override
    public BootstrapResult bootstrapThread(
        long threadId,
        long expectedExecutionEpoch,
        String title,
        RuntimeConfigSnapshot initialConfig,
        Instant now) {
      return null;
    }

    @Override
    public HarnessThread updateHead(
        long threadId, long expectedExecutionEpoch, Long headEntryId, Instant now) {
      return null;
    }

    @Override
    public Optional<RuntimeConfigSnapshot> lockAndFindCurrentConfig(
        long threadId, long expectedExecutionEpoch) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey) {
      throw new UnsupportedOperationException();
    }

    @Override
    public EnqueueResult enqueue(
        long threadId,
        ThreadInputPayload payload,
        String idempotencyKey,
        long expectedExecutionEpoch,
        Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StopResult stop(long threadId, long expectedExecutionEpoch, Instant now) {
      throw new UnsupportedOperationException();
    }
  }
}
