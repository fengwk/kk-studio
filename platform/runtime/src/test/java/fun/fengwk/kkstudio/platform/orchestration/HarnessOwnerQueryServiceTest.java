package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.api.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntrySlice;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.SessionEntryPage;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.SystemReminder;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSummaryDTO;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** owner SPI 分派与 Runtime 摘要投影契约。 */
class HarnessOwnerQueryServiceTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
  private static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");
  private static final Instant T3 = Instant.parse("2026-01-01T00:00:03Z");
  private static final Instant T4 = Instant.parse("2026-01-01T00:00:04Z");
  private static final Instant T5 = Instant.parse("2026-01-01T00:00:05Z");
  private static final BranchSettings ROOT_SETTINGS =
      new BranchSettings(
          "assistant", BuiltinExecution.of(new ModelSelection("root-model", "default")), null);

  private SessionOwnership ownership;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private HarnessOwnerQueryService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    ownership = mock(SessionOwnership.class);
    when(ownership.kind()).thenReturn("CHAT");
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    service = new HarnessOwnerQueryService(List.of(ownership), runtimes);
  }

  /** 不存在或重复的 kind 必须在查询前拒绝。 */
  @Test
  void missingOwnersFailBeforeSessionAndRuntimeLookup() {
    SessionOwner owner = new SessionOwner("OTHER", "1");
    assertEquals(
        DomainErrorCode.VALIDATION,
        assertThrows(DomainException.class, () -> service.listSessions(owner)).code());
    SessionOwnership duplicate = mock(SessionOwnership.class);
    when(duplicate.kind()).thenReturn("CHAT");
    HarnessOwnerQueryService ambiguous =
        new HarnessOwnerQueryService(List.of(ownership, duplicate), runtimes);
    assertThrows(
        IllegalStateException.class,
        () -> ambiguous.listSessions(new SessionOwner("CHAT", id(1).toString())));
    verifyNoInteractions(runtime);
  }

  /** Session 摘要的 name/createdAt/预览一律来自 durable Session 与 Entry。 */
  @Test
  void chatSessionSummariesProjectDurableNamesCreatedAtAndTextOnlyPreviews() {
    // 三个 Session 分别覆盖 text 预览、纯资源 USER（无 text）与空历史。
    UUID chatId = id(10);
    UUID textSession = id(11);
    UUID resourceSession = id(12);
    UUID emptySession = id(13);
    SessionOwner owner = new SessionOwner("CHAT", chatId.toString());
    when(ownership.listSessions(owner))
        .thenReturn(List.of(textSession, resourceSession, emptySession));
    when(runtime.getSession(textSession)).thenReturn(new Session(textSession, "chat text", T0));
    when(runtime.getSession(resourceSession))
        .thenReturn(new Session(resourceSession, "chat resource", T1));
    when(runtime.getSession(emptySession)).thenReturn(new Session(emptySession, "chat empty", T2));

    Entry textRoot = root(textSession, id(101), T0, ROOT_SETTINGS);
    Entry earlierText = userText(textSession, id(102), textRoot.id(), T1, "first text");
    Entry laterResource =
        userResource(textSession, id(103), textRoot.id(), T2, "later-resource.png");
    when(runtime.getSessionEntries(textSession))
        .thenReturn(List.of(laterResource, textRoot, earlierText));
    ThreadState firstThread = thread(id(111), textSession, textRoot.id(), T0, T3);
    ThreadState secondThread = thread(id(112), textSession, textRoot.id(), T0, T4);
    when(runtime.listThreadsBySession(textSession)).thenReturn(List.of(firstThread, secondThread));

    Entry resourceRoot = root(resourceSession, id(201), T1, ROOT_SETTINGS);
    Entry resourceMessage =
        userMessage(
            resourceSession,
            id(202),
            resourceRoot.id(),
            T2,
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(
                    new TextMessageContent(""),
                    ResourceMessageContent.media(id(203), "resource-only.pdf"))));
    when(runtime.getSessionEntries(resourceSession))
        .thenReturn(List.of(resourceMessage, resourceRoot));
    when(runtime.listThreadsBySession(resourceSession)).thenReturn(List.of());

    when(runtime.getSessionEntries(emptySession)).thenReturn(List.of());
    when(runtime.listThreadsBySession(emptySession)).thenReturn(List.of());

    List<HarnessSessionSummaryDTO> summaries = service.listSessions(owner);

    // 关系顺序保持仓库给出的最近归属优先顺序。
    assertEquals(
        List.of(textSession.toString(), resourceSession.toString(), emptySession.toString()),
        summaries.stream().map(HarnessSessionSummaryDTO::getSessionId).toList());
    HarnessSessionSummaryDTO text = summaries.get(0);
    assertEquals("chat text", text.getName());
    assertEquals(T0, text.getCreatedAt());
    assertEquals(T4, text.getLastActivityAt());
    assertEquals("first text", text.getFirstMessagePreview());
    assertEquals(2, text.getThreadCount());
    assertEquals("chat resource", summaries.get(1).getName());
    assertNull(summaries.get(1).getFirstMessagePreview(), "纯资源 USER 无 text 时预览必须为 null");
    assertEquals("chat empty", summaries.get(2).getName());
    assertNull(summaries.get(2).getFirstMessagePreview(), "空历史 Session 预览必须为 null，绝不回退名称或 id");
    assertEquals(0, summaries.get(2).getThreadCount());

    verify(ownership).listSessions(owner);
  }

  /** SPI 的产品错误原样传播，且不会查询 Runtime。 */
  @Test
  void missingChatPropagatesSpiError() {
    SessionOwner owner = new SessionOwner("CHAT", id(1).toString());
    when(ownership.listSessions(owner)).thenThrow(DomainException.notFound("chat"));
    assertThrows(DomainException.class, () -> service.listSessions(owner));
    verifyNoInteractions(runtime);
  }

  /** 预览必须跳过 durable USER {@code <system-reminder>}：注入的上下文提醒不是用户发言。 */
  @Test
  void threadSummariesSkipDurableUserReminderAndPreviewLatestUserText() {
    // 名称来自 durable ThreadState；head 是注入的 USER 提醒，因此预览回到最近的用户发言文本。
    UUID sessionId = id(50);
    UUID threadId = id(51);
    BranchSettings turnSettings =
        new BranchSettings(
            "assistant", BuiltinExecution.of(new ModelSelection("turn-model", "fast")), null);
    Entry root = root(sessionId, id(501), T0, ROOT_SETTINGS);
    Entry turnStart =
        new Entry(
            id(502),
            sessionId,
            root.id(),
            new TurnStartPayload(TurnStartReason.INPUT, turnSettings, threadId),
            T1);
    Entry user = userText(sessionId, id(503), turnStart.id(), T2, "latest user");
    Entry reminder =
        new Entry(
            id(504),
            sessionId,
            user.id(),
            new CustomMessagePayload(
                "core", "message", "message", SystemReminder.message("Runtime context."), "{}"),
            T3);
    Entry afterReminder = userText(sessionId, id(505), reminder.id(), T4, "after reminder");
    ThreadState thread = thread(threadId, sessionId, afterReminder.id(), T0, T5);
    ThreadSnapshot snapshot =
        snapshot(thread, new EntryPath(List.of(root, turnStart, user, reminder, afterReminder)));
    when(runtime.listThreadsBySession(sessionId)).thenReturn(List.of(thread));
    when(runtime.getThreadSnapshot(threadId)).thenReturn(snapshot);

    List<HarnessThreadSummaryDTO> summaries = service.listThreadSummaries(sessionId);

    HarnessThreadSummaryDTO summary = summaries.getFirst();
    assertEquals(threadId.toString(), summary.getThreadId());
    assertEquals("thread", summary.getName());
    assertEquals(ThreadRuntimeStatus.IDLE, summary.getStatus());
    assertEquals("builtin", summary.getExecution().getDriver());
    assertEquals("turn-model", summary.getExecution().getSettings().get("modelName"));
    assertEquals("fast", summary.getExecution().getSettings().get("variant"));
    assertEquals("after reminder", summary.getHeadMessagePreview());

    // head 恰为提醒本身时，预览回退到更早的真实用户发言，提醒文本绝不泄漏到用户可见摘要。
    ThreadState reminderHead = thread(threadId, sessionId, reminder.id(), T0, T5);
    when(runtime.getThreadSnapshot(threadId))
        .thenReturn(
            snapshot(reminderHead, new EntryPath(List.of(root, turnStart, user, reminder))));
    String reminderHeadPreview =
        service.listThreadSummaries(sessionId).getFirst().getHeadMessagePreview();
    assertEquals("latest user", reminderHeadPreview);
    assertFalse(reminderHeadPreview.contains("system-reminder"), reminderHeadPreview);
  }

  /** Session 摘要保留同名根与子，父身份只从既有 snapshot 读取，不追加查询。 */
  @Test
  void threadSummariesPreserveSameNamedRootAndChildWithSnapshotParentIdentity() {
    UUID sessionId = id(70);
    UUID rootId = id(71);
    UUID childId = id(72);
    Entry entry = root(sessionId, id(701), T0, ROOT_SETTINGS);
    ThreadState root = thread(rootId, sessionId, entry.id(), T0, T1);
    ThreadState child =
        new ThreadState(
            childId,
            sessionId,
            rootId,
            entry.id(),
            "0".repeat(64),
            root.name(),
            rootId,
            false,
            SpawnTrigger.MODEL_TOOL,
            ThreadConstraints.NONE,
            ThreadExecutionControl.RUNNABLE,
            0L,
            1L,
            0L,
            T0,
            T1);
    when(runtime.listThreadsBySession(sessionId)).thenReturn(List.of(root, child));
    for (ThreadState state : List.of(root, child)) {
      when(runtime.getThreadSnapshot(state.id()))
          .thenReturn(snapshot(state, new EntryPath(List.of(entry))));
    }

    List<HarnessThreadSummaryDTO> summaries = service.listThreadSummaries(sessionId);

    assertEquals(
        List.of(rootId.toString(), childId.toString()),
        summaries.stream().map(HarnessThreadSummaryDTO::getThreadId).toList());
    assertEquals(
        List.of("thread", "thread"),
        summaries.stream().map(HarnessThreadSummaryDTO::getName).toList());
    assertNull(summaries.getFirst().getParentThreadId());
    assertEquals(rootId.toString(), summaries.get(1).getParentThreadId());
    verify(runtime).listThreadsBySession(sessionId);
    verify(runtime).getThreadSnapshot(rootId);
    verify(runtime).getThreadSnapshot(childId);
    verifyNoMoreInteractions(runtime);
  }

  @Test
  void threadSummaryUsesIdleSnapshotFacts() {
    // 摘要复用无本地工作、无未完成直接委派的快照，不自行构造活动状态。
    UUID sessionId = id(32);
    UUID threadId = id(33);
    Entry root = root(sessionId, id(511), T0, ROOT_SETTINGS);
    ThreadState thread = thread(threadId, sessionId, root.id(), T0, T1);
    when(runtime.listThreadsBySession(sessionId)).thenReturn(List.of(thread));
    when(runtime.getThreadSnapshot(threadId))
        .thenReturn(snapshot(thread, new EntryPath(List.of(root))));

    HarnessThreadSummaryDTO idle = service.listThreadSummaries(sessionId).getFirst();
    assertEquals(ThreadRuntimeStatus.IDLE, idle.getStatus());
    assertEquals(ThreadExecutionControl.RUNNABLE, idle.getExecutionControl());
  }

  @Test
  void entryPageDelegatesToRuntimeAndMissingRuntimeFailsClosed() {
    // 分页结果原样来自 Runtime（父先于子由存储追加序保证）；未装配 Runtime 时所有查询都明确失败。
    UUID sessionId = id(60);
    Entry root = root(sessionId, id(601), T0, ROOT_SETTINGS);
    SessionEntryPage page = new SessionEntryPage(List.of(root), true);
    when(runtime.listSessionEntryPage(sessionId, null, 2)).thenReturn(page);

    assertEquals(page, service.listSessionEntries(sessionId, null, 2));
    verify(runtime).listSessionEntryPage(sessionId, null, 2);

    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> service.listSessionEntries(sessionId, null, 2));
    assertThrows(IllegalStateException.class, () -> service.listThreadSummaries(sessionId));
  }

  @Test
  void rejectsNullOwnerAndNullIds() {
    // owner 与 id 边界不接受缺失值。
    assertThrows(NullPointerException.class, () -> service.listSessions(null));
    assertThrows(NullPointerException.class, () -> service.listThreadSummaries(null));
    assertThrows(NullPointerException.class, () -> service.listSessionEntries(null, null, 2));
  }

  private static Entry root(
      UUID sessionId, UUID entryId, Instant createdAt, BranchSettings settings) {
    return new Entry(entryId, sessionId, null, new RootPayload(settings), createdAt);
  }

  private static Entry userText(
      UUID sessionId, UUID entryId, UUID parentId, Instant createdAt, String text) {
    return userMessage(
        sessionId,
        entryId,
        parentId,
        createdAt,
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))));
  }

  private static Entry userResource(
      UUID sessionId, UUID entryId, UUID parentId, Instant createdAt, String name) {
    return userMessage(
        sessionId,
        entryId,
        parentId,
        createdAt,
        new AgentMessage(
            AgentMessageRole.USER,
            List.of(
                ResourceMessageContent.media(id(900 + entryId.getLeastSignificantBits()), name))));
  }

  private static Entry userMessage(
      UUID sessionId, UUID entryId, UUID parentId, Instant createdAt, AgentMessage message) {
    return new Entry(
        entryId, sessionId, parentId, new MessagePayload(message, null, null), createdAt);
  }

  private static ThreadState thread(
      UUID threadId, UUID sessionId, UUID headEntryId, Instant createdAt, Instant updatedAt) {
    return new ThreadState(
        threadId,
        sessionId,
        null,
        headEntryId,
        "0".repeat(64),
        "thread",
        threadId,
        false,
        SpawnTrigger.USER,
        ThreadConstraints.NONE,
        ThreadExecutionControl.RUNNABLE,
        0L,
        1L,
        0L,
        createdAt,
        updatedAt);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static ThreadSnapshot snapshot(ThreadState thread, EntryPath path) {
    return new ThreadSnapshot(
        thread,
        EntryCurrentState.from(path),
        new EntrySlice(null, path.entries()),
        List.of(),
        null,
        List.of(),
        ThreadLiveState.IDLE_OR_HISTORICAL,
        List.of(),
        0,
        false,
        List.of(),
        false);
  }
}
