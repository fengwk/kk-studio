package fun.fengwk.kkstudio.web.controller;

import static fun.fengwk.kkstudio.web.HarnessUsageFixtures.completedUsageDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fun.fengwk.kkstudio.core.harness.run.service.HarnessRunTransactionService;
import fun.fengwk.kkstudio.core.harness.run.store.MysqlHarnessRunStore;
import fun.fengwk.kkstudio.core.harness.session.store.MysqlHarnessSessionStore;
import fun.fengwk.kkstudio.core.harness.session.store.SnowflakeSessionIdGenerator;
import fun.fengwk.kkstudio.harness.model.ModelCost;
import fun.fengwk.kkstudio.harness.model.ModelUsage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.run.AgentRun;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventDraft;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventPayloads;
import fun.fengwk.kkstudio.harness.runtime.run.RunEventType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshotEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.web.WebTestApplication;
import fun.fengwk.kkstudio.web.testing.StubProviderManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** steer/follow-up/abort 全局 API 的真实持久化契约测试。 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioRunControlControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private StubProviderManager stubProviderManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIdGen;
  @Autowired private HarnessRunTransactionService runTransactions;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private Clock harnessRunClock;

  @BeforeEach
  public void resetState() {
    jdbc.update("delete from model_usage_record");
    stubProviderManager.reset();
    jdbc.update("delete from harness_run_control_message");
    jdbc.update("delete from harness_run_event");
    jdbc.update("delete from harness_run");
    jdbc.update("delete from harness_session_entry");
    jdbc.update("delete from harness_session");
  }

  @Test
  public void shouldCreateSteerControlForActiveSession() throws Exception {
    long sessionId = seedSessionAndUserRun();

    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/steer", Long.toString(sessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"please steer\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.kind").value("STEER"))
        .andExpect(jsonPath("$.data.consumptionMode").exists())
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.content").value("please steer"))
        .andExpect(jsonPath("$.data.id").exists())
        .andExpect(jsonPath("$.data.sessionId").value(Long.toString(sessionId)))
        .andExpect(jsonPath("$.data.runId").exists())
        .andExpect(jsonPath("$.data.consumedRunId").doesNotExist());
  }

  @Test
  public void shouldCreateFollowUpControlForActiveSession() throws Exception {
    long sessionId = seedSessionAndUserRun();

    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/follow-ups", Long.toString(sessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"queue me\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.kind").value("FOLLOW_UP"))
        .andExpect(jsonPath("$.data.status").value("PENDING"))
        .andExpect(jsonPath("$.data.id").exists())
        .andExpect(jsonPath("$.data.sessionId").value(Long.toString(sessionId)));
  }

  @Test
  public void shouldPromoteFollowUpWhenNoActiveRun() throws Exception {
    long sessionId = seedSessionAndUserRun();
    completeActiveRun(sessionId);

    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/follow-ups", Long.toString(sessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"queue me\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.kind").value("FOLLOW_UP"))
        .andExpect(jsonPath("$.data.status").value("PROMOTED"))
        .andExpect(jsonPath("$.data.consumedRunId").exists())
        .andExpect(jsonPath("$.data.consumedEntryId").exists())
        .andExpect(jsonPath("$.data.id").exists());
  }

  @Test
  public void shouldConflictForNoActiveSteer() throws Exception {
    long sessionId = seedSessionAndUserRun();
    completeActiveRun(sessionId);

    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/steer", Long.toString(sessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"no active\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  public void shouldRejectBlankContent() throws Exception {
    long sessionId = seedSessionAndUserRun();

    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/steer", Long.toString(sessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"   \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectMissingContent() throws Exception {
    long sessionId = seedSessionAndUserRun();

    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/follow-ups", Long.toString(sessionId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectNonNumericSessionId() throws Exception {
    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/steer", "abc")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldRejectNonPositiveSessionId() throws Exception {
    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/steer", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  public void shouldReturnNotFoundForUnknownSession() throws Exception {
    mockMvc
        .perform(
            post("/api/sessions/{sessionId}/steer", "9999999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  public void shouldNotExposeWorkspacesRoute() throws Exception {
    mockMvc
        .perform(
            post("/api/workspaces/{sessionId}/steer", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\"}"))
        .andExpect(status().isNotFound());
  }

  /** 活跃 RUNNING Run 首次 abort 返回完整字符串标识，并持久化取消时间。 */
  @Test
  public void shouldAbortActiveRunningRun() throws Exception {
    long sessionId = seedSessionAndUserRun();
    long runId = activeRunId(sessionId);

    mockMvc
        .perform(post("/api/sessions/{sessionId}/abort", Long.toString(sessionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(Long.toString(sessionId)))
        .andExpect(jsonPath("$.data.runId").value(Long.toString(runId)))
        .andExpect(jsonPath("$.data.newlyRequested").value(true))
        .andExpect(jsonPath("$.data.status").value("RUNNING"))
        .andExpect(jsonPath("$.data.requestedAt").exists());

    assertNotNull(cancelRequestedAt(runId));
  }

  /** 重复 abort 保持首次请求时间，并且只追加一个 ABORT_REQUESTED 事件。 */
  @Test
  public void shouldKeepRepeatedAbortIdempotent() throws Exception {
    long sessionId = seedSessionAndUserRun();
    long runId = activeRunId(sessionId);

    MvcResult firstResult =
        mockMvc
            .perform(post("/api/sessions/{sessionId}/abort", Long.toString(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.newlyRequested").value(true))
            .andExpect(jsonPath("$.data.requestedAt").exists())
            .andReturn();
    JsonNode firstData =
        objectMapper.readTree(firstResult.getResponse().getContentAsString()).path("data");
    LocalDateTime firstRequestedAt =
        objectMapper.treeToValue(firstData.path("requestedAt"), LocalDateTime.class);
    LocalDateTime storedRequestedAt = cancelRequestedAt(runId);
    assertNotNull(storedRequestedAt);
    assertEquals(storedRequestedAt, firstRequestedAt);

    MvcResult repeatedResult =
        mockMvc
            .perform(post("/api/sessions/{sessionId}/abort", Long.toString(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.sessionId").value(Long.toString(sessionId)))
            .andExpect(jsonPath("$.data.runId").value(Long.toString(runId)))
            .andExpect(jsonPath("$.data.newlyRequested").value(false))
            .andExpect(jsonPath("$.data.status").value("RUNNING"))
            .andExpect(jsonPath("$.data.requestedAt").exists())
            .andReturn();
    JsonNode repeatedData =
        objectMapper.readTree(repeatedResult.getResponse().getContentAsString()).path("data");
    LocalDateTime repeatedRequestedAt =
        objectMapper.treeToValue(repeatedData.path("requestedAt"), LocalDateTime.class);

    assertEquals(storedRequestedAt, repeatedRequestedAt);
    assertEquals(storedRequestedAt, cancelRequestedAt(runId));
    assertEquals(1L, abortRequestedEventCount(runId));
  }

  /** 无活跃 Run 时 abort 成功返回空 Run 投影，且不创建新请求。 */
  @Test
  public void shouldReturnEmptyAbortResultWhenNoActiveRun() throws Exception {
    long sessionId = seedSessionAndUserRun();
    completeActiveRun(sessionId);

    mockMvc
        .perform(post("/api/sessions/{sessionId}/abort", Long.toString(sessionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value(Long.toString(sessionId)))
        .andExpect(jsonPath("$.data.runId").doesNotExist())
        .andExpect(jsonPath("$.data.newlyRequested").value(false))
        .andExpect(jsonPath("$.data.status").doesNotExist())
        .andExpect(jsonPath("$.data.requestedAt").doesNotExist());
  }

  /** abort 拒绝非数字 sessionId。 */
  @Test
  public void shouldRejectNonNumericAbortSessionId() throws Exception {
    mockMvc
        .perform(post("/api/sessions/{sessionId}/abort", "abc"))
        .andExpect(status().isBadRequest());
  }

  /** abort 拒绝非正 sessionId。 */
  @Test
  public void shouldRejectNonPositiveAbortSessionId() throws Exception {
    mockMvc
        .perform(post("/api/sessions/{sessionId}/abort", "0"))
        .andExpect(status().isBadRequest());
  }

  /** abort 未知 session 时返回 404。 */
  @Test
  public void shouldReturnNotFoundWhenAbortingUnknownSession() throws Exception {
    mockMvc
        .perform(post("/api/sessions/{sessionId}/abort", "9999999999"))
        .andExpect(status().isNotFound());
  }

  /** 旧 workspaces abort 路由不得暴露。 */
  @Test
  public void shouldNotExposeWorkspaceAbortRoute() throws Exception {
    mockMvc
        .perform(post("/api/workspaces/{sessionId}/abort", "1"))
        .andExpect(status().isNotFound());
  }

  // ---- helpers ----

  private long activeRunId(long sessionId) {
    return sessionStore.find(sessionId).orElseThrow().activeRunId();
  }

  private LocalDateTime cancelRequestedAt(long runId) {
    return jdbc.queryForObject(
        "select cancel_requested_at from harness_run where id=?", LocalDateTime.class, runId);
  }

  private long abortRequestedEventCount(long runId) {
    Long count =
        jdbc.queryForObject(
            "select count(*) from harness_run_event where run_id=? and event_type=?",
            Long.class,
            runId,
            RunEventType.ABORT_REQUESTED.value());
    return count == null ? 0L : count;
  }

  private long seedSessionAndUserRun() {
    Instant now = harnessRunClock.instant();
    Instant createdAt = now.minusSeconds(1);
    long sessionId = sessionIdGen.newSessionId();
    Session session = Session.root(sessionId, 1L, "control-test", false, createdAt);
    sessionStore.create(session);
    long snapshotId = sessionIdGen.newEntryId();
    AgentSnapshot snapshot =
        new AgentSnapshot(
            "system", "model-default", "default", List.of(), List.of(), List.of(), "{}");
    AgentSnapshotEntryPayload payload = new AgentSnapshotEntryPayload(snapshot);
    sessionStore.append(
        new SessionEntry(snapshotId, sessionId, null, null, payload.type(), payload, createdAt),
        null,
        0L);
    runTransactions.submitUserMessage(
        sessionId,
        snapshotId,
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hi"))),
        createdAt);
    runStore.claimDue("web-control", now, Duration.ofSeconds(30)).orElseThrow();
    return sessionId;
  }

  private void completeActiveRun(long sessionId) {
    Instant now = harnessRunClock.instant();
    Session session = sessionStore.find(sessionId).orElseThrow();
    AgentRun run = runStore.find(session.activeRunId()).orElseThrow();
    MessageEntryPayload assistant =
        new MessageEntryPayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("done"))),
            new AssistantMessageMetadata(
                ProviderStopReason.COMPLETED,
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
    RunEventDraft completed =
        new RunEventDraft(
            RunEventType.ASSISTANT_COMPLETED,
            RunEventPayloads.forAttempt(run, "stopReason", ProviderStopReason.COMPLETED.name()));
    runTransactions.complete(run, assistant, completedUsageDraft(), completed, now);
  }
}
