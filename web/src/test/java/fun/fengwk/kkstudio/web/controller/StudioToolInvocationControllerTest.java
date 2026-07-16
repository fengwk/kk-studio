package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.fengwk.kkstudio.core.harness.run.service.DatabaseToolPreparationPort;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.core.harness.tool.configuration.ToolSettingsProperties;
import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionTree;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionMode;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.harness.tool.schema.ToolStringSchema;
import fun.fengwk.kkstudio.web.WebTestApplication;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
class StudioToolInvocationControllerTest {
  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

  @Autowired private MockMvc mockMvc;
  @Autowired private ToolSettingsProperties toolSettingsProperties;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIds;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private HarnessRunTransactionService transactions;
  @Autowired private DatabaseToolPreparationPort preparationPort;
  @Autowired private MysqlToolInvocationStore invocationStore;
  @Autowired private SessionTree sessionTree;
  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
    jdbcTemplate.update("delete from tool_invocation");
    jdbcTemplate.update("delete from harness_run_event");
    jdbcTemplate.update("delete from harness_run");
    jdbcTemplate.update("delete from harness_session_entry");
    jdbcTemplate.update("delete from harness_session");
  }

  /** Global Decision API 同决定幂等，冲突返回 409。 */
  @Test
  void decidesPermissionIdempotentlyAndRejectsConflictGlobally() throws Exception {
    configureToolSettings("{\"permission\":{\"write\":\"ask\"}}");
    ToolInvocation invocation = askInvocation();
    String endpoint = "/api/tool-invocations/" + invocation.id() + "/decision";

    mockMvc
        .perform(
            post(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"allow\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.permissionDecision").value("ALLOW"));
    mockMvc
        .perform(
            post(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"allow\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.permissionDecision").value("ALLOW"));
    mockMvc
        .perform(
            post(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"deny\"}"))
        .andExpect(status().isConflict());

    ToolInvocation denied = askInvocation();
    mockMvc
        .perform(
            post("/api/tool-invocations/{invocationId}/decision", denied.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"deny\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("FAILED"))
        .andExpect(jsonPath("$.data.permissionDecision").value("DENY"))
        .andExpect(jsonPath("$.data.errorMessage").value("Permission denied by user for write."));
  }

  /** Global YOLO set 支持 Child 动态 Root 读取，无 active Run 也可由 GET 观察。 */
  @Test
  void setsAndReadsChildRootYoloWithoutActiveRun() throws Exception {
    configureToolSettings("{\"defaultYolo\":true}");
    Session root = sessionTree.create(null, "root");
    Session child = sessionTree.fork(root.id(), null);

    mockMvc
        .perform(get("/api/sessions/{sessionId}/yolo", child.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rootSessionId").value(Long.toString(root.id())))
        .andExpect(jsonPath("$.data.enabled").value(true));
    mockMvc
        .perform(
            put("/api/sessions/{sessionId}/yolo", child.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rootSessionId").value(Long.toString(root.id())))
        .andExpect(jsonPath("$.data.enabled").value(false));
    mockMvc
        .perform(get("/api/sessions/{sessionId}/yolo", root.id()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.enabled").value(false));
    mockMvc
        .perform(
            put("/api/sessions/{sessionId}/yolo", child.id())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  private ToolInvocation askInvocation() {
    long sessionId = sessionIds.newSessionId();
    sessionStore.create(Session.root(sessionId, null, "session", false, NOW));
    long snapshotId = sessionIds.newEntryId();
    AgentSnapshotEntryPayload snapshot =
        new AgentSnapshotEntryPayload(
            new AgentSnapshot("system", "model", "default", List.of(), List.of(), List.of(), "{}"));
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, snapshot.type(), snapshot, NOW),
        null,
        0L);
    AgentRun queued =
        transactions.submitUserMessage(sessionId, snapshotId, user(), NOW.plusMillis(1));
    AgentRun claimed =
        runStore.claimDue("web-worker", NOW.plusMillis(1), Duration.ofMinutes(1)).orElseThrow();
    ToolCall call = new ToolCall("call", "write", "{\"path\":\"notes.txt\"}");
    preparationPort.prepare(
        claimed,
        assistant(List.of(call)),
        List.of(call),
        List.of(binding()),
        Path.of("/tmp/environment"),
        Path.of("/tmp/environment"),
        List.of(
            new RunEventDraft(
                RunEventType.ASSISTANT_COMPLETED, RunEventPayloads.forAttempt(claimed))),
        NOW.plusSeconds(1));
    return invocationStore.listByRun(queued.id()).get(0);
  }

  private void configureToolSettings(String settingsJson) {
    toolSettingsProperties.setSettingsJson(settingsJson);
  }

  private static AgentMessage user() {
    return new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("go")));
  }

  private static MessageEntryPayload assistant(List<ToolCall> calls) {
    List<AgentMessageContent> contents = new ArrayList<>();
    contents.add(new TextMessageContent("calling"));
    calls.forEach(
        call ->
            contents.add(
                new ToolCallMessageContent(call.id(), call.toolName(), call.argumentsJson())));
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(
            ProviderStopReason.TOOL_CALLS,
            new ModelUsage(1, 1, 0, 0, 0, 0, 2),
            new ModelCost(
                "USD",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO)));
  }

  private static ToolBinding binding() {
    return ToolBinding.of(
        new ToolDescriptor(
            "write",
            "1",
            "write",
            null,
            new ToolParamsSchema(
                "", Map.of("path", new ToolStringSchema("path")), Set.of("path"), false),
            ToolExecutionMode.CLOUD,
            ToolSideEffect.IDEMPOTENT,
            Duration.ofSeconds(30)));
  }
}
