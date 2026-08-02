package fun.fengwk.kkstudio.core.ai.runtime.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.ai.runtime.session.service.HarnessSessionQueryService;
import fun.fengwk.kkstudio.core.ai.runtime.thread.command.TestThreads;
import fun.fengwk.kkstudio.core.ai.runtime.thread.service.HarnessThreadQueryService;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionEntryDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadSnapshotDTO;

import java.time.Instant;
import java.util.List;

/** PostgreSQL query coverage for the bound Thread and name-reference projections. */
class PostgresqlHarnessQueryServiceIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private ThreadCommandTransactions transactions;
  @Autowired private HarnessSessionQueryService sessionQueryService;
  @Autowired private HarnessThreadQueryService threadQueryService;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void createsAndReadsTheDerivedSessionAndRootPath() {
    TestThreads.Created created = TestThreads.create(transactions, "query-title", NOW);
    HarnessThreadDTO thread = threadQueryService.getThread(Long.toString(created.threadId()));

    assertEquals(Long.toString(created.threadId()), thread.getThreadId());
    assertEquals(Long.toString(created.sessionId()), thread.getSessionId());
    assertEquals(Long.toString(created.rootEntryId()), thread.getHeadEntryId());
    assertEquals("query-title", thread.getSessionTitle());
    assertEquals("IDLE", thread.getStatus());
    assertEquals(0L, thread.getInputSequence());
    assertNotNull(thread.getRevision());

    HarnessSessionDTO session = sessionQueryService.getSession(Long.toString(created.sessionId()));
    assertEquals("query-title", session.getTitle());
    List<HarnessSessionEntryDTO> entries =
        sessionQueryService.listEntries(Long.toString(created.sessionId()));
    assertEquals(1, entries.size());
    assertEquals("ROOT", entries.getFirst().getEntryType());
    assertEquals(Long.toString(created.rootEntryId()), entries.getFirst().getEntryId());
  }

  @Test
  void snapshotProjectsQueuedInputWithItsTurnSettings() throws Exception {
    TestThreads.Created created =
        TestThreads.create(transactions, "input-query", "environment-a", NOW);
    TurnSettings settings = new TurnSettings("agent-a", true);
    RuntimeEntryInputPayload payload =
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE,
            new MessageEntryPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                settings,
                null));
    transactions.enqueue(created.threadId(), payload, "input-1", created.executionEpoch(), NOW);

    HarnessThreadSnapshotDTO snapshot =
        threadQueryService.getSnapshot(Long.toString(created.threadId()));
    assertEquals(1, snapshot.getInputs().size());
    HarnessThreadInputDTO input = snapshot.getInputs().getFirst();
    JsonNode turnSettings = JSON.readTree(input.getPayloadJson()).path("turnSettings");
    assertEquals("USER_MESSAGE", input.getInputType());
    assertEquals("QUEUED", input.getStatus());
    assertEquals("input-1", input.getClientMessageId());
    assertEquals("environment-a", snapshot.getThread().getEnvironmentName());
    assertEquals("agent-a", turnSettings.path("agentName").asText());
    assertTrue(turnSettings.path("yoloEnabled").asBoolean());
    assertEquals(1, snapshot.getEntries().size());
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select count(*) from harness_thread_input where thread_id = ?",
            Long.class,
            created.threadId()));
  }

  @Test
  void unknownThreadAndSessionIdsRemainBoundaryErrors() {
    assertThrows(
        IllegalArgumentException.class,
        () -> threadQueryService.getThread(String.valueOf(Long.MAX_VALUE)));
    assertThrows(
        IllegalArgumentException.class, () -> sessionQueryService.getSession("not-a-number"));
  }
}
