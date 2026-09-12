package fun.fengwk.kkstudio.platform.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasSessionRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
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
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.platform.chat.repo.ChatRepository;
import fun.fengwk.kkstudio.platform.chat.repo.ChatSessionRepository;
import fun.fengwk.kkstudio.platform.chat.service.model.Chat;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class HarnessOwnerQueryServiceTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
  private static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");
  private static final Instant T3 = Instant.parse("2026-01-01T00:00:03Z");
  private static final Instant T4 = Instant.parse("2026-01-01T00:00:04Z");
  private static final BranchSettings ROOT_SETTINGS =
      new BranchSettings("assistant", new ModelSelection("provider", "root-model", "default"));

  private ChatRepository chatRepository;
  private ChatSessionRepository chatSessionRepository;
  private CanvasStore canvasStore;
  private CanvasSessionRepository canvasSessionRepository;
  private ObjectProvider<HarnessRuntime> runtimes;
  private HarnessRuntime runtime;
  private HarnessOwnerQueryService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    chatRepository = mock(ChatRepository.class);
    chatSessionRepository = mock(ChatSessionRepository.class);
    canvasStore = mock(CanvasStore.class);
    canvasSessionRepository = mock(CanvasSessionRepository.class);
    runtimes = mock(ObjectProvider.class);
    runtime = mock(HarnessRuntime.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    service =
        new HarnessOwnerQueryService(
            chatRepository, chatSessionRepository, canvasStore, canvasSessionRepository, runtimes);
  }

  @Test
  void missingOwnersFailBeforeRuntimeLookup() {
    // Owner 不存在时不能枚举或读取任何 Session/Harness 事实。
    UUID chatId = id(1);
    UUID canvasId = id(2);

    assertThrows(AiResourceNotFoundException.class, () -> service.listChatSessions(chatId));
    assertThrows(AiResourceNotFoundException.class, () -> service.listCanvasSessions(canvasId));

    verifyNoInteractions(chatSessionRepository, canvasSessionRepository, runtime);
  }

  @Test
  void chatSessionSummariesProjectDurableNamesCreatedAtAndTextOnlyPreviews() {
    // 三个 Session 分别覆盖 text 预览、纯资源 USER（无 text）与空历史；名称/createdAt 一律来自 durable Session。
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
                    new ResourceMessageContent(id(203), "resource-only.pdf", null))));
    when(runtime.getSessionEntries(resourceSession))
        .thenReturn(List.of(resourceMessage, resourceRoot));
    when(runtime.listThreadsBySession(resourceSession)).thenReturn(List.of());

    when(runtime.getSessionEntries(emptySession)).thenReturn(List.of());
    when(runtime.listThreadsBySession(emptySession)).thenReturn(List.of());

    List<HarnessSessionSummaryDTO> summaries = service.listChatSessions(chatId);

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
  }

  @Test
  void canvasSessionsUseTheSameProjectionAndPropagateMissingRuntimeSession() {
    // Canvas 与 Chat 共用完全相同的 Session projection；Session 事实读 Runtime，缺失时抛出 NotFound。
    UUID canvasId = id(20);
    UUID sessionId = id(21);
    when(canvasStore.findDocument(canvasId)).thenReturn(Optional.of(mock(CanvasDocument.class)));
    when(canvasSessionRepository.listSessionIds(canvasId)).thenReturn(List.of(sessionId));
    when(runtime.getSession(sessionId)).thenReturn(new Session(sessionId, "canvas chat", T1));
    Entry root = root(sessionId, id(401), T1, ROOT_SETTINGS);
    when(runtime.getSessionEntries(sessionId)).thenReturn(List.of(root));
    when(runtime.listThreadsBySession(sessionId)).thenReturn(List.of());

    List<HarnessSessionSummaryDTO> summaries = service.listCanvasSessions(canvasId);

    assertEquals(1, summaries.size());
    assertEquals("canvas chat", summaries.getFirst().getName());
    assertEquals(T1, summaries.getFirst().getCreatedAt());
    assertNull(summaries.getFirst().getFirstMessagePreview());

    when(runtime.getSession(sessionId))
        .thenThrow(new HarnessRuntimeNotFoundException("session " + sessionId + " does not exist"));
    assertThrows(HarnessRuntimeNotFoundException.class, () -> service.listCanvasSessions(canvasId));
  }

  @Test
  void threadSummariesProjectDurableNameTypedStatusModelAndLatestNonSystemMessage() {
    // 名称来自 durable ThreadState；Snapshot 使用真实 classifier 输入：SYSTEM head 被跳过，最近 USER 文本成为预览。
    UUID sessionId = id(30);
    UUID threadId = id(31);
    BranchSettings turnSettings =
        new BranchSettings("assistant", new ModelSelection("provider", "turn-model", "fast"));
    Entry root = root(sessionId, id(501), T0, ROOT_SETTINGS);
    Entry turnStart =
        new Entry(
            id(502),
            sessionId,
            root.id(),
            new TurnStartPayload(TurnStartReason.INPUT, turnSettings, threadId),
            T1);
    Entry user = userText(sessionId, id(503), turnStart.id(), T2, "latest user");
    Entry system =
        new Entry(
            id(504),
            sessionId,
            user.id(),
            new CustomMessagePayload(
                "core", "message", "message", AgentMessage.system("hidden system"), "{}"),
            T3);
    ThreadState thread = thread(threadId, sessionId, system.id(), T0, T4);
    ThreadSnapshot snapshot =
        new ThreadSnapshot(
            thread,
            new EntryPath(List.of(root, turnStart, user, system)),
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
    assertEquals("latest user", summary.getHeadMessagePreview());
  }

  @Test
  void entriesAreCopiedAndMissingRuntimeFailsClosed() {
    // 查询结果不能暴露 Runtime 的可变列表；未装配 Runtime 时所有查询都明确失败。
    UUID sessionId = id(40);
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
                new ResourceMessageContent(
                    id(900 + entryId.getLeastSignificantBits()), name, null))));
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
