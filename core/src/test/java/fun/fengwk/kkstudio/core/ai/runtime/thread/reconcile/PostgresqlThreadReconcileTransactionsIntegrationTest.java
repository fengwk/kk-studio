package fun.fengwk.kkstudio.core.ai.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.core.ai.runtime.thread.command.TestThreads;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.entry.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.EntryType;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.entry.RuntimeEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.codec.ModelInvocationRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailure;
import fun.fengwk.kkstudio.harness.runtime.model.plan.PlanningFailureKind;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ResolvedTurnExecution;
import fun.fengwk.kkstudio.harness.runtime.model.plan.TurnExecutionResolver;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ApplyOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ModelCreationOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadOwnership;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.TurnInputBatch;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** PostgreSQL regression for resolver-driven planning and typed failure persistence. */
class PostgresqlThreadReconcileTransactionsIntegrationTest extends PostgresSpringTestSupport {

  private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
  private static final ModelInvocationRequestJsonCodec REQUEST_CODEC =
      new ModelInvocationRequestJsonCodec();
  private static final RuntimeEntryPayloadJsonCodec ENTRY_CODEC =
      new RuntimeEntryPayloadJsonCodec();

  @Autowired private ThreadCommandTransactions commands;
  @Autowired private ThreadReconcileTransactions transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @MockitoBean private TurnExecutionResolver executionResolver;

  @Test
  void creationTransactionResolvesAndFreezesTheLatestDefinitions() {
    TurnSettings settings = new TurnSettings("agent-a", "environment-a", true);
    ResolvedTurnExecution first = execution("provider-a", "model-a", true);
    ResolvedTurnExecution later = execution("provider-b", "model-b", false);
    when(executionResolver.resolve(settings))
        .thenReturn(new TurnExecutionResolver.Resolution.Resolved(first));

    Prepared prepared = prepareUserTurn(settings);
    ThreadReconcileSnapshot snapshot =
        transactions.loadOwnedSnapshot(prepared.ownership(), NOW).orElseThrow();
    assertInstanceOf(CreateModelInvocation.class, snapshot.primaryWork().orElseThrow());

    when(executionResolver.resolve(settings))
        .thenReturn(new TurnExecutionResolver.Resolution.Resolved(later));
    ModelCreationOutcome.Created created =
        assertInstanceOf(
            ModelCreationOutcome.Created.class,
            transactions.createModelInvocationAndRelease(prepared.ownership(), NOW.plusSeconds(1)));

    String requestJson =
        jdbc.queryForObject(
            "select request::text from harness_model_invocation where id = ?",
            String.class,
            created.target().id());
    assertEquals(later.model(), REQUEST_CODEC.decode(requestJson).providerRequest().model());
    assertEquals(later.variant(), REQUEST_CODEC.decode(requestJson).providerRequest().variant());
    assertTrue(!REQUEST_CODEC.decode(requestJson).yoloEnabled());
    verify(executionResolver).resolve(settings);
  }

  @Test
  void typedPlanningFailurePersistsAssistantErrorWithoutModelInvocation() {
    TurnSettings settings = new TurnSettings("missing-agent", null, false);
    PlanningFailure failure =
        new PlanningFailure(PlanningFailureKind.AGENT_NOT_FOUND, "agent not found: missing-agent");
    when(executionResolver.resolve(settings))
        .thenReturn(new TurnExecutionResolver.Resolution.Failed(failure));

    Prepared prepared = prepareUserTurn(settings);
    ThreadReconcileSnapshot snapshot =
        transactions.loadOwnedSnapshot(prepared.ownership(), NOW).orElseThrow();
    assertInstanceOf(CreateModelInvocation.class, snapshot.primaryWork().orElseThrow());

    assertInstanceOf(
        ModelCreationOutcome.PlanningFailureApplied.class,
        transactions.createModelInvocationAndRelease(prepared.ownership(), NOW.plusSeconds(1)));
    assertEquals(
        0L,
        jdbc.queryForObject(
            "select count(*) from harness_model_invocation where thread_id = ?",
            Long.class,
            prepared.threadId()));

    Long headEntryId =
        jdbc.queryForObject(
            "select head_entry_id from harness_thread where id = ?",
            Long.class,
            prepared.threadId());
    assertEquals("ASSISTANT_ERROR", entryType(headEntryId));
    AssistantErrorEntryPayload error =
        assertInstanceOf(
            AssistantErrorEntryPayload.class,
            ENTRY_CODEC.decode(
                EntryType.ASSISTANT_ERROR,
                jdbc.queryForObject(
                    "select payload::text from harness_entry where id = ?",
                    String.class,
                    headEntryId)));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.error().kind());
    assertTrue(error.error().message().contains("AGENT_NOT_FOUND"));
  }

  @Test
  void creationTransactionResolvesCatalogReadsFromOneDatabaseSnapshot() {
    String providerName = "snapshot-provider";
    jdbc.update(
        """
        insert into agent_provider (name, provider_type, description, config)
        values (?, 'openai', 'before', '{}'::jsonb)
        """,
        providerName);
    TurnSettings settings = new TurnSettings("agent-a", null, false);
    List<String> observedDescriptions = new CopyOnWriteArrayList<>();
    when(executionResolver.resolve(settings))
        .thenAnswer(
            ignored -> {
              observedDescriptions.add(providerDescription(providerName));
              CompletableFuture.runAsync(
                      () ->
                          jdbc.update(
                              "update agent_provider set description = 'after' where name = ?",
                              providerName))
                  .join();
              observedDescriptions.add(providerDescription(providerName));
              return new TurnExecutionResolver.Resolution.Resolved(
                  execution(providerName, "model-a", false));
            });

    Prepared prepared = prepareUserTurn(settings);
    assertInstanceOf(
        ModelCreationOutcome.Created.class,
        transactions.createModelInvocationAndRelease(prepared.ownership(), NOW.plusSeconds(1)));

    assertEquals(List.of("before", "before"), observedDescriptions);
    assertEquals("after", providerDescription(providerName));
  }

  @Test
  void creationReturnsLostOwnershipAfterRelease() {
    TurnSettings settings = new TurnSettings("agent-a", null, false);
    when(executionResolver.resolve(settings))
        .thenReturn(
            new TurnExecutionResolver.Resolution.Resolved(
                execution("provider-a", "model-a", false)));
    Prepared prepared = prepareUserTurn(settings);
    assertInstanceOf(
        ModelCreationOutcome.Created.class,
        transactions.createModelInvocationAndRelease(prepared.ownership(), NOW.plusSeconds(1)));

    assertInstanceOf(
        ModelCreationOutcome.LostOwnership.class,
        transactions.createModelInvocationAndRelease(prepared.ownership(), NOW.plusSeconds(2)));

    verify(executionResolver).resolve(settings);
  }

  @Test
  void creationRejectsMissingResponseDebt() {
    Prepared prepared = prepareUserTurn(new TurnSettings("agent-a", null, false));
    Long rootEntryId =
        jdbc.queryForObject(
            "select id from harness_entry where session_id = "
                + "(select session_id from harness_thread where id = ?) "
                + "and parent_entry_id is null",
            Long.class,
            prepared.threadId());
    jdbc.update(
        "update harness_thread set head_entry_id = ? where id = ?",
        rootEntryId,
        prepared.threadId());

    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () ->
                transactions.createModelInvocationAndRelease(
                    prepared.ownership(), NOW.plusSeconds(1)));

    assertTrue(failure.getMessage().contains("response debt disappeared"));
    verifyNoInteractions(executionResolver);
  }

  @Test
  void creationRetriesAfterConcurrentThreadUpdateInvalidatesItsFirstSnapshot() throws Exception {
    TurnSettings settings = new TurnSettings("agent-a", null, false);
    when(executionResolver.resolve(settings))
        .thenReturn(
            new TurnExecutionResolver.Resolution.Resolved(
                execution("provider-a", "model-a", false)));
    Prepared prepared = prepareUserTurn(settings);
    TransactionTemplate concurrentCommand = new TransactionTemplate(transactionManager);
    CountDownLatch rowUpdated = new CountDownLatch(1);
    CountDownLatch releaseCommand = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<?> command =
          executor.submit(
              () ->
                  concurrentCommand.executeWithoutResult(
                      ignored -> {
                        jdbc.queryForObject(
                            "select id from harness_thread where id = ? for no key update",
                            Long.class,
                            prepared.threadId());
                        jdbc.update(
                            "update harness_thread set revision = revision + 1 where id = ?",
                            prepared.threadId());
                        rowUpdated.countDown();
                        await(releaseCommand);
                      }));
      assertTrue(rowUpdated.await(5, TimeUnit.SECONDS));

      Future<ModelCreationOutcome> creation =
          executor.submit(
              () ->
                  transactions.createModelInvocationAndRelease(
                      prepared.ownership(), NOW.plusSeconds(1)));
      assertTrue(awaitCreationThreadLock());
      releaseCommand.countDown();

      assertInstanceOf(ModelCreationOutcome.Created.class, creation.get(10, TimeUnit.SECONDS));
      command.get(10, TimeUnit.SECONDS);
      verify(executionResolver).resolve(settings);
    } finally {
      releaseCommand.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void creationRetriesSqlStateSerializationFailureFromCauseChain() {
    TurnSettings settings = new TurnSettings("agent-a", null, false);
    when(executionResolver.resolve(settings))
        .thenThrow(new RuntimeException(new SQLException("serialization failure", "40001")))
        .thenReturn(
            new TurnExecutionResolver.Resolution.Resolved(
                execution("provider-a", "model-a", false)));
    Prepared prepared = prepareUserTurn(settings);

    assertInstanceOf(
        ModelCreationOutcome.Created.class,
        transactions.createModelInvocationAndRelease(prepared.ownership(), NOW.plusSeconds(1)));

    verify(executionResolver, times(2)).resolve(settings);
  }

  @Test
  void creationStopsAfterSerializationRetryLimit() {
    TurnSettings settings = new TurnSettings("agent-a", null, false);
    CannotSerializeTransactionException failure =
        new CannotSerializeTransactionException("serialization failure");
    when(executionResolver.resolve(settings)).thenThrow(failure);
    Prepared prepared = prepareUserTurn(settings);

    assertEquals(
        failure,
        assertThrows(
            CannotSerializeTransactionException.class,
            () ->
                transactions.createModelInvocationAndRelease(
                    prepared.ownership(), NOW.plusSeconds(1))));

    verify(executionResolver, times(3)).resolve(settings);
  }

  @Test
  void creationDoesNotRetryNonSerializationFailure() {
    TurnSettings settings = new TurnSettings("agent-a", null, false);
    IllegalStateException failure = new IllegalStateException("resolver failure");
    when(executionResolver.resolve(settings)).thenThrow(failure);
    Prepared prepared = prepareUserTurn(settings);

    assertEquals(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                transactions.createModelInvocationAndRelease(
                    prepared.ownership(), NOW.plusSeconds(1))));

    verify(executionResolver).resolve(settings);
  }

  private Prepared prepareUserTurn(TurnSettings settings) {
    TestThreads.Created created = TestThreads.create(commands, "reconcile", NOW);
    ThreadCommandTransactions.EnqueueResult queued =
        commands.enqueue(
            created.threadId(),
            userPayload(settings, "hello"),
            "user-1",
            created.executionEpoch(),
            NOW);
    ThreadOwnership ownership =
        transactions.claim(created.threadId(), "processor", NOW).orElseThrow();
    ThreadReconcileSnapshot queuedSnapshot =
        transactions.loadOwnedSnapshot(ownership, NOW).orElseThrow();
    List<ThreadInput> inputs = queuedSnapshot.queuedInputs();
    assertEquals(List.of(queued.input()), inputs);
    assertEquals(
        ApplyOutcome.PROGRESSED,
        transactions.harvestBatch(ownership, new TurnInputBatch(created.threadId(), inputs), NOW));
    return new Prepared(created.threadId(), ownership);
  }

  private static RuntimeEntryInputPayload userPayload(TurnSettings settings, String content) {
    return new RuntimeEntryInputPayload(
        ThreadInputType.USER_MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(content))),
            settings,
            null));
  }

  private static ResolvedTurnExecution execution(
      String providerName, String modelName, boolean yoloEnabled) {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "test",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    return new ResolvedTurnExecution(
        "system prompt",
        new ModelDescriptor(
            providerName,
            0L,
            modelName,
            ProviderType.OPENAI,
            false,
            false,
            pricing,
            PromptCachePolicy.disabled()),
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        yoloEnabled);
  }

  private String entryType(long entryId) {
    return jdbc.queryForObject(
        "select entry_type from harness_entry where id = ?", String.class, entryId);
  }

  private String providerDescription(String providerName) {
    return jdbc.queryForObject(
        "select description from agent_provider where name = ?", String.class, providerName);
  }

  private boolean awaitCreationThreadLock() throws InterruptedException {
    for (int attempt = 0; attempt < 100; attempt++) {
      Integer waiting =
          jdbc.queryForObject(
              """
              select count(*)::int
              from pg_stat_activity
              where datname = current_database()
                and pid <> pg_backend_pid()
                and wait_event_type = 'Lock'
                and lower(query) like '%for no key update of t%'
              """,
              Integer.class);
      if (waiting != null && waiting > 0) {
        return true;
      }
      Thread.sleep(25);
    }
    return false;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for concurrent transaction");
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while waiting for concurrent transaction", error);
    }
  }

  private record Prepared(long threadId, ThreadOwnership ownership) {}
}
