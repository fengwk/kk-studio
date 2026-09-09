package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import org.springframework.test.web.servlet.MvcResult;
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

  /** 测试意图：验证 POST /api/ai/chats 创建 Chat 请求体支持可选默认 workspacePath，返回 201 Created 状态码。 */
  @Test
  void createChatCarriesOptionalDefaultWorkspacePath() throws Exception {
    when(chatService.createChat(any(ChatCreateDTO.class)))
        .thenAnswer(
            invocation -> {
              ChatCreateDTO dto = invocation.getArgument(0);
              ChatDTO dtoOut = new ChatDTO();
              dtoOut.setId("1");
              dtoOut.setTitle(dto.getTitle());
              dtoOut.setAgentName(dto.getAgentName());
              dtoOut.setWorkspacePath(dto.getWorkspacePath());
              return dtoOut;
            });

    mockMvc
        .perform(
            post("/api/ai/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"t\",\"agentName\":\"default-assistant\","
                        + "\"workspacePath\":\"src\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.workspacePath").value("src"));

    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertEquals("src", captor.getValue().getWorkspacePath());
  }

  /** 测试意图：验证 POST /api/ai/chats 省略 workspacePath 时字段为 null，返回 201 Created 状态码。 */
  @Test
  void createChatOmittingWorkspacePathLeavesItNull() throws Exception {
    when(chatService.createChat(any(ChatCreateDTO.class))).thenReturn(new ChatDTO());
    mockMvc
        .perform(
            post("/api/ai/chats")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"agentName\":\"default-assistant\"}"))
        .andExpect(status().isCreated());
    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertNull(captor.getValue().getWorkspacePath());
  }

  /** 测试意图：验证 PUT /api/ai/chats/{chatId} 响应显式保留 null workspacePath，返回 200 OK 状态码。 */
  @Test
  void chatWireJsonEmitsWorkspacePathExplicitlyIncludingNull() throws Exception {
    // @JsonInclude(ALWAYS)：null 显式序列化，前端/契约可区分缺省与显式 null。
    ChatDTO nullEnv = new ChatDTO();
    nullEnv.setId("7");
    nullEnv.setTitle("t");
    nullEnv.setAgentName("default-assistant");
    nullEnv.setWorkspacePath(null);
    nullEnv.setYoloEnabled(false);
    nullEnv.setVersion("2");
    when(chatService.updateChat(eq("7"), any(ChatUpdateDTO.class))).thenReturn(nullEnv);

    MvcResult result =
        mockMvc
            .perform(
                put("/api/ai/chats/7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedVersion\":\"2\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"workspacePath\":null"), body);
  }

  /** 测试意图：验证 PUT /api/ai/chats/{chatId} 区分显式 null 与缺省，body 中的 expectedVersion 正常解析，返回 200 OK。 */
  @Test
  void updateChatDistinguishesExplicitWorkspacePathNullFromOmission() throws Exception {
    when(chatService.updateChat(eq("7"), any(ChatUpdateDTO.class))).thenReturn(new ChatDTO());

    // 显式 null：清空默认环境（provided 标记置位）。
    mockMvc
        .perform(
            put("/api/ai/chats/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"workspacePath\":null,\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> clearCaptor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService).updateChat(eq("7"), clearCaptor.capture());
    assertTrue(clearCaptor.getValue().isWorkspacePathProvided());
    assertNull(clearCaptor.getValue().getWorkspacePath());

    // 缺省字段：provided 标记保持 false，服务端保留当前值。
    mockMvc
        .perform(
            put("/api/ai/chats/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> omitCaptor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService, times(2)).updateChat(eq("7"), omitCaptor.capture());
    assertFalse(omitCaptor.getValue().isWorkspacePathProvided());
  }
}
