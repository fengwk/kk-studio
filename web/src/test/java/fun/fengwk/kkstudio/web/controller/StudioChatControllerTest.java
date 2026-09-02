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

/** {@link StudioChatController} HTTP 契约测试。 */
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

  @Test
  void listChatSessionsForwardsChatIdAndReturnsResults() throws Exception {
    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(idText(2));
    summary.setFirstMessagePreview("hello");
    summary.setThreadCount(2);

    when(harnessQueryService.listChatSessions(id(7))).thenReturn(List.of(summary));

    mockMvc
        .perform(get("/api/ai/chat/" + idText(7) + "/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sessionId").value(idText(2)))
        .andExpect(jsonPath("$.data[0].firstMessagePreview").value("hello"))
        .andExpect(jsonPath("$.data[0].threadCount").value(2));
    verify(harnessQueryService).listChatSessions(id(7));
  }

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
            post("/api/ai/chat")
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

  @Test
  void createChatOmittingWorkspacePathLeavesItNull() throws Exception {
    when(chatService.createChat(any(ChatCreateDTO.class))).thenReturn(new ChatDTO());
    mockMvc
        .perform(
            post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"agentName\":\"default-assistant\"}"))
        .andExpect(status().isCreated());
    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertNull(captor.getValue().getWorkspacePath());
  }

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
                put("/api/ai/chat/7")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedVersion\":\"2\"}"))
            .andExpect(status().isOk())
            .andReturn();
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"workspacePath\":null"), body);
  }

  @Test
  void updateChatDistinguishesExplicitWorkspacePathNullFromOmission() throws Exception {
    when(chatService.updateChat(eq("7"), any(ChatUpdateDTO.class))).thenReturn(new ChatDTO());

    // 显式 null：清空默认环境（provided 标记置位）。
    mockMvc
        .perform(
            put("/api/ai/chat/7")
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
            put("/api/ai/chat/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> omitCaptor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService, times(2)).updateChat(eq("7"), omitCaptor.capture());
    assertFalse(omitCaptor.getValue().isWorkspacePathProvided());
  }
}
