package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomMessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ResourceMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskActivity;
import fun.fengwk.kkstudio.project.model.IssueAgentThread;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * owner 查询用例的单元契约。
 *
 * <p>owner→Session 的解析路径与 Session/Thread 投影分别验证：Chat 直接枚举 {@code chat_session}；Issue+Agent 只允许由稳定
 * Thread 绑定解析唯一 Session；投影事实全部来自 Runtime。
 */
class HarnessOwnerQueryServiceTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
  private static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");
  private static final Instant T3 = Instant.parse("2026-01-01T00:00:03Z");
  private static final Instant T4 = Instant.parse("2026-01-01T00:00:04Z");
  private static final Instant T5 = Instant.parse("2026-01-01T00:00:05Z");
  private static final BranchSettings ROOT_SETTINGS =
      new BranchSettings(
          "assistant", new ModelSelection("provider", "root-model", "default"), null);

  private ChatRepository chatRepository;
  private ChatSessionRepository chatSessionRepository;
  private IssueAgentThreadRepository issueAgentThreadRepository;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private SubagentTaskActivity subagentTaskActivity;
  private HarnessOwnerQueryService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatRepository = mock(ChatRepository.class);
    chatSessionRepository = mock(ChatSessionRepository.class);
    issueAgentThreadRepository = mock(IssueAgentThreadRepository.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    subagentTaskActivity = mock(SubagentTaskActivity.class);
    service =
        new HarnessOwnerQueryService(
            chatRepository,
            chatSessionRepository,
            issueAgentThreadRepository,
            runtimes,
            subagentTaskActivity);
  }

  /** owner 无法定位时必须确定性失败，不能枚举 Session 或读取任何 Harness 事实。 */
  @Test
  void missingOwnersFailBeforeSessionAndRuntimeLookup() {
    UUID chatId = id(1);
    UUID issueId = id(2);
    String agentName = "executor";

    assertThrows(AiResourceNotFoundException.class, () -> service.listChatSessions(chatId));
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.listSessionsByOwner(new OwnerRef.IssueAgent(issueId, agentName)));

    verifyNoInteractions(chatSessionRepository, runtime);
  }

  /** Chat 摘要的 name/createdAt/预览一律来自 durable Session 与 Entry，且 OwnerRef 分派与直接查询一致。 */
  @Test
  void chatSessionSummariesProjectDurableNamesCreatedAtAndTextOnlyPreviews() {
    // 三个 Session 分别覆盖 text 预览、纯资源 USER（无 text）与空历史。
    UUID chatId = id(10);
    UUID textSession = id(11);
    UUID resourceSession = id(12);
    UUID emptySession = id(13);
    when(chatRepository.getById(chatId)).thenReturn(mock(Chat.class));
    when(chatSessionRepository.listSessionIds(chatId))
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

    List<HarnessSessionSummaryDTO> summaries = service.listChatSessions(chatId);

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

    // OwnerRef 统一入口与 Chat 直接查询完全一致。
    List<HarnessSessionSummaryDTO> byOwner = service.listSessionsByOwner(new OwnerRef.Chat(chatId));
    assertEquals(
        summaries.stream().map(HarnessSessionSummaryDTO::getSessionId).toList(),
        byOwner.stream().map(HarnessSessionSummaryDTO::getSessionId).toList());
  }

  /** Issue+Agent 的 Session 只能由稳定绑定 Thread 解析，同 Session 的其他 Thread 不参与归属。 */
  @Test
  void issueAgentSessionsResolveThroughBoundThreadOnly() {
    UUID issueId = id(30);
    String agentName = "executor";
    UUID threadId = id(31);
    UUID sessionId = id(32);
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(issueId, agentName))
        .thenReturn(new IssueAgentThread(issueId, agentName, threadId));
    ThreadState thread = thread(threadId, sessionId, id(33), T0, T1);
    when(runtime.getThreadSnapshot(threadId))
        .thenReturn(
            new ThreadSnapshot(
                thread,
                new EntryPath(List.of(root(sessionId, id(34), T0, ROOT_SETTINGS))),
                List.of(),
                null,
                List.of(),
                List.of()));
    when(runtime.getSession(sessionId)).thenReturn(new Session(sessionId, "agent session", T0));
    when(runtime.getSessionEntries(sessionId)).thenReturn(List.of());
    when(runtime.listThreadsBySession(sessionId)).thenReturn(List.of(thread));

    List<HarnessSessionSummaryDTO> summaries =
        service.listSessionsByOwner(new OwnerRef.IssueAgent(issueId, agentName));

    assertEquals(1, summaries.size());
    assertEquals(sessionId.toString(), summaries.getFirst().getSessionId());
    assertEquals("agent session", summaries.getFirst().getName());
    verify(runtime).getThreadSnapshot(threadId);
  }

  /** 绑定存在但 Thread 已不可解析时 fail closed，不返回空列表也不猜测归属。 */
  @Test
  void unreadableBoundThreadFailsClosed() {
    UUID issueId = id(40);
    String agentName = "executor";
    UUID threadId = id(41);
    when(issueAgentThreadRepository.findByIssueIdAndAgentName(issueId, agentName))
        .thenReturn(new IssueAgentThread(issueId, agentName, threadId));
    when(runtime.getThreadSnapshot(threadId))
        .thenThrow(new HarnessRuntimeNotFoundException("thread " + threadId + " does not exist"));

    assertThrows(
        HarnessRuntimeNotFoundException.class,
        () -> service.listSessionsByOwner(new OwnerRef.IssueAgent(issueId, agentName)));
    verifyNoInteractions(chatSessionRepository);
  }

  /** 预览必须跳过 durable USER {@code <system-reminder>}：注入的上下文提醒不是用户发言。 */
  @Test
  void threadSummariesSkipDurableUserReminderAndPreviewLatestUserText() {
    // 名称来自 durable ThreadState；head 是注入的 USER 提醒，因此预览回到最近的用户发言文本。
    UUID sessionId = id(50);
    UUID threadId = id(51);
    BranchSettings turnSettings =
        new BranchSettings("assistant", new ModelSelection("provider", "turn-model", "fast"), null);
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
        new ThreadSnapshot(
            thread,
            new EntryPath(List.of(root, turnStart, user, reminder, afterReminder)),
            List.of(),
            null,
            List.of(),
            List.of());
    when(runtime.listThreadsBySession(sessionId)).thenReturn(List.of(thread));
    when(runtime.getThreadSnapshot(threadId)).thenReturn(snapshot);

    List<HarnessThreadSummaryDTO> summaries = service.listThreadSummaries(sessionId);

    HarnessThreadSummaryDTO summary = summaries.getFirst();
    assertEquals(threadId.toString(), summary.getThreadId());
    assertEquals("thread", summary.getName());
    assertEquals("IDLE", summary.getStatus());
    assertEquals("provider", summary.getModel().getProviderName());
    assertEquals("turn-model", summary.getModel().getModelName());
    assertEquals("fast", summary.getModel().getVariant());
    assertEquals("after reminder", summary.getHeadMessagePreview());

    // head 恰为提醒本身时，预览回退到更早的真实用户发言，提醒文本绝不泄漏到用户可见摘要。
    ThreadState reminderHead = thread(threadId, sessionId, reminder.id(), T0, T5);
    when(runtime.getThreadSnapshot(threadId))
        .thenReturn(
            new ThreadSnapshot(
                reminderHead,
                new EntryPath(List.of(root, turnStart, user, reminder)),
                List.of(),
                null,
                List.of(),
                List.of()));
    String reminderHeadPreview =
        service.listThreadSummaries(sessionId).getFirst().getHeadMessagePreview();
    assertEquals("latest user", reminderHeadPreview);
    assertFalse(reminderHeadPreview.contains("system-reminder"), reminderHeadPreview);
  }

  @Test
  void threadSummaryProcessingAggregatesPendingDelegatedWork() {
    // 测试意图：Thread 自身静止但子树仍有未交付委派时摘要必须保持 processing：
    // 否则会话列表会显示"已结束"，用户据此开始下一轮，而异步 task 的结果其实还没回来。
    UUID sessionId = id(32);
    UUID threadId = id(33);
    Entry root = root(sessionId, id(511), T0, ROOT_SETTINGS);
    ThreadState thread = thread(threadId, sessionId, root.id(), T0, T1);
    when(runtime.listThreadsBySession(sessionId)).thenReturn(List.of(thread));
    when(runtime.getThreadSnapshot(threadId))
        .thenReturn(
            new ThreadSnapshot(
                thread, new EntryPath(List.of(root)), List.of(), null, List.of(), List.of()));

    HarnessThreadSummaryDTO idle = service.listThreadSummaries(sessionId).getFirst();
    assertEquals("IDLE", idle.getStatus());
    assertFalse(idle.isProcessing());

    when(subagentTaskActivity.hasPendingDelegatedWork(threadId)).thenReturn(true);
    HarnessThreadSummaryDTO waiting = service.listThreadSummaries(sessionId).getFirst();
    // status 只描述该 Thread 自身执行，聚合结果只体现在 processing 上。
    assertEquals("IDLE", waiting.getStatus());
    assertTrue(waiting.isProcessing());
  }

  @Test
  void entriesAreCopiedAndMissingRuntimeFailsClosed() {
    // 查询结果不能暴露 Runtime 的可变列表；未装配 Runtime 时所有查询都明确失败。
    UUID sessionId = id(60);
    Entry root = root(sessionId, id(601), T0, ROOT_SETTINGS);
    List<Entry> source = new ArrayList<>(List.of(root));
    when(runtime.getSessionEntries(sessionId)).thenReturn(source);

    List<Entry> result = service.listSessionEntries(sessionId);
    source.clear();

    assertEquals(List.of(root), result);
    assertThrows(UnsupportedOperationException.class, () -> result.add(root));
    verify(runtime).getSessionEntries(sessionId);

    when(runtimes.getIfAvailable()).thenReturn(null);
    assertThrows(IllegalStateException.class, () -> service.listSessionEntries(sessionId));
    assertThrows(IllegalStateException.class, () -> service.listThreadSummaries(sessionId));
  }

  @Test
  void rejectsNullOwnerAndNullIds() {
    // owner 与 id 边界不接受缺失值。
    assertThrows(NullPointerException.class, () -> service.listSessionsByOwner(null));
    assertThrows(NullPointerException.class, () -> service.listChatSessions(null));
    assertThrows(NullPointerException.class, () -> service.listThreadSummaries(null));
    assertThrows(NullPointerException.class, () -> service.listSessionEntries(null));
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
        headEntryId,
        "0".repeat(64),
        "thread",
        false,
        1L,
        0L,
        createdAt,
        updatedAt);
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }
}
