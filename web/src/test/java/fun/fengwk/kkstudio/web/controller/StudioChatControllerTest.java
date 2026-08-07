package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

import fun.fengwk.kkstudio.core.ai.chat.service.ChatService;
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadService;
import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.share.ai.chat.ChatCreateDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatDTO;
import fun.fengwk.kkstudio.share.ai.chat.ChatUpdateDTO;
import fun.fengwk.kkstudio.web.runtime.HarnessRuntimeTestFixtures;

import java.util.List;

/**
 * {@link StudioChatController} Chat-scoped Thread 编排契约：列表按关联顺序映射快照、创建先 createThread
 * 再关联并返回创建快照、关联前校验快照存在。
 */
class StudioChatControllerTest {

  private ChatService chatService;
  private ChatThreadService chatThreadService;
  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    chatService = mock(ChatService.class);
    chatThreadService = mock(ChatThreadService.class);
    runtime = mock(HarnessRuntime.class);
    StudioChatController controller =
        new StudioChatController(chatService, chatThreadService, runtime);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void listChatThreadsReturnsMappedThreadsInAssociationOrder() throws Exception {
    when(chatThreadService.listThreadIds("7")).thenReturn(List.of(2L, 1L));
    when(runtime.getThreadSnapshot(2L))
        .thenReturn(HarnessRuntimeTestFixtures.continuationDueSnapshot(2L));
    when(runtime.getThreadSnapshot(1L)).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot(1L));

    mockMvc
        .perform(get("/api/ai/chat/7/threads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].threadId").value("2"))
        .andExpect(jsonPath("$.data[0].status").value("CONTINUATION_DUE"))
        .andExpect(jsonPath("$.data[1].threadId").value("1"))
        .andExpect(jsonPath("$.data[1].status").value("IDLE"));
  }

  @Test
  void createChatThreadCreatesAssociatesAndReturnsCreatedSnapshot() throws Exception {
    when(runtime.createThread(any(CreateThreadCommand.class)))
        .thenReturn(
            new CreatedThread(
                HarnessRuntimeTestFixtures.session(),
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.thread(1)));
    when(runtime.getThreadSnapshot(1L)).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    String body =
        """
        {
          "title": "new chat",
          "branchSettings": {
            "environmentName": "123e4567-e89b-12d3-a456-426614174000",
            "agentName": "default-assistant",
            "model": {"providerName": "openai", "modelName": "gpt-5", "variant": "default"},
            "thinkingLevel": "low",
            "activeTools": ["web_search"]
          },
          "yoloEnabled": true
        }
        """;

    mockMvc
        .perform(
            post("/api/ai/chat/7/threads").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.thread.threadId").value("1"))
        .andExpect(jsonPath("$.data.thread.status").value("IDLE"))
        .andExpect(jsonPath("$.data.thread.branchSettings.agentName").value("default-assistant"));

    ArgumentCaptor<CreateThreadCommand> captor = ArgumentCaptor.forClass(CreateThreadCommand.class);
    verify(runtime).createThread(captor.capture());
    assertEquals("new chat", captor.getValue().title());
    assertEquals("default-assistant", captor.getValue().branchSettings().agentName());
    assertEquals("gpt-5", captor.getValue().branchSettings().model().modelName());
    assertEquals(List.of("web_search"), captor.getValue().branchSettings().activeTools());
    assertEquals(true, captor.getValue().yoloEnabled());
    verify(chatThreadService).associateThread("7", 1L);
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
              dtoOut.setEnvironmentName(dto.getEnvironmentName());
              return dtoOut;
            });

    mockMvc
        .perform(
            post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"title\":\"t\",\"agentName\":\"default-assistant\","
                        + "\"environmentName\":\"env-dev\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.environmentName").value("env-dev"));

    ArgumentCaptor<ChatCreateDTO> captor = ArgumentCaptor.forClass(ChatCreateDTO.class);
    verify(chatService).createChat(captor.capture());
    assertEquals("env-dev", captor.getValue().getEnvironmentName());
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
    assertNull(captor.getValue().getEnvironmentName());
  }

  @Test
  void chatWireJsonEmitsEnvironmentNameExplicitlyIncludingNull() throws Exception {
    // @JsonInclude(ALWAYS)：null 显式序列化，前端/契约可区分缺省与显式 null。
    ChatDTO nullEnv = new ChatDTO();
    nullEnv.setId("7");
    nullEnv.setTitle("t");
    nullEnv.setAgentName("default-assistant");
    nullEnv.setEnvironmentName(null);
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
    assertTrue(body.contains("\"environmentName\":null"), body);
  }

  @Test
  void updateChatDistinguishesExplicitEnvironmentNameNullFromOmission() throws Exception {
    when(chatService.updateChat(eq("7"), any(ChatUpdateDTO.class))).thenReturn(new ChatDTO());

    // 显式 null：清空默认环境（provided 标记置位）。
    mockMvc
        .perform(
            put("/api/ai/chat/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"environmentName\":null,\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> clearCaptor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService).updateChat(eq("7"), clearCaptor.capture());
    assertTrue(clearCaptor.getValue().isEnvironmentNameProvided());
    assertNull(clearCaptor.getValue().getEnvironmentName());

    // 缺省字段：provided 标记保持 false，服务端保留当前值。
    mockMvc
        .perform(
            put("/api/ai/chat/7")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"2\"}"))
        .andExpect(status().isOk());
    ArgumentCaptor<ChatUpdateDTO> omitCaptor = ArgumentCaptor.forClass(ChatUpdateDTO.class);
    verify(chatService, times(2)).updateChat(eq("7"), omitCaptor.capture());
    assertFalse(omitCaptor.getValue().isEnvironmentNameProvided());
  }

  @Test
  void associateValidatesRuntimeSnapshotThenAssociates() throws Exception {
    when(runtime.getThreadSnapshot(5L)).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc.perform(put("/api/ai/chat/7/threads/5")).andExpect(status().isNoContent());

    verify(runtime).getThreadSnapshot(5L);
    verify(chatThreadService).associateThread("7", 5L);
  }

  @Test
  void associateMissingThreadIsNotFoundAndDoesNotAssociate() throws Exception {
    when(runtime.getThreadSnapshot(anyLong()))
        .thenThrow(new HarnessRuntimeNotFoundException("thread 9 does not exist"));

    mockMvc.perform(put("/api/ai/chat/7/threads/9")).andExpect(status().isNotFound());

    verify(chatThreadService, never()).associateThread(eq("7"), anyLong());
  }

  @Test
  void createChatThreadRejectsIncompleteBranchSettingsAsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/ai/chat/7/threads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"broken\",\"yoloEnabled\":true}"))
        .andExpect(status().isBadRequest());
    verify(runtime, never()).createThread(any(CreateThreadCommand.class));
  }
}
