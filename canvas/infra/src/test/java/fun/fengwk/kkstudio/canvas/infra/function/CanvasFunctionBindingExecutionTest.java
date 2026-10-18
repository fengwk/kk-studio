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

/** 执行与取消都按函数名取当前目录：未提交的 Run 要求定义未变，已提交的 Run 与取消只需同名函数仍存在。 */
class CanvasFunctionBindingExecutionTest {

  private static final Instant NOW = Instant.parse("2026-02-03T04:05:06.123Z");
  private static final UUID CANVAS = UUID.randomUUID();
  private static final UUID NODE = UUID.randomUUID();
  private static final UUID REQUEST = UUID.randomUUID();
  private static final UUID TARGET = UUID.randomUUID();

  /** worker 在整个 execute 期间持有目录租用，结束后释放。 */
  @Test
  void workerHoldsLeaseThroughExecution() {
    CanvasFunctionDefinition definition = definition("test.function", "Base Definition");
    CanvasFunctionAdapter adapter = enabledAdapter(definition);
    FixedCanvasFunctionCatalogSource catalogSource =
        new FixedCanvasFunctionCatalogSource(CanvasFunctionCatalog.from(List.of(adapter)));

    CanvasFunctionFrozenRun frozen =
        frozenRun(new CanvasFunctionBinding(definition), CanvasFunctionSubmitState.SUBMITTED);
    CanvasFunctionRun claimRun = runningRun();
    ClaimedRun claim = new ClaimedRun(claimRun);
    CanvasFunctionRunStateCodecPort stateCodec = mock(CanvasFunctionRunStateCodecPort.class);
    when(stateCodec.decode(claimRun.stateJson())).thenReturn(frozen);
    CanvasFunctionRunTransactions transactions = mock(CanvasFunctionRunTransactions.class);
    when(adapter.execute(any(), any()))
        .thenAnswer(
            ignored -> {
              assertEquals(1, catalogSource.openLeases());
              return List.of(TARGET);
            });

    runWorker(catalogSource, stateCodec, transactions, claim);

    verify(adapter).execute(any(), eq(frozen));
    verify(transactions).completeSuccess(any(), eq(claim.leaseToken()), eq(List.of(TARGET)));
    assertEquals(0, catalogSource.openLeases());
  }

  /** 尚未提交的 Run 遇到定义变化直接失败并提示重新开始，绝不按新定义发出外部请求。 */
  @Test
  void workerFailsPendingRunWhenDefinitionChanged() {
    CanvasFunctionDefinition definition = definition("test.function", "Base Definition");
    CanvasFunctionAdapter currentAdapter =
        enabledAdapter(definition("test.function", "Drifted Definition"));
    FixedCanvasFunctionCatalogSource catalogSource =
        new FixedCanvasFunctionCatalogSource(CanvasFunctionCatalog.from(List.of(currentAdapter)));

    CanvasFunctionFrozenRun frozen =
        frozenRun(new CanvasFunctionBinding(definition), CanvasFunctionSubmitState.PENDING);
    CanvasFunctionRun claimRun = runningRun();
    ClaimedRun claim = new ClaimedRun(claimRun);
    CanvasFunctionRunStateCodecPort stateCodec = mock(CanvasFunctionRunStateCodecPort.class);
    when(stateCodec.decode(claimRun.stateJson())).thenReturn(frozen);
    CanvasFunctionRunTransactions transactions = mock(CanvasFunctionRunTransactions.class);

    runWorker(catalogSource, stateCodec, transactions, claim);

    verify(currentAdapter, never()).submit(any(), any());
    verify(currentAdapter, never()).execute(any(), any());
    verify(transactions)
        .failIfRunning(
            claim.nodeId(),
            claim.requestId().toString(),
            claim.leaseToken(),
            "Function is no longer available or its definition changed; start it again");
    assertEquals(0, catalogSource.openLeases());
  }

  /** 已提交的 Run 在函数被移除后进入人工核查，绝不重新提交。 */
  @Test
  void workerMarksSubmittedRunUnknownWhenFunctionRemoved() {
    CanvasFunctionDefinition definition = definition("test.function", "Base Definition");
    FixedCanvasFunctionCatalogSource catalogSource =
        new FixedCanvasFunctionCatalogSource(CanvasFunctionCatalog.from(List.of()));

    CanvasFunctionFrozenRun frozen =
        frozenRun(new CanvasFunctionBinding(definition), CanvasFunctionSubmitState.SUBMITTED);
    CanvasFunctionRun claimRun = runningRun();
    ClaimedRun claim = new ClaimedRun(claimRun);
    CanvasFunctionRunStateCodecPort stateCodec = mock(CanvasFunctionRunStateCodecPort.class);
    when(stateCodec.decode(claimRun.stateJson())).thenReturn(frozen);
    CanvasFunctionRunTransactions transactions = mock(CanvasFunctionRunTransactions.class);

    runWorker(catalogSource, stateCodec, transactions, claim);

    verify(transactions)
        .markUnknown(
            eq(claim.nodeId()), eq(claim.requestId().toString()), eq(claim.leaseToken()), any());
    assertEquals(0, catalogSource.openLeases());
  }

  /** best-effort cancel 按函数名取当前实例。 */
  @Test
  void runtimeServiceCancelUsesCurrentFunctionByName() {
    CanvasFunctionDefinition definition = definition("test.function", "Base Definition");
    CanvasFunctionAdapter currentAdapter =
        enabledAdapter(definition("test.function", "Drifted Definition"));
    FixedCanvasFunctionCatalogSource catalogSource =
        new FixedCanvasFunctionCatalogSource(CanvasFunctionCatalog.from(List.of(currentAdapter)));

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
    when(frozen.binding()).thenReturn(new CanvasFunctionBinding(definition));
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

    verify(currentAdapter).cancel(frozen);
    assertEquals(0, catalogSource.openLeases());
  }

  private static void runWorker(
      FixedCanvasFunctionCatalogSource catalogSource,
      CanvasFunctionRunStateCodecPort stateCodec,
      CanvasFunctionRunTransactions transactions,
      ClaimedRun claim) {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    try {
      worker(catalogSource, stateCodec, transactions, scheduler).run(claim);
    } finally {
      scheduler.shutdownNow();
    }
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

  private static CanvasFunctionFrozenRun frozenRun(
      CanvasFunctionBinding binding, CanvasFunctionSubmitState submitState) {
    return new CanvasFunctionFrozenRun(
        CANVAS,
        NODE,
        "output",
        REQUEST,
        binding,
        CanvasJson.parseObject("{}"),
        List.of(),
        List.of(new CanvasFunctionFrozenOutput(TARGET, 0, CanvasResourceKind.IMAGE, "output.png")),
        submitState,
        submitState.name(),
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
