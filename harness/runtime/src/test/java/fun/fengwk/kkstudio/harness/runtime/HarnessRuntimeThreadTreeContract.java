package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.mappedAssistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.modelInvocationWithRequest;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.responseWithToolCalls;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.toolInvocation;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.tooledModelRequest;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadLifecycleStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * getThreadTree：永久 parentThreadId 闭包、当前路径计数和单事务锁序。
 *
 * <p>跨 Session 的父子与同 Session 的无关根必须由 parentThreadId 区分，不能用 Session 成员代替执行树。
 */
public abstract class HarnessRuntimeThreadTreeContract {

  private static final Instant T3 = Instant.ofEpochMilli(4_000);

  private HarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  protected void setUp() {
    store = createStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T3, ZoneOffset.UTC));
  }

  protected abstract HarnessStore createStore();

  @Test
  protected void anyNodeReturnsTheSameParentClosureAndExcludesUnrelatedSessionRoots() {
    // 父、子、孙分属不同 Session；同 Session 的另一个根和更晚创建但 UUID 更小的兄弟必须按永久关系取舍。
    TreeFixture tree = seedCrossSessionTree(store);

    List<ThreadSnapshot> fromGrandchild = runtime.getThreadTree(tree.grandchildId());
    List<ThreadSnapshot> fromChild = runtime.getThreadTree(tree.childId());

    assertEquals(ids(fromGrandchild), ids(fromChild));
    assertEquals(ids(fromGrandchild), ids(runtime.getThreadTree(tree.rootId())));
    assertEquals(
        List.of(tree.rootId(), tree.childId(), tree.grandchildId(), tree.earlySiblingId()),
        ids(fromGrandchild));
    assertTrue(ids(fromGrandchild).stream().noneMatch(id -> id.equals(tree.unrelatedId())));
    assertEquals(List.of(tree.unrelatedId()), ids(runtime.getThreadTree(tree.unrelatedId())));
    // 尚未开始回合的空闲根不等于“已完成”。
    assertNull(runtime.getThreadTree(tree.unrelatedId()).get(0).outcome());

    ThreadSnapshot root = fromGrandchild.get(0);
    ThreadSnapshot child = fromGrandchild.get(1);
    ThreadSnapshot grandchild = fromGrandchild.get(2);
    ThreadSnapshot early = fromGrandchild.get(3);
    assertNull(root.thread().parentThreadId());
    assertEquals(tree.rootId(), early.thread().parentThreadId());
    assertEquals(tree.rootId(), child.thread().parentThreadId());
    assertEquals(tree.childId(), grandchild.thread().parentThreadId());
    assertEquals(ThreadRuntimeStatus.WAITING_CHILDREN, root.runtimeStatus());
    assertEquals(ThreadRuntimeStatus.IDLE, early.runtimeStatus());
    assertEquals(ThreadRuntimeStatus.MODEL_RUNNING, child.runtimeStatus());
    assertEquals(ThreadRuntimeStatus.TOOL_WAITING_APPROVAL, grandchild.runtimeStatus());
    assertEquals(TurnEndOutcome.FAILED, early.outcome());
    assertNull(root.outcome());
    assertNull(child.outcome());
    assertEquals(1, child.turnCount());
    assertEquals(0, child.toolCallCount());
    assertEquals(1, grandchild.turnCount());
    assertEquals(1, grandchild.toolCallCount());
    assertEquals(1, early.turnCount());
    assertEquals(0, early.toolCallCount());
  }

  @Test
  protected void countsIgnoreCompactionAndStopAndResumeKeepsAccumulating() {
    // STOP/COMPACTION 不是模型工作轮；resume 后新的 INPUT 继续累加，离开当前 head 的分支不计入。
    UUID threadId = seedCountedHistory(store);

    ThreadSnapshot snapshot = runtime.getThreadTree(threadId).get(0);

    assertEquals(ThreadRuntimeStatus.IDLE, snapshot.runtimeStatus());
    assertEquals(TurnEndOutcome.COMPLETED, snapshot.outcome());
    assertEquals(3, snapshot.turnCount());
    assertEquals(2, snapshot.toolCallCount());
    assertEquals(0L, snapshot.thread().version());
  }

  @Test
  protected void missingThreadIsNotFoundAndQueryDoesNotWrite() {
    assertThrows(
        HarnessRuntimeNotFoundException.class, () -> runtime.getThreadTree(TestIds.id(999)));
    TreeFixture tree = seedCrossSessionTree(store);
    long version = store.transaction(tx -> tx.findThread(tree.rootId()).orElseThrow().version());

    runtime.getThreadTree(tree.childId());

    long after = store.transaction(tx -> tx.findThread(tree.rootId()).orElseThrow().version());
    assertEquals(version, after);
  }

  @Test
  protected void treeQueryLocksRootThenThreadsByUuidBeforeCommands() {
    // 低 UUID 的后代必须先于高 UUID 的根被锁；全部 Thread 锁必须先于任一 Command 锁。
    TreeFixture tree = seedCrossSessionTree(store);
    runtime.acceptCommands(
        new AcceptCommandsCommand(
            new AcceptCommandsTarget.Thread(tree.earlySiblingId(), tree.earlyHeadId(), 1),
            List.of(userMessageCommand(TestIds.id(90), "queued"))),
        AcceptancePreflight.IDENTITY);
    List<String> locks = new ArrayList<>();

    recordingStore(store, locks).transaction(tx -> null);
    locks.clear();
    HarnessRuntime recording =
        HarnessRuntimeTestSupport.runtime(
            recordingStore(store, locks), Clock.fixed(T3, ZoneOffset.UTC));
    recording.getThreadTree(tree.grandchildId());

    assertEquals("lockTree:" + tree.rootId(), locks.get(0));
    int rootThread = locks.indexOf("lockThread:" + tree.rootId());
    int earlyThread = locks.indexOf("lockThread:" + tree.earlySiblingId());
    int childThread = locks.indexOf("lockThread:" + tree.childId());
    int grandchildThread = locks.indexOf("lockThread:" + tree.grandchildId());
    int command = locks.indexOf("loadQueuedCommands:" + tree.earlySiblingId());
    assertTrue(earlyThread < childThread && childThread < grandchildThread);
    assertTrue(grandchildThread < rootThread);
    assertTrue(rootThread < command);
    assertEquals(
        ThreadRuntimeStatus.QUEUED,
        runtime.getThreadTree(tree.earlySiblingId()).stream()
            .filter(snapshot -> snapshot.thread().id().equals(tree.earlySiblingId()))
            .findFirst()
            .orElseThrow()
            .runtimeStatus());
  }

  private static HarnessStore recordingStore(HarnessStore delegate, List<String> locks) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (!method.getName().equals("transaction")) {
                return method.invoke(delegate, args);
              }
              @SuppressWarnings("unchecked")
              Function<HarnessStore.Transaction, ?> callback =
                  (Function<HarnessStore.Transaction, ?>) args[0];
              return delegate.transaction(
                  tx -> {
                    HarnessStore.Transaction wrapped =
                        (HarnessStore.Transaction)
                            Proxy.newProxyInstance(
                                HarnessStore.Transaction.class.getClassLoader(),
                                new Class<?>[] {HarnessStore.Transaction.class},
                                (transactionProxy, transactionMethod, transactionArgs) -> {
                                  String name = transactionMethod.getName();
                                  if (name.equals("lockTree") || name.equals("lockThread")) {
                                    locks.add(name + ":" + transactionArgs[0]);
                                  }
                                  if (name.equals("loadQueuedCommands")) {
                                    locks.add(name + ":" + transactionArgs[0]);
                                  }
                                  if (name.startsWith("lockModel") || name.startsWith("lockTool")) {
                                    throw new AssertionError(
                                        "tree query must probe invocations: " + name);
                                  }
                                  if (name.startsWith("insert")
                                      || name.startsWith("update")
                                      || name.startsWith("delete")
                                      || name.equals("ensureWork")
                                      || name.equals("nextId")) {
                                    throw new AssertionError("tree query must not write: " + name);
                                  }
                                  return transactionMethod.invoke(tx, transactionArgs);
                                });
                    return callback.apply(wrapped);
                  });
            });
  }

  private static TreeFixture seedCrossSessionTree(HarnessStore store) {
    // 低 UUID 的兄弟后创建，验证顺序是 createdAt 再 UUID，而不是插入顺序。
    UUID rootSession = TestIds.id(1);
    UUID childSession = TestIds.id(2);
    UUID grandchildSession = TestIds.id(3);
    UUID rootId = TestIds.id(40);
    UUID childId = TestIds.id(20);
    UUID grandchildId = TestIds.id(30);
    UUID earlySiblingId = TestIds.id(10);
    UUID unrelatedId = TestIds.id(50);
    UUID rootEntryId =
        store.transaction(
            tx -> {
              UUID rootEntry = insertRoot(tx, rootSession);
              tx.insertThread(
                  thread(
                      rootId,
                      rootSession,
                      null,
                      rootEntry,
                      "root",
                      T0,
                      ThreadLifecycleStatus.WAITING_CHILDREN));
              tx.insertThread(
                  thread(
                      unrelatedId,
                      rootSession,
                      null,
                      rootEntry,
                      "other",
                      T0,
                      ThreadLifecycleStatus.IDLE));
              return rootEntry;
            });
    UUID earlyEndId =
        store.transaction(
            tx -> {
              UUID end = insertFailedTurn(tx, rootSession, rootEntryId, earlySiblingId);
              tx.insertThread(
                  thread(
                      earlySiblingId,
                      rootSession,
                      rootId,
                      end,
                      "early",
                      T2,
                      ThreadLifecycleStatus.IDLE));
              return end;
            });
    // 每次种子事务只写一个后代，避免 Thread/Model/Tool 锁序跨节点倒退。
    store.transaction(tx -> insertRunningModel(tx, childSession, childId, rootId));
    store.transaction(tx -> insertWaitingApproval(tx, grandchildSession, grandchildId, childId));
    return new TreeFixture(rootId, childId, grandchildId, earlySiblingId, unrelatedId, earlyEndId);
  }

  private static UUID insertRoot(HarnessStore.Transaction tx, UUID sessionId) {
    if (tx.findSession(sessionId).isEmpty()) {
      tx.insertSession(session(sessionId));
    }
    UUID rootId = tx.nextId();
    tx.insertEntry(rootEntry(rootId, sessionId));
    return rootId;
  }

  private static UUID insertFailedTurn(
      HarnessStore.Transaction tx, UUID sessionId, UUID rootEntryId, UUID ownerId) {
    UUID startId = tx.nextId();
    UUID userId = tx.nextId();
    UUID errorId = tx.nextId();
    UUID endId = tx.nextId();
    tx.insertEntry(
        turn(startId, sessionId, rootEntryId, T1, TurnStartReason.INPUT, settings(), ownerId));
    tx.insertEntry(user(userId, sessionId, startId, T1));
    tx.insertEntry(error(errorId, sessionId, userId, T2));
    tx.insertEntry(
        new Entry(
            endId,
            sessionId,
            errorId,
            new TurnEndPayload(
                startId, TurnEndOutcome.FAILED, false, TurnEndReason.TURN_FAILED, null),
            T2));
    return endId;
  }

  private static UUID insertRunningModel(
      HarnessStore.Transaction tx, UUID sessionId, UUID threadId, UUID parentId) {
    UUID rootId = insertRoot(tx, sessionId);
    UUID startId = tx.nextId();
    tx.insertEntry(turnStartEntry(startId, sessionId, rootId, T1, threadId));
    tx.insertThread(
        thread(threadId, sessionId, parentId, startId, "child", T1, ThreadLifecycleStatus.ACTIVE));
    UUID modelId = tx.nextId();
    ModelInvocation model = modelInvocation(modelId, threadId, startId, startId, T1);
    tx.insertModelInvocation(model);
    tx.updateModelInvocation(model.beginDispatch(T2));
    tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
    return startId;
  }

  private static UUID insertWaitingApproval(
      HarnessStore.Transaction tx, UUID sessionId, UUID threadId, UUID parentId) {
    UUID rootId = insertRoot(tx, sessionId);
    UUID startId = tx.nextId();
    UUID userId = tx.nextId();
    UUID assistantId = tx.nextId();
    tx.insertEntry(turnStartEntry(startId, sessionId, rootId, T1, threadId));
    tx.insertEntry(userMessageEntry(userId, sessionId, startId, T1));
    ModelRequestSpec request = tooledModelRequest(List.of("bash"));
    ProviderResponse response = responseWithToolCalls("call-1");
    tx.insertEntry(mappedAssistantEntry(assistantId, sessionId, userId, T1, request, response));
    ThreadState thread =
        thread(threadId, sessionId, parentId, startId, "grand", T1, ThreadLifecycleStatus.ACTIVE);
    tx.insertThread(thread);
    UUID modelId = tx.nextId();
    ModelInvocation model =
        modelInvocationWithRequest(modelId, threadId, startId, startId, request, T1);
    tx.insertModelInvocation(model);
    ModelInvocation succeeded = model.beginDispatch(T2).markRunning(T2).succeed(response, T2);
    tx.updateModelInvocation(model.beginDispatch(T2));
    tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
    tx.updateModelInvocation(succeeded);
    tx.updateModelInvocation(succeeded.attachResultEntry(assistantId, T2));
    UUID toolId = tx.nextId();
    ToolInvocation ready = toolInvocation(toolId, modelId, assistantId, 0, "call-1", T1);
    tx.insertToolInvocations(List.of(ready));
    tx.updateToolInvocations(List.of(ready.requestApproval("need approval", T2)));
    tx.updateThread(thread.advanceHead(assistantId, T2));
    return assistantId;
  }

  private static UUID seedCountedHistory(HarnessStore store) {
    UUID sessionId = TestIds.id(1);
    UUID threadId = TestIds.id(2);
    UUID rootId = TestIds.id(3);
    UUID inputStart = TestIds.id(4);
    UUID userId = TestIds.id(5);
    UUID assistantId = TestIds.id(6);
    UUID firstResult = TestIds.id(23);
    UUID secondResult = TestIds.id(24);
    UUID inputEnd = TestIds.id(7);
    UUID abandonedStart = TestIds.id(8);
    UUID continuationStart = TestIds.id(9);
    UUID continuationAssistant = TestIds.id(10);
    UUID continuationEnd = TestIds.id(11);
    UUID compactionStart = TestIds.id(12);
    UUID compactionBody = TestIds.id(13);
    UUID compactionEnd = TestIds.id(14);
    UUID stopStart = TestIds.id(15);
    UUID stopError = TestIds.id(16);
    UUID stopEnd = TestIds.id(17);
    UUID resumeStart = TestIds.id(18);
    UUID resumeUser = TestIds.id(19);
    UUID resumeAssistant = TestIds.id(25);
    UUID resumeEnd = TestIds.id(21);
    store.transaction(
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootId, sessionId));
          tx.insertEntry(
              turn(inputStart, sessionId, rootId, T1, TurnStartReason.INPUT, settings(), threadId));
          tx.insertEntry(user(userId, sessionId, inputStart, T1));
          tx.insertEntry(assistantEntry(assistantId, sessionId, userId, T1, "call-1", "call-2"));
          tx.insertEntry(toolResult(firstResult, sessionId, assistantId, assistantId, 0, "call-1"));
          tx.insertEntry(
              toolResult(secondResult, sessionId, firstResult, assistantId, 1, "call-2"));
          tx.insertEntry(
              new Entry(
                  inputEnd,
                  sessionId,
                  secondResult,
                  new TurnEndPayload(inputStart, TurnEndOutcome.COMPLETED, false, null, null),
                  T1));
          tx.insertEntry(
              turn(
                  abandonedStart,
                  sessionId,
                  inputEnd,
                  T1,
                  TurnStartReason.INPUT,
                  settings(),
                  threadId));
          tx.insertEntry(
              turn(
                  continuationStart,
                  sessionId,
                  inputEnd,
                  T1,
                  TurnStartReason.CONTINUATION,
                  settings(),
                  threadId));
          tx.insertEntry(assistantEntry(continuationAssistant, sessionId, continuationStart, T2));
          tx.insertEntry(
              new Entry(
                  continuationEnd,
                  sessionId,
                  continuationAssistant,
                  new TurnEndPayload(
                      continuationStart, TurnEndOutcome.COMPLETED, false, null, null),
                  T2));
          tx.insertEntry(
              compactionTurn(compactionStart, sessionId, continuationEnd, T2, threadId, userId));
          tx.insertEntry(
              new Entry(
                  compactionBody,
                  sessionId,
                  compactionStart,
                  new CompactionPayload("summary"),
                  T2));
          tx.insertEntry(
              new Entry(
                  compactionEnd,
                  sessionId,
                  compactionBody,
                  new TurnEndPayload(compactionStart, TurnEndOutcome.COMPLETED, false, null, null),
                  T2));
          tx.insertEntry(
              turn(
                  stopStart,
                  sessionId,
                  compactionEnd,
                  T2,
                  TurnStartReason.STOP,
                  settings(),
                  threadId));
          tx.insertEntry(
              new Entry(
                  stopError,
                  sessionId,
                  stopStart,
                  new AssistantErrorPayload(
                      new AssistantError(AssistantError.CANCELLED_CODE, "cancelled"), null),
                  T2));
          tx.insertEntry(
              new Entry(
                  stopEnd,
                  sessionId,
                  stopError,
                  new TurnEndPayload(
                      stopStart,
                      TurnEndOutcome.STOPPED,
                      false,
                      TurnEndReason.USER_STOP,
                      TestIds.id(20)),
                  T2));
          tx.insertEntry(
              turn(
                  resumeStart,
                  sessionId,
                  stopEnd,
                  T2,
                  TurnStartReason.INPUT,
                  settings(),
                  threadId));
          tx.insertEntry(user(resumeUser, sessionId, resumeStart, T2));
          tx.insertEntry(assistantEntry(resumeAssistant, sessionId, resumeUser, T2));
          tx.insertEntry(
              new Entry(
                  resumeEnd,
                  sessionId,
                  resumeAssistant,
                  new TurnEndPayload(resumeStart, TurnEndOutcome.COMPLETED, false, null, null),
                  T3));
          tx.insertThread(idle(threadId, sessionId, null, resumeEnd, "counted", T0));
          return null;
        });
    return threadId;
  }

  private static Entry error(UUID id, UUID sessionId, UUID parentId, Instant createdAt) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new AssistantErrorPayload(new AssistantError("TURN_FAILED", "failed"), null),
        createdAt);
  }

  private static Entry toolResult(
      UUID id, UUID sessionId, UUID parentId, UUID assistantId, int callIndex, String toolCallId) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            toolCallId, "bash", "bash", List.of(new TextMessageContent("ok")), false, "{}");
    return new Entry(
        id,
        sessionId,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.TOOL, List.of(content)),
            null,
            new ToolResultMetadata(
                TestIds.id(100 + callIndex),
                assistantId,
                toolCallId,
                callIndex,
                ToolResultStatus.SUCCEEDED,
                false,
                null,
                null)),
        T1);
  }

  private static Entry compactionTurn(
      UUID id,
      UUID sessionId,
      UUID parentId,
      Instant createdAt,
      UUID ownerThreadId,
      UUID cutEntryId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(
            TurnStartReason.COMPACTION,
            settings(),
            ownerThreadId,
            100_000,
            16_384,
            new CompactionStart(
                CompactionPhase.FULL,
                CompactionTrigger.MANUAL,
                settings().model(),
                cutEntryId,
                null,
                null)),
        createdAt);
  }

  private static Entry turn(
      UUID id,
      UUID sessionId,
      UUID parentId,
      Instant createdAt,
      TurnStartReason reason,
      BranchSettings branchSettings,
      UUID ownerThreadId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(reason, branchSettings, ownerThreadId, 100_000, 16_384, null),
        createdAt);
  }

  private static Entry user(UUID id, UUID sessionId, UUID parentId, Instant createdAt) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
            null,
            null),
        createdAt);
  }

  private static ThreadState idle(
      UUID id, UUID sessionId, UUID parentId, UUID headId, String name, Instant createdAt) {
    return thread(id, sessionId, parentId, headId, name, createdAt, ThreadLifecycleStatus.IDLE);
  }

  private static ThreadState thread(
      UUID id,
      UUID sessionId,
      UUID parentId,
      UUID headId,
      String name,
      Instant createdAt,
      ThreadLifecycleStatus status) {
    return new ThreadState(
        id,
        sessionId,
        parentId,
        headId,
        CREATION_REQUEST_HASH,
        name,
        false,
        status,
        1,
        0,
        createdAt,
        createdAt);
  }

  private static List<UUID> ids(List<ThreadSnapshot> snapshots) {
    return snapshots.stream().map(snapshot -> snapshot.thread().id()).toList();
  }

  private record TreeFixture(
      UUID rootId,
      UUID childId,
      UUID grandchildId,
      UUID earlySiblingId,
      UUID unrelatedId,
      UUID earlyHeadId) {}
}
