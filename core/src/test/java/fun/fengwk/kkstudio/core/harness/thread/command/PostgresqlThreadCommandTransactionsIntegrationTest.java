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
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantAbortedEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RuntimeEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlanner;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeConfigInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** PostgreSQL final-schema command transaction 的 create/bootstrap/rebind/mailbox/stop 基线。 */
class PostgresqlThreadCommandTransactionsIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

  @Autowired private ThreadCommandTransactions transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;

  @Test
  void createThreadProducesUnboundThreadAndBootstrapCreatesBundledSession() {
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
    long sessionsBefore = countSessions();
    ThreadCommandTransactions.BootstrapResult bootstrap =
        transactions.bootstrapThread(
            thread.id(),
            thread.executionEpoch(),
            "pure-session",
            TestRuntimeConfigs.bootstrap(),
            NOW);
    assertEquals(
        threadsBefore, countThreads(), "bootstrap must not allocate additional Thread rows");
    assertEquals(
        sessionsBefore + 1, countSessions(), "bootstrap must create exactly one new Session row");
    assertEquals("ROOT", entryType(bootstrap.rootEntry().id()));
    assertEquals("RUNTIME_CONFIG", entryType(bootstrap.configEntry().id()));
    assertEquals(bootstrap.rootEntry().id(), parentEntryId(bootstrap.configEntry().id()));
  }

  @Test
  void revisionAdvancesForProjectionChangesButNotLeasesAndRollsBackWithChildMutation()
      throws Exception {
    HarnessThread thread = transactions.createThread(NOW);
    assertEquals(0L, revision(thread.id()));

    jdbc.update(
        "update harness_thread set processor_token = ?, processor_until = current_timestamp +"
            + " interval '1 minute' where id = ?",
        "lease",
        thread.id());
    assertEquals(0L, revision(thread.id()), "lease heartbeats are not snapshot changes");

    jdbc.update(
        "update harness_thread set processor_token = null, processor_until = null, input_sequence"
            + " = input_sequence + 1 where id = ?",
        thread.id());
    assertEquals(1L, revision(thread.id()), "visible Thread columns advance the cursor");

    jdbc.update(
        "insert into harness_thread_input (thread_id, sequence, input_type, payload,"
            + " idempotency_key, status) values (?, 2, 'USER_MESSAGE', '{}'::jsonb, 'committed',"
            + " 'QUEUED')",
        thread.id());
    assertEquals(2L, revision(thread.id()), "committed child facts advance the cursor");

    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "insert into harness_thread_input (thread_id, sequence, input_type, payload,"
                  + " idempotency_key, status) values (?, 3, 'USER_MESSAGE', '{}'::jsonb,"
                  + " 'rolled-back', 'QUEUED')")) {
        statement.setLong(1, thread.id());
        statement.executeUpdate();
      }
      assertEquals(3L, revision(connection, thread.id()));
      connection.rollback();
    }
    assertEquals(2L, revision(thread.id()), "a rolled-back child mutation must not leak a cursor");
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
    OffsetDateTime movedAt = NOW.plusSeconds(30).atOffset(ZoneOffset.UTC);
    assertEquals(
        1,
        jdbc.update(
            "update harness_execution_target set available_at = ? where target_kind = 'THREAD' and target_id = ?",
            movedAt,
            boot.threadId()));
    ThreadCommandTransactions.EnqueueResult unchangedRetry =
        transactions.enqueue(
            boot.threadId(), userPayload("third retry body"), "message-1", epoch, NOW);
    assertEquals(first.input(), unchangedRetry.input());
    assertEquals(
        movedAt,
        jdbc.queryForObject(
            "select available_at from harness_execution_target where target_kind = 'THREAD' and target_id = ?",
            OffsetDateTime.class,
            boot.threadId()));

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

  // ---------- /stop atomic partial abort path ----------

  /**
   * Real {@code transactions.stop} over a RUNNING partial invocation with a populated safe stream
   * snapshot must persist an {@code ASSISTANT_ABORTED} entry that carries the assistant text and
   * rebinds the head; the pre-existing RUNNING row stays RUNNING because stop is an epoch fence,
   * not a lifecycle change — any subsequent completeSuccess/completeFailure/completeCancelled CAS
   * from the prior generation is now rejected by the (thread, epoch, attempt, token) tuple.
   */
  @Test
  void stopBuildsAssistantAbortedHeadFromRunningSafeSnapshot() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "abort-running", NOW);
    long userMessageId = appendUserMessageUnderConfig(boot, "question");
    HarnessThread rebounded =
        transactions.updateHead(boot.threadId(), boot.executionEpoch(), userMessageId, NOW);
    long headEpoch = rebounded.executionEpoch();
    long invocationId = insertRunningModelInvocation(boot, userMessageId, headEpoch, "partial");
    Instant completion = NOW.plusSeconds(1);

    ThreadCommandTransactions.StopResult stopped =
        transactions.stop(boot.threadId(), headEpoch, completion);
    assertEquals(headEpoch + 1, stopped.executionEpoch());

    Long abortedEntryId = headEntryId(boot.threadId());
    assertNotNull(abortedEntryId);
    assertEquals("ASSISTANT_ABORTED", entryType(abortedEntryId));
    AssistantAbortedEntryPayload aborted = readAssistantAborted(abortedEntryId);
    assertEquals("partial", ((TextMessageContent) aborted.contents().get(0)).text());
    // Use the runtime strict codec to avoid hand-rolled JSON parsing; verify the message really
    // survives shape round-trip via decode/encode symmetry for downstream Planner.
    String abortedJson =
        jdbc.queryForObject(
            "select payload::text from harness_entry where id = ?", String.class, abortedEntryId);
    assertEquals(
        aborted,
        new RuntimeEntryPayloadJsonCodec().decode(EntryType.ASSISTANT_ABORTED, abortedJson));

    // The pre-existing RUNNING invocation is the epoch-predecessor; stop is a fence, not a
    // lifecycle change, so its status remains RUNNING and the worker token is left untouched
    // until the lease recovery path decides the outcome.
    assertEquals(
        "RUNNING",
        jdbc.queryForObject(
            "select status from harness_model_invocation where id = ?",
            String.class,
            invocationId));

    // Stop never fabricates a ToolInvocation; the partial assistant turn is text-only and the
    // durable aborted entry is the only new persisted artifact.
    assertEquals(
        0,
        jdbc.queryForObject(
            "select count(*) from harness_tool_invocation where thread_id = ?",
            Integer.class,
            boot.threadId()));
  }

  /**
   * Stop on a head whose only current epoch invocation has no safe stream snapshot must write a
   * cancellation barrier ({@code ASSISTANT_ERROR} with {@code CANCELLED}) rather than fabricate
   * tool fragments or a tool fragment-style aborted entry.
   */
  @Test
  void stopWithEmptySnapshotWritesAssistantErrorCancellationBarrier() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "abort-empty", NOW);
    long userMessageId = appendUserMessageUnderConfig(boot, "question");
    HarnessThread rebounded =
        transactions.updateHead(boot.threadId(), boot.executionEpoch(), userMessageId, NOW);
    long headEpoch = rebounded.executionEpoch();
    insertRunningModelInvocationNoSnapshot(boot, userMessageId, headEpoch);

    ThreadCommandTransactions.StopResult stopped =
        transactions.stop(boot.threadId(), headEpoch, NOW.plusSeconds(1));
    assertEquals(headEpoch + 1, stopped.executionEpoch());

    Long barrierEntryId = headEntryId(boot.threadId());
    assertNotNull(barrierEntryId);
    assertEquals("ASSISTANT_ERROR", entryType(barrierEntryId));
    // Decode the barrier through the strict codec; the assistant_error payload wraps a
    // ModelInvocationError(kind=CANCELLED) and must NOT carry any text/thinking content.
    String payloadJson =
        jdbc.queryForObject(
            "select payload::text from harness_entry where id = ?", String.class, barrierEntryId);
    AssistantErrorEntryPayload barrier =
        (AssistantErrorEntryPayload)
            new RuntimeEntryPayloadJsonCodec().decode(EntryType.ASSISTANT_ERROR, payloadJson);
    assertEquals(ProviderErrorKind.CANCELLED, barrier.error().kind());
    assertFalse(payloadJson.contains("\"text\""));
  }

  /**
   * A Row that already reached {@code SUCCEEDED} but still has a non-null safe stream snapshot and
   * {@code applied_at is null} must still contribute its snapshot to the {@code ASSISTANT_ABORTED}
   * barrier on the next stop; this preserves already-written Provider output across the stop
   * transition.
   */
  @Test
  void stopReusesSucceededUnappliedSnapshot() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "abort-succeeded", NOW);
    long userMessageId = appendUserMessageUnderConfig(boot, "question");
    HarnessThread rebounded =
        transactions.updateHead(boot.threadId(), boot.executionEpoch(), userMessageId, NOW);
    long headEpoch = rebounded.executionEpoch();
    long invocationId =
        insertSucceededUnappliedModelInvocation(boot, userMessageId, headEpoch, "stale-safe");

    ThreadCommandTransactions.StopResult stopped =
        transactions.stop(boot.threadId(), headEpoch, NOW.plusSeconds(1));
    assertEquals(headEpoch + 1, stopped.executionEpoch());

    Long abortedEntryId = headEntryId(boot.threadId());
    assertNotNull(abortedEntryId);
    assertEquals("ASSISTANT_ABORTED", entryType(abortedEntryId));
    AssistantAbortedEntryPayload aborted = readAssistantAborted(abortedEntryId);
    assertEquals("stale-safe", ((TextMessageContent) aborted.contents().get(0)).text());
    // The pre-existing succeeded invocation must remain SUCCEEDED and the stop must not rewrite
    // it with a stale partial.
    assertEquals(
        "SUCCEEDED",
        jdbc.queryForObject(
            "select status from harness_model_invocation where id = ?",
            String.class,
            invocationId));
  }

  /**
   * A follow-up USER message after the partial abort must rebuild debt for the NEW turn only. The
   * persisted root-to-followup-user Entry path is read back through the runtime strict codec and
   * fed to {@link ModelInvocationPlanner}; the resulting plan must carry the partial assistant text
   * AND the follow-up USER verbatim, in that order — but NOT as a fresh re-run of the original debt
   * (which is closed by the {@code ASSISTANT_ABORTED} barrier).
   */
  @Test
  void stopFollowUpUserDebtIncorporatesPartialAssistantContext() {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(transactions, "abort-continue", NOW);
    long firstUserId = appendUserMessageUnderConfig(boot, "first-question");
    HarnessThread rebounded =
        transactions.updateHead(boot.threadId(), boot.executionEpoch(), firstUserId, NOW);
    long headEpoch = rebounded.executionEpoch();
    insertRunningModelInvocation(boot, firstUserId, headEpoch, "half-answer");
    Instant completion = NOW.plusSeconds(1);
    transactions.stop(boot.threadId(), headEpoch, completion);

    long abortedEntryId = headEntryId(boot.threadId());
    assertNotNull(abortedEntryId);
    assertEquals("ASSISTANT_ABORTED", entryType(abortedEntryId));

    long secondUserId = appendUserMessage(boot.sessionId(), abortedEntryId, "second-question");
    assertEquals("MESSAGE", entryType(secondUserId));
    assertEquals(abortedEntryId, parentEntryId(secondUserId));

    // Read the root-to-followup-user path back from the DB so the planner sees exactly what the
    // runtime would see when the reconciler next picks up the thread.
    List<SessionEntry> path = loadEntryPathAsSessionEntries(boot.sessionId(), secondUserId);

    ModelInvocationPlanner planner = new ModelInvocationPlanner();
    ModelInvocationPlan followupPlan =
        planner.plan(boot.sessionId(), secondUserId, path).orElseThrow();

    // The plan must carry exactly one SYSTEM (composed from the active RUNTIME_CONFIG), the first
    // USER, the partial ASSISTANT turn, and the follow-up USER. A regression that re-derives the
    // first USER as a fresh assistant debt would surface here as either a missing partial reply
    // or a duplicated USER message.
    ProviderMessage systemMsg = followupPlan.request().messages().get(0);
    ProviderMessage firstUserMsg = followupPlan.request().messages().get(1);
    ProviderMessage partialAssistantMsg = followupPlan.request().messages().get(2);
    ProviderMessage followupUserMsg = followupPlan.request().messages().get(3);

    assertEquals(ProviderMessageRole.SYSTEM, systemMsg.role());
    assertEquals(ProviderMessageRole.USER, firstUserMsg.role());
    assertEquals("first-question", ((ProviderTextBlock) firstUserMsg.contents().get(0)).text());
    assertEquals(ProviderMessageRole.ASSISTANT, partialAssistantMsg.role());
    assertEquals("half-answer", ((ProviderTextBlock) partialAssistantMsg.contents().get(0)).text());
    assertEquals(ProviderMessageRole.USER, followupUserMsg.role());
    assertEquals("second-question", ((ProviderTextBlock) followupUserMsg.contents().get(0)).text());
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
        "insert into harness_model_invocation (id, thread_id, source_head_entry_id,"
            + " execution_epoch, request, status, attempt, created_at) values (?, ?, ?, ?,"
            + " '{}'::jsonb, ?, 1, ?)",
        id,
        boot.threadId(),
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

  // ---------- /stop fixture helpers ----------

  /**
   * RUNNING model invocation with a populated safe stream snapshot containing the supplied text.
   * Used as the canonical "partial stopped" precondition.
   */
  private long insertRunningModelInvocation(
      TestThreads.Bootstrapped boot, long sourceEntryId, long epoch, String safeText) {
    long id = nextId();
    OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    OffsetDateTime deadline = now.plusSeconds(30);
    OffsetDateTime leaseUntil = now.plusSeconds(15);
    String snapshotJson =
        "{\"text\":\""
            + safeText.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\",\"thinking\":\"\"}";
    jdbc.update(
        "insert into harness_model_invocation (id, thread_id, source_head_entry_id,"
            + " execution_epoch, request, status, attempt, worker_token, worker_until,"
            + " started_at, deadline_at, last_activity_at, created_at,"
            + " safe_stream_snapshot) values (?, ?, ?, ?, '{}'::jsonb, 'RUNNING', 1,"
            + " 'tok-stop-partial', ?, ?, ?, ?, ?, cast(? as jsonb))",
        id,
        boot.threadId(),
        sourceEntryId,
        epoch,
        leaseUntil,
        now,
        deadline,
        now,
        now,
        snapshotJson);
    return id;
  }

  /**
   * RUNNING model invocation with a deliberately empty safe stream snapshot. Stop on this row must
   * fall back to the cancellation barrier rather than fabricating an aborted entry with empty text.
   */
  private long insertRunningModelInvocationNoSnapshot(
      TestThreads.Bootstrapped boot, long sourceEntryId, long epoch) {
    long id = nextId();
    OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    OffsetDateTime deadline = now.plusSeconds(30);
    OffsetDateTime leaseUntil = now.plusSeconds(15);
    jdbc.update(
        "insert into harness_model_invocation (id, thread_id, source_head_entry_id,"
            + " execution_epoch, request, status, attempt, worker_token, worker_until,"
            + " started_at, deadline_at, last_activity_at, created_at) values (?, ?, ?, ?,"
            + " '{}'::jsonb, 'RUNNING', 1, 'tok-stop-empty', ?, ?, ?, ?, ?)",
        id,
        boot.threadId(),
        sourceEntryId,
        epoch,
        leaseUntil,
        now,
        deadline,
        now,
        now);
    return id;
  }

  /**
   * SUCCEEDED model invocation with non-null safe_stream_snapshot and applied_at is null; mimics
   * the small window between completeSuccess and Reconciler apply where the partial snapshot is
   * still authoritative.
   */
  private long insertSucceededUnappliedModelInvocation(
      TestThreads.Bootstrapped boot, long sourceEntryId, long epoch, String safeText) {
    long id = nextId();
    OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    String snapshotJson =
        "{\"text\":\""
            + safeText.replace("\\", "\\\\").replace("\"", "\\\"")
            + "\",\"thinking\":\"\"}";
    jdbc.update(
        "insert into harness_model_invocation (id, thread_id, source_head_entry_id,"
            + " execution_epoch, request, status, attempt, started_at, deadline_at,"
            + " last_activity_at, finished_at, result, created_at, safe_stream_snapshot) values"
            + " (?, ?, ?, ?, '{}'::jsonb, 'SUCCEEDED', 1, ?, ?, ?, ?,"
            + " '{}'::jsonb, ?, cast(? as jsonb))",
        id,
        boot.threadId(),
        sourceEntryId,
        epoch,
        now,
        now.plusSeconds(30),
        now,
        now,
        now,
        snapshotJson);
    return id;
  }

  private long appendUserMessage(long sessionId, long parentEntryId, String content) {
    long entryId = nextId();
    String payload =
        "{\"message\":{\"role\":\"USER\",\"contents\":[{\"type\":\"text\",\"text\":\""
            + content
            + "\"}]},\"assistantMetadata\":null}";
    jdbc.update(
        "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
            + " created_at) values (?, ?, ?, 'MESSAGE', cast(? as jsonb), ?)",
        entryId,
        sessionId,
        parentEntryId,
        payload,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    return entryId;
  }

  /**
   * Append a USER message under the bootstrap's RUNTIME_CONFIG entry so the resulting root-to-head
   * path preserves the canonical {@code ROOT -> RUNTIME_CONFIG -> USER -> ...} chain that {@link
   * ModelInvocationPlanner#plan} expects.
   */
  private long appendUserMessageUnderConfig(TestThreads.Bootstrapped boot, String content) {
    return appendUserMessage(boot.sessionId(), boot.configEntryId(), content);
  }

  private long revision(long threadId) {
    return jdbc.queryForObject(
        "select revision from harness_thread where id = ?", Long.class, threadId);
  }

  private static long revision(Connection connection, long threadId) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("select revision from harness_thread where id = ?")) {
      statement.setLong(1, threadId);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getLong(1);
      }
    }
  }

  private Long headEntryId(long threadId) {
    return jdbc.queryForObject(
        "select head_entry_id from harness_thread where id = ?", Long.class, threadId);
  }

  /**
   * Decode an {@code ASSISTANT_ABORTED} Entry's payload through the runtime strict codec so the
   * assertion exercise matches what {@link ModelInvocationPlanner} and downstream consumers
   * actually see — no hand-rolled JSON slicing.
   */
  private AssistantAbortedEntryPayload readAssistantAborted(long entryId) {
    RuntimeEntryPayloadJsonCodec codec = new RuntimeEntryPayloadJsonCodec();
    String payloadJson =
        jdbc.queryForObject(
            "select payload::text from harness_entry where id = ?", String.class, entryId);
    AssistantAbortedEntryPayload payload =
        (AssistantAbortedEntryPayload) codec.decode(EntryType.ASSISTANT_ABORTED, payloadJson);
    assertEquals(EntryType.ASSISTANT_ABORTED, payload.type());
    return payload;
  }

  /**
   * Read the root-to-head Entry path as {@link SessionEntry}s using the runtime strict codec. Tests
   * that exercise {@link ModelInvocationPlanner#plan} against persisted state must build the plan
   * input from durable rows, not from in-memory fakes, so any codec/decoder divergence surfaces
   * here instead of at reconcile time.
   */
  private List<SessionEntry> loadEntryPathAsSessionEntries(long sessionId, long headEntryId) {
    RuntimeEntryPayloadJsonCodec codec = new RuntimeEntryPayloadJsonCodec();
    RuntimeConfigJsonCodec configCodec = new RuntimeConfigJsonCodec();
    return jdbc.query(
        """
        with recursive path (id, parent_entry_id, depth, entry_type) as (
          select e.id, e.parent_entry_id, 0, e.entry_type from harness_entry e
          where e.session_id = ? and e.id = ?
          union all
          select e.id, e.parent_entry_id, p.depth + 1, e.entry_type
          from harness_entry e join path p on p.parent_entry_id = e.id
          where e.session_id = ?
        )
        select p.id, p.parent_entry_id, p.entry_type, e.payload::text as payload_json
        from path p
        join harness_entry e on e.id = p.id
        order by depth desc
        """,
        (rs, i) -> {
          long id = rs.getLong("id");
          Long parent = rs.getObject("parent_entry_id", Long.class);
          EntryType type = EntryType.valueOf(rs.getString("entry_type"));
          EntryPayload payload;
          if (type == EntryType.RUNTIME_CONFIG) {
            payload = configCodec.decode(rs.getString("payload_json"));
          } else {
            payload = codec.decode(type, rs.getString("payload_json"));
          }
          return new SessionEntry(id, parent, payload);
        },
        sessionId,
        headEntryId,
        sessionId);
  }
}
