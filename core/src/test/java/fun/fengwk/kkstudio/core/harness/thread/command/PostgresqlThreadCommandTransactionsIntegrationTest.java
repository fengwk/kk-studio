package fun.fengwk.kkstudio.core.harness.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeConfigInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** PostgreSQL final-schema command transaction 的 create/bootstrap/rebind/mailbox/stop 基线。 */
class PostgresqlThreadCommandTransactionsIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private ThreadCommandTransactions transactions;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void createThreadProducesUnboundThreadAndCreateSessionCreatesNoThread() {
    HarnessThread thread = transactions.createThread(NOW);
    assertNull(thread.headEntryId(), "new Thread must be UNBOUND");
    assertFalse(thread.isBound());
    assertFalse(thread.runnable());
    assertEquals(0L, thread.executionEpoch());
    assertEquals(0L, thread.inputSequence());
    assertNull(thread.processorLease());
    assertThrows(IllegalStateException.class, thread::requireHeadEntryId);
    assertNull(
        jdbc.queryForObject(
            "select head_entry_id from harness_thread where id = ?", Long.class, thread.id()));

    long threadsBefore = countThreads();
    ThreadCommandTransactions.SessionCreation session =
        transactions.createSession("pure-session", TestRuntimeConfigs.bootstrap(), NOW);
    assertEquals(threadsBefore, countThreads(), "createSession must not create a Thread");
    assertEquals("ROOT", entryType(session.rootEntry().id()));
    assertEquals("RUNTIME_CONFIG", entryType(session.configEntry().id()));
    assertEquals(session.rootEntry().id(), parentEntryId(session.configEntry().id()));
  }

  @Test
  void bootstrapBindsUnboundThreadAndRejectsStaleEpochOrAlreadyBoundThread() {
    HarnessThread unbound = transactions.createThread(NOW);

    // stale epoch is rejected before anything is created
    long sessionsBefore = countSessions();
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.bootstrapThread(
                unbound.id(), unbound.executionEpoch() + 7, "stale", config(false), NOW));
    assertEquals(sessionsBefore, countSessions(), "stale bootstrap must not create a Session");

    ThreadCommandTransactions.BootstrapResult result =
        transactions.bootstrapThread(
            unbound.id(), unbound.executionEpoch(), "boot", config(false), NOW);
    assertEquals(unbound.id(), result.thread().id());
    assertEquals(result.configEntry().id(), result.thread().headEntryId());
    assertEquals(unbound.executionEpoch() + 1, result.thread().executionEpoch());
    assertEquals("boot", result.session().title());
    assertEquals(
        config(false), transactions.lockAndFindCurrentConfig(unbound.id(), 1L).orElseThrow());

    // already bound Thread cannot be bootstrapped again
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.bootstrapThread(
                unbound.id(), result.thread().executionEpoch(), "again", config(true), NOW));
  }

  @Test
  void rebindMovesHeadWithinSessionAcrossSessionsAndSupportsUnbind() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "rebind", NOW);
    long messageEntryId = appendMessage(boot.sessionId(), boot.configEntryId());

    // same-session rewind: head moves back onto an earlier Entry on the same tree
    HarnessThread forward =
        transactions.updateHead(boot.threadId(), boot.executionEpoch(), messageEntryId, NOW);
    assertEquals(messageEntryId, forward.headEntryId());
    HarnessThread rewound =
        transactions.updateHead(boot.threadId(), forward.executionEpoch(), boot.rootEntryId(), NOW);
    assertEquals(boot.rootEntryId(), rewound.headEntryId());
    assertEquals(forward.executionEpoch() + 1, rewound.executionEpoch());

    // cross-session rebind onto a foreign Session's Entry keeps the same Thread row
    TestThreads.Bootstrapped other = TestThreads.bootstrap(transactions, "other", NOW);
    HarnessThread crossed =
        transactions.updateHead(
            boot.threadId(), rewound.executionEpoch(), other.rootEntryId(), NOW);
    assertEquals(boot.threadId(), crossed.id());
    assertEquals(other.rootEntryId(), crossed.headEntryId());
    assertEquals(rewound.executionEpoch() + 1, crossed.executionEpoch());
    assertEquals(
        other.sessionId(),
        jdbc.queryForObject(
            "select e.session_id from harness_thread t join harness_entry e on e.id ="
                + " t.head_entry_id where t.id = ?",
            Long.class,
            boot.threadId()));

    // unbind: null head returns the Thread to UNBOUND
    HarnessThread unbound =
        transactions.updateHead(boot.threadId(), crossed.executionEpoch(), null, NOW);
    assertNull(unbound.headEntryId());
    assertFalse(unbound.isBound());
    assertEquals(crossed.executionEpoch() + 1, unbound.executionEpoch());

    // unknown entry / unknown thread
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.updateHead(boot.threadId(), unbound.executionEpoch(), 999_999_999L, NOW));
    assertThrows(
        IllegalArgumentException.class, () -> transactions.updateHead(999_999_999L, 0L, null, NOW));
  }

  @Test
  void rebindIsRejectedOnStaleEpochRunnableOrActiveProcessorLease() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "guard", NOW);
    long epoch = boot.executionEpoch();

    assertThrows(
        IllegalStateException.class,
        () -> transactions.updateHead(boot.threadId(), epoch + 5, boot.rootEntryId(), NOW),
        "stale expectedExecutionEpoch must be rejected");

    jdbc.update("update harness_thread set runnable = true where id = ?", boot.threadId());
    assertThrows(
        IllegalStateException.class,
        () -> transactions.updateHead(boot.threadId(), epoch, boot.rootEntryId(), NOW),
        "runnable Thread must be rejected");
    jdbc.update("update harness_thread set runnable = false where id = ?", boot.threadId());

    jdbc.update(
        "update harness_thread set processor_token = 'lease', processor_until = ? where id = ?",
        OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC),
        boot.threadId());
    assertThrows(
        IllegalStateException.class,
        () -> transactions.updateHead(boot.threadId(), epoch, boot.rootEntryId(), NOW),
        "active processor lease must be rejected");
    // an expired lease no longer blocks rebind
    assertNotNull(
        transactions.updateHead(boot.threadId(), epoch, boot.rootEntryId(), NOW.plusSeconds(120)));
  }

  @Test
  void rebindIsRejectedWhileCurrentEpochHasLiveModelToolOrInteraction() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "active", NOW);
    long epoch = boot.executionEpoch();

    // QUEUED input blocks rebind
    transactions.enqueue(boot.threadId(), userPayload("queued"), "in-1", epoch, NOW);
    assertThrows(
        IllegalStateException.class,
        () -> transactions.updateHead(boot.threadId(), epoch, boot.rootEntryId(), NOW));
    jdbc.update(
        "update harness_thread_input set status = 'CANCELLED' where thread_id = ?",
        boot.threadId());
    jdbc.update("update harness_thread set runnable = false where id = ?", boot.threadId());

    // non-terminal Model invocation in the current epoch blocks rebind
    long modelId = insertModel(boot, epoch, boot.rootEntryId(), "QUEUED");
    assertThrows(
        IllegalStateException.class,
        () -> transactions.updateHead(boot.threadId(), epoch, boot.rootEntryId(), NOW),
        "non-terminal Model in current epoch must block rebind");
    jdbc.update(
        "update harness_model_invocation set status = 'CANCELLED', finished_at = ? where id = ?",
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        modelId);

    // non-terminal Tool invocation in the current epoch blocks rebind
    long assistantEntryId = appendMessage(boot.sessionId(), boot.rootEntryId());
    long toolId = insertTool(boot, epoch, assistantEntryId, 0);
    assertThrows(
        IllegalStateException.class,
        () -> transactions.updateHead(boot.threadId(), epoch, boot.rootEntryId(), NOW),
        "non-terminal Tool in current epoch must block rebind");
    jdbc.update(
        "update harness_tool_invocation set status = 'CANCELLED', finished_at = ? where id = ?",
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        toolId);

    // OPEN Interaction on the Thread blocks rebind
    long interactionId = insertOpenThreadInteraction(boot.threadId());
    assertThrows(
        IllegalStateException.class,
        () -> transactions.updateHead(boot.threadId(), epoch, boot.rootEntryId(), NOW),
        "OPEN Interaction must block rebind");
    jdbc.update(
        "update harness_interaction set status = 'CANCELLED', resolved_at = ? where id = ?",
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        interactionId);

    // once every blocker is terminal the Thread is rebindable again
    assertNotNull(transactions.updateHead(boot.threadId(), epoch, boot.rootEntryId(), NOW));
  }

  @Test
  void stopCancelsOpenInteractionsSoThreadBecomesImmediatelyRebindable() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "stop-rebind", NOW);
    long epoch = boot.executionEpoch();
    long interactionId = insertOpenThreadInteraction(boot.threadId());
    transactions.enqueue(boot.threadId(), userPayload("pending"), "in-1", epoch, NOW);

    ThreadCommandTransactions.StopResult stopped =
        transactions.stop(boot.threadId(), epoch, NOW.plusSeconds(1));
    assertEquals(epoch + 1, stopped.executionEpoch());
    assertEquals(1, stopped.cancelledInputs().size());
    assertTrue(stopped.cancelledInputs().stream().allMatch(input -> input.isTerminal()));
    assertEquals(
        "CANCELLED",
        jdbc.queryForObject(
            "select status from harness_interaction where id = ?", String.class, interactionId));

    HarnessThread rebound =
        transactions.updateHead(
            boot.threadId(), stopped.executionEpoch(), boot.rootEntryId(), NOW.plusSeconds(2));
    assertEquals(boot.rootEntryId(), rebound.headEntryId());
    assertEquals(stopped.executionEpoch() + 1, rebound.executionEpoch());
  }

  @Test
  void rebindFencesStaleEpochMutationsAndClearsOwnership() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "fence", NOW);
    long staleEpoch = boot.executionEpoch();
    jdbc.update(
        "update harness_thread set processor_token = 'worker-1', processor_until = ? where id = ?",
        OffsetDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC),
        boot.threadId());

    HarnessThread rebound =
        transactions.updateHead(boot.threadId(), staleEpoch, boot.configEntryId(), NOW);
    long freshEpoch = rebound.executionEpoch();
    assertEquals(staleEpoch + 1, freshEpoch);
    assertNull(
        jdbc.queryForObject(
            "select processor_token from harness_thread where id = ?",
            String.class,
            boot.threadId()),
        "rebind must clear the old processor ownership");
    assertFalse(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select runnable from harness_thread where id = ?",
                Boolean.class,
                boot.threadId())));

    // the old epoch's worker can no longer enqueue, stop, read config or rebind
    assertThrows(
        IllegalStateException.class,
        () -> transactions.enqueue(boot.threadId(), userPayload("x"), "stale", staleEpoch, NOW));
    assertThrows(
        IllegalStateException.class, () -> transactions.stop(boot.threadId(), staleEpoch, NOW));
    assertThrows(
        IllegalStateException.class,
        () -> transactions.lockAndFindCurrentConfig(boot.threadId(), staleEpoch));
    assertThrows(
        IllegalStateException.class,
        () -> transactions.updateHead(boot.threadId(), staleEpoch, boot.configEntryId(), NOW));

    // the fresh epoch still resolves the RUNTIME_CONFIG on the new head path
    assertNotNull(transactions.lockAndFindCurrentConfig(boot.threadId(), freshEpoch).orElseThrow());
    // rewinding above the RUNTIME_CONFIG Entry legitimately leaves the Thread without a config
    HarnessThread atRoot =
        transactions.updateHead(boot.threadId(), freshEpoch, boot.rootEntryId(), NOW);
    assertTrue(
        transactions.lockAndFindCurrentConfig(boot.threadId(), atRoot.executionEpoch()).isEmpty());
  }

  @Test
  void unboundThreadRejectsEnqueue() {
    HarnessThread unbound = transactions.createThread(NOW);
    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                transactions.enqueue(
                    unbound.id(), userPayload("hi"), "in-1", unbound.executionEpoch(), NOW));
    assertTrue(error.getMessage().contains("unbound"), error.getMessage());
    assertEquals(0L, inputCount(unbound.id()));
    assertFalse(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "select runnable from harness_thread where id = ?", Boolean.class, unbound.id())));
    assertTrue(transactions.lockAndFindCurrentConfig(unbound.id(), 0L).isEmpty());
  }

  @Test
  void enqueueIsIdempotentAndAllocatesMonotonicSequence() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "mailbox", NOW);
    long epoch = boot.executionEpoch();

    ThreadCommandTransactions.EnqueueResult first =
        transactions.enqueue(boot.threadId(), userPayload("hello"), "message-1", epoch, NOW);
    ThreadCommandTransactions.EnqueueResult retry =
        transactions.enqueue(
            boot.threadId(), userPayload("different retry body"), "message-1", epoch, NOW);
    assertEquals(1, first.input().sequence());
    assertEquals(first.input(), retry.input());
    assertEquals(boot.threadId(), first.target().id());

    ThreadCommandTransactions.EnqueueResult second =
        transactions.enqueue(boot.threadId(), userPayload("second"), "message-2", epoch, NOW);
    assertEquals(2, second.input().sequence());

    // a mutation with a stale epoch never reaches the mailbox
    assertThrows(
        IllegalStateException.class,
        () -> transactions.enqueue(boot.threadId(), userPayload("x"), "message-3", epoch + 1, NOW));
    assertEquals(2L, inputCount(boot.threadId()));
  }

  @Test
  void effectiveConfigPrefersLatestQueuedSnapshotBeforeHeadPathFallback() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "config", NOW);
    long epoch = boot.executionEpoch();
    RuntimeConfigSnapshot agent = TestRuntimeConfigs.config("agent-a", true);
    RuntimeConfigSnapshot model = TestRuntimeConfigs.config("agent-a", false);
    transactions.enqueue(
        boot.threadId(),
        new RuntimeConfigInputPayload(ThreadInputType.SET_AGENT, agent),
        "agent",
        epoch,
        NOW);
    assertEquals(
        agent, transactions.lockAndFindCurrentConfig(boot.threadId(), epoch).orElseThrow());
    transactions.enqueue(
        boot.threadId(),
        new RuntimeConfigInputPayload(ThreadInputType.SET_MODEL, model),
        "model",
        epoch,
        NOW.plusSeconds(1));
    assertEquals(
        model, transactions.lockAndFindCurrentConfig(boot.threadId(), epoch).orElseThrow());
  }

  @Test
  void toolInvocationUniquenessIncludesExecutionEpochSoNewEpochCanRetryTheSameAssistantEntry() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "epoch-key", NOW);
    long firstEpoch = boot.executionEpoch();
    long assistantEntryId = appendMessage(boot.sessionId(), boot.rootEntryId());
    long firstTool = insertTool(boot, firstEpoch, assistantEntryId, 0);
    jdbc.update(
        "update harness_tool_invocation set status = 'CANCELLED', finished_at = ? where id = ?",
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        firstTool);

    // same (thread, assistant entry, ordinal) inside the same epoch still collides
    assertThrows(
        DuplicateKeyException.class, () -> insertTool(boot, firstEpoch, assistantEntryId, 0));

    HarnessThread rebound =
        transactions.updateHead(boot.threadId(), firstEpoch, boot.rootEntryId(), NOW);
    long secondEpoch = rebound.executionEpoch();
    long secondTool = insertTool(boot, secondEpoch, assistantEntryId, 0);
    assertNotEquals(firstTool, secondTool);
    assertEquals(
        2,
        jdbc.queryForObject(
            "select count(*) from harness_tool_invocation where thread_id = ?"
                + " and assistant_entry_id = ? and ordinal = 0",
            Integer.class,
            boot.threadId(),
            assistantEntryId));
  }

  private static void assertNotEquals(long unexpected, long actual) {
    assertFalse(unexpected == actual, "expected different ids but both were " + actual);
  }

  private long countThreads() {
    return jdbc.queryForObject("select count(*) from harness_thread", Long.class);
  }

  private long countSessions() {
    return jdbc.queryForObject("select count(*) from harness_session", Long.class);
  }

  private long inputCount(long threadId) {
    return jdbc.queryForObject(
        "select count(*) from harness_thread_input where thread_id = ?", Long.class, threadId);
  }

  private String entryType(long entryId) {
    return jdbc.queryForObject(
        "select entry_type from harness_entry where id = ?", String.class, entryId);
  }

  private Long parentEntryId(long entryId) {
    return jdbc.queryForObject(
        "select parent_entry_id from harness_entry where id = ?", Long.class, entryId);
  }

  private long appendMessage(long sessionId, long parentEntryId) {
    long entryId = nextId();
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, ?)",
        entryId,
        sessionId,
        parentEntryId,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return entryId;
  }

  private long insertModel(
      TestThreads.Bootstrapped boot, long epoch, long sourceEntryId, String status) {
    long id = nextId();
    jdbc.update(
        "insert into harness_model_invocation (id, thread_id, session_id, source_head_entry_id,"
            + " execution_epoch, request, status, attempt, created_at) values (?, ?, ?, ?, ?,"
            + " '{}'::jsonb, ?, 1, ?)",
        id,
        boot.threadId(),
        boot.sessionId(),
        sourceEntryId,
        epoch,
        status,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return id;
  }

  private long insertTool(
      TestThreads.Bootstrapped boot, long epoch, long assistantEntryId, int ordinal) {
    long id = nextId();
    jdbc.update(
        "insert into harness_tool_invocation (id, thread_id, session_id, assistant_entry_id,"
            + " ordinal, tool_call_id, descriptor, arguments, location, execution_epoch, status,"
            + " attempt, created_at) values (?, ?, ?, ?, ?, ?, '{}'::jsonb, '{}'::jsonb,"
            + " 'PLATFORM', ?, 'QUEUED', 1, ?)",
        id,
        boot.threadId(),
        boot.sessionId(),
        assistantEntryId,
        ordinal,
        "call-" + id,
        epoch,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return id;
  }

  private long insertOpenThreadInteraction(long threadId) {
    long id = nextId();
    jdbc.update(
        "insert into harness_interaction (id, owner_kind, owner_id, handler_type, request, status,"
            + " version, created_at) values (?, 'THREAD', ?, 'ASK', '{}'::jsonb, 'OPEN', 0, ?)",
        id,
        threadId,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return id;
  }

  private long nextId() {
    return jdbc.queryForObject("select nextval('kk_studio_id_seq')", Long.class);
  }

  private static RuntimeConfigSnapshot config(boolean yolo) {
    return TestRuntimeConfigs.config("bootstrap", yolo);
  }

  private static RuntimeEntryInputPayload userPayload(String content) {
    return new RuntimeEntryInputPayload(
        ThreadInputType.USER_MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.<AgentMessageContent>of(new TextMessageContent(content)))));
  }
}
