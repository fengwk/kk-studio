package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSource;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.reconcile.ReconcileTestSupport;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime command coordinator: idempotency short-circuit, payload construction and config freeze
 * ordering. Uses hand-rolled fakes (runtime has no Mockito dependency).
 */
class ThreadCommandCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final ExecutionTarget TARGET = new ExecutionTarget(ExecutionTargetKind.THREAD, 1L);

  private FakeTransactions transactions;
  private FakeConfigSource configSource;
  private ThreadCommandCoordinator coordinator;

  @BeforeEach
  void setUp() {
    transactions = new FakeTransactions();
    configSource = new FakeConfigSource();
    coordinator = new ThreadCommandCoordinator(transactions, configSource, CLOCK);
  }

  /** Same idempotency key must return the persisted input before live config resolution. */
  @Test
  void configRetryShortCircuitsBeforeLiveResolution() {
    ThreadInputPayload payload = new StubPayload(ThreadInputType.SET_AGENT);
    ThreadInput persisted =
        new ThreadInput(
            9L,
            1L,
            1L,
            ThreadInputType.SET_AGENT,
            payload,
            "same-key",
            InputStatus.QUEUED,
            NOW,
            null);
    transactions.existing.put(
        "1:same-key", new ThreadCommandTransactions.EnqueueResult(persisted, TARGET));

    ThreadCommandCoordinator.EnqueueResult result =
        coordinator.queueAgent(1L, 99L, false, "same-key");
    assertSame(persisted, result.input());
    assertEquals(0, configSource.resolveCalls);
    assertEquals(0, transactions.lockCalls);
    assertEquals(0, transactions.enqueueCalls);
  }

  /**
   * Every typed command returns its durable idempotent result before validating new request data.
   */
  @Test
  void typedCommandRetriesShortCircuitBeforeValidationAndConfigLookup() {
    ThreadInput user = rememberExisting(ThreadInputType.USER_MESSAGE, "user-key");
    ThreadInput custom = rememberExisting(ThreadInputType.CUSTOM_MESSAGE, "custom-key");
    ThreadInput yolo = rememberExisting(ThreadInputType.SET_YOLO, "yolo-key");
    ThreadInput model = rememberExisting(ThreadInputType.SET_MODEL, "model-key");

    assertSame(user, coordinator.submitUserMessage(1L, " ", "user-key").input());
    assertSame(custom, coordinator.submitCustomMessage(1L, null, " ", "custom-key").input());
    assertSame(yolo, coordinator.queueYolo(1L, true, "yolo-key").input());
    assertSame(model, coordinator.queueModel(1L, 99L, "deleted", "model-key").input());
    assertEquals(0, transactions.lockCalls);
    assertEquals(0, transactions.enqueueCalls);
    assertEquals(0, configSource.replaceCalls);
  }

  /** Branch creation is a framework-free pass-through with the injected clock instant. */
  @Test
  void createBranchDelegatesWithInjectedClock() {
    HarnessThread expected = new HarnessThread(5L, 2L, 3L, 0L, false, 0L, null, NOW, NOW);
    transactions.branch = expected;

    assertSame(expected, coordinator.createBranch(2L, 3L));
    assertEquals(2L, transactions.lastBranchSessionId);
    assertEquals(3L, transactions.lastBranchEntryId);
    assertEquals(NOW, transactions.lastBranchNow);
  }

  /** User message payload is typed USER_MESSAGE with USER role text content after validation. */
  @Test
  void submitUserMessageBuildsTypedPayloadAfterContentValidation() {
    coordinator.submitUserMessage(1L, "hello", "m1");

    assertEquals(1, transactions.enqueueCalls);
    RuntimeEntryInputPayload payload = (RuntimeEntryInputPayload) transactions.lastPayload;
    assertEquals(ThreadInputType.USER_MESSAGE, payload.type());
    MessageEntryPayload entry = (MessageEntryPayload) payload.payload();
    assertEquals(AgentMessageRole.USER, entry.message().role());
    assertEquals("hello", ((TextMessageContent) entry.message().contents().getFirst()).text());
    assertEquals(NOW, transactions.lastNow);
  }

  /** Blank content is rejected after the idempotency miss path. */
  @Test
  void submitUserMessageRejectsBlankContent() {
    assertThrows(
        IllegalArgumentException.class, () -> coordinator.submitUserMessage(1L, "  ", "m1"));
    assertEquals(0, transactions.enqueueCalls);
  }

  /** Custom role must be system/user; payload type is CUSTOM_MESSAGE. */
  @Test
  void submitCustomMessageParsesRoleAndBuildsPayload() {
    coordinator.submitCustomMessage(1L, "system", "note", "c1");

    RuntimeEntryInputPayload payload = (RuntimeEntryInputPayload) transactions.lastPayload;
    assertEquals(ThreadInputType.CUSTOM_MESSAGE, payload.type());
    CustomMessageEntryPayload entry = (CustomMessageEntryPayload) payload.payload();
    assertEquals(AgentMessageRole.SYSTEM, entry.message().role());
  }

  @Test
  void submitCustomMessageRejectsAssistantRole() {
    assertThrows(
        IllegalArgumentException.class,
        () -> coordinator.submitCustomMessage(1L, "assistant", "x", "c1"));
  }

  @Test
  void submitCustomMessageRejectsBlankAndUnknownRoles() {
    assertThrows(
        IllegalArgumentException.class,
        () -> coordinator.submitCustomMessage(1L, " ", "x", "blank-role"));
    assertThrows(
        IllegalArgumentException.class,
        () -> coordinator.submitCustomMessage(1L, "operator", "x", "unknown-role"));
  }

  /** YOLO freezes via pure snapshot transform without touching RuntimeConfigSource. */
  @Test
  void queueYoloUsesPureSnapshotTransform() {
    RuntimeConfigSnapshot current = ReconcileTestSupport.configSnapshot();
    transactions.currentConfig = Optional.of(current);

    coordinator.queueYolo(1L, true, "y1");

    RuntimeConfigInputPayload payload = (RuntimeConfigInputPayload) transactions.lastPayload;
    assertEquals(ThreadInputType.SET_YOLO, payload.type());
    assertTrue(payload.snapshot().policy().yoloEnabled());
    assertEquals(current.agent(), payload.snapshot().agent());
    assertEquals(0, configSource.resolveCalls);
    assertEquals(0, configSource.replaceCalls);
  }

  /** SET_AGENT reuses current yolo when present; otherwise product defaultYolo is applied. */
  @Test
  void queueAgentPreservesCurrentYoloAndResolvesLiveAgent() {
    RuntimeConfigSnapshot current = ReconcileTestSupport.configSnapshot().withYoloEnabled(true);
    transactions.currentConfig = Optional.of(current);
    configSource.resolved = ReconcileTestSupport.configSnapshot().withYoloEnabled(true);

    coordinator.queueAgent(1L, 7L, false, "a1");

    assertEquals(1, configSource.resolveCalls);
    assertEquals(7L, configSource.lastDefinitionId);
    assertTrue(configSource.lastYolo);
    assertEquals(ThreadInputType.SET_AGENT, transactions.lastPayload.type());
  }

  @Test
  void queueAgentFallsBackToDefaultYoloWhenNoCurrentConfig() {
    transactions.currentConfig = Optional.empty();
    configSource.resolved = ReconcileTestSupport.configSnapshot();

    coordinator.queueAgent(1L, 7L, false, "a2");

    assertEquals(7L, configSource.lastDefinitionId);
    assertEquals(false, configSource.lastYolo);
  }

  /** SET_MODEL replaces model through SPI after locking current config. */
  @Test
  void queueModelDelegatesToConfigSource() {
    RuntimeConfigSnapshot current = ReconcileTestSupport.configSnapshot();
    transactions.currentConfig = Optional.of(current);
    configSource.replaced = ReconcileTestSupport.configSnapshot();

    coordinator.queueModel(1L, 3L, "fast", "mod1");

    assertEquals(1, configSource.replaceCalls);
    assertSame(current, configSource.lastCurrent);
    assertEquals(3L, configSource.lastModelId);
    assertEquals("fast", configSource.lastVariant);
    assertEquals(ThreadInputType.SET_MODEL, transactions.lastPayload.type());
  }

  /** Config replacement commands require an existing frozen runtime config. */
  @Test
  void configReplacementRejectsThreadWithoutRuntimeConfig() {
    assertThrows(IllegalStateException.class, () -> coordinator.queueYolo(1L, true, "y-missing"));
    assertThrows(
        IllegalStateException.class, () -> coordinator.queueModel(1L, 3L, "fast", "model-missing"));
    assertEquals(0, transactions.enqueueCalls);
    assertEquals(0, configSource.replaceCalls);
  }

  @Test
  void stopUsesInjectedClock() {
    ThreadCommandCoordinator.StopResult stop = coordinator.stop(1L);
    assertEquals(NOW, transactions.lastStopNow);
    assertEquals(1L, stop.executionEpoch());
  }

  private ThreadInput rememberExisting(ThreadInputType type, String key) {
    ThreadInputPayload payload = new StubPayload(type);
    ThreadInput input =
        new ThreadInput(
            9L + transactions.existing.size(),
            1L,
            1L,
            type,
            payload,
            key,
            InputStatus.QUEUED,
            NOW,
            null);
    transactions.existing.put(
        "1:" + key, new ThreadCommandTransactions.EnqueueResult(input, TARGET));
    return input;
  }

  private record StubPayload(ThreadInputType type) implements ThreadInputPayload {}

  private static final class FakeConfigSource implements RuntimeConfigSource {
    int resolveCalls;
    int replaceCalls;
    long lastDefinitionId;
    boolean lastYolo;
    RuntimeConfigSnapshot lastCurrent;
    long lastModelId;
    String lastVariant;
    RuntimeConfigSnapshot resolved = ReconcileTestSupport.configSnapshot();
    RuntimeConfigSnapshot replaced = ReconcileTestSupport.configSnapshot();

    @Override
    public RuntimeConfigSnapshot resolveAgent(long definitionId, boolean yoloEnabled) {
      resolveCalls++;
      lastDefinitionId = definitionId;
      lastYolo = yoloEnabled;
      return resolved;
    }

    @Override
    public RuntimeConfigSnapshot replaceModel(
        RuntimeConfigSnapshot current, long modelId, String requestedVariant) {
      replaceCalls++;
      lastCurrent = current;
      lastModelId = modelId;
      lastVariant = requestedVariant;
      return replaced;
    }
  }

  private static final class FakeTransactions implements ThreadCommandTransactions {
    final Map<String, EnqueueResult> existing = new HashMap<>();
    Optional<RuntimeConfigSnapshot> currentConfig = Optional.empty();
    int lockCalls;
    int enqueueCalls;
    ThreadInputPayload lastPayload;
    Instant lastNow;
    Instant lastStopNow;
    HarnessThread branch;
    long lastBranchSessionId;
    long lastBranchEntryId;
    Instant lastBranchNow;
    final AtomicInteger nextId = new AtomicInteger(100);

    @Override
    public SessionCreation createSession(
        String title, RuntimeConfigSnapshot initialConfig, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public HarnessThread createBranch(long sessionId, long fromEntryId, Instant now) {
      lastBranchSessionId = sessionId;
      lastBranchEntryId = fromEntryId;
      lastBranchNow = now;
      return branch;
    }

    @Override
    public Optional<RuntimeConfigSnapshot> lockAndFindCurrentConfig(long threadId) {
      lockCalls++;
      return currentConfig;
    }

    @Override
    public Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey) {
      return Optional.ofNullable(existing.get(threadId + ":" + idempotencyKey));
    }

    @Override
    public EnqueueResult enqueue(
        long threadId, ThreadInputPayload payload, String idempotencyKey, Instant now) {
      enqueueCalls++;
      lastPayload = payload;
      lastNow = now;
      ThreadInput input =
          new ThreadInput(
              nextId.getAndIncrement(),
              threadId,
              1L,
              payload.type(),
              payload,
              idempotencyKey,
              InputStatus.QUEUED,
              now,
              null);
      return new EnqueueResult(input, TARGET);
    }

    @Override
    public StopResult stop(long threadId, Instant now) {
      lastStopNow = now;
      return new StopResult(1L, List.of(), TARGET);
    }
  }
}
