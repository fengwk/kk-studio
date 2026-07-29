package fun.fengwk.kkstudio.core.harness.thread.reconcile;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.harness.thread.command.TestThreads;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.continuation.ContinuationRef;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.plan.ModelInvocationPlan;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeConfigInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ApplyOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ModelCreationOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.QuiesceOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.SuspendOutcome;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadOwnership;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.ApplyTerminalToolBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileSnapshot.PrimaryWork.SuspendForBlocker;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.ThreadReconcileTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.reconcile.TurnBoundary;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolResultJsonCodec;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.ArtifactToolContent;
import fun.fengwk.kkstudio.harness.tool.JsonToolContent;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** PostgreSQL 17 上 reconcile 的 lease、fence、apply 与回滚契约。 */
class PostgresqlThreadReconcileTransactionsIntegrationTest extends PostgresSpringTestSupport {

  private static final ProviderResponseJsonCodec RESPONSE_CODEC = new ProviderResponseJsonCodec();
  private static final ToolInvocationErrorJsonCodec TOOL_ERROR_CODEC =
      new ToolInvocationErrorJsonCodec();

  @Autowired private ThreadReconcileTransactions transactions;
  @Autowired private ThreadCommandTransactions commands;

  @Test
  void claimsRenewsAndFencesExpiredTokenAndEpoch() {
    Instant now = Instant.now();
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(commands, "lease", now);
    long threadId = boot.threadId();
    commands.enqueue(threadId, user("lease"), "lease", boot.executionEpoch(), now);

    ThreadOwnership ownership = transactions.claim(threadId, "one", now).orElseThrow();
    assertTrue(transactions.renew(ownership, now.plusSeconds(1)));
    assertFalse(
        transactions.renew(
            new ThreadOwnership(ownership.threadId(), ownership.executionEpoch(), "other"),
            now.plusSeconds(1)));
    assertTrue(transactions.claim(threadId, "two", now.plus(Duration.ofMinutes(10))).isPresent());
    ThreadOwnership recovered =
        transactions.claim(threadId, "three", now.plus(Duration.ofMinutes(20))).orElseThrow();
    assertFalse(
        transactions.loadOwnedSnapshot(ownership, now.plus(Duration.ofMinutes(10))).isPresent());
    commands.stop(
        threadId, recovered.executionEpoch(), now.plus(Duration.ofMinutes(20)).plusSeconds(1));
    assertFalse(transactions.renew(recovered, now.plus(Duration.ofMinutes(20)).plusSeconds(2)));

    long afterStopEpoch = recovered.executionEpoch() + 1;
    commands.enqueue(
        threadId,
        user("after-stop"),
        "after-stop",
        afterStopEpoch,
        now.plus(Duration.ofMinutes(20)).plusSeconds(3));
    ThreadOwnership afterStop =
        transactions
            .claim(threadId, "after-stop", now.plus(Duration.ofMinutes(20)).plusSeconds(3))
            .orElseThrow();
    assertEquals(afterStopEpoch, afterStop.executionEpoch());
  }

  /** stop + rebind 之后旧 epoch 的 ownership 不能 renew/claim，也不能再 apply 结果。 */
  @Test
  void rebindInvalidatesStaleOwnershipRenewClaimAndApply() {
    Instant now = Instant.now();
    Prepared prepared = prepareDebt(now, List.of());
    long modelId = createInvocation(prepared, now);
    completeModel(modelId, "SUCCEEDED", response(List.of()), null);
    ThreadOwnership stale = prepared.ownership();
    long staleEpoch = stale.executionEpoch();

    // 用户逻辑 stop 后把 head 拨回 ROOT：epoch 前进两次，旧 worker 的一切写入都被 fence。
    commands.stop(prepared.threadId(), staleEpoch, Instant.now());
    commands.updateHead(prepared.threadId(), staleEpoch + 1, prepared.rootEntryId(), Instant.now());

    assertFalse(
        transactions.renew(stale, Instant.now()), "stale ownership must not renew after rebind");
    assertFalse(
        transactions.loadOwnedSnapshot(stale, Instant.now()).isPresent(),
        "stale ownership must not load a snapshot after rebind");
    assertEquals(
        ApplyOutcome.LOST_OWNERSHIP,
        transactions.applyTerminalModel(stale, modelId, Instant.now()),
        "the old epoch worker must not apply into the new context");
    // rebind cleared runnable, so no processor can claim the Thread until new work arrives
    assertFalse(transactions.claim(prepared.threadId(), "stale-claim", Instant.now()).isPresent());
    assertEquals(
        prepared.rootEntryId(),
        longScalar("select head_entry_id from harness_thread where id = " + prepared.threadId()));
  }

  /** UNBOUND Thread 既不接受 enqueue，也不会被 reconcile claim。 */
  @Test
  void reconcileNeverClaimsUnboundThread() {
    Instant now = Instant.now();
    long threadId = commands.createThread(now).id();
    assertThrows(
        IllegalStateException.class, () -> commands.enqueue(threadId, user("x"), "x", 0L, now));
    assertFalse(transactions.claim(threadId, "unbound", now).isPresent());
    // even a forcibly runnable UNBOUND Thread stays unclaimable
    execute("update harness_thread set runnable = true where id = " + threadId);
    assertFalse(transactions.claim(threadId, "unbound-runnable", now).isPresent());
  }

  @Test
  void snapshotsPlanBeforeLaterQueuedInputThenHarvestsAndCreatesInvocation() {
    Instant now = Instant.now();
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(commands, "snapshot", now);
    long threadId = boot.threadId();
    long epoch = boot.executionEpoch();
    RuntimeConfigInputPayload frozenConfig = config(List.of(platformTool()));
    commands.enqueue(threadId, frozenConfig, "config", epoch, now);
    commands.enqueue(threadId, user("hello"), "user", epoch, now);
    ThreadOwnership ownership = transactions.claim(threadId, "snapshot", now).orElseThrow();

    ThreadReconcileSnapshot queued = transactions.loadOwnedSnapshot(ownership, now).orElseThrow();
    assertEquals(2, queued.queuedInputs().size());
    assertInstanceOf(RuntimeConfigInputPayload.class, queued.queuedInputs().get(0).payload());
    assertInstanceOf(RuntimeEntryInputPayload.class, queued.queuedInputs().get(1).payload());
    ThreadInput durableMessage = queued.queuedInputs().get(1);
    ThreadInput tamperedMessage =
        new ThreadInput(
            durableMessage.id(),
            durableMessage.threadId(),
            durableMessage.sequence(),
            durableMessage.type(),
            user("tampered"),
            durableMessage.idempotencyKey(),
            durableMessage.status(),
            durableMessage.createdAt(),
            durableMessage.appliedAt());
    assertEquals(
        ApplyOutcome.LOST_OWNERSHIP,
        transactions.harvestBoundary(
            ownership,
            new TurnBoundary(threadId, List.of(queued.queuedInputs().get(0), tamperedMessage)),
            now));
    assertEquals(
        ApplyOutcome.PROGRESSED,
        transactions.harvestBoundary(
            ownership, new TurnBoundary(threadId, queued.queuedInputs()), now));

    ThreadReconcileSnapshot snapshot = transactions.loadOwnedSnapshot(ownership, now).orElseThrow();
    CreateModelInvocation cmi =
        assertInstanceOf(CreateModelInvocation.class, snapshot.primaryWork().orElseThrow());
    ModelInvocationPlan plan = cmi.plan();
    assertEquals(frozenConfig.snapshot(), plan.configSnapshot());

    commands.enqueue(threadId, config(List.of()), "later-config", epoch, now.plusSeconds(1));
    assertTrue(
        transactions
            .loadOwnedSnapshot(ownership, now.plusSeconds(1))
            .orElseThrow()
            .primaryWork()
            .isPresent());
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.createModelInvocationAndRelease(
                ownership,
                new ModelInvocationPlan(
                    plan.sourceHeadEntryId(), plan.request(), config(List.of()).snapshot()),
                now.plusSeconds(1)));
    ModelCreationOutcome outcome =
        transactions.createModelInvocationAndRelease(ownership, plan, now.plusSeconds(1));
    assertTrue(outcome instanceof ModelCreationOutcome.Created);
    assertEquals(1, count("select count(*) from harness_model_invocation"));
    assertEquals(1, count("select count(*) from harness_thread_input where status = 'QUEUED'"));
  }

  @Test
  void appliesSuccessfulModelWithFullUsageAndFrozenPlatformAndEnvironmentTools() {
    Instant now = Instant.now();
    List<ToolBinding> bindings = List.of(platformTool(), environmentTool());
    Prepared prepared = prepareDebt(now, bindings);
    long modelId = createInvocation(prepared, now);
    commands.enqueue(
        prepared.threadId(),
        config(List.of()),
        "later-config",
        prepared.ownership().executionEpoch(),
        now.plusSeconds(1));
    completeModel(
        modelId,
        "SUCCEEDED",
        response(
            List.of(
                new ProviderToolCall("platform-call", "platformTool", "{}"),
                new ProviderToolCall("environment-call", "environmentTool", "{}"))),
        null);

    wake(prepared.threadId());
    ThreadOwnership owner =
        transactions.claim(prepared.threadId(), "apply", Instant.now()).orElseThrow();
    assertEquals(
        ApplyOutcome.PROGRESSED, transactions.applyTerminalModel(owner, modelId, Instant.now()));
    assertEquals(1, count("select count(*) from harness_model_usage"));
    assertEquals(2, count("select count(*) from harness_tool_invocation"));
    assertEquals(
        "PLATFORM", scalar("select location from harness_tool_invocation where ordinal = 0"));
    assertEquals(
        "environment",
        scalar("select environment_name from harness_tool_invocation where ordinal = 1"));
    assertEquals(
        1, count("select count(*) from harness_model_invocation where applied_at is not null"));
    assertEquals(7L, longScalar("select usage_provider_total_tokens from harness_model_usage"));
    assertEquals(1L, longScalar("select provider_resource_id from harness_model_usage"));
    assertEquals(2L, longScalar("select model_resource_id from harness_model_usage"));
    assertEquals("OPENAI", scalar("select provider_type from harness_model_usage"));
    assertEquals("model", scalar("select provider_model_id from harness_model_usage"));
    assertEquals("UNSUPPORTED", scalar("select prompt_cache_mode from harness_model_usage"));
    assertEquals("NONE", scalar("select prompt_cache_retention from harness_model_usage"));
    assertEquals("TOOL_CALLS", scalar("select stop_reason from harness_model_usage"));
    assertEquals("USD", scalar("select pricing_currency from harness_model_usage"));
    assertEquals("tier", scalar("select pricing_tier from harness_model_usage"));
    assertEquals("request", scalar("select request_id from harness_model_usage"));
    assertEquals("tier", scalar("select reported_service_tier from harness_model_usage"));
    assertEquals("{}", scalar("select raw_usage::text from harness_model_usage"));
    assertEquals(
        1,
        count(
            "select count(*) from harness_thread_input where thread_id = "
                + prepared.threadId()
                + " and status = 'QUEUED'"));
  }

  @Test
  void appliesFailedAndCancelledModelsWithoutUsageOrTools() {
    Instant now = Instant.now();
    for (String status : List.of("FAILED", "UNKNOWN", "CANCELLED")) {
      Prepared prepared = prepareDebt(now, List.of());
      long modelId = createInvocation(prepared, now);
      completeModel(
          modelId,
          status,
          null,
          "CANCELLED".equals(status) ? null : "{\"kind\":\"TRANSIENT\",\"message\":\"boom\"}");
      wake(prepared.threadId());
      ThreadOwnership owner =
          transactions.claim(prepared.threadId(), status, Instant.now()).orElseThrow();
      assertEquals(
          ApplyOutcome.PROGRESSED, transactions.applyTerminalModel(owner, modelId, Instant.now()));
    }
    assertEquals(0, count("select count(*) from harness_model_usage"));
    assertEquals(0, count("select count(*) from harness_tool_invocation"));
    assertEquals(
        3, count("select count(*) from harness_entry where entry_type = 'ASSISTANT_ERROR'"));
  }

  @Test
  void appliesTypedTerminalToolSiblingsAndRejectsPartialBatchInvariant() {
    Instant now = Instant.now();
    Prepared prepared = prepareDebt(now, List.of(platformTool(), environmentTool()));
    long modelId = createInvocation(prepared, now);
    completeModel(
        modelId,
        "SUCCEEDED",
        response(
            List.of(
                new ProviderToolCall("one", "platformTool", "{}"),
                new ProviderToolCall("two", "environmentTool", "{}"),
                new ProviderToolCall("three", "platformTool", "{}"),
                new ProviderToolCall("four", "environmentTool", "{}"))),
        null);
    wake(prepared.threadId());
    ThreadOwnership modelOwner =
        transactions.claim(prepared.threadId(), "model", Instant.now()).orElseThrow();
    transactions.applyTerminalModel(modelOwner, modelId, Instant.now());
    long assistantId =
        longScalar("select head_entry_id from harness_thread where id = " + prepared.threadId());
    List<Long> tools = ids("select id from harness_tool_invocation order by ordinal");
    completeTool(
        tools.get(0),
        "SUCCEEDED",
        ToolResultJsonCodec.encode(
            new ToolResult(
                "one",
                List.of(
                    new TextToolContent("text"),
                    new JsonToolContent("{\"v\":1}"),
                    new ArtifactToolContent(
                        new ArtifactRef("artifact-1", "application/octet-stream", 3))),
                false,
                "{\"source\":\"tool\"}",
                false)),
        null);
    completeTool(
        tools.get(1), "FAILED", null, TOOL_ERROR_CODEC.encode(new ToolInvocationError("IO", "no")));
    completeTool(tools.get(2), "CANCELLED", null, null);
    completeTool(
        tools.get(3),
        "UNKNOWN",
        null,
        TOOL_ERROR_CODEC.encode(new ToolInvocationError("UNKNOWN_STATE", "lost")));
    assertEquals(
        ApplyOutcome.PROGRESSED,
        transactions.applyTerminalToolResults(modelOwner, assistantId, Instant.now()));
    assertEquals(
        4, count("select count(*) from harness_tool_invocation where applied_at is not null"));
    assertEquals(
        4,
        count(
            "select count(*) from harness_entry where entry_type = 'MESSAGE' and payload #>> '{message,role}' = 'TOOL'"));
    List<String> payloads = toolResultPayloads(assistantId);
    assertEquals(4, payloads.size());
    assertTrue(payloads.get(0).contains("artifact-1"));
    assertTrue(payloads.get(0).contains("{\\\"v\\\":1}"));
    assertTrue(payloads.get(0).contains("{\\\"source\\\":\\\"tool\\\"}"));
    assertTrue(payloads.get(1).contains("\\\"kind\\\":\\\"IO\\\""));
    assertTrue(payloads.get(2).contains("\\\"kind\\\":\\\"CANCELLED\\\""));
    assertTrue(payloads.get(3).contains("\\\"kind\\\":\\\"UNKNOWN_STATE\\\""));
    assertFalse(
        transactions
            .loadOwnedSnapshot(modelOwner, Instant.now())
            .orElseThrow()
            .primaryWork()
            .filter(ApplyTerminalToolBatch.class::isInstance)
            .isPresent());

    Prepared partial = prepareDebt(Instant.now(), List.of(platformTool(), environmentTool()));
    long partialModel = createInvocation(partial, Instant.now());
    completeModel(
        partialModel,
        "SUCCEEDED",
        response(
            List.of(
                new ProviderToolCall("partial-one", "platformTool", "{}"),
                new ProviderToolCall("partial-two", "environmentTool", "{}"))),
        null);
    wake(partial.threadId());
    ThreadOwnership partialOwner =
        transactions.claim(partial.threadId(), "partial", Instant.now()).orElseThrow();
    transactions.applyTerminalModel(partialOwner, partialModel, Instant.now());
    List<Long> partialTools =
        ids(
            "select id from harness_tool_invocation where thread_id = "
                + partial.threadId()
                + " order by ordinal");
    completeTool(
        partialTools.get(0),
        "SUCCEEDED",
        ToolResultJsonCodec.encode(
            new ToolResult("partial-one", List.of(new TextToolContent("ok")), false, "{}", false)),
        null);
    completeTool(
        partialTools.get(1),
        "SUCCEEDED",
        ToolResultJsonCodec.encode(
            new ToolResult("partial-two", List.of(new TextToolContent("ok")), false, "{}", false)),
        null);
    execute(
        "update harness_tool_invocation set applied_at = finished_at where id = ?",
        partialTools.get(0));
    assertThrows(
        IllegalStateException.class,
        () -> transactions.loadOwnedSnapshot(partialOwner, Instant.now()));
  }

  @Test
  void suspendsForStableBlockerQuiescesAndRollsBackUnboundToolMaterialization() {
    Instant now = Instant.now();
    Prepared prepared = prepareDebt(now, List.of());
    long modelId = createInvocation(prepared, now);
    wake(prepared.threadId());
    ThreadOwnership owner =
        transactions.claim(prepared.threadId(), "blocker", Instant.now()).orElseThrow();
    commands.enqueue(
        prepared.threadId(),
        user("later"),
        "later",
        prepared.ownership().executionEpoch(),
        Instant.now());
    ContinuationRef blocker =
        transactions
            .loadOwnedSnapshot(owner, Instant.now())
            .orElseThrow()
            .primaryWork()
            .map(SuspendForBlocker.class::cast)
            .map(SuspendForBlocker::blocker)
            .orElseThrow();
    assertEquals(
        SuspendOutcome.SUSPENDED, transactions.suspendAndRecheck(owner, blocker, Instant.now()));
    assertEquals(
        1,
        count(
            "select count(*) from harness_thread_input where thread_id = "
                + prepared.threadId()
                + " and status = 'QUEUED'"));

    Prepared rollback = prepareDebt(Instant.now(), List.of());
    long rollbackModel = createInvocation(rollback, Instant.now());
    long rollbackHead =
        longScalar("select head_entry_id from harness_thread where id = " + rollback.threadId());
    completeModel(
        rollbackModel,
        "SUCCEEDED",
        response(List.of(new ProviderToolCall("unknown", "notBound", "{}"))),
        null);
    wake(rollback.threadId());
    ThreadOwnership rollbackOwner =
        transactions.claim(rollback.threadId(), "rollback", Instant.now()).orElseThrow();
    assertThrows(
        IllegalStateException.class,
        () -> transactions.applyTerminalModel(rollbackOwner, rollbackModel, Instant.now()));
    assertEquals(0, count("select count(*) from harness_model_usage"));
    assertEquals(0, count("select count(*) from harness_tool_invocation"));
    assertEquals(
        rollbackHead,
        longScalar("select head_entry_id from harness_thread where id = " + rollback.threadId()));
    assertEquals(
        0,
        count(
            "select count(*) from harness_model_invocation where id = "
                + rollbackModel
                + " and applied_at is not null"));
    assertEquals(
        QuiesceOutcome.LOST_OWNERSHIP, transactions.quiesceAndRecheck(owner, Instant.now()));

    TestThreads.Bootstrapped quiescentBoot =
        TestThreads.bootstrap(commands, "quiescent", Instant.now());
    long quiescentThread = quiescentBoot.threadId();
    commands.enqueue(
        quiescentThread,
        config(List.of()),
        "config-only",
        quiescentBoot.executionEpoch(),
        Instant.now());
    ThreadOwnership quiescentOwner =
        transactions.claim(quiescentThread, "quiescent", Instant.now()).orElseThrow();
    List<ThreadInput> configOnly =
        transactions.loadOwnedSnapshot(quiescentOwner, Instant.now()).orElseThrow().queuedInputs();
    assertEquals(
        ApplyOutcome.PROGRESSED,
        transactions.harvestBoundary(
            quiescentOwner, new TurnBoundary(quiescentThread, configOnly), Instant.now()));
    assertEquals(
        QuiesceOutcome.QUIESCENT, transactions.quiesceAndRecheck(quiescentOwner, Instant.now()));
    assertEquals(
        0L,
        longScalar(
            "select case when runnable then 1 else 0 end from harness_thread where id = "
                + quiescentThread));
  }

  @Test
  void quiesceAndConcurrentEnqueueCannotLoseTheWake() {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int iteration = 0; iteration < 8; iteration++) {
        Instant now = Instant.now();
        TestThreads.Bootstrapped boot =
            TestThreads.bootstrap(commands, "lost-wake-" + iteration, now);
        long threadId = boot.threadId();
        long epoch = boot.executionEpoch();
        commands.enqueue(threadId, config(List.of()), "config", epoch, now);
        ThreadOwnership ownership =
            transactions.claim(threadId, "owner-" + iteration, now).orElseThrow();
        List<ThreadInput> inputs =
            transactions.loadOwnedSnapshot(ownership, now).orElseThrow().queuedInputs();
        assertEquals(
            ApplyOutcome.PROGRESSED,
            transactions.harvestBoundary(ownership, new TurnBoundary(threadId, inputs), now));

        CountDownLatch start = new CountDownLatch(1);
        Future<QuiesceOutcome> quiesce =
            executor.submit(
                () -> {
                  start.await();
                  return transactions.quiesceAndRecheck(ownership, Instant.now());
                });
        Future<?> enqueue =
            executor.submit(
                () -> {
                  start.await();
                  commands.enqueue(threadId, user("wake"), "wake", epoch, Instant.now());
                  return null;
                });
        start.countDown();

        QuiesceOutcome outcome = get(quiesce);
        get(enqueue);
        assertTrue(outcome == QuiesceOutcome.QUIESCENT || outcome == QuiesceOutcome.WORK_AVAILABLE);
        assertEquals(
            1L,
            longScalar(
                "select case when runnable then 1 else 0 end from harness_thread where id = "
                    + threadId));
        assertEquals(
            1,
            count(
                "select count(*) from harness_thread_input where thread_id = "
                    + threadId
                    + " and status = 'QUEUED'"));
        transactions.bestEffortRelease(ownership, Instant.now());
      }
    }
  }

  private Prepared prepareDebt(Instant now, List<ToolBinding> tools) {
    TestThreads.Bootstrapped boot = TestThreads.bootstrap(commands, "reconcile", now);
    long threadId = boot.threadId();
    commands.enqueue(threadId, config(tools), "config", boot.executionEpoch(), now);
    commands.enqueue(threadId, user("hello"), "user", boot.executionEpoch(), now);
    Instant claimedAt = now;
    assertEquals(
        1L,
        longScalar(
            "select case when runnable then 1 else 0 end from harness_thread where id = "
                + threadId));
    ThreadOwnership ownership = transactions.claim(threadId, "harvest", claimedAt).orElseThrow();
    List<ThreadInput> inputs =
        transactions.loadOwnedSnapshot(ownership, claimedAt).orElseThrow().queuedInputs();
    assertEquals(
        ApplyOutcome.PROGRESSED,
        transactions.harvestBoundary(ownership, new TurnBoundary(threadId, inputs), claimedAt));
    return new Prepared(threadId, boot.rootEntryId(), ownership);
  }

  private long createInvocation(Prepared prepared, Instant now) {
    ThreadReconcileSnapshot snapshot =
        transactions.loadOwnedSnapshot(prepared.ownership(), now).orElseThrow();
    ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation cmi =
        assertInstanceOf(
            ThreadReconcileSnapshot.PrimaryWork.CreateModelInvocation.class,
            snapshot.primaryWork().orElseThrow());
    ModelCreationOutcome outcome =
        transactions.createModelInvocationAndRelease(prepared.ownership(), cmi.plan(), now);
    return ((ModelCreationOutcome.Created) outcome).target().id();
  }

  private static RuntimeConfigInputPayload config(List<ToolBinding> tools) {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier",
            "service",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ONE);
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            1,
            2,
            ProviderType.OPENAI,
            "model",
            !tools.isEmpty(),
            false,
            pricing,
            PromptCachePolicy.disabled());
    return new RuntimeConfigInputPayload(
        ThreadInputType.SET_AGENT,
        new RuntimeConfigSnapshot(
            new AgentSnapshot(1, "agent", "system"),
            new ModelSnapshot(descriptor, variant),
            tools,
            List.of(),
            false));
  }

  private static RuntimeEntryInputPayload user(String text) {
    return new RuntimeEntryInputPayload(
        ThreadInputType.USER_MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.<AgentMessageContent>of(new TextMessageContent(text)))));
  }

  private static ToolBinding platformTool() {
    return ToolBinding.of(tool("platformTool", ToolExecutionLocation.PLATFORM));
  }

  private static ToolBinding environmentTool() {
    return ToolBinding.of(
        tool("environmentTool", ToolExecutionLocation.ENVIRONMENT), "environment");
  }

  private static ToolDescriptor tool(String name, ToolExecutionLocation location) {
    return new ToolDescriptor(
        name,
        "v1",
        name,
        null,
        new ToolParamsSchema(null, Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(1));
  }

  private static ProviderResponse response(List<ProviderToolCall> calls) {
    ModelUsage usage = new ModelUsage(1, 2, 1, 1, 1, 1, 7);
    ModelCost cost =
        new ModelCost(
            "USD",
            new BigDecimal("0.000001"),
            new BigDecimal("0.000002"),
            new BigDecimal("0.000001"),
            new BigDecimal("0.000001"),
            new BigDecimal("0.000001"),
            new BigDecimal("0.000001"),
            new BigDecimal("0.000007"));
    return new ProviderResponse(
        "answer",
        "thinking",
        calls,
        calls.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS,
        usage,
        cost,
        "request",
        "tier",
        "{}");
  }

  private static void completeModel(long id, String status, ProviderResponse result, String error) {
    execute(
        "update harness_model_invocation set status = ?, result = cast(? as jsonb), error = cast(? as jsonb),"
            + " started_at = greatest(current_timestamp, created_at),"
            + " deadline_at = greatest(current_timestamp, created_at) + interval '1 minute',"
            + " last_activity_at = greatest(current_timestamp, created_at),"
            + " finished_at = greatest(current_timestamp, created_at) where id = ?",
        status,
        result == null ? null : RESPONSE_CODEC.encode(result),
        error,
        id);
  }

  private static void completeTool(long id, String status, String result, String error) {
    execute(
        "update harness_tool_invocation set status = ?, result = cast(? as jsonb), error = cast(? as jsonb),"
            + " started_at = greatest(current_timestamp, created_at),"
            + " deadline_at = greatest(current_timestamp, created_at) + interval '1 minute',"
            + " last_activity_at = greatest(current_timestamp, created_at),"
            + " finished_at = greatest(current_timestamp, created_at) where id = ?",
        status,
        result,
        error,
        id);
  }

  private static void execute(String sql, Object... values) {
    try (Connection connection = newConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      assertEquals(1, statement.executeUpdate());
    } catch (SQLException exception) {
      throw new AssertionError(exception);
    }
  }

  private static void wake(long threadId) {
    execute("update harness_thread set runnable = true where id = ?", threadId);
  }

  private static int count(String sql) {
    return Math.toIntExact(longScalar(sql));
  }

  private static long longScalar(String sql) {
    try (Connection connection = newConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      assertTrue(result.next());
      return result.getLong(1);
    } catch (SQLException exception) {
      throw new AssertionError(exception);
    }
  }

  private static String scalar(String sql) {
    try (Connection connection = newConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      assertTrue(result.next());
      return result.getString(1);
    } catch (SQLException exception) {
      throw new AssertionError(exception);
    }
  }

  private static List<Long> ids(String sql) {
    try (Connection connection = newConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet result = statement.executeQuery()) {
      ArrayList<Long> ids = new ArrayList<>();
      while (result.next()) {
        ids.add(result.getLong(1));
      }
      return ids;
    } catch (SQLException exception) {
      throw new AssertionError(exception);
    }
  }

  private static List<String> toolResultPayloads(long assistantEntryId) {
    String sql =
        "with recursive chain as ("
            + "select id, payload, 1 as depth from harness_entry where parent_entry_id = ? "
            + "union all select entry.id, entry.payload, chain.depth + 1 "
            + "from harness_entry entry join chain on entry.parent_entry_id = chain.id) "
            + "select payload::text from chain order by depth";
    try (Connection connection = newConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, assistantEntryId);
      try (ResultSet result = statement.executeQuery()) {
        ArrayList<String> payloads = new ArrayList<>();
        while (result.next()) {
          payloads.add(result.getString(1));
        }
        return payloads;
      }
    } catch (SQLException exception) {
      throw new AssertionError(exception);
    }
  }

  private static <T> T get(Future<T> future) {
    try {
      return future.get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting for concurrent transaction", exception);
    } catch (ExecutionException exception) {
      throw new AssertionError("concurrent transaction failed", exception.getCause());
    }
  }

  private record Prepared(long threadId, long rootEntryId, ThreadOwnership ownership) {}
}
