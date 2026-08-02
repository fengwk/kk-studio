package fun.fengwk.kkstudio.core.ai.runtime.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** PostgreSQL command transaction coverage for the final bound-thread contract. */
class PostgresqlThreadCommandTransactionsIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private ThreadCommandTransactions transactions;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void createThreadAtomicallyCreatesSessionRootAndBoundHead() {
    HarnessThread thread = transactions.createThread("atomic-title", NOW);

    assertTrue(thread.headEntryId() > 0);
    assertEquals(0L, thread.executionEpoch());
    assertEquals(0L, thread.inputSequence());
    assertFalse(thread.runnable());

    Map<String, Object> projection =
        jdbc.queryForMap(
            """
            select t.head_entry_id, e.session_id, e.entry_type, e.parent_entry_id, s.title
            from harness_thread t
            join harness_entry e on e.id = t.head_entry_id
            join harness_session s on s.id = e.session_id
            where t.id = ?
            """,
            thread.id());
    assertEquals(thread.headEntryId(), ((Number) projection.get("head_entry_id")).longValue());
    assertEquals("ROOT", projection.get("entry_type"));
    assertEquals("atomic-title", projection.get("title"));
    assertTrue(projection.get("parent_entry_id") == null);
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select count(*) from harness_session where id = ?",
            Long.class,
            projection.get("session_id")));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select count(*) from harness_entry where session_id = ?",
            Long.class,
            projection.get("session_id")));
  }

  @Test
  void enqueueAcceptsOnlyUserOrCustomMessagesAndPreservesIdempotency() {
    HarnessThread thread = transactions.createThread("mailbox", NOW);
    TurnSettings settings = new TurnSettings("agent-a", "environment-a", true);
    RuntimeEntryInputPayload user =
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE,
            new MessageEntryPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                settings,
                null));

    ThreadCommandTransactions.EnqueueResult first =
        transactions.enqueue(thread.id(), user, "message-1", thread.executionEpoch(), NOW);
    RuntimeEntryInputPayload retryPayload =
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE,
            new MessageEntryPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("changed"))),
                new TurnSettings("agent-b", null, false),
                null));
    ThreadCommandTransactions.EnqueueResult retry =
        transactions.enqueue(thread.id(), retryPayload, "message-1", thread.executionEpoch(), NOW);

    assertEquals(first.input(), retry.input());
    RuntimeEntryInputPayload persistedInput =
        assertInstanceOf(RuntimeEntryInputPayload.class, first.input().payload());
    MessageEntryPayload persisted =
        assertInstanceOf(MessageEntryPayload.class, persistedInput.payload());
    assertEquals(settings, persisted.turnSettings());
    assertEquals(1L, first.input().sequence());

    RuntimeEntryInputPayload custom =
        new RuntimeEntryInputPayload(
            ThreadInputType.CUSTOM_MESSAGE,
            new CustomMessageEntryPayload(
                new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent("rules"))),
                settings));
    ThreadCommandTransactions.EnqueueResult second =
        transactions.enqueue(thread.id(), custom, "message-2", thread.executionEpoch(), NOW);
    assertEquals(2L, second.input().sequence());
    assertEquals(
        2L,
        jdbc.queryForObject(
            "select count(*) from harness_thread_input where thread_id = ?",
            Long.class,
            thread.id()));
    assertEquals(
        1L,
        jdbc.queryForObject(
            "select count(*) from harness_execution_target"
                + " where target_kind = 'THREAD' and target_id = ?",
            Long.class,
            thread.id()));
  }

  @Test
  void staleEpochNeverWritesMailboxState() {
    HarnessThread thread = transactions.createThread("epoch", NOW);
    RuntimeEntryInputPayload payload =
        new RuntimeEntryInputPayload(
            ThreadInputType.USER_MESSAGE,
            new MessageEntryPayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
                new TurnSettings("agent", null, false),
                null));

    assertThrows(
        IllegalStateException.class,
        () -> transactions.enqueue(thread.id(), payload, "stale", 1L, NOW));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "select count(*) from harness_thread_input where thread_id = ?",
            Long.class,
            thread.id()));
    assertFalse(
        jdbc.queryForObject(
            "select runnable from harness_thread where id = ?", Boolean.class, thread.id()));
  }
}
