package fun.fengwk.kkstudio.harness.infra.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtinDriver;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.mappedAssistant;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.StoreTestSupport.succeededRequest;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T3;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** 在真实 PostgreSQL 事务中验证带 replay state 的模型结果与兄弟 Tool batch 的完整交接。 */
class PostgresqlThreadProcessorReplayTest {

  @Test
  void toolSiblingBatchWithTransferredReplayStateAdvancesAndWakesContinuation() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    StoreTestSupport.TurnBaseline baseline =
        StoreTestSupport.seedTurnBaseline(store, succeededRequest().toolBindings());
    Instant now = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    UUID userId =
        store.transaction(
            tx -> {
              var thread = tx.threads().lock(baseline.threadId()).orElseThrow();
              UUID id = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          id,
                          baseline.sessionId(),
                          baseline.turnStartEntryId(),
                          StoreTestSupport.userMessagePayload(),
                          T2));
              tx.threads().update(thread.advanceHead(id, T2));
              return id;
            });

    ModelRequestSpec request = succeededRequest();
    ProviderResponse response = StoreTestSupport.assistantResponse("call-0", "call-1");
    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            new ProviderReplayAffinity(
                request.providerType(),
                request.model().providerName(),
                request.providerConnectionGenerationId(),
                request.model().modelId()),
            JsonNodeFactory.instance
                .objectNode()
                .put("role", "assistant")
                .put("content", "assistant reply"));
    UUID modelId =
        store.transaction(
            tx -> {
              tx.threads().lock(baseline.threadId()).orElseThrow();
              UUID id = tx.nextId();
              builtin(store, tx)
                  .insertModelInvocation(
                      new ModelInvocation(
                          id,
                          baseline.threadId(),
                          baseline.turnStartEntryId(),
                          userId,
                          request,
                          ModelInvocationStatus.READY,
                          0,
                          null,
                          null,
                          null,
                          null,
                          List.of(),
                          T2,
                          T2));
              var model = builtin(store, tx).lockModelInvocation(id).orElseThrow();
              builtin(store, tx).updateModelInvocation(model.markRunning(T2));
              model = builtin(store, tx).lockModelInvocation(id).orElseThrow();
              builtin(store, tx).updateModelInvocation(model.succeed(response, null, replay, T2));
              return id;
            });

    UUID assistantId =
        store.transaction(
            tx -> {
              var thread = tx.threads().lock(baseline.threadId()).orElseThrow();
              var model = builtin(store, tx).lockModelInvocation(modelId).orElseThrow();
              UUID id = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          id,
                          baseline.sessionId(),
                          userId,
                          mappedAssistant(request, response),
                          T3,
                          replay));
              builtin(store, tx).updateModelInvocation(model.attachResultEntry(id, T3));
              tx.threads().update(thread.advanceHead(id, T3));
              tx.tools()
                  .insert(
                      List.of(
                          StoreTestSupport.toolInvocation(
                              tx.nextId(),
                              baseline.threadId(),
                              baseline.turnStartEntryId(),
                              id,
                              0,
                              "call-0",
                              ToolInvocationStatus.READY,
                              T3),
                          StoreTestSupport.toolInvocation(
                              tx.nextId(),
                              baseline.threadId(),
                              baseline.turnStartEntryId(),
                              id,
                              1,
                              "call-1",
                              ToolInvocationStatus.READY,
                              T3)));
              return id;
            });

    // 模拟两个远端工具已持久化 SUCCEEDED：一个线程 claim 必须原子物化全部结果并重新唤醒 continuation。
    store.transaction(
        tx -> {
          tx.threads().lock(baseline.threadId()).orElseThrow();
          List<ToolInvocation> siblings = tx.tools().lockByAssistantEntry(assistantId);
          for (ToolInvocation sibling : siblings) {
            var ready = sibling.markApprovalNotRequired(T3);
            tx.tools().update(List.of(ready));
            var running = ready.markRunning(T3);
            tx.tools().update(List.of(running));
            tx.tools()
                .update(
                    List.of(
                        running.succeed(
                            new ToolResult(
                                sibling.call().id(),
                                List.of(new TextResultContent("ok")),
                                false,
                                "{}"),
                            T3)));
          }
          tx.work().request(target);
          return null;
        });
    assertNull(
        store.transaction(
            tx ->
                builtin(store, tx)
                    .findModelInvocation(modelId)
                    .orElseThrow()
                    .providerReplayState()));
    assertEquals(
        replay,
        store.transaction(
            tx -> tx.entries().find(assistantId).orElseThrow().providerReplayState()));

    TurnResolver resolver =
        (threadId, path, preparation) ->
            new TurnResolver.Rejected(
                new AssistantError("TEST_REJECTED", "stop after continuation"));
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadProcessor processor =
          new ThreadProcessor(
              store,
              builtinDriver(store, resolver),
              new ThreadProcessorConfig(Duration.ofSeconds(5), () -> new CompactionConfig(20_000)));
      ClaimedWork first =
          store
              .transaction(
                  tx ->
                      tx.work()
                          .claimNext(WorkTargetType.THREAD, "first-claim", Duration.ofSeconds(30)))
              .orElseThrow();
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(first));
      var applied =
          store.transaction(
              tx ->
                  tx.entries()
                      .loadPath(
                          tx.threads().find(baseline.threadId()).orElseThrow().headEntryId()));
      List<ToolResultMetadata> results =
          applied.entries().stream()
              .filter(
                  e -> e.payload() instanceof MessagePayload p && p.toolResultMetadata() != null)
              .map(e -> ((MessagePayload) e.payload()).toolResultMetadata())
              .toList();
      assertEquals(List.of(0, 1), results.stream().map(ToolResultMetadata::callIndex).toList());
      assertTrue(results.stream().allMatch(r -> r.status() == ToolResultStatus.SUCCEEDED));
      assertTrue(
          applied.entries().stream()
              .anyMatch(e -> e.id().equals(assistantId) && replay.equals(e.providerReplayState())));
      TurnEndPayload batchEnd = assertInstanceOf(TurnEndPayload.class, applied.head().payload());
      assertEquals(TurnEndOutcome.COMPLETED, batchEnd.outcome());
      assertTrue(batchEnd.continueModel());
      assertTrue(
          store.transaction(tx -> builtin(store, tx).findModelInvocation(modelId)).isEmpty());
      assertTrue(store.transaction(tx -> tx.tools().loadByAssistantEntry(assistantId)).isEmpty());
      assertTrue(store.transaction(tx -> tx.work().find(target)).isPresent());

      ClaimedWork second =
          store
              .transaction(
                  tx ->
                      tx.work()
                          .claimNext(
                              WorkTargetType.THREAD, "continuation-claim", Duration.ofSeconds(30)))
              .orElseThrow();
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(second));
      var continued =
          store.transaction(
              tx ->
                  tx.entries()
                      .loadPath(
                          tx.threads().find(baseline.threadId()).orElseThrow().headEntryId()));
      assertTrue(continued.entries().size() > applied.entries().size());
      TurnEndPayload continuationEnd =
          assertInstanceOf(TurnEndPayload.class, continued.head().payload());
      assertEquals(TurnEndOutcome.FAILED, continuationEnd.outcome());
      assertTrue(store.transaction(tx -> tx.work().find(target)).isEmpty());
    } finally {
      scheduler.shutdownNow();
    }
  }
}
