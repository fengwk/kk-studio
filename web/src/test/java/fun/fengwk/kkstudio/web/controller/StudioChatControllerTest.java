package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import fun.fengwk.kkstudio.core.ai.chat.service.ChatThreadCommandService;
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
import java.util.UUID;

/**
 * {@link StudioChatController} Chat-scoped Thread 编排契约：列表按关联顺序映射快照、创建先 createThread
 * 再关联并返回创建快照、关联前校验快照存在。
 */
class StudioChatControllerTest {

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static String idText(long value) {
    return id(value).toString();
  }

  private ChatService chatService;
  private ChatThreadService chatThreadService;
  private ChatThreadCommandService chatThreadCommandService;
  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    chatService = mock(ChatService.class);
    chatThreadService = mock(ChatThreadService.class);
    chatThreadCommandService = mock(ChatThreadCommandService.class);
    runtime = mock(HarnessRuntime.class);
    StudioChatController controller =
        new StudioChatController(chatService, chatThreadService, chatThreadCommandService, runtime);
    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void listChatThreadsReturnsMappedThreadsInAssociationOrder() throws Exception {
    when(chatThreadService.listThreadIds("7")).thenReturn(List.of(id(2), id(1)));
    when(runtime.getThreadSnapshot(id(2)))
        .thenReturn(HarnessRuntimeTestFixtures.continuationDueSnapshot(id(2)));
    when(runtime.getThreadSnapshot(id(1)))
        .thenReturn(HarnessRuntimeTestFixtures.idleSnapshot(id(1)));

    mockMvc
        .perform(get("/api/ai/chat/7/threads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].threadId").value(idText(2)))
        .andExpect(jsonPath("$.data[0].status").value("CONTINUATION_DUE"))
        .andExpect(jsonPath("$.data[1].threadId").value(idText(1)))
        .andExpect(jsonPath("$.data[1].status").value("IDLE"));
  }

  @Test
  void createChatThreadCreatesAssociatesAndReturnsCreatedSnapshot() throws Exception {
    when(chatThreadCommandService.createChatThread(any(), any(CreateThreadCommand.class)))
        .thenReturn(
            new CreatedThread(
                HarnessRuntimeTestFixtures.session(),
                HarnessRuntimeTestFixtures.rootEntry(),
                HarnessRuntimeTestFixtures.thread(id(1))));
    when(runtime.getThreadSnapshot(id(1))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    String body =
        """
        {
          "branchSettings": {
            "environment": {"name": "123e4567-e89b-12d3-a456-426614174000", "workspacePath": "."},
            "agentName": "default-assistant",
            "model": {"providerName": "openai", "modelName": "gpt-5", "variant": "default"},
            "activeTools": ["web_search"]
          },
          "yoloEnabled": true
        }
        """;

    mockMvc
        .perform(
            post("/api/ai/chat/7/threads").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.thread.threadId").value(idText(1)))
        .andExpect(jsonPath("$.data.thread.status").value("IDLE"))
        .andExpect(jsonPath("$.data.thread.branchSettings.agentName").value("default-assistant"));

    ArgumentCaptor<CreateThreadCommand> captor = ArgumentCaptor.forClass(CreateThreadCommand.class);
    verify(chatThreadCommandService).createChatThread(eq("7"), captor.capture());
    assertEquals("default-assistant", captor.getValue().branchSettings().agentName());
    assertEquals("gpt-5", captor.getValue().branchSettings().model().modelName());
    assertEquals(List.of("web_search"), captor.getValue().branchSettings().activeTools());
    assertEquals(true, captor.getValue().yoloEnabled());
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

  @Test
  void associateValidatesRuntimeSnapshotThenAssociates() throws Exception {
    when(runtime.getThreadSnapshot(id(5))).thenReturn(HarnessRuntimeTestFixtures.idleSnapshot());

    mockMvc.perform(put("/api/ai/chat/7/threads/" + idText(5))).andExpect(status().isNoContent());

    verify(runtime).getThreadSnapshot(id(5));
    verify(chatThreadService).associateThread("7", id(5));
  }

  @Test
  void associateMissingThreadIsNotFoundAndDoesNotAssociate() throws Exception {
    when(runtime.getThreadSnapshot(any()))
        .thenThrow(new HarnessRuntimeNotFoundException("thread 9 does not exist"));

    mockMvc.perform(put("/api/ai/chat/7/threads/" + idText(9))).andExpect(status().isNotFound());

    verify(chatThreadService, never()).associateThread(eq("7"), any());
  }

  @Test
  void createChatThreadRejectsIncompleteBranchSettingsAsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/ai/chat/7/threads")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"yoloEnabled\":true}"))
        .andExpect(status().isBadRequest());
    verify(runtime, never()).createThread(any(CreateThreadCommand.class));
  }

  @Test
  void createChatThreadRejectsUnknownBranchSettingsField() throws Exception {
    String body =
        """
        {
          "branchSettings": {
            "environment": null,
            "agentName": "default-assistant",
            "model": {"providerName": "openai", "modelName": "gpt-5", "variant": "default"},
            "activeTools": [],
            "unexpected": true
          },
          "yoloEnabled": false
        }
        """;

    mockMvc
        .perform(
            post("/api/ai/chat/7/threads").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest());
    verify(runtime, never()).createThread(any(CreateThreadCommand.class));
  }
}
