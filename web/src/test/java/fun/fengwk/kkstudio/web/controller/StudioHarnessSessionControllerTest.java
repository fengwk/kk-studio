package fun.fengwk.kkstudio.web.controller;

import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.api.RenameSessionCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.history.SessionEntryPage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.Session;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.builtin.driver.BuiltinExecution;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.platform.catalog.dto.HarnessExecutionSelectionDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessThreadSummaryDTO;
import fun.fengwk.kkstudio.platform.harness.dto.HarnessUsageCostDTO;
import fun.fengwk.kkstudio.platform.harness.http.HarnessSessionController;
import fun.fengwk.kkstudio.platform.harness.thread.query.UsageCostProjectionService;
import fun.fengwk.kkstudio.platform.i18n.StudioMessageService;
import fun.fengwk.kkstudio.platform.orchestration.HarnessOwnerQueryService;
import fun.fengwk.kkstudio.web.HttpBoundaryConfiguration;
import fun.fengwk.kkstudio.web.StrictHttpJsonMapperTestSupport;
import fun.fengwk.kkstudio.web.advice.RequestBodyRequiredValidationAdvice;
import fun.fengwk.kkstudio.web.advice.StudioProblemAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runtime Session 的 HTTP 契约：rename、Thread 摘要列表与 parent-linked Entry tree。 */
class StudioHarnessSessionControllerTest {

  private static final UUID SESSION_ID = new UUID(0L, 1L);

  private HarnessOwnerQueryService harnessQueryService;
  private HarnessRuntime runtime;
  private UsageCostProjectionService usageCostProjectionService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    harnessQueryService = mock(HarnessOwnerQueryService.class);
    runtime = mock(HarnessRuntime.class);
    usageCostProjectionService = mock(UsageCostProjectionService.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new HarnessSessionController(
                    harnessQueryService, runtime, usageCostProjectionService))
            .setMessageConverters(
                new JacksonJsonHttpMessageConverter(StrictHttpJsonMapperTestSupport.strictHttp()))
            .setControllerAdvice(
                new StudioProblemAdvice(new StudioMessageService()),
                new RequestBodyRequiredValidationAdvice(StrictHttpJsonMapperTestSupport.get()),
                new HttpBoundaryConfiguration.CanonicalUuidBinding())
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
        .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
        .andExpect(jsonPath("$.name").value("canonical name"));

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
        .thenThrow(DomainException.notFound("session", "session is missing"));
    mockMvc
        .perform(
            put("/api/harness/sessions/" + SESSION_ID + "/name")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ok\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void listsThreadSummariesBySession() throws Exception {
    HarnessThreadSummaryDTO thread = new HarnessThreadSummaryDTO();
    thread.setThreadId(new UUID(0L, 2L).toString());
    thread.setName("thread");
    thread.setCreatedAt(Instant.parse("2026-08-10T00:00:00Z"));
    thread.setUpdatedAt(Instant.parse("2026-08-10T00:01:00Z"));
    thread.setStatus(ThreadRuntimeStatus.IDLE);
    HarnessExecutionSelectionDTO execution = new HarnessExecutionSelectionDTO();
    execution.setDriver("builtin");
    execution.setSettings(Map.of("modelName", "gpt-5", "variant", "default"));
    thread.setExecution(execution);
    thread.setHeadMessagePreview("hello");
    // 真实 Session HTTP 出口必须保留同名根/子及显式的根 null 父身份。
    HarnessThreadSummaryDTO child = new HarnessThreadSummaryDTO();
    child.setThreadId(new UUID(0L, 5L).toString());
    child.setParentThreadId(thread.getThreadId());
    child.setName(thread.getName());
    child.setCreatedAt(thread.getCreatedAt());
    child.setUpdatedAt(thread.getUpdatedAt());
    child.setStatus(thread.getStatus());
    child.setExecution(execution);
    when(harnessQueryService.listThreadSummaries(SESSION_ID)).thenReturn(List.of(thread, child));

    mockMvc
        .perform(get("/api/harness/sessions/" + SESSION_ID + "/threads"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].threadId").value(thread.getThreadId()))
        .andExpect(jsonPath("$[0]").value(hasKey("parentThreadId")))
        .andExpect(jsonPath("$[0].parentThreadId").value((Object) null))
        .andExpect(jsonPath("$[1].threadId").value(child.getThreadId()))
        .andExpect(jsonPath("$[1].parentThreadId").value(thread.getThreadId()))
        .andExpect(jsonPath("$[1].name").value("thread"))
        .andExpect(jsonPath("$[0].name").value("thread"))
        .andExpect(jsonPath("$[0].status").value("IDLE"))
        .andExpect(jsonPath("$[0].execution.driver").value("builtin"))
        .andExpect(jsonPath("$[0].execution.settings.modelName").value("gpt-5"))
        .andExpect(jsonPath("$[0].execution.settings.variant").value("default"))
        .andExpect(jsonPath("$[0].headMessagePreview").value("hello"));
    verify(harnessQueryService).listThreadSummaries(SESSION_ID);
  }

  @Test
  void listsEntryPageAndProjectsUsageForThatPageOnly() throws Exception {
    // 意图：Entry 列表返回 keyset 页包装；费用只投影本页，未计价 Entry 显式输出 null。
    Entry root =
        new Entry(
            new UUID(0L, 3L),
            SESSION_ID,
            null,
            new RootPayload(
                new BranchSettings(
                    "assistant",
                    BuiltinExecution.of(new ModelSelection("gpt-5", "default")),
                    null)),
            Instant.parse("2026-08-10T00:00:00Z"));
    Entry user =
        new Entry(
            new UUID(0L, 4L),
            SESSION_ID,
            root.id(),
            new MessagePayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                null,
                null),
            Instant.parse("2026-08-10T00:00:01Z"));
    List<Entry> entries = List.of(root, user);
    HarnessUsageCostDTO cost = new HarnessUsageCostDTO();
    cost.setCurrency("USD");
    cost.setAmount("0.010000000000");
    when(harnessQueryService.listSessionEntries(SESSION_ID, null, 100))
        .thenReturn(new SessionEntryPage(entries, false));
    when(usageCostProjectionService.project(entries)).thenReturn(Map.of(user.id(), cost));

    mockMvc
        .perform(get("/api/harness/sessions/" + SESSION_ID + "/entries"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].entryId").value(root.id().toString()))
        .andExpect(jsonPath("$.items[0].sessionId").value(SESSION_ID.toString()))
        .andExpect(jsonPath("$.items[0].parentEntryId").value((Object) null))
        .andExpect(jsonPath("$.items[0].entryType").value("ROOT"))
        .andExpect(jsonPath("$.items[0].usageCost").value((Object) null))
        .andExpect(jsonPath("$.items[1].entryId").value(user.id().toString()))
        .andExpect(jsonPath("$.items[1].entryType").value("MESSAGE"))
        .andExpect(jsonPath("$.items[1].usageCost.currency").value("USD"))
        .andExpect(jsonPath("$.items[1].usageCost.amount").value("0.010000000000"))
        .andExpect(jsonPath("$.nextCursor").value((Object) null));
    verify(harnessQueryService).listSessionEntries(SESSION_ID, null, 100);
    verify(usageCostProjectionService).project(entries);
  }

  @Test
  void entryPageUsesKeysetCursorAndRejectsInvalidBounds() throws Exception {
    UUID cursor = new UUID(0L, 7L);
    Entry root =
        new Entry(
            new UUID(0L, 8L),
            SESSION_ID,
            null,
            new RootPayload(
                new BranchSettings(
                    "assistant",
                    BuiltinExecution.of(new ModelSelection("gpt-5", "default")),
                    null)),
            Instant.parse("2026-08-10T00:00:00Z"));
    List<Entry> entries = List.of(root);
    when(harnessQueryService.listSessionEntries(SESSION_ID, cursor, 25))
        .thenReturn(new SessionEntryPage(entries, true));
    when(usageCostProjectionService.project(entries)).thenReturn(Map.of());

    mockMvc
        .perform(
            get("/api/harness/sessions/" + SESSION_ID + "/entries")
                .param("cursor", cursor.toString())
                .param("limit", "25"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].entryId").value(root.id().toString()))
        .andExpect(jsonPath("$.nextCursor").value(root.id().toString()));
    verify(harnessQueryService).listSessionEntries(SESSION_ID, cursor, 25);

    // 分页边界在到达查询服务前拒绝：0、超过上限、非十进制与非法游标都是 400。
    for (String limit : List.of("0", "201", "abc")) {
      mockMvc
          .perform(get("/api/harness/sessions/" + SESSION_ID + "/entries").param("limit", limit))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(
            get("/api/harness/sessions/" + SESSION_ID + "/entries").param("cursor", "not-a-uuid"))
        .andExpect(status().isBadRequest());
    verify(harnessQueryService, times(1)).listSessionEntries(any(), any(), anyInt());
  }

  @Test
  void translatesMissingSessionToNotFound() throws Exception {
    when(harnessQueryService.listThreadSummaries(SESSION_ID))
        .thenThrow(DomainException.notFound("session", "session is missing"));

    mockMvc
        .perform(get("/api/harness/sessions/" + SESSION_ID + "/threads"))
        .andExpect(status().isNotFound());
  }
}
