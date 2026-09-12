package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import fun.fengwk.convention4j.springboot.starter.web.result.ResultResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.platform.chat.service.ChatService;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionSummaryDTO;

import java.util.List;
import java.util.UUID;

/** {@link StudioChatController} HTTP 契约测试：验证 /api/ai/chats 路由复数化、path/method/query CAS 与状态码。 */
class StudioChatControllerTest {

  private ChatService chatService;
  private HarnessOwnerQueryService harnessQueryService;
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
    harnessQueryService = mock(HarnessOwnerQueryService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new StudioChatController(chatService, harnessQueryService))
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .build();
  }

  /** 测试意图：验证 GET /api/ai/chats 集合列表查询契约与 200 OK 状态码。 */
  @Test
  void listChatsReturnsAllChats() throws Exception {
    ChatDTO chat = new ChatDTO();
    chat.setId(idText(1));
    chat.setTitle("chat-1");
    when(chatService.listChats()).thenReturn(List.of(chat));

    mockMvc
        .perform(get("/api/ai/chats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(idText(1)))
        .andExpect(jsonPath("$.data[0].title").value("chat-1"));
    verify(chatService).listChats();
  }

  /** 测试意图：验证 GET /api/ai/chats/{chatId} 单资源查询契约，明确 path variable 传递与 200 OK 状态码。 */
  @Test
  void getChatForwardsPathIdAndReturnsChat() throws Exception {
    ChatDTO chat = new ChatDTO();
    chat.setId(idText(5));
    chat.setTitle("chat-5");
    when(chatService.getChat(idText(5))).thenReturn(chat);

    mockMvc
        .perform(get("/api/ai/chats/{chatId}", idText(5)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(idText(5)))
        .andExpect(jsonPath("$.data.title").value("chat-5"));
    verify(chatService).getChat(idText(5));
  }

  /**
   * 测试意图：验证 DELETE /api/ai/chats/{chatId} 使用 query 参数 expectedVersion 实施 CAS 删除，返回 204 NoContent。
   */
  @Test
  void deleteChatRequiresExpectedVersionQueryAndReturnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/ai/chats/{chatId}", idText(9)).param("expectedVersion", "3"))
        .andExpect(status().isNoContent());
    verify(chatService).deleteChat(idText(9), "3");
  }

  /** 测试意图：验证 GET /api/ai/chats/{chatId}/sessions 正确传递 chatId 并返回会话摘要（含权威 name）与 200 OK 状态码。 */
  @Test
  void listChatSessionsForwardsChatIdAndReturnsResults() throws Exception {
    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(idText(2));
    summary.setName("session name");
    summary.setFirstMessagePreview("hello");
    summary.setThreadCount(2);

    when(harnessQueryService.listChatSessions(id(7))).thenReturn(List.of(summary));

    mockMvc
        .perform(get("/api/ai/chats/" + idText(7) + "/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sessionId").value(idText(2)))
        .andExpect(jsonPath("$.data[0].name").value("session name"))
        .andExpect(jsonPath("$.data[0].firstMessagePreview").value("hello"))
        .andExpect(jsonPath("$.data[0].threadCount").value(2));
    verify(harnessQueryService).listChatSessions(id(7));
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
            post("/api/ai/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"agentName\":\"default-assistant\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.workspacePath").doesNotExist());

    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertEquals("t", captor.getValue().getTitle());
    assertEquals("default-assistant", captor.getValue().getAgentName());
  }

  /** 测试意图：验证旧 workspacePath 创建字段在严格 DTO 边界被拒绝。 */
  @Test
  void createChatRejectsLegacyWorkspacePath() throws Exception {
    mockMvc
        .perform(
            post("/api/ai/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"t\",\"agentName\":\"default-assistant\","
                        + "\"workspacePath\":\"src\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(chatService);
  }

  /** 测试意图：验证 Chat 更新继续传递 CAS version，响应中不存在 Workspace 状态。 */
  @Test
  void updateChatCarriesNoWorkspaceState() throws Exception {
    ChatDTO updated = new ChatDTO();
    updated.setId("7");
    updated.setTitle("t");
    updated.setAgentName("default-assistant");
    updated.setYoloEnabled(false);
    updated.setVersion("2");
    when(chatService.updateChat(eq("7"), any(ChatUpdateDTO.class))).thenReturn(updated);

    mockMvc
        .perform(
            put("/api/ai/chats/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.workspacePath").doesNotExist());
    ArgumentCaptor<ChatUpdateDTO> captor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService).updateChat(eq("7"), captor.capture());
    assertEquals("2", captor.getValue().getExpectedVersion());
  }

  /** 测试意图：验证旧 workspacePath 更新字段在严格 DTO 边界被拒绝。 */
  @Test
  void updateChatRejectsLegacyWorkspacePath() throws Exception {
    mockMvc
        .perform(
            put("/api/ai/chats/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspacePath\":null,\"expectedVersion\":\"2\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(chatService);
  }
}
