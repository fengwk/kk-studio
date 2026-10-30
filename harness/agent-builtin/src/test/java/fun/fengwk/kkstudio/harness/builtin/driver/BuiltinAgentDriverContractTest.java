package fun.fengwk.kkstudio.harness.builtin.driver;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtinDriver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.GenerationProjection;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link BuiltinAgentDriver} 的锁定契约：Driver 只按 (threadId, 当前 open turn 的 TURN_START entry id) 锁定自己的
 * generation，跨 Thread/跨 turn 的模型绝不暴露；中立投影因此永远属于本 Thread 的本 turn，公共快照同样拒绝外来 generation。
 *
 * <p>测试直接使用真实 Driver 与真实 Store：锁定的归属判定、projection 的构造与公共快照的 fail-closed 都由生产代码 决定，不使用替身。
 */
class BuiltinAgentDriverContractTest {

  private static final Instant T0 = Instant.ofEpochMilli(1_000);
  private static final Instant T1 = Instant.ofEpochMilli(2_000);
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  /** 本测试不解析 turn：resolver 永不参与锁定路径。 */
  private static final TurnResolver UNUSED_RESOLVER = (threadId, path, preparation) -> null;

  private InMemoryBuiltinHarnessStore store;
  private BuiltinAgentDriver driver;

  @BeforeEach
  void setUp() {
    store = new InMemoryBuiltinHarnessStore();
    driver = builtinDriver(store, UNUSED_RESOLVER);
  }

  @Test
  void locksOwnGenerationForItsThreadAndOpenTurn() {
    // 测试意图：Driver 锁定的是本 Thread 本 open turn 的 generation，投影身份与请求 basis 完全一致。
    Seed owner = seedOwnerThread();
    LockedDriverState state = lockState(owner.threadId(), owner.requestHeadEntryId());

    assertEquals(DriverActivity.ACTIVE, state.activity());
    assertEquals(owner.modelId(), state.generationId());
    assertEquals(owner.requestHeadEntryId(), state.requestHeadEntryId());
    assertNull(state.resultEntryId());
    GenerationProjection projection = state.projection();
    assertNotNull(projection);
    assertEquals(owner.modelId(), projection.generationId());
    assertEquals(owner.turnStartEntryId(), projection.turnStartEntryId());
    assertEquals(owner.requestHeadEntryId(), projection.requestHeadEntryId());
  }

  @Test
  void modelOfAnotherThreadIsNeverExposed() {
    // 即使两个 Thread 共享同一个 open turn，Driver 也必须按 Thread 身份隔离 generation。
    Seed owner = seedOwnerThread();
    UUID otherThreadId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              tx.threads()
                  .insert(thread(id, owner.sessionId(), owner.requestHeadEntryId(), "shared"));
              return id;
            });
    LockedDriverState state = lockState(otherThreadId, owner.requestHeadEntryId());

    assertEquals(DriverActivity.NONE, state.activity());
    assertNull(state.projection());
    assertNull(state.generationId());
    assertNull(state.requestHeadEntryId());
    assertNull(state.resultEntryId());
  }

  @Test
  void modelOfAnotherTurnIsNeverExposed() {
    // 测试意图：同一 Thread 上一 turn 的 generation 绝不串到新 turn：锁定键精确到 open turn。
    Seed thread = seedThreadWithClosedTurnModel();

    // 对照组：锁定的正是本 turn 的模型时，Driver 暴露自己的 generation。
    LockedDriverState own = lockState(thread.threadId(), thread.requestHeadEntryId());
    assertEquals(DriverActivity.ACTIVE, own.activity());
    assertEquals(thread.modelId(), own.generationId());

    // 下一个 turn：上一 turn 的模型不可见。
    LockedDriverState nextTurn = lockState(thread.threadId(), thread.nextTurnHeadEntryId());
    assertEquals(DriverActivity.NONE, nextTurn.activity());
    assertNull(nextTurn.projection());
    assertNull(nextTurn.generationId());
  }

  @Test
  void publicSnapshotPublishesOnlyItsOwnGeneration() {
    // 测试意图：公共快照只发布属于本 Thread 本 turn 的中立投影；另一个 Thread 的快照绝不携带外来 generation。
    Seed owner = seedOwnerThread();
    Seed other = seedOtherThread(owner.sessionId(), owner.rootEntryId());
    HarnessRuntime runtime = new HarnessRuntime(store, driver, () -> CompactionConfig.DEFAULT);

    ThreadSnapshot ownerSnapshot = runtime.getThreadSnapshot(owner.threadId());
    assertNotNull(ownerSnapshot.generation());
    assertEquals(owner.turnStartEntryId(), ownerSnapshot.generation().turnStartEntryId());
    assertEquals(owner.modelId(), ownerSnapshot.generation().generationId());

    ThreadSnapshot otherSnapshot = runtime.getThreadSnapshot(other.threadId());
    assertNull(otherSnapshot.generation());
  }

  @Test
  void foreignGenerationIsRejectedByPublicSnapshot() {
    // 测试意图：Driver 的中立投影仍受公共快照的归属校验保护：外来 generation 必须 fail closed，而不是降级为 IDLE。
    Seed owner = seedOwnerThread();
    Seed other = seedOtherThread(owner.sessionId(), owner.rootEntryId());
    GenerationProjection foreign =
        lockState(owner.threadId(), owner.requestHeadEntryId()).projection();
    assertNotNull(foreign);
    HarnessRuntime runtime = new HarnessRuntime(store, driver, () -> CompactionConfig.DEFAULT);
    ThreadSnapshot otherSnapshot = runtime.getThreadSnapshot(other.threadId());

    assertThrows(
        IllegalStateException.class,
        () ->
            new ThreadSnapshot(
                otherSnapshot.thread(),
                otherSnapshot.entryPath(),
                List.of(),
                foreign,
                List.of(),
                ThreadLiveState.IDLE_OR_HISTORICAL,
                List.of(),
                0,
                false,
                List.of(),
                false));
  }

  // -----------------------------------------------------------------------------------------------
  // 真实 Driver 锁定
  // -----------------------------------------------------------------------------------------------

  private LockedDriverState lockState(UUID threadId, UUID headEntryId) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(threadId).orElseThrow();
          EntryPath path = tx.entries().loadPath(headEntryId);
          return driver.lockState(tx, thread, path);
        });
  }

  // -----------------------------------------------------------------------------------------------
  // fixture
  // -----------------------------------------------------------------------------------------------

  /** 一个 Thread 的 seed 事实：Thread、open turn、请求 basis 与（可选的）模型。 */
  private record Seed(
      UUID sessionId,
      UUID rootEntryId,
      UUID threadId,
      UUID turnStartEntryId,
      UUID requestHeadEntryId,
      UUID modelId,
      UUID nextTurnHeadEntryId) {}

  /** 带 open turn 与模型的 owner Thread。 */
  private Seed seedOwnerThread() {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID requestHeadEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID modelId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, T0));
          tx.entries().insert(rootEntry(rootEntryId, sessionId));
          tx.entries().insert(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, threadId));
          tx.entries().insert(userEntry(requestHeadEntryId, sessionId, turnStartEntryId));
          tx.threads().insert(thread(threadId, sessionId, requestHeadEntryId, "owner"));
          builtin(store, tx)
              .insertModelInvocation(
                  model(modelId, threadId, turnStartEntryId, requestHeadEntryId));
          return new Seed(
              sessionId,
              rootEntryId,
              threadId,
              turnStartEntryId,
              requestHeadEntryId,
              modelId,
              null);
        });
  }

  /** 同一 Session 的第二个 Thread：有自己的 open turn，但没有模型。 */
  private Seed seedOtherThread(UUID sessionId, UUID rootEntryId) {
    return store.transaction(
        tx -> {
          UUID turnStartEntryId = tx.nextId();
          UUID requestHeadEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.entries().insert(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, threadId));
          tx.entries().insert(userEntry(requestHeadEntryId, sessionId, turnStartEntryId));
          tx.threads().insert(thread(threadId, sessionId, requestHeadEntryId, "other"));
          return new Seed(
              sessionId, rootEntryId, threadId, turnStartEntryId, requestHeadEntryId, null, null);
        });
  }

  /** 一个 Thread 的两个 turn：模型挂在已关闭的第一个 turn 上，第二个 turn 才是当前 open turn。 */
  private Seed seedThreadWithClosedTurnModel() {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID firstTurnStartEntryId = tx.nextId();
          UUID firstUserEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID turnEndEntryId = tx.nextId();
          UUID secondTurnStartEntryId = tx.nextId();
          UUID secondUserEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID modelId = tx.nextId();
          tx.sessions().insert(new Session(sessionId, "session-" + sessionId, T0));
          tx.entries().insert(rootEntry(rootEntryId, sessionId));
          tx.entries()
              .insert(turnStartEntry(firstTurnStartEntryId, sessionId, rootEntryId, threadId));
          tx.entries().insert(userEntry(firstUserEntryId, sessionId, firstTurnStartEntryId));
          ThreadState thread = thread(threadId, sessionId, firstUserEntryId, "two-turns");
          tx.threads().insert(thread);
          builtin(store, tx)
              .insertModelInvocation(
                  model(modelId, threadId, firstTurnStartEntryId, firstUserEntryId));
          tx.entries().insert(assistantEntry(assistantEntryId, sessionId, firstUserEntryId));
          tx.entries()
              .insert(
                  turnEndEntry(turnEndEntryId, sessionId, assistantEntryId, firstTurnStartEntryId));
          tx.entries()
              .insert(turnStartEntry(secondTurnStartEntryId, sessionId, turnEndEntryId, threadId));
          tx.entries().insert(userEntry(secondUserEntryId, sessionId, secondTurnStartEntryId));
          tx.threads().update(thread.advanceHead(secondUserEntryId, T1));
          return new Seed(
              sessionId,
              rootEntryId,
              threadId,
              firstTurnStartEntryId,
              firstUserEntryId,
              modelId,
              secondUserEntryId);
        });
  }

  // -----------------------------------------------------------------------------------------------
  // 构造 helper
  // -----------------------------------------------------------------------------------------------

  private static Entry rootEntry(UUID id, UUID sessionId) {
    return new Entry(id, sessionId, null, new RootPayload(settings()), T0);
  }

  private static Entry turnStartEntry(
      UUID id, UUID sessionId, UUID parentEntryId, UUID ownerThreadId) {
    return new Entry(
        id,
        sessionId,
        parentEntryId,
        new TurnStartPayload(
            TurnStartReason.INPUT, settings(), ownerThreadId, 100_000, 16_384, null, null, null),
        T0);
  }

  private static Entry userEntry(UUID id, UUID sessionId, UUID parentEntryId) {
    return new Entry(
        id,
        sessionId,
        parentEntryId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
            null,
            null),
        T0);
  }

  private static Entry assistantEntry(UUID id, UUID sessionId, UUID parentEntryId) {
    return new Entry(
        id,
        sessionId,
        parentEntryId,
        new MessagePayload(
            new AgentMessage(
                AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("assistant reply"))),
            new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage()),
            null),
        T1);
  }

  private static Entry turnEndEntry(
      UUID id, UUID sessionId, UUID parentEntryId, UUID turnStartEntryId) {
    return new Entry(
        id,
        sessionId,
        parentEntryId,
        new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
        T1);
  }

  private static ThreadState thread(UUID id, UUID sessionId, UUID headEntryId, String name) {
    return new ThreadState(
        id,
        sessionId,
        null,
        headEntryId,
        CREATION_REQUEST_HASH,
        name,
        id,
        false,
        SpawnTrigger.USER,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1,
        0,
        T0,
        T0);
  }

  private static ModelInvocation model(
      UUID id, UUID threadId, UUID turnStartEntryId, UUID requestHeadEntryId) {
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        requestHeadEntryId,
        modelRequest(),
        ModelInvocationStatus.READY,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        T1,
        T1);
  }

  private static ModelRequestSpec modelRequest() {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true);
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, Map.of(), 0L, 3L);
  }

  private static BranchSettings settings() {
    return new BranchSettings("agent", ModelSelection.model("model", "v1"), null);
  }
}
