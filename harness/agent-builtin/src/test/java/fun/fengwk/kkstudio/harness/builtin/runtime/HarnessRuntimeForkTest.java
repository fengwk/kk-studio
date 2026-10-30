package fun.fengwk.kkstudio.harness.builtin.runtime;

import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.modelError;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.modelInvocation;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.runtime;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.session;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.thread;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.turnEndEntry;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.builtin.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.loadCommandsByThread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.builtin.store.testing.InMemoryBuiltinHarnessStore;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builtin fork 契约：分支 fork 必须接受 Builtin SET_MODEL，会话 fork 不复制 Builtin 私有模型状态。
 *
 * <p>纯 kernel 的 fork 断言保留在 runtime {@code HarnessRuntimeForkTest}；本测试只补充必须依赖真实 Builtin driver 与
 * InMemoryBuiltinHarnessStore 的模型语义。
 */
class HarnessRuntimeForkTest {

  private InMemoryBuiltinHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryBuiltinHarnessStore();
    // 该 helper 使用 BuiltinAgentDriver；内存 store 同时实现 BuiltinStore。
    runtime = runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  /** fork 初始 batch 的 SET_MODEL 以原子模型整体落盘，fork 来源的 Builtin 模型切换保持可用。 */
  @Test
  void forkAcceptsBuiltinModelSwitch() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);

    UUID acceptedThreadId = TestIds.id(983);
    ModelSelection switched = ModelSelection.model("model-2", "v2");
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewThread(
                    baseline.sessionId(),
                    baseline.rootEntryId(),
                    acceptedThreadId,
                    "branch",
                    false),
                List.of(
                    new NewThreadCommand(new SetModelCommandPayload(switched), TestIds.id(984)),
                    userMessageCommand(TestIds.id(985), "hello"))),
            AcceptancePreflight.IDENTITY);
    assertEquals(acceptedThreadId, accepted.thread().id());
    List<ThreadCommand> commands =
        store.transaction(tx -> loadCommandsByThread(tx, acceptedThreadId));
    SetModelCommandPayload queued =
        assertInstanceOf(SetModelCommandPayload.class, commands.getFirst().payload());
    assertEquals(switched, queued.model());
  }

  /** 无压缩时复制 ROOT 之后的完整历史：新身份、链条连续、tool pair 保留，且不携带来源的 Builtin 模型状态。 */
  @Test
  void sessionForkCopiesFullHistoryAndRetainsToolPairs() {
    HistorySeed source = seedHistoryWithModel(store);
    // 证明来源会话确实包含私有 ModelInvocation，而不是用一个无模型源断言 empty。
    assertTrue(hasModelInvocation(source.threadId(), source.firstTurnStartEntryId()));
    UUID newSessionId = TestIds.id(950);
    UUID newThreadId = TestIds.id(951);
    AcceptedCommands accepted =
        acceptSessionFork(source.threadId(), source.lastEntryId(), newSessionId, newThreadId);

    assertFalse(accepted.replayed());
    assertEquals(newSessionId, accepted.session().id());
    assertEquals(newSessionId, accepted.thread().sessionId());
    assertNull(accepted.thread().parentThreadId());

    EntryPath sourcePath = store.transaction(tx -> tx.entries().loadPath(source.lastEntryId()));
    EntryPath path =
        store.transaction(tx -> tx.entries().loadPath(accepted.thread().headEntryId()));
    List<Entry> entries = path.entries();
    // ROOT + 完整来源历史（不含来源 ROOT）+ FORK。
    assertEquals(sourcePath.entries().size() + 1, entries.size());
    assertInstanceOf(ForkPayload.class, entries.getLast().payload());
    assertEquals(ForkPayload.NOTICE, projectedTexts(path).getLast());

    List<UUID> sourceIds = sourcePath.entries().stream().map(Entry::id).toList();
    for (Entry copied : entries.subList(1, entries.size() - 1)) {
      assertFalse(sourceIds.contains(copied.id()));
      assertEquals(newSessionId, copied.sessionId());
    }
    // tool pair 保留：复制后的 tool result 引用复制后的 assistant Entry，绝不复用旧身份。
    Entry copiedAssistant = singleRole(entries, AgentMessageRole.ASSISTANT);
    Entry copiedToolResult = singleRole(entries, AgentMessageRole.TOOL);
    ToolResultMetadata metadata =
        ((MessagePayload) copiedToolResult.payload()).toolResultMetadata();
    assertEquals(copiedAssistant.id(), metadata.assistantEntryId());
    assertNotNull(metadata.binding());
    assertEquals(
        ((MessagePayload) singleRole(sourcePath.entries(), AgentMessageRole.TOOL).payload())
            .toolResultMetadata()
            .binding(),
        metadata.binding());
    assertNotEquals(source.assistantEntryId(), copiedAssistant.id());
    // 复制不携带任何活跃 invocation / command / join：新 Thread 上只有本批初始命令。
    assertEquals(
        List.of(1L),
        store.transaction(
            tx ->
                loadCommandsByThread(tx, newThreadId).stream()
                    .map(command -> command.sequence())
                    .toList()));
    // 来源模型状态仍留在原 Session；新 Thread/复制 Turn 上没有任何 Builtin 私有模型行。
    assertTrue(hasModelInvocation(source.threadId(), source.firstTurnStartEntryId()));
    assertFalse(hasModelInvocation(newThreadId, entries.get(1).id()));
  }

  private AcceptedCommands acceptSessionFork(
      UUID sourceThreadId, UUID cutEntryId, UUID newSessionId, UUID newThreadId) {
    return runtime.acceptCommands(
        sessionForkCommand(sourceThreadId, cutEntryId, newSessionId, newThreadId),
        AcceptancePreflight.IDENTITY);
  }

  private static AcceptCommandsCommand sessionForkCommand(
      UUID sourceThreadId, UUID cutEntryId, UUID newSessionId, UUID newThreadId) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewForkedSession(
            sourceThreadId, cutEntryId, newSessionId, newThreadId, false),
        List.of(userMessageCommand(TestIds.id(952), "fork")));
  }

  private boolean hasModelInvocation(UUID threadId, UUID turnStartEntryId) {
    return store.transaction(
        tx -> builtin(tx).findModelInvocationByTurn(threadId, turnStartEntryId).isPresent());
  }

  private static Entry singleRole(List<Entry> entries, AgentMessageRole role) {
    return entries.stream()
        .filter(
            entry ->
                entry.payload() instanceof MessagePayload message
                    && message.message().role() == role)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> projectedTexts(EntryPath path) {
    ProviderRequest request =
        new ModelRequestMaterializer().materialize(path, HarnessRuntimeTestSupport.modelRequest());
    List<String> texts = new ArrayList<>();
    for (ProviderMessage message : request.messages()) {
      texts.add(textOf(message));
    }
    return texts;
  }

  private static String textOf(ProviderMessage message) {
    StringBuilder text = new StringBuilder();
    for (var block : message.contents()) {
      text.append(((ProviderTextBlock) block).text());
    }
    return text.toString();
  }

  private static Entry userEntry(
      UUID id, UUID sessionId, UUID parentId, Instant createdAt, String text) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))),
            null,
            null),
        createdAt);
  }

  private static Entry toolResultEntry(
      UUID id,
      UUID sessionId,
      UUID parentId,
      UUID assistantId,
      int callIndex,
      String toolCallId,
      Instant createdAt) {
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
                TestIds.id(500 + callIndex),
                assistantId,
                toolCallId,
                callIndex,
                ToolResultStatus.SUCCEEDED,
                false,
                null,
                null,
                HarnessRuntimeTestSupport.tooledModelRequest(List.of("bash"))
                    .toolBindings()
                    .get(0))),
        createdAt);
  }

  /**
   * ROOT -&gt; [INPUT u1, A1(tool call), TOOL result, END] -&gt; [INPUT u2, A2, END]；head =
   * END2，并在来源 Session 的第一轮挂一个 CANCELLED ModelInvocation。
   */
  private static HistorySeed seedHistoryWithModel(InMemoryBuiltinHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID root = tx.nextId();
          UUID ts1 = tx.nextId();
          UUID u1 = tx.nextId();
          UUID a1 = tx.nextId();
          UUID tr1 = tx.nextId();
          UUID te1 = tx.nextId();
          UUID ts2 = tx.nextId();
          UUID u2 = tx.nextId();
          UUID a2 = tx.nextId();
          UUID te2 = tx.nextId();
          UUID modelId = tx.nextId();
          tx.sessions().insert(session(sessionId));
          tx.entries().insert(rootEntry(root, sessionId));
          tx.entries().insert(turnStartEntry(ts1, sessionId, root, T1, threadId));
          tx.entries().insert(userEntry(u1, sessionId, ts1, T1, "u1"));
          tx.entries().insert(assistantEntry(a1, sessionId, u1, T1, "call-1"));
          tx.entries().insert(toolResultEntry(tr1, sessionId, a1, a1, 0, "call-1", T1));
          tx.entries().insert(turnEndEntry(te1, sessionId, tr1, T1, ts1, false));
          tx.entries().insert(turnStartEntry(ts2, sessionId, te1, T2, threadId));
          tx.entries().insert(userEntry(u2, sessionId, ts2, T2, "u2"));
          tx.entries().insert(assistantEntry(a2, sessionId, u2, T2));
          tx.entries().insert(turnEndEntry(te2, sessionId, a2, T2, ts2, false));
          ThreadState initialThread = thread(threadId, sessionId, u1);
          tx.threads().insert(initialThread);
          ModelInvocation model = modelInvocation(modelId, threadId, ts1, u1, T1);
          builtin(tx).insertModelInvocation(model);
          builtin(tx).updateModelInvocation(model.cancel(modelError(), T2));
          tx.threads().update(initialThread.advanceHead(te2, T2));
          return new HistorySeed(sessionId, threadId, root, ts1, a1, te1, te2);
        });
  }

  private record HistorySeed(
      UUID sessionId,
      UUID threadId,
      UUID rootEntryId,
      UUID firstTurnStartEntryId,
      UUID assistantEntryId,
      UUID firstTurnEndId,
      UUID lastEntryId) {}
}
