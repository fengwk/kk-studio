package fun.fengwk.kkstudio.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** steer/follow-up 全局 API 的真实持久化契约测试。 */
@AutoConfigureMockMvc
@SpringBootTest(classes = WebTestApplication.class)
public class StudioRunControlControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private StubProviderManager stubProviderManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MysqlHarnessSessionStore sessionStore;
  @Autowired private SnowflakeSessionIdGenerator sessionIdGen;
  @Autowired private HarnessRunTransactionService runTransactions;
  @Autowired private MysqlHarnessRunStore runStore;
  @Autowired private Clock harnessRunClock;

  @BeforeEach
  public void resetState() {
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

  // ---- helpers ----

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
                new ModelUsage(1, 1, 0, 0, 0),
                new ModelCost("USD", BigDecimal.ZERO)));
    RunEventDraft completed =
        new RunEventDraft(
            RunEventType.ASSISTANT_COMPLETED,
            RunEventPayloads.forAttempt(run, "stopReason", ProviderStopReason.COMPLETED.name()));
    runTransactions.complete(run, assistant, completed, now);
  }
}
