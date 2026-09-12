package fun.fengwk.kkstudio.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.RenameSessionCommand;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessModelSelectionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.web.advice.StudioResponseStatusErrorAdvice;
import fun.fengwk.kkstudio.web.i18n.StudioMessageService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Runtime Session 的 HTTP 契约：rename、Thread 摘要列表与 parent-linked Entry tree。 */
class StudioHarnessSessionControllerTest {

  private static final UUID SESSION_ID = new UUID(0L, 1L);

  private HarnessOwnerQueryService harnessQueryService;
  private HarnessRuntime runtime;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    harnessQueryService = mock(HarnessOwnerQueryService.class);
    runtime = mock(HarnessRuntime.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new StudioHarnessSessionController(harnessQueryService, runtime))
            .setControllerAdvice(
                new StudioResponseStatusErrorAdvice(new StudioMessageService()),
                new ResultResponseBodyAdvice())
            .build();
  }

  @Test
  void renamesSessionAndReturnsCanonicalDto() throws Exception {
    // 意图：PUT /name 调用 Runtime 重命名并返回权威 Session DTO（名称经 Core 规范化后回显）。
    when(runtime.renameSession(any(RenameSessionCommand.class)))
        .thenReturn(
            new Session(SESSION_ID, "canonical name", Instant.parse("2026-08-10T00:00:00Z")));

    mockMvc
        .perform(
            put("/api/harness/sessions/" + SESSION_ID + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  canonical name  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID.toString()))
        .andExpect(jsonPath("$.data.name").value("canonical name"));

    ArgumentCaptor<RenameSessionCommand> captor =
        ArgumentCaptor.forClass(RenameSessionCommand.class);
    verify(runtime).renameSession(captor.capture());
    assertEquals(SESSION_ID, captor.getValue().sessionId());
    assertEquals("canonical name", captor.getValue().name());
  }

  @Test
  void renameRejectsMissingBlankAndInvalidNamesAtTheHttpBoundary() throws Exception {
    // 意图：name missing / 显式 null / 非字符串 / blank 都在到达 Runtime 前被拒绝（400），绝不回显非法名称值。
    mockMvc
        .perform(
            put("/api/harness/sessions/" + SESSION_ID + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/harness/sessions/" + SESSION_ID + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":null}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/harness/sessions/" + SESSION_ID + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":42}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/harness/sessions/" + SESSION_ID + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}"))
        .andExpect(status().isBadRequest());
    verify(runtime, never()).renameSession(any(RenameSessionCommand.class));
  }

  @Test
  void renameRejectsOverlongNameWithoutEchoingTheSubmittedValue() throws Exception {
    // 意图：超长 name 由 Core 命令构造器拒绝为 400，且错误响应绝不回显用户提交的名称值（敏感数据不外泄）。
    String overlongName = "OVERLONG-SECRET-" + "x".repeat(257);
    String body = "{\"name\":\"" + overlongName + "\"}";
    String response =
        mockMvc
            .perform(
                put("/api/harness/sessions/" + SESSION_ID + "/name")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isBadRequest())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(response.contains(overlongName), "400 响应不得回显非法名称值");
    assertFalse(response.contains("OVERLONG-SECRET"), "400 响应不得回显敏感 marker");
    verify(runtime, never()).renameSession(any(RenameSessionCommand.class));
  }

  @Test
  void renameRejectsUnknownFieldsAndMapsMissingSessionToNotFound() throws Exception {
    // 意图：未知字段（strict DTO）与不存在的 Session 分别映射 400 / 404。
    mockMvc
        .perform(
            put("/api/harness/sessions/" + SESSION_ID + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\",\"extra\":true}"))
        .andExpect(status().isBadRequest());

    when(runtime.renameSession(any(RenameSessionCommand.class)))
        .thenThrow(new HarnessRuntimeNotFoundException("session is missing"));
    mockMvc
        .perform(
            put("/api/harness/sessions/" + SESSION_ID + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void listsThreadSummariesAndEntriesBySession() throws Exception {
    HarnessThreadSummaryDTO thread = new HarnessThreadSummaryDTO();
    thread.setThreadId(new UUID(0L, 2L).toString());
    thread.setName("thread");
    thread.setCreatedAt(Instant.parse("2026-08-10T00:00:00Z"));
    thread.setUpdatedAt(Instant.parse("2026-08-10T00:01:00Z"));
    thread.setStatus("IDLE");
    HarnessModelSelectionDTO model = new HarnessModelSelectionDTO();
    model.setProviderName("openai");
    model.setModelName("gpt-5");
    model.setVariant("default");
    thread.setModel(model);
    thread.setHeadMessagePreview("hello");
    when(harnessQueryService.listThreadSummaries(SESSION_ID)).thenReturn(List.of(thread));

    mockMvc
        .perform(get("/api/harness/sessions/" + SESSION_ID + "/threads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].threadId").value(thread.getThreadId()))
        .andExpect(jsonPath("$.data[0].name").value("thread"))
        .andExpect(jsonPath("$.data[0].status").value("IDLE"))
        .andExpect(jsonPath("$.data[0].model.providerName").value("openai"))
        .andExpect(jsonPath("$.data[0].model.modelName").value("gpt-5"))
        .andExpect(jsonPath("$.data[0].model.variant").value("default"))
        .andExpect(jsonPath("$.data[0].headMessagePreview").value("hello"));
    verify(harnessQueryService).listThreadSummaries(SESSION_ID);

    Entry root =
        new Entry(
            new UUID(0L, 3L),
            SESSION_ID,
            null,
            new RootPayload(
                new BranchSettings("assistant", new ModelSelection("openai", "gpt-5", "default"))),
            Instant.parse("2026-08-10T00:00:00Z"));
    when(harnessQueryService.listSessionEntries(SESSION_ID)).thenReturn(List.of(root));

    mockMvc
        .perform(get("/api/harness/sessions/" + SESSION_ID + "/entries"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].entryId").value(root.id().toString()))
        .andExpect(jsonPath("$.data[0].sessionId").value(SESSION_ID.toString()))
        .andExpect(jsonPath("$.data[0].parentEntryId").value((Object) null))
        .andExpect(jsonPath("$.data[0].entryType").value("ROOT"));
    verify(harnessQueryService).listSessionEntries(SESSION_ID);
  }

  @Test
  void translatesMissingSessionToNotFound() throws Exception {
    when(harnessQueryService.listThreadSummaries(SESSION_ID))
        .thenThrow(new HarnessRuntimeNotFoundException("session is missing"));

    mockMvc
        .perform(get("/api/harness/sessions/" + SESSION_ID + "/threads"))
        .andExpect(status().isNotFound());
  }
}
