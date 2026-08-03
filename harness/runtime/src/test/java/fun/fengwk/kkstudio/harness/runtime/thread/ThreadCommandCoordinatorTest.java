package fun.fengwk.kkstudio.harness.runtime.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Coordinator tests for idempotency, message-only inputs, and exact per-turn settings. */
class ThreadCommandCoordinatorTest {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final TurnSettings SETTINGS = new TurnSettings("agent", true);

  private FakeTransactions transactions;
  private ThreadCommandCoordinator coordinator;

  @BeforeEach
  void setUp() {
    transactions = new FakeTransactions();
    coordinator = new ThreadCommandCoordinator(transactions, CLOCK);
  }

  @Test
  void idempotentRetryReturnsExistingMessageBeforeValidatingNewContent() {
    ThreadInput persisted = input(9L, 1L, ThreadInputType.USER_MESSAGE, SETTINGS);
    transactions.existing.put("1:same-key", new ThreadCommandTransactions.EnqueueResult(persisted));

    ThreadCommandCoordinator.EnqueueResult result =
        coordinator.submitUserMessage(1L, SETTINGS, " ", "same-key", 4L);

    assertSame(persisted, result.input());
    assertEquals(0, transactions.enqueueCalls);
    assertEquals(1, transactions.findCalls);
  }

  @Test
  void createsAndUpdatesThreadsThroughInjectedClock() {
    assertSame(transactions.createdThread, coordinator.createThread("title", null));
    assertEquals("title", transactions.lastTitle);
    assertEquals(NOW, transactions.lastCreateNow);

    assertSame(transactions.updatedThread, coordinator.updateHead(1L, 7L, 99L));
    assertEquals(1L, transactions.lastUpdateThreadId);
    assertEquals(7L, transactions.lastExpectedEpoch);
    assertEquals(99L, transactions.lastHeadEntryId);
    assertEquals(NOW, transactions.lastUpdateNow);
  }

  @Test
  void submitUserMessageStoresTheExactTurnSettings() {
    ThreadCommandCoordinator.EnqueueResult result =
        coordinator.submitUserMessage(1L, SETTINGS, "hello", "user-key", 7L);

    RuntimeEntryInputPayload payload = (RuntimeEntryInputPayload) transactions.lastPayload;
    MessageEntryPayload entry = (MessageEntryPayload) payload.payload();
    assertEquals(ThreadInputType.USER_MESSAGE, payload.type());
    assertEquals(AgentMessageRole.USER, entry.message().role());
    assertEquals("hello", ((TextMessageContent) entry.message().contents().getFirst()).text());
    assertEquals(SETTINGS, entry.turnSettings());
    assertSame(result.input(), transactions.lastEnqueuedInput);
    assertEquals(7L, transactions.lastExpectedEpoch);
    assertEquals(NOW, transactions.lastNow);
  }

  @Test
  void submitCustomMessageStoresTheExactTurnSettingsAndRole() {
    ThreadCommandCoordinator.EnqueueResult result =
        coordinator.submitCustomMessage(1L, SETTINGS, "system", "instruction", "custom-key", 8L);

    RuntimeEntryInputPayload payload = (RuntimeEntryInputPayload) transactions.lastPayload;
    CustomMessageEntryPayload entry = (CustomMessageEntryPayload) payload.payload();
    assertEquals(ThreadInputType.CUSTOM_MESSAGE, payload.type());
    assertEquals(AgentMessageRole.SYSTEM, entry.message().role());
    assertEquals("instruction", text(entry.message()));
    assertEquals(SETTINGS, entry.turnSettings());
    assertSame(result.input(), transactions.lastEnqueuedInput);
  }

  @Test
  void rejectsInvalidNewMessagesAfterIdempotencyMiss() {
    assertThrows(
        IllegalArgumentException.class,
        () -> coordinator.submitUserMessage(1L, SETTINGS, " ", "blank", 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> coordinator.submitCustomMessage(1L, SETTINGS, "assistant", "x", "role", 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> coordinator.submitCustomMessage(1L, SETTINGS, "operator", "x", "role-2", 0L));
    assertEquals(0, transactions.enqueueCalls);
  }

  @Test
  void findExistingInputMapsTheDurableResult() {
    ThreadInput persisted = input(9L, 1L, ThreadInputType.CUSTOM_MESSAGE, SETTINGS);
    transactions.existing.put("1:key", new ThreadCommandTransactions.EnqueueResult(persisted));

    Optional<ThreadCommandCoordinator.EnqueueResult> result =
        coordinator.findExistingInput(1L, "key");

    assertTruePresent(result);
    assertSame(persisted, result.orElseThrow().input());
    assertEquals(1, transactions.findCalls);
  }

  @Test
  void stopUsesInjectedClockAndPreservesTransactionOutcome() {
    ThreadCommandCoordinator.StopResult result = coordinator.stop(1L, 9L);

    assertEquals(10L, result.executionEpoch());
    assertEquals(List.of(), result.cancelledInputs());
    assertEquals(1L, transactions.lastStopThreadId);
    assertEquals(9L, transactions.lastStopExpectedEpoch);
    assertEquals(NOW, transactions.lastStopNow);
  }

  private static void assertTruePresent(Optional<?> value) {
    if (value.isEmpty()) {
      throw new AssertionError("expected an existing input");
    }
  }

  private static String text(AgentMessage message) {
    return ((TextMessageContent) message.contents().getFirst()).text();
  }

  private static ThreadInput input(
      long id, long sequence, ThreadInputType type, TurnSettings settings) {
    RuntimeEntryInputPayload payload =
        type == ThreadInputType.USER_MESSAGE
            ? new RuntimeEntryInputPayload(
                type, new MessageEntryPayload(userMessage("persisted"), settings, null))
            : new RuntimeEntryInputPayload(
                type, new CustomMessageEntryPayload(systemMessage("persisted"), settings));
    return new ThreadInput(
        id, 1L, sequence, type, payload, "persisted-" + id, InputStatus.QUEUED, NOW, null);
  }

  private static AgentMessage userMessage(String content) {
    return message(AgentMessageRole.USER, content);
  }

  private static AgentMessage systemMessage(String content) {
    return message(AgentMessageRole.SYSTEM, content);
  }

  private static AgentMessage message(AgentMessageRole role, String content) {
    return new AgentMessage(role, List.of(new TextMessageContent(content)));
  }

  private static final class FakeTransactions implements ThreadCommandTransactions {
    private final Map<String, EnqueueResult> existing = new HashMap<>();
    private final HarnessThread createdThread =
        new HarnessThread(1L, 1L, null, 0L, false, 0L, 0L, null, NOW, NOW);
    private final HarnessThread updatedThread =
        new HarnessThread(1L, 99L, null, 0L, false, 7L, 1L, null, NOW, NOW);
    private int findCalls;
    private int enqueueCalls;
    private Instant lastCreateNow;
    private String lastTitle;
    private long lastUpdateThreadId;
    private long lastExpectedEpoch;
    private long lastHeadEntryId;
    private Instant lastUpdateNow;
    private ThreadInputPayload lastPayload;
    private ThreadInput lastEnqueuedInput;
    private Instant lastNow;
    private long lastStopThreadId;
    private long lastStopExpectedEpoch;
    private Instant lastStopNow;
    private int nextId = 100;

    @Override
    public HarnessThread createThread(String title, String environmentName, Instant now) {
      lastTitle = title;
      lastCreateNow = now;
      return createdThread;
    }

    @Override
    public HarnessThread updateHead(
        long threadId, long expectedExecutionEpoch, long headEntryId, Instant now) {
      lastUpdateThreadId = threadId;
      lastExpectedEpoch = expectedExecutionEpoch;
      lastHeadEntryId = headEntryId;
      lastUpdateNow = now;
      return updatedThread;
    }

    @Override
    public HarnessThread updateEnvironment(
        long threadId, long expectedExecutionEpoch, String environmentName, Instant now) {
      return updatedThread;
    }

    @Override
    public Optional<EnqueueResult> findExistingInput(long threadId, String idempotencyKey) {
      findCalls++;
      return Optional.ofNullable(existing.get(threadId + ":" + idempotencyKey));
    }

    @Override
    public EnqueueResult enqueue(
        long threadId,
        ThreadInputPayload payload,
        String idempotencyKey,
        long expectedExecutionEpoch,
        Instant now) {
      enqueueCalls++;
      lastPayload = payload;
      lastExpectedEpoch = expectedExecutionEpoch;
      lastNow = now;
      lastEnqueuedInput =
          new ThreadInput(
              nextId++,
              threadId,
              1L,
              payload.type(),
              payload,
              idempotencyKey,
              InputStatus.QUEUED,
              now,
              null);
      return new EnqueueResult(lastEnqueuedInput);
    }

    @Override
    public StopResult stop(long threadId, long expectedExecutionEpoch, Instant now) {
      lastStopThreadId = threadId;
      lastStopExpectedEpoch = expectedExecutionEpoch;
      lastStopNow = now;
      return new StopResult(expectedExecutionEpoch + 1L, List.of());
    }
  }
}
