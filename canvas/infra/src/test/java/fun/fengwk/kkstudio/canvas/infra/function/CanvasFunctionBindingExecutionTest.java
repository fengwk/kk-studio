package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBinding;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionBlobAccess;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenOutput;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionSubmitState;
import fun.fengwk.kkstudio.canvas.infra.postgresql.CanvasFunctionWorkStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/** 执行与取消都按冻结 binding 的 epoch 选实例：current 已漂移到新 epoch 时，旧 Run 仍用旧 epoch 的定义与 adapter。 */
class CanvasFunctionBindingExecutionTest {

  private static final Instant NOW = Instant.parse("2026-02-03T04:05:06.123Z");
  private static final UUID CANVAS = UUID.randomUUID();
  private static final UUID NODE = UUID.randomUUID();
  private static final UUID REQUEST = UUID.randomUUID();
  private static final UUID TARGET = UUID.randomUUID();

  /** worker 按冻结 epoch 取租用，并在整个 submit/execute 期间持有；漂移后的 current 版本不参与执行。 */
  @Test
  void workerAcquiresFrozenEpochLeaseAndHoldsItThroughExecution() {
    CanvasFunctionDefinition definition = definition("test.function", "Base Definition");
    CanvasFunctionAdapter frozenAdapter = enabledAdapter(definition);
    CanvasFunctionAdapter currentAdapter =
        enabledAdapter(definition("test.function", "Drifted Definition"));
    FixedCanvasFunctionCatalogSource catalogSource =
        new FixedCanvasFunctionCatalogSource(
            1L, CanvasFunctionCatalog.from(List.of(frozenAdapter)));
    catalogSource.register(2L, CanvasFunctionCatalog.from(List.of(currentAdapter)));
    catalogSource.currentEpoch(2L);

    CanvasFunctionFrozenRun frozen = frozenRun(new CanvasFunctionBinding(1L, definition, null));
    CanvasFunctionRun claimRun = runningRun();
    ClaimedRun claim = new ClaimedRun(claimRun);
    CanvasFunctionRunStateCodecPort stateCodec = mock(CanvasFunctionRunStateCodecPort.class);
    when(stateCodec.decode(claimRun.stateJson())).thenReturn(frozen);
    CanvasFunctionRunTransactions transactions = mock(CanvasFunctionRunTransactions.class);
    when(frozenAdapter.execute(any(), any()))
        .thenAnswer(
            ignored -> {
              // 执行期间租用仍然打开，且用的是旧 epoch 的 adapter。
              assertEquals(1, catalogSource.openLeases());
              verify(currentAdapter, never()).execute(any(), any());
              return List.of(TARGET);
            });
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    CanvasFunctionWorker worker = worker(catalogSource, stateCodec, transactions, scheduler);

    try {
      worker.run(claim);
    } finally {
      scheduler.shutdownNow();
    }

    verify(frozenAdapter).execute(any(), eq(frozen));
    verify(currentAdapter, never()).execute(any(), any());
    verify(transactions).completeSuccess(any(), eq(claim.leaseToken()), eq(List.of(TARGET)));
    assertEquals(0, catalogSource.openLeases());
  }

  /** 冻结 epoch 已不可用时拒绝执行：绝不按函数名换成 current 版本，收敛为有界公开失败。 */
  @Test
  void workerFailsWhenFrozenEpochIsNoLongerAvailable() {
    CanvasFunctionDefinition definition = definition("test.function", "Base Definition");
    CanvasFunctionAdapter currentAdapter =
        enabledAdapter(definition("test.function", "Drifted Definition"));
    FixedCanvasFunctionCatalogSource catalogSource =
        new FixedCanvasFunctionCatalogSource(
            2L, CanvasFunctionCatalog.from(List.of(currentAdapter)));

    CanvasFunctionFrozenRun frozen = frozenRun(new CanvasFunctionBinding(1L, definition, null));
    CanvasFunctionRun claimRun = runningRun();
    ClaimedRun claim = new ClaimedRun(claimRun);
    CanvasFunctionRunStateCodecPort stateCodec = mock(CanvasFunctionRunStateCodecPort.class);
    when(stateCodec.decode(claimRun.stateJson())).thenReturn(frozen);
    CanvasFunctionRunTransactions transactions = mock(CanvasFunctionRunTransactions.class);
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    CanvasFunctionWorker worker = worker(catalogSource, stateCodec, transactions, scheduler);

    try {
      worker.run(claim);
    } finally {
      scheduler.shutdownNow();
    }

    verify(currentAdapter, never()).execute(any(), any());
    verify(transactions)
        .failIfRunning(
            claim.nodeId(),
            claim.requestId().toString(),
            claim.leaseToken(),
            "Function execution failed");
    assertEquals(0, catalogSource.openLeases());
  }

  /** best-effort cancel 只按冻结 binding 的 epoch 取实例，current 漂移不会取消错对象。 */
  @Test
  void runtimeServiceCancelUsesFrozenEpochInsteadOfCurrent() {
    CanvasFunctionDefinition definition = definition("test.function", "Base Definition");
    CanvasFunctionAdapter frozenAdapter = enabledAdapter(definition);
    CanvasFunctionAdapter currentAdapter =
        enabledAdapter(definition("test.function", "Drifted Definition"));
    FixedCanvasFunctionCatalogSource catalogSource =
        new FixedCanvasFunctionCatalogSource(
            1L, CanvasFunctionCatalog.from(List.of(frozenAdapter)));
    catalogSource.register(2L, CanvasFunctionCatalog.from(List.of(currentAdapter)));
    catalogSource.currentEpoch(2L);

    CanvasFunctionRun cancelled =
        new CanvasFunctionRun(
            NODE,
            REQUEST,
            CanvasFunctionRunStatus.CANCELLED,
            1,
            null,
            null,
            null,
            "CANCELLED",
            "{\"stage\":\"CANCELLED\"}",
            null,
            NOW,
            NOW);
    CanvasFunctionRunStateCodecPort stateCodec = mock(CanvasFunctionRunStateCodecPort.class);
    CanvasFunctionFrozenRun frozen = mock(CanvasFunctionFrozenRun.class);
    when(frozen.binding()).thenReturn(new CanvasFunctionBinding(1L, definition, null));
    when(stateCodec.decode(cancelled.stateJson())).thenReturn(frozen);
    CanvasFunctionRunTransactions transactions = mock(CanvasFunctionRunTransactions.class);
    when(transactions.cancel(CANVAS, NODE, REQUEST.toString())).thenReturn(cancelled);
    CanvasFunctionRuntimeService service =
        new CanvasFunctionRuntimeService(
            mock(CanvasStore.class),
            mock(CanvasFunctionRunRepository.class),
            transactions,
            catalogSource,
            stateCodec);

    assertEquals(cancelled, service.cancel(CANVAS, NODE, REQUEST.toString()));

    verify(frozenAdapter).cancel(frozen);
    verify(currentAdapter, never()).cancel(any());
    assertEquals(0, catalogSource.openLeases());
  }

  private static CanvasFunctionWorker worker(
      FixedCanvasFunctionCatalogSource catalogSource,
      CanvasFunctionRunStateCodecPort stateCodec,
      CanvasFunctionRunTransactions transactions,
      ScheduledThreadPoolExecutor scheduler) {
    CanvasFunctionWorkStore workStore = mock(CanvasFunctionWorkStore.class);
    when(workStore.renew(any(), any(), any())).thenReturn(true);
    @SuppressWarnings("unchecked")
    ObjectProvider<CanvasResourceMaterializer> materializers = mock(ObjectProvider.class);
    when(materializers.getIfAvailable()).thenReturn(mock(CanvasResourceMaterializer.class));
    CanvasFunctionRuntimeProperties properties = new CanvasFunctionRuntimeProperties();
    properties.setLeaseDurationMillis(100);
    properties.setHeartbeatIntervalMillis(10);
    return new CanvasFunctionWorker(
        mock(CanvasFunctionRunRepository.class),
        catalogSource,
        stateCodec,
        transactions,
        mock(CanvasFunctionBlobAccess.class),
        materializers,
        workStore,
        properties,
        Clock.fixed(NOW, ZoneOffset.UTC),
        scheduler);
  }

  private static CanvasFunctionFrozenRun frozenRun(CanvasFunctionBinding binding) {
    return new CanvasFunctionFrozenRun(
        CANVAS,
        NODE,
        "output",
        REQUEST,
        binding,
        CanvasJson.parseObject("{}"),
        List.of(),
        List.of(new CanvasFunctionFrozenOutput(TARGET, 0, CanvasResourceKind.IMAGE, "output.png")),
        CanvasFunctionSubmitState.SUBMITTED,
        "SUBMITTED",
        Map.of());
  }

  private static CanvasFunctionRun runningRun() {
    return new CanvasFunctionRun(
        NODE,
        REQUEST,
        CanvasFunctionRunStatus.RUNNING,
        1,
        null,
        "lease",
        NOW.plusSeconds(30),
        "SUBMITTED",
        "{\"stage\":\"SUBMITTED\"}",
        null,
        NOW,
        NOW);
  }

  private static CanvasFunctionDefinition definition(String name, String description) {
    return CanvasFunctionDefinition.of(
        name,
        description,
        CanvasJson.parseObject(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }

  private static CanvasFunctionAdapter enabledAdapter(CanvasFunctionDefinition definition) {
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    when(adapter.functions()).thenReturn(List.of(definition));
    when(adapter.enabled()).thenReturn(true);
    when(adapter.unavailableReason()).thenReturn(null);
    return adapter;
  }
}
