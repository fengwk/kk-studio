package fun.fengwk.kkstudio.harness.runtime.store.testing;

import static fun.fengwk.kkstudio.harness.runtime.store.testing.StoreTestSupport.T2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ProcessorLeaseConfig;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessResult;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorConfig;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadRuntimeStatus;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真 PostgreSQL 验证超大上游错误正文（HTTP 404 HTML）的失败结算：完整 ASSISTANT_ERROR、FAILED TURN_END、Model 行与 THREAD
 * Work 删除在同一事务内提交，线程回到 IDLE，重复 claim 不产生重复条目。
 */
class PostgresqlAssistantErrorSettlementTest {

  @Test
  void largeUpstreamErrorBodySettlesFailureAtomically() {
    HarnessStore store = PostgresqlHarnessStoreFixture.resetAndCreate();
    StoreTestSupport.TurnBaseline baseline = StoreTestSupport.seedTurnBaseline(store);
    Instant now = Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    WorkTarget target = new WorkTarget(WorkTargetType.THREAD, baseline.threadId());
    String message =
        "HTTP 404\n\n<!DOCTYPE html>\n<html>\n<body>\n  "
            + "not found ".repeat(600)
            + "错误\n</body>\n</html>\n  ";
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.INVALID_REQUEST, message);

    UUID userEntryId =
        store.transaction(
            tx -> {
              var thread = tx.lockThread(baseline.threadId()).orElseThrow();
              UUID id = tx.nextId();
              tx.insertEntry(
                  new Entry(
                      id,
                      baseline.sessionId(),
                      baseline.turnStartEntryId(),
                      StoreTestSupport.userMessagePayload(),
                      T2));
              tx.updateThread(thread.advanceHead(id, T2));
              return id;
            });

    UUID modelId =
        store.transaction(
            tx -> {
              tx.lockThread(baseline.threadId()).orElseThrow();
              UUID id = tx.nextId();
              ModelRequestSpec request = StoreTestSupport.modelRequest();
              tx.insertModelInvocation(
                  new ModelInvocation(
                      id,
                      baseline.threadId(),
                      baseline.turnStartEntryId(),
                      userEntryId,
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
              // 与生产一致：dispatch 已开始、上游返回 HTTP 404，invocation 终结为 FAILED(INVALID_REQUEST)。
              var model = tx.lockModelInvocation(id).orElseThrow();
              tx.updateModelInvocation(model.beginDispatch(T2));
              model = tx.lockModelInvocation(id).orElseThrow();
              tx.updateModelInvocation(model.markRunning(T2));
              model = tx.lockModelInvocation(id).orElseThrow();
              tx.updateModelInvocation(model.fail(error, T2));
              tx.requestWork(target, now);
              return id;
            });

    AtomicBoolean resolved = new AtomicBoolean(false);
    TurnResolver resolver =
        (threadId, path, preparation) -> {
          resolved.set(true);
          return new TurnResolver.Rejected(
              new AssistantError("TEST_REJECTED", "must not resolve on terminal settlement"));
        };
    ThreadProcessorConfig config =
        new ThreadProcessorConfig(
            new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5)),
            Duration.ofSeconds(5),
            () -> new CompactionConfig(20_000, null));
    Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ThreadProcessor processor =
          new ThreadProcessor(store, resolver, config, clock, scheduler, Runnable::run);
      ClaimedWork claimed =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, now, "settle-claim", Duration.ofSeconds(30)))
              .orElseThrow();
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(claimed));

      assertFalse(resolved.get());
      assertTrue(store.transaction(tx -> tx.findModelInvocation(modelId)).isEmpty());
      assertTrue(store.transaction(tx -> tx.findWork(target)).isEmpty());
      EntryPath path = loadPath(store, baseline.threadId());
      TurnEndPayload end = assertInstanceOf(TurnEndPayload.class, path.head().payload());
      assertEquals(TurnEndOutcome.FAILED, end.outcome());
      AssistantErrorPayload errorPayload =
          path.entries().stream()
              .filter(e -> e.payload() instanceof AssistantErrorPayload)
              .map(e -> (AssistantErrorPayload) e.payload())
              .findFirst()
              .orElseThrow();
      assertEquals("INVALID_REQUEST", errorPayload.error().code());
      assertEquals(message, errorPayload.error().message());

      // durable 状态经既有 snapshot 投影读到 IDLE（不再 RUNNABLE/挂起）。
      HarnessRuntime snapshotRuntime =
          new HarnessRuntime(store, clock, resolver, () -> new CompactionConfig(20_000, null));
      assertEquals(
          ThreadRuntimeStatus.IDLE,
          snapshotRuntime.getThreadSnapshot(baseline.threadId()).runtimeStatus());

      // 重新请求同一 THREAD Work 再 claim：终态已收敛，重复处理不得产生重复条目。
      store.transaction(
          tx -> {
            tx.lockThread(baseline.threadId()).orElseThrow();
            tx.requestWork(target, now);
            return null;
          });
      ClaimedWork replayed =
          store
              .transaction(
                  tx ->
                      tx.claimNextWork(
                          WorkTargetType.THREAD, now, "replay-claim", Duration.ofSeconds(30)))
              .orElseThrow();
      assertEquals(ThreadProcessResult.COMPLETED, processor.process(replayed));
      EntryPath replayedPath = loadPath(store, baseline.threadId());
      assertEquals(path.entries().size(), replayedPath.entries().size());
      assertEquals(path.head().id(), replayedPath.head().id());
      assertTrue(store.transaction(tx -> tx.findWork(target)).isEmpty());
      assertEquals(
          ThreadRuntimeStatus.IDLE,
          snapshotRuntime.getThreadSnapshot(baseline.threadId()).runtimeStatus());
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static EntryPath loadPath(HarnessStore store, UUID threadId) {
    return store.transaction(
        tx -> tx.loadEntryPath(tx.findThread(threadId).orElseThrow().headEntryId()));
  }
}
