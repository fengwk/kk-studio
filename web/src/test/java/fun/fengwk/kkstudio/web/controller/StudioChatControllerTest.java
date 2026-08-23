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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@link StudioChatController} Chat CRUD 与 owner Session listing 的 HTTP 契约。 */
class StudioChatControllerTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  private ChatService chatService;
  private HarnessOwnerQueryService harnessQueryService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    chatService = mock(ChatService.class);
    harnessQueryService = mock(HarnessOwnerQueryService.class);
    StudioChatController controller = new StudioChatController(chatService, harnessQueryService);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void listChatSessionsReturnsOwnerSummaries() throws Exception {
    HarnessSessionSummaryDTO summary = new HarnessSessionSummaryDTO();
    summary.setSessionId(idText(2));
    summary.setCreatedAt(Instant.parse("2026-08-10T00:00:00Z"));
    summary.setLastActivityAt(Instant.parse("2026-08-10T00:01:00Z"));
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
  void createChatCarriesOptionalDefaultEnvironmentName() throws Exception {
    when(chatService.createChat(any(ChatCreateDTO.class)))
        .thenAnswer(
            invocation -> {
              ChatCreateDTO dto = invocation.getArgument(0);
              ChatDTO dtoOut = new ChatDTO();
              dtoOut.setId("1");
              dtoOut.setTitle(dto.getTitle());
              dtoOut.setAgentName(dto.getAgentName());
              dtoOut.setEnvironment(dto.getEnvironment());
              return dtoOut;
            });

    mockMvc
        .perform(
            post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"t\",\"agentName\":\"default-assistant\","
                        + "\"environment\":{\"name\":\"env-dev\",\"workspacePath\":\".\"}}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.environment.name").value("env-dev"));

    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertEquals("env-dev", captor.getValue().getEnvironment().getName());
  }

  @Test
  void createChatOmittingEnvironmentNameLeavesItNull() throws Exception {
    when(chatService.createChat(any(ChatCreateDTO.class))).thenReturn(new ChatDTO());
    mockMvc
        .perform(
            post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"agentName\":\"default-assistant\"}"))
        .andExpect(status().isCreated());
    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertNull(captor.getValue().getEnvironment());
  }

  @Test
  void chatWireJsonEmitsEnvironmentNameExplicitlyIncludingNull() throws Exception {
    // @JsonInclude(ALWAYS)：null 显式序列化，前端/契约可区分缺省与显式 null。
    ChatDTO nullEnv = new ChatDTO();
    nullEnv.setId("7");
    nullEnv.setTitle("t");
    nullEnv.setAgentName("default-assistant");
    nullEnv.setEnvironment(null);
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
    // @JsonInclude(ALWAYS)：null 必须显式出现在 wire JSON 中（可区分缺省与显式 null）。
    String body = result.getResponse().getContentAsString();
    assertTrue(body.contains("\"environment\":null"), body);
  }

  @Test
  void updateChatDistinguishesExplicitEnvironmentNameNullFromOmission() throws Exception {
    when(chatService.updateChat(eq("7"), any(ChatUpdateDTO.class))).thenReturn(new ChatDTO());

    // 显式 null：清空默认环境（provided 标记置位）。
    mockMvc
        .perform(
            put("/api/ai/chat/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"environment\":null,\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> clearCaptor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService).updateChat(eq("7"), clearCaptor.capture());
    assertTrue(clearCaptor.getValue().isEnvironmentProvided());
    assertNull(clearCaptor.getValue().getEnvironment());

    // 缺省字段：provided 标记保持 false，服务端保留当前值。
    mockMvc
        .perform(
            put("/api/ai/chat/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> omitCaptor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService, times(2)).updateChat(eq("7"), omitCaptor.capture());
    assertFalse(omitCaptor.getValue().isEnvironmentProvided());
  }
}
