package fun.fengwk.kkstudio.core.harness.thread.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelPricing;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.configuration.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.EnvironmentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ExecutionPolicySnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.ModelSnapshot;
import fun.fengwk.kkstudio.harness.runtime.configuration.RuntimeConfigSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeConfigInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.RuntimeEntryInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/** PostgreSQL 17 final-schema command transaction 的 create/branch/mailbox/stop 基线。 */
class PostgresqlThreadCommandTransactionsIntegrationTest extends PostgresSpringTestSupport {

  @Autowired private ThreadCommandTransactions transactions;

  @Test
  void createsAgentlessSessionBranchesOnlyWithinSessionAndAllocatesIdempotentSequence() {
    Instant now = Instant.parse("2026-07-24T00:00:00Z");
    ThreadCommandTransactions.SessionCreation first =
        transactions.createSession("first", TestRuntimeConfigs.bootstrap(), now);
    ThreadCommandTransactions.SessionCreation second =
        transactions.createSession("second", TestRuntimeConfigs.bootstrap(), now);

    assertFalse(first.mainThread().runnable());
    assertTrue(first.mainThread().headEntryId() > first.rootEntry().id());
    assertEquals(first.mainThread().id(), first.session().mainThreadId());
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.createBranch(second.session().id(), first.rootEntry().id(), now));
    assertEquals(
        first.rootEntry().id(),
        transactions.createBranch(first.session().id(), first.rootEntry().id(), now).headEntryId());

    RuntimeEntryInputPayload payload = userPayload("hello");
    ThreadCommandTransactions.EnqueueResult firstEnqueue =
        transactions.enqueue(first.mainThread().id(), payload, "message-1", now);
    ThreadCommandTransactions.EnqueueResult retry =
        transactions.enqueue(
            first.mainThread().id(), userPayload("different retry body"), "message-1", now);
    assertEquals(1, firstEnqueue.input().sequence());
    assertEquals(firstEnqueue.input(), retry.input());
    assertEquals(first.mainThread().id(), firstEnqueue.target().id());
  }

  @Test
  void stopFencesEpochAndCancelsQueuedInputsWithoutMakingThreadRunnable() {
    Instant now = Instant.parse("2026-07-24T00:00:00Z");
    ThreadCommandTransactions.SessionCreation session =
        transactions.createSession("stop", TestRuntimeConfigs.bootstrap(), now);
    long threadId = session.mainThread().id();
    transactions.enqueue(threadId, userPayload("one"), "one", now);
    transactions.enqueue(threadId, userPayload("two"), "two", now);

    ThreadCommandTransactions.StopResult stopped = transactions.stop(threadId, now.plusSeconds(1));
    assertEquals(1, stopped.executionEpoch());
    assertEquals(2, stopped.cancelledInputs().size());
    assertTrue(stopped.cancelledInputs().stream().allMatch(input -> input.isTerminal()));
    assertEquals(threadId, stopped.target().id());
  }

  @Test
  void effectiveConfigPrefersLatestQueuedSnapshotBeforeHeadPathFallback() {
    Instant now = Instant.parse("2026-07-24T00:00:00Z");
    ThreadCommandTransactions.SessionCreation session =
        transactions.createSession("config", TestRuntimeConfigs.bootstrap(), now);
    long threadId = session.mainThread().id();
    RuntimeConfigSnapshot agent = config("agent-a", true);
    RuntimeConfigSnapshot model = config("agent-a", false);
    transactions.enqueue(
        threadId, new RuntimeConfigInputPayload(ThreadInputType.SET_AGENT, agent), "agent", now);
    assertEquals(agent, transactions.lockAndFindCurrentConfig(threadId).orElseThrow());
    transactions.enqueue(
        threadId,
        new RuntimeConfigInputPayload(ThreadInputType.SET_MODEL, model),
        "model",
        now.plusSeconds(1));
    assertEquals(model, transactions.lockAndFindCurrentConfig(threadId).orElseThrow());
  }

  private static RuntimeEntryInputPayload userPayload(String content) {
    return new RuntimeEntryInputPayload(
        ThreadInputType.USER_MESSAGE,
        new MessageEntryPayload(
            new AgentMessage(
                AgentMessageRole.USER,
                List.<AgentMessageContent>of(new TextMessageContent(content)))));
  }

  private static RuntimeConfigSnapshot config(String name, boolean yolo) {
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    ModelDescriptor descriptor =
        new ModelDescriptor(
            1,
            2,
            ProviderType.OPENAI,
            "model",
            "model",
            1024,
            512,
            EnumSet.of(ModelInputModality.TEXT),
            true,
            false,
            List.of(variant),
            new ModelPricing(
                "USD",
                "default",
                "default",
                BigDecimal.ONE,
                "v1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO),
            PromptCachePolicy.disabled());
    return new RuntimeConfigSnapshot(
        new AgentSnapshot(1, name, "prompt"),
        new ModelSnapshot(descriptor, variant),
        List.of(),
        List.of(),
        new ExecutionPolicySnapshot(1, 1, 1, null, List.of(), yolo),
        new EnvironmentSnapshot(null, "workspace"));
  }
}
