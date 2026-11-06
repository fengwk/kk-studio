package fun.fengwk.kkstudio.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.chat.dto.ChatDTO;
import fun.fengwk.kkstudio.chat.dto.ChatPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatSessionPageDTO;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.platform.orchestration.SessionDeletionOrchestrator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Chat 分页信封与单 Session 归属校验：limit+1 探测、游标定位、跨 Chat 拒绝。 */
class ChatServiceTest {

  private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

  private ChatRepository chats;
  private ChatSessionRepository sessions;
  private ChatGuard guard;
  private HarnessOwnerQueryService harnessQueryService;
  private ChatService service;

  @BeforeEach
  void setUp() {
    chats = mock(ChatRepository.class);
    sessions = mock(ChatSessionRepository.class);
    guard = mock(ChatGuard.class);
    harnessQueryService = mock(HarnessOwnerQueryService.class);
    service =
        new ChatService(
            chats,
            sessions,
            mock(ChatMutationFactory.class),
            guard,
            harnessQueryService,
            mock(SessionDeletionOrchestrator.class));
  }

  /** 测试意图：多取一行只用于判断下一页；nextCursor 精确定位本页最后一条返回行。 */
  @Test
  void listChatsTrimsProbeRowAndEncodesLastReturnedCursor() {
    Chat first = chat(1, BASE.plusSeconds(3));
    Chat second = chat(2, BASE.plusSeconds(2));
    Chat third = chat(3, BASE.plusSeconds(1));
    when(chats.listPage(null, 3)).thenReturn(List.of(first, second, third));

    ChatPageDTO page = service.listChats(null, 2);

    assertEquals(List.of(id(1).toString(), id(2).toString()), ids(page.getItems()));
    assertEquals(ChatCursor.of(second).encode(), page.getNextCursor());

    ChatCursor cursor = ChatCursor.parse(page.getNextCursor());
    when(chats.listPage(cursor, 3)).thenReturn(List.of(third));
    ChatPageDTO lastPage = service.listChats(page.getNextCursor(), 2);

    assertEquals(List.of(id(3).toString()), ids(lastPage.getItems()));
    assertNull(lastPage.getNextCursor());
  }

  /** 测试意图：非法游标在查询前失败，避免静默返回首屏。 */
  @Test
  void listChatsRejectsInvalidCursor() {
    assertThrows(DomainException.class, () -> service.listChats("bad", 2));
    assertThrows(DomainException.class, () -> service.listChats(null, 0));
    assertThrows(DomainException.class, () -> service.listSessions(id(7).toString(), null, 101));
  }

  /** 测试意图：会话页只投影本页 id，并保持仓库给定的稳定顺序与游标。 */
  @Test
  void listSessionsProjectsOnlyThePageSessionIds() {
    UUID chatId = id(7);
    ChatSessionRow newest = new ChatSessionRow(id(21), BASE.plusSeconds(2));
    ChatSessionRow older = new ChatSessionRow(id(22), BASE.plusSeconds(1));
    HarnessSessionSummaryDTO newestSummary = summary(newest.sessionId());
    HarnessSessionSummaryDTO olderSummary = summary(older.sessionId());
    when(guard.requireChat(chatId)).thenReturn(chat(7, BASE));
    when(sessions.listPage(chatId, null, 3)).thenReturn(List.of(newest, older));
    when(harnessQueryService.listSessionSummaries(List.of(id(21), id(22))))
        .thenReturn(List.of(newestSummary, olderSummary));

    ChatSessionPageDTO page = service.listSessions(chatId.toString(), null, 2);

    assertEquals(
        List.of(id(21).toString(), id(22).toString()),
        page.getItems().stream().map(HarnessSessionSummaryDTO::getSessionId).toList());
    assertNull(page.getNextCursor());
  }

  /** 测试意图：仍有下一页时会话游标取本页最后一条归属边。 */
  @Test
  void listSessionsEmitsCursorWhenProbeRowExists() {
    UUID chatId = id(7);
    ChatSessionRow newest = new ChatSessionRow(id(21), BASE.plusSeconds(3));
    ChatSessionRow older = new ChatSessionRow(id(22), BASE.plusSeconds(2));
    ChatSessionRow probe = new ChatSessionRow(id(23), BASE.plusSeconds(1));
    when(guard.requireChat(chatId)).thenReturn(chat(7, BASE));
    when(sessions.listPage(chatId, null, 3)).thenReturn(List.of(newest, older, probe));
    when(harnessQueryService.listSessionSummaries(List.of(id(21), id(22))))
        .thenReturn(List.of(summary(id(21)), summary(id(22))));

    ChatSessionPageDTO page = service.listSessions(chatId.toString(), null, 2);

    assertEquals(ChatSessionCursor.of(older).encode(), page.getNextCursor());
    assertNotNull(page.getNextCursor());
  }

  /** 测试意图：单 Session 摘要只解析本 Chat 的归属边；缺失或属于其他 Chat 都按 404 fail closed。 */
  @Test
  void getSessionRejectsMissingOrForeignOwnership() {
    UUID chatId = id(7);
    UUID sessionId = id(21);
    when(sessions.findBySessionId(sessionId)).thenReturn(null);
    assertThrows(
        DomainException.class, () -> service.getSession(chatId.toString(), sessionId.toString()));

    when(sessions.findBySessionId(sessionId)).thenReturn(new ChatSession(sessionId, id(8)));
    assertThrows(
        DomainException.class, () -> service.getSession(chatId.toString(), sessionId.toString()));

    when(sessions.findBySessionId(sessionId)).thenReturn(new ChatSession(sessionId, chatId));
    when(harnessQueryService.listSessionSummaries(List.of(sessionId)))
        .thenReturn(List.of(summary(sessionId)));
    assertEquals(
        sessionId.toString(),
        service.getSession(chatId.toString(), sessionId.toString()).getSessionId());
  }

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static List<String> ids(List<ChatDTO> items) {
    return items.stream().map(ChatDTO::getId).toList();
  }

  private static Chat chat(long id, Instant updatedAt) {
    Chat chat = new Chat();
    chat.setId(id(id));
    chat.setTitle("chat-" + id);
    chat.setAgentName("assistant");
    chat.setVersion(0L);
    chat.setCreatedAt(BASE);
    chat.setUpdatedAt(updatedAt);
    return chat;
  }

  private static HarnessSessionSummaryDTO summary(UUID sessionId) {
    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(sessionId.toString());
    summary.setName("session-" + sessionId);
    return summary;
  }
}
