package fun.fengwk.kkstudio.core.ai.runtime.thread.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;
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
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.ApplyPlanningFailure;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.TurnInputBatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

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
  @MockitoBean private TurnExecutionResolver executionResolver;

  @Test
  void resolvesTurnSettingsAndFreezesTheSuccessfulRequest() {
    TurnSettings settings = new TurnSettings("agent-a", "environment-a", true);
    ResolvedTurnExecution first = execution("provider-a", "model-a", true);
    ResolvedTurnExecution later = execution("provider-b", "model-b", false);
    when(executionResolver.resolve(settings))
        .thenReturn(new TurnExecutionResolver.Resolution.Resolved(first));

    Prepared prepared = prepareUserTurn(settings);
    ThreadReconcileSnapshot snapshot =
        transactions.loadOwnedSnapshot(prepared.ownership(), NOW).orElseThrow();
    CreateModelInvocation create =
        assertInstanceOf(CreateModelInvocation.class, snapshot.primaryWork().orElseThrow());
    ModelInvocationPlan plan = create.plan();
    assertEquals(first.model(), plan.request().providerRequest().model());
    assertEquals(first.variant(), plan.request().providerRequest().variant());
    assertTrue(plan.request().yoloEnabled());

    ModelCreationOutcome.Created created =
        assertInstanceOf(
            ModelCreationOutcome.Created.class,
            transactions.createModelInvocationAndRelease(
                prepared.ownership(), plan, NOW.plusSeconds(1)));

    when(executionResolver.resolve(settings))
        .thenReturn(new TurnExecutionResolver.Resolution.Resolved(later));
    String requestJson =
        jdbc.queryForObject(
            "select request::text from harness_model_invocation where id = ?",
            String.class,
            created.target().id());
    assertEquals(first.model(), REQUEST_CODEC.decode(requestJson).providerRequest().model());
    assertEquals(first.variant(), REQUEST_CODEC.decode(requestJson).providerRequest().variant());
    assertTrue(REQUEST_CODEC.decode(requestJson).yoloEnabled());
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
    ApplyPlanningFailure apply =
        assertInstanceOf(ApplyPlanningFailure.class, snapshot.primaryWork().orElseThrow());
    assertEquals(failure, apply.failure());

    assertEquals(
        ApplyOutcome.PROGRESSED,
        transactions.applyPlanningFailure(
            prepared.ownership(), apply.failure(), NOW.plusSeconds(1)));
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

  private record Prepared(long threadId, ThreadOwnership ownership) {}
}
