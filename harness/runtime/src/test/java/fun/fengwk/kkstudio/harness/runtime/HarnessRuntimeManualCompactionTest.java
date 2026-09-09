package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** HarnessRuntime 手动压缩入口：availability、两阶段 version fence 与 durable plan 提交。 */
class HarnessRuntimeManualCompactionTest {

  private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  private static final int CONTEXT_WINDOW = 100_000;
  private static final int MAX_OUTPUT_TOKENS = 16_384;
  private static final ModelUsage USAGE = new ModelUsage(50_000L, 2L, 0L, 0L, 0L, 0L, 50_002L);
  private static final ModelCost COST =
      new ModelCost(
          "USD",
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO,
          BigDecimal.ZERO);

  @Test
  void availabilityDisablesSmallAndBusyThreads() {
    // 小上下文低于 manualMinimum；open turn 即使无 Model 行也属于不安全控制面。
    Fixture smallFixture = fixture();
    ClosedTurnBaseline small = seedSmallClosedTurn(smallFixture.store);
    ManualCompactionAvailability smallAvailability =
        smallFixture.runtime.manualCompactionAvailability(small.threadId());
    assertFalse(smallAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.BELOW_MINIMUM,
        smallAvailability.disabledReason());

    Fixture busyFixture = fixture();
    HarnessRuntimeTestSupport.TurnBaseline busy =
        HarnessRuntimeTestSupport.seedOpenTurn(busyFixture.store);
    ManualCompactionAvailability busyAvailability =
        busyFixture.runtime.manualCompactionAvailability(busy.threadId());
    assertFalse(busyAvailability.available());
    assertEquals(
        ManualCompactionAvailability.DisabledReason.THREAD_BUSY, busyAvailability.disabledReason());
  }

  @Test
  void compactThreadCommitsManualTurnAndModelWorkWithoutMailboxCommand() {
    // Runtime 同步提交 TURN_START + ModelInvocation；模型执行仍由既有 MODEL Work 异步驱动。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    assertTrue(fixture.runtime.manualCompactionAvailability(baseline.threadId()).available());

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertEquals(1L, result.thread().version());
    assertNotNull(result.modelInvocationId());
    EntryPath path = path(fixture.store, baseline.threadId());
    TurnStartPayload start = (TurnStartPayload) path.head().payload();
    assertEquals(result.turnStartEntryId(), path.head().id());
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
    assertEquals(HarnessRuntimeTestSupport.settings().model(), start.compaction().executionModel());
    assertNotNull(start.contextWindow());
    assertNotNull(start.maxOutputTokens());
    assertNotNull(
        work(fixture.store, new WorkTarget(WorkTargetType.MODEL, result.modelInvocationId())));
    assertNull(work(fixture.store, new WorkTarget(WorkTargetType.THREAD, baseline.threadId())));
  }

  @Test
  void compactThreadUsesTypedVersionAndAvailabilityConflicts() {
    Fixture staleFixture = fixture();
    ClosedTurnBaseline stale = seedCompactionReadyClosedTurn(staleFixture.store);
    HarnessRuntimeConflictException staleError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                staleFixture.runtime.compactThread(new CompactThreadCommand(stale.threadId(), 1)));
    assertEquals(HarnessRuntimeConflictException.Reason.STALE_VERSION, staleError.reason());
    assertEquals(9, path(staleFixture.store, stale.threadId()).entries().size());

    Fixture busyFixture = fixture();
    HarnessRuntimeTestSupport.TurnBaseline busy =
        HarnessRuntimeTestSupport.seedOpenTurn(busyFixture.store);
    HarnessRuntimeConflictException busyError =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> busyFixture.runtime.compactThread(new CompactThreadCommand(busy.threadId(), 0)));
    assertEquals(
        HarnessRuntimeConflictException.Reason.MANUAL_COMPACTION_UNAVAILABLE, busyError.reason());
  }

  @Test
  void resolverRejectionClosesManualTurnWithoutModelInvocation() {
    // Resolver 业务拒绝仍形成完整 failed turn；没有半开的 manual intent。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.results.add(
        new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "model unavailable")));

    CompactThreadResult result =
        fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0));

    assertNull(result.modelInvocationId());
    assertEquals(1L, result.thread().version());
    EntryPath path = path(fixture.store, baseline.threadId());
    assertEquals(12, path.entries().size());
    TurnStartPayload start = (TurnStartPayload) path.entries().get(9).payload();
    assertEquals(CompactionTrigger.MANUAL, start.compaction().trigger());
    TurnEndPayload end = (TurnEndPayload) path.head().payload();
    assertEquals(TurnEndOutcome.FAILED, end.outcome());
  }

  @Test
  void threadMustExistForAvailabilityAndCompact() {
    // Thread 不存在时，availability 与 compact 都由根控制面确定性返回 NotFound。
    Fixture fixture = fixture();
    UUID missing = UUID.randomUUID();
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> fixture.runtime.manualCompactionAvailability(missing));
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> fixture.runtime.compactThread(new CompactThreadCommand(missing, 0)));
  }

  @Test
  void compactThreadRejectsNullResolverResult() {
    // Resolver 返回 null 违背契约，必须零 durable mutation。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0)));
    assertEquals("turn resolver returned null for manual compaction", error.getMessage());
    assertEquals(0L, thread(fixture.store, baseline.threadId()).version());
  }

  @Test
  void compactThreadRejectsConcurrentCommandAcceptedDuringResolve() {
    // plan 后出现新 Command 时，第二事务必须命中 version/head/Command snapshot fence。
    Fixture fixture = fixture();
    ClosedTurnBaseline baseline = seedCompactionReadyClosedTurn(fixture.store);
    fixture.resolver.autoConsistent = true;
    fixture.resolver.onResolve =
        () ->
            seedCommand(
                fixture.store,
                baseline.threadId(),
                new UserMessageCommandPayload(userMessage("concurrent input")));

    HarnessRuntimeConflictException error =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () -> fixture.runtime.compactThread(new CompactThreadCommand(baseline.threadId(), 0)));

    assertEquals(HarnessRuntimeConflictException.Reason.STALE_VERSION, error.reason());
    assertEquals(1L, thread(fixture.store, baseline.threadId()).version());
    assertEquals(
        baseline.turnEndEntryId(), thread(fixture.store, baseline.threadId()).headEntryId());
    assertEquals(9, path(fixture.store, baseline.threadId()).entries().size());
  }

  @Test
  void resolverRejectionWakesThreadOnlyForDeferredUserDemand() {
    // USER message 保留 INPUT obligation；SYSTEM steering 自身不构成 user demand。
    Fixture userFixture = fixture();
    ClosedTurnBaseline userBaseline = seedCompactionReadyClosedTurn(userFixture.store);
    seedCommand(
        userFixture.store,
        userBaseline.threadId(),
        new CustomMessageCommandPayload(userMessage("deferred input")));
    userFixture.resolver.results.add(rejection());
    long userVersion = thread(userFixture.store, userBaseline.threadId()).version();
    userFixture.runtime.compactThread(
        new CompactThreadCommand(userBaseline.threadId(), userVersion));
    assertNotNull(
        work(userFixture.store, new WorkTarget(WorkTargetType.THREAD, userBaseline.threadId())));

    Fixture systemFixture = fixture();
    ClosedTurnBaseline systemBaseline = seedCompactionReadyClosedTurn(systemFixture.store);
    seedCommand(
        systemFixture.store,
        systemBaseline.threadId(),
        new CustomMessageCommandPayload(systemMessage("deferred steering")));
    systemFixture.resolver.results.add(rejection());
    long systemVersion = thread(systemFixture.store, systemBaseline.threadId()).version();
    systemFixture.runtime.compactThread(
        new CompactThreadCommand(systemBaseline.threadId(), systemVersion));
    assertNull(
        work(
            systemFixture.store, new WorkTarget(WorkTargetType.THREAD, systemBaseline.threadId())));
  }

  private static Fixture fixture() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    FakeTurnResolver resolver = new FakeTurnResolver();
    HarnessRuntime runtime =
        new HarnessRuntime(
            store, Clock.fixed(NOW, ZoneOffset.UTC), resolver, () -> CompactionConfig.DEFAULT);
    return new Fixture(store, resolver, runtime);
  }

  private static TurnResolver.Rejected rejection() {
    return new TurnResolver.Rejected(new AssistantError("CONFIG_ERROR", "model unavailable"));
  }

  private static UUID seedCommand(
      InMemoryHarnessStore store, UUID threadId, ThreadCommandPayload payload) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          UUID idempotencyKey = tx.nextId();
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      threadId,
                      thread.nextCommandSequence(),
                      payload,
                      idempotencyKey,
                      ThreadCommandPayloadJsonCodec.requestHash(payload),
                      null,
                      null,
                      null,
                      NOW)));
          tx.updateThread(thread.reserveCommandSequences(1, NOW));
          return idempotencyKey;
        });
  }

  private static ClosedTurnBaseline seedCompactionReadyClosedTurn(InMemoryHarnessStore store) {
    return seedClosedTurn(store, 50_000, 90_000);
  }

  private static ClosedTurnBaseline seedSmallClosedTurn(InMemoryHarnessStore store) {
    return seedClosedTurn(store, 10, 10);
  }

  private static ClosedTurnBaseline seedClosedTurn(
      InMemoryHarnessStore store, int historicalChars, int currentChars) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootId = tx.nextId();
          UUID firstStartId = tx.nextId();
          UUID firstUserId = tx.nextId();
          UUID firstAssistantId = tx.nextId();
          UUID firstEndId = tx.nextId();
          UUID secondStartId = tx.nextId();
          UUID secondUserId = tx.nextId();
          UUID secondAssistantId = tx.nextId();
          UUID secondEndId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          tx.insertEntry(
              new Entry(
                  rootId,
                  sessionId,
                  null,
                  new RootPayload(HarnessRuntimeTestSupport.settings()),
                  NOW));
          tx.insertEntry(turnStart(firstStartId, sessionId, rootId, threadId));
          tx.insertEntry(
              message(
                  firstUserId,
                  sessionId,
                  firstStartId,
                  userMessage("historical user " + "h".repeat(historicalChars)),
                  null));
          tx.insertEntry(
              message(
                  firstAssistantId,
                  sessionId,
                  firstUserId,
                  assistantMessage("historical assistant " + "a".repeat(historicalChars)),
                  metadata()));
          tx.insertEntry(turnEnd(firstEndId, sessionId, firstAssistantId, firstStartId));
          tx.insertEntry(turnStart(secondStartId, sessionId, firstEndId, threadId));
          tx.insertEntry(
              message(
                  secondUserId,
                  sessionId,
                  secondStartId,
                  userMessage("user asks a very long question" + "x".repeat(currentChars)),
                  null));
          tx.insertEntry(
              message(
                  secondAssistantId,
                  sessionId,
                  secondUserId,
                  assistantMessage("assistant reply"),
                  metadata()));
          tx.insertEntry(turnEnd(secondEndId, sessionId, secondAssistantId, secondStartId));
          tx.insertThread(
              new ThreadState(
                  threadId,
                  sessionId,
                  secondEndId,
                  HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                  "main",
                  false,
                  1,
                  0,
                  NOW,
                  NOW));
          return new ClosedTurnBaseline(threadId, secondEndId);
        });
  }

  private static Entry turnStart(UUID id, UUID sessionId, UUID parentId, UUID threadId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(
            TurnStartReason.INPUT,
            HarnessRuntimeTestSupport.settings(),
            threadId,
            CONTEXT_WINDOW,
            MAX_OUTPUT_TOKENS,
            null),
        NOW);
  }

  private static Entry turnEnd(UUID id, UUID sessionId, UUID parentId, UUID turnStartEntryId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
        NOW);
  }

  private static Entry message(
      UUID id,
      UUID sessionId,
      UUID parentId,
      AgentMessage message,
      AssistantMessageMetadata metadata) {
    return new Entry(id, sessionId, parentId, new MessagePayload(message, metadata, null), NOW);
  }

  private static AgentMessage userMessage(String text) {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage systemMessage(String text) {
    return new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent(text)));
  }

  private static AgentMessage assistantMessage(String text) {
    return new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text)));
  }

  private static AssistantMessageMetadata metadata() {
    return new AssistantMessageMetadata(GenerationStopReason.COMPLETE, USAGE, COST);
  }

  private static EntryPath path(InMemoryHarnessStore store, UUID threadId) {
    ThreadState thread = thread(store, threadId);
    return store.transaction(tx -> tx.loadEntryPath(thread.headEntryId()));
  }

  private static ThreadState thread(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId).orElseThrow());
  }

  private static Work work(InMemoryHarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.findWork(target).orElse(null));
  }

  private record Fixture(
      InMemoryHarnessStore store, FakeTurnResolver resolver, HarnessRuntime runtime) {}

  private record ClosedTurnBaseline(UUID threadId, UUID turnEndEntryId) {}

  /** Scripted Resolver：可返回显式结果、在 resolve 中触发竞态，或按冻结 compaction facts 生成一致请求。 */
  private static final class FakeTurnResolver implements TurnResolver {
    private final Deque<Result> results = new ArrayDeque<>();
    private Runnable onResolve;
    private boolean autoConsistent;

    @Override
    public Result resolve(UUID threadId, EntryPath path, CompactionPreparation preparation) {
      if (onResolve != null) {
        onResolve.run();
      }
      if (autoConsistent) {
        return new Resolved(
            HarnessRuntimeTestSupport.modelRequest(), CONTEXT_WINDOW, MAX_OUTPUT_TOKENS);
      }
      return results.poll();
    }
  }
}
