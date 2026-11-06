package fun.fengwk.kkstudio.web.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import fun.fengwk.kkstudio.chat.ChatService;
import fun.fengwk.kkstudio.chat.dto.ChatCreateDTO;
import fun.fengwk.kkstudio.chat.dto.ChatDTO;
import fun.fengwk.kkstudio.chat.dto.ChatPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatSessionPageDTO;
import fun.fengwk.kkstudio.chat.dto.ChatUpdateDTO;
import fun.fengwk.kkstudio.chat.http.ChatController;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.share.runtime.HarnessSessionSummaryDTO;
import fun.fengwk.kkstudio.web.advice.ApiResponseStatusAdvice;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;

import java.util.List;
import java.util.UUID;

/** {@link ChatController} HTTP 契约测试：验证 /api/chats 路由、keyset 分页参数、path/method/query CAS 与状态码。 */
class ChatControllerTest {

  private ChatService chatService;
  private MockMvc mockMvc;

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  @BeforeEach
  void setUp() {
    chatService = mock(ChatService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new ChatController(chatService))
            .setControllerAdvice(
                new ApiResponseStatusAdvice(), new StudioProblemAdvice(new StudioMessageService()))
            .build();
  }

  /** 测试意图：验证 GET /api/chats 返回分页资源（items + nextCursor）与 200 OK 状态码。 */
  @Test
  void listChatsReturnsPageWithCursor() throws Exception {
    ChatDTO chat = new ChatDTO();
    chat.setId(idText(1));
    chat.setTitle("chat-1");
    ChatPageDTO page = new ChatPageDTO();
    page.setItems(List.of(chat));
    page.setNextCursor("cursor-2");
    when(chatService.listChats(null, 50)).thenReturn(page);

    mockMvc
        .perform(get("/api/chats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(idText(1)))
        .andExpect(jsonPath("$.items[0].title").value("chat-1"))
        .andExpect(jsonPath("$.nextCursor").value("cursor-2"));
    verify(chatService).listChats(null, 50);
  }

  @Test
  void translatesHarnessDomainErrorsButPropagatesOtherDomainFailures() {
    // 只把 Harness not-found 与已知 conflict 转为旧 HTTP 状态，保留原因供 advice 输出。
    ChatController controller = new ChatController(chatService);
    DomainException missing = DomainException.notFound("session", "gone");
    when(chatService.getSession("chat", "session")).thenThrow(missing);
    ResponseStatusException notFound =
        assertThrows(
            ResponseStatusException.class, () -> controller.getChatSession("chat", "session"));
    assertEquals(404, notFound.getStatusCode().value());
    assertSame(missing, notFound.getCause());
    DomainException conflict = DomainException.conflict("STALE_VERSION", "stale");
    doThrow(conflict).when(chatService).getSession("chat", "session");
    ResponseStatusException rejected =
        assertThrows(
            ResponseStatusException.class, () -> controller.getChatSession("chat", "session"));
    assertEquals(409, rejected.getStatusCode().value());
    assertSame(conflict, rejected.getCause());
    DomainException other = DomainException.validation("chat", "invalid");
    doThrow(other).when(chatService).getSession("chat", "session");
    assertSame(
        other,
        assertThrows(DomainException.class, () -> controller.getChatSession("chat", "session")));
  }

  /** 测试意图：验证分页参数原样传递，且显式 limit 上限被拒绝为 400。 */
  @Test
  void listChatsForwardsCursorAndLimitAndRejectsOutOfRangeLimit() throws Exception {
    ChatPageDTO page = new ChatPageDTO();
    when(chatService.listChats("cursor-9", 20)).thenReturn(page);

    mockMvc
        .perform(get("/api/chats").param("cursor", "cursor-9").param("limit", "20"))
        .andExpect(status().isOk());
    verify(chatService).listChats("cursor-9", 20);

    mockMvc.perform(get("/api/chats").param("limit", "101")).andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/chats").param("limit", "0")).andExpect(status().isBadRequest());
  }

  /** 测试意图：验证 GET /api/chats/{chatId} 单资源查询契约，明确 path variable 传递与 200 OK 状态码。 */
  @Test
  void getChatForwardsPathIdAndReturnsChat() throws Exception {
    ChatDTO chat = new ChatDTO();
    chat.setId(idText(5));
    chat.setTitle("chat-5");
    when(chatService.getChat(idText(5))).thenReturn(chat);

    mockMvc
        .perform(get("/api/chats/{chatId}", idText(5)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(idText(5)))
        .andExpect(jsonPath("$.title").value("chat-5"));
    verify(chatService).getChat(idText(5));
  }

  /** 测试意图：验证 DELETE /api/chats/{chatId} 使用 query 参数 expectedVersion 实施 CAS 删除，返回 204 NoContent。 */
  @Test
  void deleteChatRequiresExpectedVersionQueryAndReturnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/chats/{chatId}", idText(9)).param("expectedVersion", "3"))
        .andExpect(status().isNoContent());
    verify(chatService).deleteChat(idText(9), "3");
  }

  /** 测试意图：验证 GET /api/chats/{chatId}/sessions 返回分页资源并透传 chatId、cursor 与 limit。 */
  @Test
  void listChatSessionsForwardsChatIdAndPagingParameters() throws Exception {
    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(idText(2));
    summary.setName("session name");
    summary.setFirstMessagePreview("hello");
    summary.setThreadCount(2);
    ChatSessionPageDTO page = new ChatSessionPageDTO();
    page.setItems(List.of(summary));
    page.setNextCursor("next");

    when(chatService.listSessions(idText(7), "cursor-1", 10)).thenReturn(page);

    mockMvc
        .perform(
            get("/api/chats/" + idText(7) + "/sessions")
                .param("cursor", "cursor-1")
                .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].sessionId").value(idText(2)))
        .andExpect(jsonPath("$.items[0].name").value("session name"))
        .andExpect(jsonPath("$.items[0].firstMessagePreview").value("hello"))
        .andExpect(jsonPath("$.items[0].threadCount").value(2))
        .andExpect(jsonPath("$.nextCursor").value("next"));
    verify(chatService).listSessions(idText(7), "cursor-1", 10);
  }

  /** 测试意图：单个归属 Session 摘要按 id 解析，供面包屑与重命名使用。 */
  @Test
  void getChatSessionForwardsIdsAndReturnsSummary() throws Exception {
    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(idText(3));
    summary.setName("focused session");
    when(chatService.getSession(idText(7), idText(3))).thenReturn(summary);

    mockMvc
        .perform(get("/api/chats/" + idText(7) + "/sessions/" + idText(3)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionId").value(idText(3)))
        .andExpect(jsonPath("$.name").value("focused session"));
    verify(chatService).getSession(idText(7), idText(3));
  }

  /** 测试意图：验证 Chat 创建只接受当前字段，响应中不存在 Workspace 状态。 */
  @Test
  void createChatCarriesNoWorkspaceState() throws Exception {
    when(chatService.createChat(any(ChatCreateDTO.class)))
        .thenAnswer(
            invocation -> {
              ChatCreateDTO dto = invocation.getArgument(0);
              ChatDTO dtoOut = new ChatDTO();
              dtoOut.setId("1");
              dtoOut.setTitle(dto.getTitle());
              dtoOut.setAgentName(dto.getAgentName());
              return dtoOut;
            });

    mockMvc
        .perform(
            post("/api/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"agentName\":\"default-assistant\"}"))
        .andExpect(status().isCreated());

    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertEquals("t", captor.getValue().getTitle());
    assertEquals("default-assistant", captor.getValue().getAgentName());
  }

  /** 测试意图：验证创建 Chat 请求严格拒绝未知字段。 */
  @Test
  void createChatRejectsUnknownField() throws Exception {
    mockMvc
        .perform(
            post("/api/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"t\",\"agentName\":\"default-assistant\"," + "\"extra\":true}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(chatService);
  }

  /** 测试意图：验证 Chat 更新继续传递 CAS version。 */
  @Test
  void updateChatCarriesExpectedVersion() throws Exception {
    ChatDTO updated = new ChatDTO();
    updated.setId("7");
    updated.setTitle("t");
    updated.setAgentName("default-assistant");
    updated.setYoloEnabled(false);
    updated.setVersion("2");
    when(chatService.updateChat(eq("7"), any(ChatUpdateDTO.class))).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/chats/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> captor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService).updateChat(eq("7"), captor.capture());
    assertEquals("2", captor.getValue().getExpectedVersion());
  }

  /** 测试意图：验证更新 Chat 请求严格拒绝未知字段。 */
  @Test
  void updateChatRejectsUnknownField() throws Exception {
    mockMvc
        .perform(
            put("/api/chats/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"extra\":true,\"expectedVersion\":\"2\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(chatService);
  }

  /** 测试意图：创建请求透传 environmentName，并在响应中回传持久化的默认环境。 */
  @Test
  void createChatForwardsEnvironmentName() throws Exception {
    when(chatService.createChat(any(ChatCreateDTO.class)))
        .thenAnswer(
            invocation -> {
              ChatCreateDTO dto = invocation.getArgument(0);
              ChatDTO dtoOut = new ChatDTO();
              dtoOut.setId("1");
              dtoOut.setTitle(dto.getTitle());
              dtoOut.setAgentName(dto.getAgentName());
              dtoOut.setEnvironmentName(dto.getEnvironmentName());
              return dtoOut;
            });

    mockMvc
        .perform(
            post("/api/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"t\",\"agentName\":\"default-assistant\","
                        + "\"environmentName\":\"env-a\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.environmentName").value("env-a"));

    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertEquals("env-a", captor.getValue().getEnvironmentName());
  }

  /** 测试意图：更新显式 environmentName=null 被标记为 provided（清空默认环境），与省略保留区分。 */
  @Test
  void updateChatDistinguishesExplicitNullEnvironmentNameFromOmission() throws Exception {
    ChatDTO updated = new ChatDTO();
    updated.setId("7");
    updated.setTitle("t");
    updated.setAgentName("default-assistant");
    updated.setVersion("2");
    when(chatService.updateChat(eq("7"), any(ChatUpdateDTO.class))).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/chats/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentName\":null,\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<ChatUpdateDTO> captor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService).updateChat(eq("7"), captor.capture());
    assertTrue(captor.getValue().isEnvironmentNameProvided());
    assertNull(captor.getValue().getEnvironmentName());
  }
}
