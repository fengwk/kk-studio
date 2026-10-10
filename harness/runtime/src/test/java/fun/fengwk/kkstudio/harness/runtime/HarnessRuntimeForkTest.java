package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T3;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.assistantEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.rootEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.seedOpenTurn;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.session;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.settings;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.thread;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnEndEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.turnStartEntry;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessageCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTurns;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CompactionPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.ForkMode;
import fun.fengwk.kkstudio.harness.runtime.history.ForkPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestMaterializer;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadYoloPolicy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * history-only fork 契约。
 *
 * <p>NEW_THREAD 分支 fork：只追加 FORK 事实节点（parent 为共享前缀边界），模型上下文投影固定通知，绝不复制前缀。
 *
 * <p>NEW_FORKED_SESSION 会话 fork：把来源执行根在合法切点处的有效上下文复制到新 Session（有 complete 压缩时只复制压缩切点所属完整 Turn
 * 起的保留尾部，摘要由复制过去的 COMPACTION Entry 重建），复制范围外的压缩内部引用安全降级为 null；来源不是执行根、切点不在来源 head 路径、切点半轮、切点跨
 * Session、来源 Session 不存在都必须零写入拒绝；同 raw 请求精确 replay 幂等。
 */
class HarnessRuntimeForkTest {

  private InMemoryHarnessStore store;
  private HarnessRuntime runtime;

  @BeforeEach
  void setUp() {
    store = new InMemoryHarnessStore();
    runtime = HarnessRuntimeTestSupport.runtime(store, Clock.fixed(T0, ZoneOffset.UTC));
  }

  // ---------------------------------------------------------------- 分支 fork

  /** 分支 fork 的 head 事实是 FORK Entry，模型上下文投影固定通知，且不复制任何共享前缀。 */
  @Test
  void branchForkAppendsForkFactAndProjectsFixedNotice() {
    HarnessRuntimeTestSupport.Baseline baseline = seedBaseline(store);
    UUID threadId = TestIds.id(900);
    AcceptedCommands accepted =
        runtime.acceptCommands(
            new AcceptCommandsCommand(
                new AcceptCommandsTarget.NewThread(
                    baseline.sessionId(), baseline.rootEntryId(), threadId, "branch", false),
                List.of(userMessageCommand(TestIds.id(1), "hello"))),
            AcceptancePreflight.IDENTITY);

    assertFalse(accepted.replayed());
    assertEquals(baseline.sessionId(), accepted.session().id());
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(accepted.thread().headEntryId()));
    assertEquals(2, path.entries().size());
    Entry fork = path.head();
    ForkPayload payload = assertInstanceOf(ForkPayload.class, fork.payload());
    assertEquals(ForkMode.BRANCH, payload.mode());
    assertEquals(baseline.rootEntryId(), payload.sourceEntryId());
    assertNull(payload.sourceThreadId());
    assertEquals(baseline.rootEntryId(), fork.parentEntryId());
    // head 保持 FORK：初始 batch 只入队，不物化新 Entry。
    assertEquals(fork.id(), accepted.thread().headEntryId());
    assertEquals(List.of(ForkPayload.NOTICE), projectedTexts(path));
  }

  /** 分支 fork 的切点只接受 ROOT 或已闭合 TURN_END；半轮前缀必须零写入拒绝。 */
  @Test
  void branchForkRejectsNonBoundaryCutWithZeroWrites() {
    HarnessRuntimeTestSupport.TurnBaseline openTurn = seedOpenTurn(store);
    UUID threadId = TestIds.id(901);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                runtime.acceptCommands(
                    new AcceptCommandsCommand(
                        new AcceptCommandsTarget.NewThread(
                            openTurn.sessionId(),
                            openTurn.turnStartEntryId(),
                            threadId,
                            "branch",
                            false),
                        List.of(userMessageCommand(TestIds.id(2), "hello"))),
                    AcceptancePreflight.IDENTITY));

    assertTrue(error.getMessage().contains("not a legal NEW_THREAD fork boundary"));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(threadId).isEmpty()));
  }

  // ---------------------------------------------------------------- 会话 fork：完整历史

  /** 无压缩时复制 ROOT 之后的完整历史：新身份、链条连续、tool pair 保留。 */
  @Test
  void sessionForkCopiesFullHistoryAndRetainsToolPairs() {
    HistorySeed source = seedHistory(store, false);
    UUID newSessionId = TestIds.id(950);
    UUID newThreadId = TestIds.id(951);
    AcceptedCommands accepted =
        acceptSessionFork(source.threadId(), source.lastEntryId(), newSessionId, newThreadId);

    assertFalse(accepted.replayed());
    assertEquals(newSessionId, accepted.session().id());
    assertEquals(newSessionId, accepted.thread().sessionId());
    assertNull(accepted.thread().parentThreadId());

    EntryPath sourcePath = store.transaction(tx -> tx.loadEntryPath(source.lastEntryId()));
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(accepted.thread().headEntryId()));
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
    assertNotEquals(source.assistantEntryId(), copiedAssistant.id());
    // 复制不携带任何活跃 invocation / command / join：新 Thread 上只有本批初始命令。
    assertEquals(
        List.of(1L),
        store.transaction(
            tx ->
                tx.loadCommandsByThread(newThreadId).stream()
                    .map(command -> command.sequence())
                    .toList()));
    assertTrue(
        store
            .<Boolean>transaction(
                tx -> tx.findModelInvocationByTurn(newThreadId, entries.get(1).id()).isEmpty())
            .equals(Boolean.TRUE));
  }

  /**
   * providerReplayState 是隔离的 compat opaque 事实：复制时原样保留（不作为活跃 provider 会话续接），且复制后的 Entry 使用新身份。 同时新
   * Session 只承载新的执行根，不克隆任何子执行树。
   */
  @Test
  void sessionForkPreservesProviderReplayStateAsOpaqueFact() {
    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            new ProviderReplayAffinity(
                ProviderType.ANTHROPIC, "anthropic", TestIds.id(700), "claude"),
            JsonNodeFactory.instance.objectNode().put("k", "v"));
    UUID sessionId = TestIds.id(930);
    UUID threadId = TestIds.id(931);
    UUID root = TestIds.id(932);
    UUID turnStart = TestIds.id(933);
    UUID user = TestIds.id(934);
    UUID assistant = TestIds.id(935);
    UUID turnEnd = TestIds.id(936);
    store.transaction(
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(root, sessionId));
          tx.insertEntry(turnStartEntry(turnStart, sessionId, root, T1, threadId));
          tx.insertEntry(userEntry(user, sessionId, turnStart, T1, "u"));
          tx.insertEntry(
              new Entry(
                  assistant,
                  sessionId,
                  user,
                  assistantEntry(TestIds.id(937), sessionId, user, T1).payload(),
                  T1,
                  replayState));
          tx.insertEntry(turnEndEntry(turnEnd, sessionId, assistant, T1, turnStart, false));
          tx.insertThread(thread(threadId, sessionId, turnEnd));
          return null;
        });
    UUID newSessionId = TestIds.id(938);
    UUID newThreadId = TestIds.id(939);
    AcceptedCommands accepted = acceptSessionFork(threadId, turnEnd, newSessionId, newThreadId);
    assertFalse(accepted.replayed());

    EntryPath path = store.transaction(tx -> tx.loadEntryPath(accepted.thread().headEntryId()));
    Entry copiedAssistant =
        path.entries().stream()
            .filter(
                entry ->
                    entry.payload() instanceof MessagePayload message
                        && message.message().role() == AgentMessageRole.ASSISTANT)
            .findFirst()
            .orElseThrow();
    assertEquals(replayState, copiedAssistant.providerReplayState());
    assertNotEquals(assistant, copiedAssistant.id());
    // 只承载新的执行根，不克隆子执行树。
    assertEquals(1, store.transaction(tx -> tx.listThreadsBySession(newSessionId)).size());
  }

  // ---------------------------------------------------------------- 会话 fork：压缩保留尾部
  /** 存在 complete 压缩时只复制压缩切点所属完整 Turn 起的保留尾部，摘要由复制的 COMPACTION Entry 重建。 */
  @Test
  void sessionForkCopiesOnlyCompactionTailAndProjectsSummary() {
    CompactionSeed source = seedCompactedHistory(store);
    UUID newSessionId = TestIds.id(970);
    UUID newThreadId = TestIds.id(971);
    AcceptedCommands accepted =
        acceptSessionFork(source.threadId(), source.lastEntryId(), newSessionId, newThreadId);

    EntryPath sourcePath = store.transaction(tx -> tx.loadEntryPath(source.lastEntryId()));
    EntryPath path = store.transaction(tx -> tx.loadEntryPath(accepted.thread().headEntryId()));
    List<Entry> entries = path.entries();
    int retainedStartIndex = indexOf(sourcePath, source.retainedStartEntryId());
    // ROOT + 保留尾部（含压缩 turn）+ FORK：压缩切点之前的 Turn 1 不复制。
    assertEquals(sourcePath.entries().size() - retainedStartIndex + 2, entries.size());
    TurnStartPayload firstTurnStart =
        assertInstanceOf(TurnStartPayload.class, entries.get(1).payload());
    assertEquals(TurnStartReason.INPUT, firstTurnStart.reason());
    assertFalse(projectedTexts(path).contains("u1"), projectedTexts(path).toString());

    CompactionTurns.CompactionTurn complete = CompactionTurns.latestComplete(path).orElseThrow();
    assertEquals("summary", complete.result().summaryText());
    assertTrue(
        entries.stream().anyMatch(entry -> entry.id().equals(complete.freezing().cutEntryId())));

    List<String> texts = projectedTexts(path);
    assertTrue(texts.getFirst().contains("summary"), texts.toString());
    assertTrue(texts.contains("u2"));
    assertTrue(texts.contains("u3"));
    assertEquals(ForkPayload.NOTICE, texts.getLast());
  }

  /**
   * 保留尾部切点之外的压缩内部引用必须安全降级为 null：TURN_PREFIX 精确引用的 HISTORY 结果 Entry 不在复制范围内时 {@code
   * historyCompactionEntryId} 置 null（TURN_PREFIX 的合法取值），路径仍可加载与投影，绝不留悬空引用，也不拼接半轮。
   */
  @Test
  void sessionForkNullsCompactionReferencesOutsideCopiedRange() {
    CompactionSeed source = seedSplitCompactionHistory(store);
    UUID newSessionId = TestIds.id(980);
    UUID newThreadId = TestIds.id(981);
    AcceptedCommands accepted =
        acceptSessionFork(source.threadId(), source.lastEntryId(), newSessionId, newThreadId);

    EntryPath path = store.transaction(tx -> tx.loadEntryPath(accepted.thread().headEntryId()));
    List<CompactionStart> copiedStarts =
        path.entries().stream()
            .filter(
                entry ->
                    entry.payload() instanceof TurnStartPayload start && start.compaction() != null)
            .map(entry -> ((TurnStartPayload) entry.payload()).compaction())
            .toList();
    assertEquals(1, copiedStarts.size());
    CompactionStart copiedStart = copiedStarts.getFirst();
    assertEquals(CompactionPhase.TURN_PREFIX, copiedStart.phase());
    assertNull(copiedStart.historyCompactionEntryId());
    assertTrue(
        path.entries().stream().anyMatch(entry -> entry.id().equals(copiedStart.cutEntryId())));
    assertTrue(
        path.entries().stream()
            .anyMatch(entry -> entry.id().equals(copiedStart.turnPrefixStartEntryId())));
    // 复制的每个 TURN_START 都在复制范围内闭合：绝不留下半轮。
    assertEquals(
        0,
        countUnclosedTurns(path.entries()),
        "copied history must only contain whole closed turns");
  }

  // ---------------------------------------------------------------- 会话 fork：来源校验

  /** 来源必须是执行根：子 Thread 作为来源必须零写入拒绝。 */
  @Test
  void sessionForkRejectsNonExecutionRootSourceWithZeroWrites() {
    UUID parentThreadId = TestIds.id(990);
    UUID childThreadId = TestIds.id(991);
    UUID sessionId = TestIds.id(992);
    UUID sharedRootEntryId = TestIds.id(993);
    store.transaction(
        tx -> {
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(sharedRootEntryId, sessionId));
          tx.insertThread(thread(parentThreadId, sessionId, sharedRootEntryId));
          tx.insertThread(
              new ThreadState(
                  childThreadId,
                  sessionId,
                  parentThreadId,
                  sharedRootEntryId,
                  HarnessRuntimeTestSupport.CREATION_REQUEST_HASH,
                  "child",
                  ThreadYoloPolicy.follow(parentThreadId),
                  ThreadExecutionControl.RUNNABLE,
                  0L,
                  1L,
                  0,
                  T0,
                  T0));
          return null;
        });
    UUID newSessionId = TestIds.id(994);
    UUID newThreadId = TestIds.id(995);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> acceptSessionFork(childThreadId, sharedRootEntryId, newSessionId, newThreadId));

    assertTrue(error.getMessage().contains("must be an execution root"));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(newSessionId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(newThreadId).isEmpty()));
  }

  /** 切点必须位于来源 Thread 的当前 head 路径上：sibling 分支上的 Entry 必须零写入拒绝。 */
  @Test
  void sessionForkRejectsCutOffSourcePathWithZeroWrites() {
    HistorySeed source = seedHistory(store, false);
    // 在同一 Session 上从 Turn 1 的 END 另开一条闭合分支：切到它就不在来源 head 路径上。
    UUID siblingTurnEnd =
        store.transaction(
            tx -> {
              UUID turnStart = tx.nextId();
              UUID user = tx.nextId();
              UUID assistant = tx.nextId();
              UUID end = tx.nextId();
              tx.insertEntry(
                  turnStartEntry(
                      turnStart,
                      source.sessionId(),
                      source.firstTurnEndId(),
                      T3,
                      source.threadId()));
              tx.insertEntry(userEntry(user, source.sessionId(), turnStart, T3, "sibling"));
              tx.insertEntry(assistantEntry(assistant, source.sessionId(), user, T3));
              tx.insertEntry(
                  turnEndEntry(end, source.sessionId(), assistant, T3, turnStart, false));
              return end;
            });
    UUID newSessionId = TestIds.id(996);
    UUID newThreadId = TestIds.id(997);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> acceptSessionFork(source.threadId(), siblingTurnEnd, newSessionId, newThreadId));

    assertTrue(error.getMessage().contains("is not on the source thread path"));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(newSessionId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(newThreadId).isEmpty()));
  }

  /** 切点必须是 ROOT 或已闭合 TURN_END：半轮前缀必须零写入拒绝。 */
  @Test
  void sessionForkRejectsIllegalBoundaryWithZeroWrites() {
    HistorySeed source = seedHistory(store, true);
    UUID newSessionId = TestIds.id(998);
    UUID newThreadId = TestIds.id(999);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                acceptSessionFork(
                    source.threadId(), source.assistantEntryId(), newSessionId, newThreadId));

    assertTrue(error.getMessage().contains("not a legal NEW_FORKED_SESSION fork boundary"));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(newSessionId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(newThreadId).isEmpty()));
  }

  /** 切点必须属于来源 Session：别的 Session 的 Entry 必须拒绝。 */
  @Test
  void sessionForkRejectsCutFromAnotherSession() {
    HistorySeed source = seedHistory(store, false);
    HarnessRuntimeTestSupport.Baseline other = seedBaseline(store);
    UUID newSessionId = TestIds.id(960);
    UUID newThreadId = TestIds.id(961);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                acceptSessionFork(
                    source.threadId(), other.rootEntryId(), newSessionId, newThreadId));

    assertTrue(error.getMessage().contains("is not in the source session"));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(newSessionId).isEmpty()));
  }

  /** 来源 Thread 不存在：零写入拒绝。 */
  @Test
  void sessionForkRejectsMissingSourceThread() {
    UUID newSessionId = TestIds.id(962);
    UUID newThreadId = TestIds.id(963);
    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> acceptSessionFork(TestIds.id(964), TestIds.id(965), newSessionId, newThreadId));
    assertTrue(store.<Boolean>transaction(tx -> tx.findSession(newSessionId).isEmpty()));
    assertTrue(store.<Boolean>transaction(tx -> tx.findThread(newThreadId).isEmpty()));
  }

  /** 从 ROOT 切点的会话 fork：有效上下文只有新建 ROOT，FORK Entry 直接挂在 ROOT 上，模型上下文只有固定通知。 */
  @Test
  void sessionForkFromRootProducesRootForkOnly() {
    HistorySeed source = seedHistory(store, false);
    UUID newSessionId = TestIds.id(940);
    UUID newThreadId = TestIds.id(941);
    AcceptedCommands accepted =
        acceptSessionFork(source.threadId(), source.rootEntryId(), newSessionId, newThreadId);

    EntryPath path = store.transaction(tx -> tx.loadEntryPath(accepted.thread().headEntryId()));
    assertEquals(2, path.entries().size());
    ForkPayload payload = assertInstanceOf(ForkPayload.class, path.head().payload());
    assertEquals(ForkMode.SESSION, payload.mode());
    assertEquals(source.threadId(), payload.sourceThreadId());
    assertEquals(source.rootEntryId(), payload.sourceEntryId());
    assertEquals(List.of(ForkPayload.NOTICE), projectedTexts(path));
  }

  // ---------------------------------------------------------------- 会话 fork：幂等

  /** 同 raw 请求精确 replay 不重复复制；内容变化映射为 ID reuse 冲突且零新增行。 */
  @Test
  void sessionForkReplaysInitialCreationIdempotently() {
    HistorySeed source = seedHistory(store, false);
    UUID newSessionId = TestIds.id(955);
    UUID newThreadId = TestIds.id(956);
    AcceptedCommands first =
        acceptSessionFork(source.threadId(), source.lastEntryId(), newSessionId, newThreadId);
    assertFalse(first.replayed());
    int entriesAfterFirst = store.transaction(tx -> tx.loadEntriesBySessionId(newSessionId)).size();

    AcceptedCommands replay =
        acceptSessionFork(source.threadId(), source.lastEntryId(), newSessionId, newThreadId);
    assertTrue(replay.replayed());
    assertEquals(first.thread().id(), replay.thread().id());
    assertEquals(first.session().id(), replay.session().id());
    assertEquals(
        entriesAfterFirst, store.transaction(tx -> tx.loadEntriesBySessionId(newSessionId)).size());

    HarnessRuntimeConflictException conflict =
        assertThrows(
            HarnessRuntimeConflictException.class,
            () ->
                runtime.acceptCommands(
                    sessionForkCommand(
                        source.threadId(),
                        source.lastEntryId(),
                        newSessionId,
                        newThreadId,
                        "different-command"),
                    AcceptancePreflight.IDENTITY));
    assertEquals(HarnessRuntimeConflictException.Reason.THREAD_ID_REUSED, conflict.reason());
    assertEquals(
        entriesAfterFirst, store.transaction(tx -> tx.loadEntriesBySessionId(newSessionId)).size());
  }

  // ---------------------------------------------------------------- 辅助

  private AcceptedCommands acceptSessionFork(
      UUID sourceThreadId, UUID cutEntryId, UUID newSessionId, UUID newThreadId) {
    return runtime.acceptCommands(
        sessionForkCommand(sourceThreadId, cutEntryId, newSessionId, newThreadId, "fork"),
        AcceptancePreflight.IDENTITY);
  }

  private static AcceptCommandsCommand sessionForkCommand(
      UUID sourceThreadId, UUID cutEntryId, UUID newSessionId, UUID newThreadId, String text) {
    return new AcceptCommandsCommand(
        new AcceptCommandsTarget.NewForkedSession(
            sourceThreadId, cutEntryId, newSessionId, newThreadId, false),
        List.of(userMessageCommand(TestIds.id(Math.abs(text.hashCode()) + 1000L), text)));
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

  private static int indexOf(EntryPath path, UUID entryId) {
    List<Entry> entries = path.entries();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    throw new IllegalArgumentException("entry not on path: " + entryId);
  }

  /** 统计复制范围内未闭合的 TURN_START 数量；合法复制必须为 0。 */
  private static int countUnclosedTurns(List<Entry> entries) {
    boolean open = false;
    for (Entry entry : entries) {
      if (entry.payload() instanceof TurnStartPayload) {
        assertFalse(open, "copied history must not contain nested TURN_START");
        open = true;
      } else if (entry.payload() instanceof TurnEndPayload) {
        open = false;
      }
    }
    return open ? 1 : 0;
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
                null)),
        createdAt);
  }

  /** 追加一个完整 COMPACTION turn（TURN_START + COMPACTION payload + TURN_END）并返回其三个 Entry id。 */
  private static CompactionTurnIds insertCompactionTurn(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID parentId,
      UUID ownerThreadId,
      CompactionStart start,
      Instant createdAt,
      boolean continueModel) {
    UUID turnStartId = tx.nextId();
    tx.insertEntry(
        new Entry(
            turnStartId,
            sessionId,
            parentId,
            new TurnStartPayload(
                TurnStartReason.COMPACTION, settings(), ownerThreadId, 100_000, 16_384, start),
            createdAt));
    UUID resultId = tx.nextId();
    tx.insertEntry(
        new Entry(
            resultId,
            sessionId,
            turnStartId,
            new CompactionPayload(
                start.phase() == CompactionPhase.HISTORY ? "history" : "summary", null),
            createdAt));
    UUID endId = tx.nextId();
    tx.insertEntry(
        new Entry(
            endId,
            sessionId,
            resultId,
            new TurnEndPayload(turnStartId, TurnEndOutcome.COMPLETED, continueModel, null, null),
            createdAt));
    return new CompactionTurnIds(turnStartId, resultId, endId);
  }

  private record CompactionTurnIds(UUID turnStartId, UUID resultId, UUID endId) {}

  /**
   * ROOT -&gt; [INPUT u1, A1(tool call), TOOL result, END] -&gt; [INPUT u2, A2, END]；head = END2。
   *
   * @param keepOpenTail 为 true 时末尾追加未闭合 INPUT turn（用于验证半轮切点被拒绝）
   */
  private static HistorySeed seedHistory(InMemoryHarnessStore store, boolean keepOpenTail) {
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
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(root, sessionId));
          tx.insertEntry(turnStartEntry(ts1, sessionId, root, T1, threadId));
          tx.insertEntry(userEntry(u1, sessionId, ts1, T1, "u1"));
          tx.insertEntry(assistantEntry(a1, sessionId, u1, T1, "call-1"));
          tx.insertEntry(toolResultEntry(tr1, sessionId, a1, a1, 0, "call-1", T1));
          tx.insertEntry(turnEndEntry(te1, sessionId, tr1, T1, ts1, false));
          tx.insertEntry(turnStartEntry(ts2, sessionId, te1, T2, threadId));
          tx.insertEntry(userEntry(u2, sessionId, ts2, T2, "u2"));
          tx.insertEntry(assistantEntry(a2, sessionId, u2, T2));
          tx.insertEntry(turnEndEntry(te2, sessionId, a2, T2, ts2, false));
          UUID head = te2;
          if (keepOpenTail) {
            UUID openStart = tx.nextId();
            tx.insertEntry(turnStartEntry(openStart, sessionId, te2, T3, threadId));
            head = openStart;
          }
          tx.insertThread(thread(threadId, sessionId, head));
          return new HistorySeed(sessionId, threadId, root, ts2, a1, te1, te2);
        });
  }

  /** ROOT -&gt; [T1] -&gt; [T2 u2] -&gt; COMPACTION(cut=u2) -&gt; [T3]；head = END3，保留尾部从 T2 开始。 */
  private static CompactionSeed seedCompactedHistory(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID root = tx.nextId();
          UUID ts1 = tx.nextId();
          UUID u1 = tx.nextId();
          UUID a1 = tx.nextId();
          UUID te1 = tx.nextId();
          UUID ts2 = tx.nextId();
          UUID u2 = tx.nextId();
          UUID a2 = tx.nextId();
          UUID te2 = tx.nextId();
          UUID ts3 = tx.nextId();
          UUID u3 = tx.nextId();
          UUID a3 = tx.nextId();
          UUID te3 = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(root, sessionId));
          tx.insertEntry(turnStartEntry(ts1, sessionId, root, T1, threadId));
          tx.insertEntry(userEntry(u1, sessionId, ts1, T1, "u1"));
          tx.insertEntry(assistantEntry(a1, sessionId, u1, T1));
          tx.insertEntry(turnEndEntry(te1, sessionId, a1, T1, ts1, false));
          tx.insertEntry(turnStartEntry(ts2, sessionId, te1, T2, threadId));
          tx.insertEntry(userEntry(u2, sessionId, ts2, T2, "u2"));
          tx.insertEntry(assistantEntry(a2, sessionId, u2, T2));
          tx.insertEntry(turnEndEntry(te2, sessionId, a2, T2, ts2, false));
          UUID compactionEnd =
              insertCompactionTurn(
                      tx,
                      sessionId,
                      te2,
                      threadId,
                      new CompactionStart(
                          CompactionPhase.FULL,
                          CompactionTrigger.MANUAL,
                          settings().model(),
                          4_096L,
                          u2,
                          null,
                          null,
                          tx.nextId(),
                          tx.nextId()),
                      T2,
                      false)
                  .endId();
          tx.insertEntry(turnStartEntry(ts3, sessionId, compactionEnd, T3, threadId));
          tx.insertEntry(userEntry(u3, sessionId, ts3, T3, "u3"));
          tx.insertEntry(assistantEntry(a3, sessionId, u3, T3));
          tx.insertEntry(turnEndEntry(te3, sessionId, a3, T3, ts3, false));
          tx.insertThread(thread(threadId, sessionId, te3));
          return new CompactionSeed(sessionId, threadId, te3, ts2);
        });
  }

  /** HISTORY 压缩后紧接 TURN_PREFIX 压缩（引用 HISTORY 结果）；head = END3，保留尾部从 T2 开始。 */
  private static CompactionSeed seedSplitCompactionHistory(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID threadId = tx.nextId();
          UUID root = tx.nextId();
          UUID ts1 = tx.nextId();
          UUID u1 = tx.nextId();
          UUID a1 = tx.nextId();
          UUID te1 = tx.nextId();
          UUID ts2 = tx.nextId();
          UUID u2 = tx.nextId();
          UUID a2 = tx.nextId();
          UUID te2 = tx.nextId();
          UUID ts3 = tx.nextId();
          UUID u3 = tx.nextId();
          UUID a3 = tx.nextId();
          UUID te3 = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(root, sessionId));
          tx.insertEntry(turnStartEntry(ts1, sessionId, root, T1, threadId));
          tx.insertEntry(userEntry(u1, sessionId, ts1, T1, "u1"));
          tx.insertEntry(assistantEntry(a1, sessionId, u1, T1));
          tx.insertEntry(turnEndEntry(te1, sessionId, a1, T1, ts1, false));
          CompactionTurnIds historyTurn =
              insertCompactionTurn(
                  tx,
                  sessionId,
                  te1,
                  threadId,
                  new CompactionStart(
                      CompactionPhase.HISTORY,
                      CompactionTrigger.THRESHOLD,
                      settings().model(),
                      4_096L,
                      a1,
                      u1,
                      null,
                      tx.nextId(),
                      tx.nextId()),
                  T1,
                  true);
          tx.insertEntry(turnStartEntry(ts2, sessionId, historyTurn.endId(), T2, threadId));
          tx.insertEntry(userEntry(u2, sessionId, ts2, T2, "u2"));
          tx.insertEntry(assistantEntry(a2, sessionId, u2, T2));
          tx.insertEntry(turnEndEntry(te2, sessionId, a2, T2, ts2, false));
          CompactionTurnIds prefixTurn =
              insertCompactionTurn(
                  tx,
                  sessionId,
                  te2,
                  threadId,
                  new CompactionStart(
                      CompactionPhase.TURN_PREFIX,
                      CompactionTrigger.MANUAL,
                      settings().model(),
                      4_096L,
                      a2,
                      u2,
                      historyTurn.resultId(),
                      tx.nextId(),
                      tx.nextId()),
                  T2,
                  false);
          tx.insertEntry(turnStartEntry(ts3, sessionId, prefixTurn.endId(), T3, threadId));
          tx.insertEntry(userEntry(u3, sessionId, ts3, T3, "u3"));
          tx.insertEntry(assistantEntry(a3, sessionId, u3, T3));
          tx.insertEntry(turnEndEntry(te3, sessionId, a3, T3, ts3, false));
          tx.insertThread(thread(threadId, sessionId, te3));
          return new CompactionSeed(sessionId, threadId, te3, ts2);
        });
  }

  private record HistorySeed(
      UUID sessionId,
      UUID threadId,
      UUID rootEntryId,
      UUID retainedStartEntryId,
      UUID assistantEntryId,
      UUID firstTurnEndId,
      UUID lastEntryId) {}

  private record CompactionSeed(
      UUID sessionId, UUID threadId, UUID lastEntryId, UUID retainedStartEntryId) {}
}
