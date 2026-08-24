package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionExecutionContext;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** worker heartbeat 必须持续续租；续租丢失后阻止旧 worker 写 terminal。 */
class CanvasFunctionWorkerHeartbeatTest {

  private static final Instant NOW = Instant.parse("2026-02-03T04:05:06.123Z");

  /** 阻塞 adapter 运行期间 heartbeat 会 renew，完成后仍使用同一 token 写 terminal。 */
  @Test
  void heartbeatRenewsWhileBlockingAdapterRuns() throws Exception {
    Fixture fixture = new Fixture();
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
    Fixture fixture = new Fixture();
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
    Fixture fixture = new Fixture();
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
    Fixture fixture = new Fixture();
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
    private final CanvasFunctionFrozenRun frozen = mock(CanvasFunctionFrozenRun.class);
    private final CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    private final CanvasFunctionModel model = mock(CanvasFunctionModel.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ClaimedRun claim;
    private final CanvasFunctionCatalog catalog;
    private final CanvasFunctionWorker worker;

    @SuppressWarnings("unchecked")
    private Fixture() throws Exception {
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
      when(stateCodec.modelKey(run.stateJson())).thenReturn("model");
      when(frozen.nodeId()).thenReturn(run.nodeId());
      when(frozen.requestId()).thenReturn(run.requestId());
      when(frozen.targetResourceId()).thenReturn(targetResourceId);
      when(model.key()).thenReturn("model");
      when(adapter.models()).thenReturn(List.of(model));
      when(adapter.enabled()).thenReturn(true);
      when(adapter.unavailableReason()).thenReturn(null);
      catalog = CanvasFunctionCatalog.from(List.of(adapter));
      CanvasFunctionCatalog.RegisteredModel registered = catalog.require("model");
      when(stateCodec.decode(run.stateJson(), registered.model())).thenReturn(frozen);
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
