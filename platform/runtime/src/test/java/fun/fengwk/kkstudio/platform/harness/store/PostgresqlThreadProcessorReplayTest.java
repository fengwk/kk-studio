package fun.fengwk.kkstudio.platform.harness.store;

import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtin;
import static fun.fengwk.kkstudio.harness.builtin.runtime.store.testing.BuiltinTestTransactions.builtinRegistry;
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
import fun.fengwk.kkstudio.harness.api.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.api.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.thread.StopCommand;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.TurnResolver;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocation;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.builtin.port.store.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayAffinity;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
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
  void stopSettlesPendingToolResponseInPostgresql() {
    // 真实 PostgreSQL Store 的父子删除约束要求 pending 响应生成的子行先于 Model 删除。
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    ModelRequestSpec request = succeededRequest();
    var baseline = StoreTestSupport.seedTurnBaseline(store, request.toolBindings());
    UUID modelId =
        store.transaction(
            tx -> {
              var thread = tx.threads().lock(baseline.threadId()).orElseThrow();
              UUID userId = tx.nextId();
              tx.entries()
                  .insert(
                      new Entry(
                          userId,
                          baseline.sessionId(),
                          baseline.turnStartEntryId(),
                          StoreTestSupport.userMessagePayload(),
                          T2));
              tx.threads().update(thread.advanceHead(userId, T2));
              UUID id = tx.nextId();
              var model =
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
                      T2);
              builtin(store, tx).insertModelInvocation(model);
              var running = model.markRunning(T2);
              builtin(store, tx).updateModelInvocation(running);
              builtin(store, tx)
                  .updateModelInvocation(
                      running.succeed(StoreTestSupport.assistantResponse("call-0"), T2));
              return id;
            });
    var before = store.transaction(tx -> tx.threads().find(baseline.threadId()).orElseThrow());
    var runtime =
        new HarnessRuntime(
            store,
            builtinRegistry(store, (id, rootSessionId, path, preparation) -> null),
            () -> new CompactionConfig(20_000));
    var result =
        runtime.stop(new StopCommand(baseline.threadId(), UUID.randomUUID(), before.version()));
    assertEquals(1, result.stoppedThreads().size());
    var path =
        store.transaction(
            tx ->
                tx.entries()
                    .loadPath(tx.threads().find(baseline.threadId()).orElseThrow().headEntryId()));
    var metadata =
        path.entries().stream()
            .filter(e -> e.payload() instanceof MessagePayload p && p.toolResultMetadata() != null)
            .map(e -> ((MessagePayload) e.payload()).toolResultMetadata())
            .toList();
    assertEquals(
        List.of(ToolResultStatus.CANCELLED),
        metadata.stream().map(ToolResultMetadata::status).toList());
    assertEquals(TurnEndOutcome.STOPPED, ((TurnEndPayload) path.head().payload()).outcome());
    assertTrue(store.transaction(tx -> builtin(store, tx).findModelInvocation(modelId)).isEmpty());
    assertTrue(
        store
            .transaction(
                tx -> tx.tools().loadByAssistantEntry(metadata.getFirst().assistantEntryId()))
            .isEmpty());
    assertTrue(
        store
            .transaction(
                tx ->
                    tx.work()
                        .claimNext(WorkTargetType.TOOL, UUID.randomUUID(), Duration.ofSeconds(30)))
            .isEmpty());
  }

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
        (threadId, rootSessionId, path, preparation) ->
            new TurnResolver.Rejected(
                new AssistantError("TEST_REJECTED", "stop after continuation"));
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadProcessor processor =
          new ThreadProcessor(
              store,
              builtinRegistry(store, resolver),
              new ThreadProcessorConfig(Duration.ofSeconds(5), () -> new CompactionConfig(20_000)));
      ClaimedWork first =
          store
              .transaction(
                  tx ->
                      tx.work()
                          .claimNext(
                              WorkTargetType.THREAD, UUID.randomUUID(), Duration.ofSeconds(30)))
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
                              WorkTargetType.THREAD, UUID.randomUUID(), Duration.ofSeconds(30)))
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
