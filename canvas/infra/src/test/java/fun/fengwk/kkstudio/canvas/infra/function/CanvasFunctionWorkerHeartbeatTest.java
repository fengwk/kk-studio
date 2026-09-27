package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownException;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** worker heartbeat 续租、两阶段提交流程、SUBMITTING 崩溃恢复与 UNKNOWN 收敛验证。 */
class CanvasFunctionWorkerHeartbeatTest {

  private static final Instant NOW = Instant.parse("2026-02-03T04:05:06.123Z");

  /** 两阶段提交：PENDING 状态下先持久化 SUBMITTING，调用 adapter.submit，再确认 SUBMITTED，最后 execute 并完成。 */
  @Test
  void twoPhaseExecutionFlowPersistsSubmitIntentBeforeExecute() throws Exception {
    Fixture fixture = new Fixture(CanvasFunctionSubmitState.PENDING);
    when(fixture.workStore.renew(any(), any(), any())).thenReturn(true);
    when(fixture.transactions.beginSubmit(any(), eq(fixture.claim.leaseToken())))
        .thenReturn(fixture.frozenSubmitting);
    when(fixture.transactions.confirmSubmitted(any(), eq(fixture.claim.leaseToken())))
        .thenReturn(fixture.frozenSubmitted);
    when(fixture.adapter.execute(any(), any())).thenReturn(List.of(fixture.targetResourceId));

    try {
      fixture.worker.run(fixture.claim);
      verify(fixture.transactions).beginSubmit(fixture.frozen, fixture.claim.leaseToken());
      verify(fixture.adapter).submit(any(), eq(fixture.frozenSubmitting));
      verify(fixture.transactions).confirmSubmitted(any(), eq(fixture.claim.leaseToken()));
      verify(fixture.adapter).execute(any(), eq(fixture.frozenSubmitted));
      verify(fixture.transactions)
          .completeSuccess(
              any(), eq(fixture.claim.leaseToken()), eq(List.of(fixture.targetResourceId)));
    } finally {
      fixture.close();
    }
  }

  /** 如果 Run 以 SUBMITTING 状态恢复，Worker 绝不调用 submit，而是直接收敛为 UNKNOWN 等待人工核查。 */
  @Test
  void resumedSubmittingRunConvergesDirectlyToUnknownWithoutCallingSubmit() throws Exception {
    Fixture fixture = new Fixture(CanvasFunctionSubmitState.SUBMITTING);
    when(fixture.workStore.renew(any(), any(), any())).thenReturn(true);

    try {
      fixture.worker.run(fixture.claim);
      verify(fixture.adapter, never()).submit(any(), any());
      verify(fixture.adapter, never()).execute(any(), any());
      verify(fixture.transactions)
          .markUnknown(
              fixture.claim.nodeId(),
              fixture.claim.requestId().toString(),
              fixture.claim.leaseToken(),
              "external submission outcome is unknown; manual verification required");
    } finally {
      fixture.close();
    }
  }

  /** adapter 抛出 CanvasFunctionUnknownException 时 Worker 收敛为 UNKNOWN。 */
  @Test
  void adapterUnknownExceptionConvergesToUnknownStatus() throws Exception {
    Fixture fixture = new Fixture(CanvasFunctionSubmitState.SUBMITTED);
    when(fixture.workStore.renew(any(), any(), any())).thenReturn(true);
    doThrow(new CanvasFunctionUnknownException("external task timeout"))
        .when(fixture.adapter)
        .execute(any(), any());

    try {
      fixture.worker.run(fixture.claim);
      verify(fixture.transactions)
          .markUnknown(
              fixture.claim.nodeId(),
              fixture.claim.requestId().toString(),
              fixture.claim.leaseToken(),
              "external task timeout");
      verify(fixture.transactions, never()).failIfRunning(any(), any(), any(), any());
    } finally {
      fixture.close();
    }
  }

  /** 阻塞 adapter 运行期间 heartbeat 会 renew，完成后仍使用同一 token 写 terminal。 */
  @Test
  void heartbeatRenewsWhileBlockingAdapterRuns() throws Exception {
    Fixture fixture = new Fixture(CanvasFunctionSubmitState.SUBMITTED);
    when(fixture.workStore.renew(any(), any(), any()))
        .thenAnswer(
            ignored -> {
              fixture.renewed.countDown();
              return true;
            });
    when(fixture.adapter.execute(any(), any()))
        .thenAnswer(
            ignored -> {
              assertTrue(fixture.renewed.await(5, TimeUnit.SECONDS));
              return List.of(fixture.targetResourceId);
            });

    try {
      fixture.worker.run(fixture.claim);
      assertTrue(fixture.renewed.await(5, TimeUnit.SECONDS));
      verify(fixture.transactions)
          .completeSuccess(
              fixture.frozen, fixture.claim.leaseToken(), List.of(fixture.targetResourceId));
    } finally {
      fixture.close();
    }
  }

  /** heartbeat 返回 false 表示 ownership 已丢失；adapter 即使随后返回也不能 checkpoint/terminal。 */
  @Test
  void heartbeatLossCancelsCheckpointAndTerminalWrite() throws Exception {
    Fixture fixture = new Fixture(CanvasFunctionSubmitState.SUBMITTED);
    when(fixture.workStore.renew(any(), any(), any()))
        .thenAnswer(
            ignored -> {
              fixture.renewed.countDown();
              return false;
            });
    when(fixture.adapter.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              CanvasFunctionExecutionContext context = invocation.getArgument(0);
              assertTrue(fixture.ownershipLost.await(5, TimeUnit.SECONDS));
              assertThrows(
                  CanvasFunctionInternalCancellation.class,
                  () -> context.checkpoint("LATE", Map.of("jobId", "late")));
              return List.of(fixture.targetResourceId);
            });

    try {
      try (var workerExecutor = Executors.newSingleThreadExecutor()) {
        var future = workerExecutor.submit(() -> fixture.worker.run(fixture.claim));
        assertTrue(fixture.renewed.await(5, TimeUnit.SECONDS));
        fixture.scheduler.execute(fixture.ownershipLost::countDown);
        future.get(5, TimeUnit.SECONDS);
      }
      verify(fixture.transactions, never())
          .checkpoint(any(), any(), anyString(), anyString(), anyString(), any());
      verify(fixture.transactions, never()).completeSuccess(any(), any(), any());
      verify(fixture.transactions, never()).failIfRunning(any(), any(), any(), any());
    } finally {
      fixture.close();
    }
  }

  /** heartbeat 存储异常与 renew=false 同义：立即丢 ownership，旧 worker 不写终态。 */
  @Test
  void heartbeatExceptionCancelsTerminalWrite() throws Exception {
    Fixture fixture = new Fixture(CanvasFunctionSubmitState.SUBMITTED);
    when(fixture.workStore.renew(any(), any(), any()))
        .thenAnswer(
            ignored -> {
              fixture.renewed.countDown();
              throw new IllegalStateException("database unavailable");
            });
    when(fixture.adapter.execute(any(), any()))
        .thenAnswer(
            invocation -> {
              CanvasFunctionExecutionContext context = invocation.getArgument(0);
              assertTrue(fixture.ownershipLost.await(5, TimeUnit.SECONDS));
              assertThrows(
                  CanvasFunctionInternalCancellation.class,
                  () -> context.checkpoint("LATE", Map.of("jobId", "late")));
              return List.of(fixture.targetResourceId);
            });

    try {
      try (var workerExecutor = Executors.newSingleThreadExecutor()) {
        var future = workerExecutor.submit(() -> fixture.worker.run(fixture.claim));
        assertTrue(fixture.renewed.await(5, TimeUnit.SECONDS));
        fixture.scheduler.execute(fixture.ownershipLost::countDown);
        future.get(5, TimeUnit.SECONDS);
      }
      verify(fixture.transactions, never())
          .checkpoint(any(), any(), anyString(), anyString(), anyString(), any());
      verify(fixture.transactions, never()).completeSuccess(any(), any(), any());
      verify(fixture.transactions, never()).failIfRunning(any(), any(), any(), any());
    } finally {
      fixture.close();
    }
  }

  /** adapter 异常只能由仍持有 lease 的 worker 写固定公开失败，不能泄漏 provider 错误。 */
  @Test
  void adapterFailureUsesFencedPublicFailureTransition() throws Exception {
    Fixture fixture = new Fixture(CanvasFunctionSubmitState.SUBMITTED);
    when(fixture.workStore.renew(any(), any(), any())).thenReturn(true);
    doThrow(new IllegalStateException("provider")).when(fixture.adapter).execute(any(), any());

    try {
      fixture.worker.run(fixture.claim);
      verify(fixture.transactions)
          .failIfRunning(
              fixture.claim.nodeId(),
              fixture.claim.requestId().toString(),
              fixture.claim.leaseToken(),
              "Function execution failed");
      verify(fixture.transactions, never()).completeSuccess(any(), any(), any());
    } finally {
      fixture.close();
    }
  }

  private static final class Fixture implements AutoCloseable {

    private final UUID targetResourceId = UUID.randomUUID();
    private final CountDownLatch renewed = new CountDownLatch(1);
    private final CountDownLatch ownershipLost = new CountDownLatch(1);
    private final CanvasFunctionRunRepository runRepository =
        mock(CanvasFunctionRunRepository.class);
    private final CanvasFunctionRunStateCodecPort stateCodec =
        mock(CanvasFunctionRunStateCodecPort.class);
    private final CanvasFunctionRunTransactions transactions =
        mock(CanvasFunctionRunTransactions.class);
    private final CanvasFunctionBlobAccess blobAccess = mock(CanvasFunctionBlobAccess.class);
    private final CanvasFunctionWorkStore workStore = mock(CanvasFunctionWorkStore.class);
    private final CanvasFunctionFrozenRun frozen;
    private final CanvasFunctionFrozenRun frozenSubmitting;
    private final CanvasFunctionFrozenRun frozenSubmitted;
    private final CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    private final CanvasFunctionDefinition definition;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ClaimedRun claim;
    private final CanvasFunctionCatalog catalog;
    private final CanvasFunctionWorker worker;

    @SuppressWarnings("unchecked")
    private Fixture(CanvasFunctionSubmitState submitState) {
      definition =
          CanvasFunctionDefinition.of(
              "test.function",
              "Test Function",
              CanvasJson.parseObject(
                  "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
              CanvasResourceKind.IMAGE,
              new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
      when(adapter.functions()).thenReturn(List.of(definition));
      when(adapter.enabled()).thenReturn(true);
      when(adapter.unavailableReason()).thenReturn(null);
      catalog = CanvasFunctionCatalog.from(List.of(adapter));

      CanvasFunctionRun run =
          new CanvasFunctionRun(
              UUID.randomUUID(),
              UUID.randomUUID(),
              CanvasFunctionRunStatus.RUNNING,
              1,
              null,
              "lease",
              NOW.plusSeconds(30),
              "QUEUED",
              "{\"stage\":\"QUEUED\"}",
              null,
              NOW,
              NOW);
      claim = new ClaimedRun(run);
      when(runRepository.findByNodeId(run.nodeId())).thenReturn(Optional.of(run));
      when(stateCodec.functionName(run.stateJson())).thenReturn("test.function");

      frozen =
          new CanvasFunctionFrozenRun(
              UUID.randomUUID(),
              run.nodeId(),
              "output",
              run.requestId(),
              definition,
              CanvasJson.parseObject("{}"),
              List.of(),
              List.of(
                  new CanvasFunctionFrozenOutput(
                      targetResourceId, 0, CanvasResourceKind.IMAGE, "output.png")),
              submitState,
              "QUEUED",
              Map.of());

      frozenSubmitting =
          new CanvasFunctionFrozenRun(
              frozen.canvasId(),
              frozen.nodeId(),
              frozen.nodeName(),
              frozen.requestId(),
              frozen.definition(),
              frozen.args(),
              frozen.manifest(),
              frozen.outputs(),
              CanvasFunctionSubmitState.SUBMITTING,
              "SUBMITTING",
              Map.of());

      frozenSubmitted =
          new CanvasFunctionFrozenRun(
              frozen.canvasId(),
              frozen.nodeId(),
              frozen.nodeName(),
              frozen.requestId(),
              frozen.definition(),
              frozen.args(),
              frozen.manifest(),
              frozen.outputs(),
              CanvasFunctionSubmitState.SUBMITTED,
              "SUBMITTED",
              Map.of());

      CanvasFunctionCatalog.RegisteredFunction registered = catalog.require("test.function");
      when(stateCodec.decode(run.stateJson(), registered.function())).thenReturn(frozen);

      ObjectProvider<CanvasResourceMaterializer> materializers = mock(ObjectProvider.class);
      when(materializers.getIfAvailable()).thenReturn(mock(CanvasResourceMaterializer.class));
      CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
      properties.setLeaseDurationMillis(100);
      properties.setHeartbeatIntervalMillis(10);
      worker =
          new CanvasFunctionWorker(
              runRepository,
              catalog,
              stateCodec,
              transactions,
              blobAccess,
              materializers,
              workStore,
              properties,
              Clock.fixed(NOW, ZoneOffset.UTC),
              scheduler);
    }

    @Override
    public void close() {
      scheduler.shutdownNow();
    }
  }
}
