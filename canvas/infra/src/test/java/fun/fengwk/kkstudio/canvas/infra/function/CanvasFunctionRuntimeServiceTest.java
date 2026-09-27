package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionUnknownResolution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** API orchestration 只提交 durable READY；查询、取消与人工解除保持原协议和 adapter best-effort hook。 */
class CanvasFunctionRuntimeServiceTest {

  private static final UUID CANVAS = UUID.randomUUID();
  private static final UUID NODE = UUID.randomUUID();
  private static final UUID REQUEST = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-02-03T04:05:06.123Z");

  /** start 提交 READY 状态并直接返回事务结果。 */
  @Test
  void startReturnsTheDurableReadyRun() {
    Fixture fixture = new Fixture();
    CanvasFunctionRun ready = run(CanvasFunctionRunStatus.READY);
    when(fixture.transactions.start(CANVAS, NODE, REQUEST.toString()))
        .thenReturn(new CanvasFunctionStartResult(ready, true));

    assertEquals(ready, fixture.service.start(CANVAS, NODE, REQUEST.toString()));
  }

  /** get 先区分 Canvas node 不存在，再读取当前/最后 run。 */
  @Test
  void getDistinguishesMissingNodeAndMissingRun() {
    Fixture fixture = new Fixture();
    when(fixture.canvasStore.findNode(CANVAS, NODE)).thenReturn(Optional.empty());
    CanvasFunctionRunException missingNode =
        assertThrows(CanvasFunctionRunException.class, () -> fixture.service.get(CANVAS, NODE));
    assertEquals(CanvasFunctionRunException.Reason.NOT_FOUND, missingNode.reason());

    when(fixture.canvasStore.findNode(CANVAS, NODE))
        .thenReturn(Optional.of(mock(CanvasStore.NodeRecord.class)));
    when(fixture.runRepository.findByNodeId(NODE)).thenReturn(Optional.empty());
    CanvasFunctionRunException missingRun =
        assertThrows(CanvasFunctionRunException.class, () -> fixture.service.get(CANVAS, NODE));
    assertEquals(CanvasFunctionRunException.Reason.NOT_FOUND, missingRun.reason());
  }

  /** READY/RUNNING cancel 转 CANCELLED 终态后触发 adapter 的 best-effort cancel hook。 */
  @Test
  void cancelInvokesTheExistingAdapterHook() {
    CanvasFunctionRun cancelled = run(CanvasFunctionRunStatus.CANCELLED);
    CanvasFunctionFrozenRun frozen = mock(CanvasFunctionFrozenRun.class);
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    CanvasFunctionDefinition definition = definition("test.function");
    when(adapter.functions()).thenReturn(List.of(definition));
    when(adapter.enabled()).thenReturn(true);
    when(adapter.unavailableReason()).thenReturn(null);
    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(List.of(adapter));
    CanvasFunctionCatalog.RegisteredFunction registered = catalog.require("test.function");
    Fixture cancelFixture = new Fixture(catalog);
    when(cancelFixture.transactions.cancel(CANVAS, NODE, REQUEST.toString())).thenReturn(cancelled);
    when(cancelFixture.stateCodec.functionName(cancelled.stateJson())).thenReturn("test.function");
    when(cancelFixture.stateCodec.decode(cancelled.stateJson(), registered.function()))
        .thenReturn(frozen);

    assertEquals(cancelled, cancelFixture.service.cancel(CANVAS, NODE, REQUEST.toString()));
    verify(adapter).cancel(frozen);
  }

  /**
   * resolve UNKNOWN 状态：RESUME 回到 READY 且不调 cancel，CANCELLED 调 adapter cancel hook，FAILED 不调 cancel。
   */
  @Test
  void resolveHandlesResolutionsAndCancelsWhenAppropriate() {
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    CanvasFunctionDefinition definition = definition("test.function");
    when(adapter.functions()).thenReturn(List.of(definition));
    when(adapter.enabled()).thenReturn(true);
    when(adapter.unavailableReason()).thenReturn(null);
    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(List.of(adapter));
    CanvasFunctionCatalog.RegisteredFunction registered = catalog.require("test.function");
    Fixture fixture = new Fixture(catalog);

    // 1. RESUME -> READY (不调 adapter.cancel)
    CanvasFunctionRun resumed = run(CanvasFunctionRunStatus.READY);
    when(fixture.transactions.resolve(
            CANVAS, NODE, REQUEST.toString(), CanvasFunctionUnknownResolution.RESUME, "verified"))
        .thenReturn(resumed);
    assertEquals(
        resumed,
        fixture.service.resolve(
            CANVAS, NODE, REQUEST.toString(), CanvasFunctionUnknownResolution.RESUME, "verified"));
    verify(adapter, never()).cancel(any());

    // 2. CANCELLED -> CANCELLED (调用 adapter.cancel)
    CanvasFunctionRun cancelled = run(CanvasFunctionRunStatus.CANCELLED);
    CanvasFunctionFrozenRun frozen = mock(CanvasFunctionFrozenRun.class);
    when(fixture.transactions.resolve(
            CANVAS,
            NODE,
            REQUEST.toString(),
            CanvasFunctionUnknownResolution.CANCELLED,
            "confirmed cancelled"))
        .thenReturn(cancelled);
    when(fixture.stateCodec.functionName(cancelled.stateJson())).thenReturn("test.function");
    when(fixture.stateCodec.decode(cancelled.stateJson(), registered.function()))
        .thenReturn(frozen);

    assertEquals(
        cancelled,
        fixture.service.resolve(
            CANVAS,
            NODE,
            REQUEST.toString(),
            CanvasFunctionUnknownResolution.CANCELLED,
            "confirmed cancelled"));
    verify(adapter).cancel(frozen);

    // 3. FAILED -> FAILED (不调 adapter.cancel)
    CanvasFunctionRun failed =
        new CanvasFunctionRun(
            NODE,
            REQUEST,
            CanvasFunctionRunStatus.FAILED,
            1,
            null,
            null,
            null,
            "FAILED",
            "{\"stage\":\"FAILED\"}",
            "confirmed failed",
            NOW,
            NOW);
    when(fixture.transactions.resolve(
            CANVAS,
            NODE,
            REQUEST.toString(),
            CanvasFunctionUnknownResolution.FAILED,
            "confirmed failed"))
        .thenReturn(failed);
    assertEquals(
        failed,
        fixture.service.resolve(
            CANVAS,
            NODE,
            REQUEST.toString(),
            CanvasFunctionUnknownResolution.FAILED,
            "confirmed failed"));
  }

  private static CanvasFunctionRun run(CanvasFunctionRunStatus status) {
    return new CanvasFunctionRun(
        NODE,
        REQUEST,
        status,
        status == CanvasFunctionRunStatus.READY ? 0 : 1,
        status == CanvasFunctionRunStatus.READY ? NOW : null,
        null,
        null,
        status.name(),
        "{\"stage\":\"" + status.name() + "\"}",
        status == CanvasFunctionRunStatus.UNKNOWN ? "unknown reason" : null,
        NOW,
        NOW);
  }

  private static CanvasFunctionDefinition definition(String name) {
    return new CanvasFunctionDefinition(
        name,
        "Description",
        CanvasJson.parseObject(
            "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
        CanvasResourceKind.IMAGE,
        new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()));
  }

  private static final class Fixture {
    private final CanvasStore canvasStore = mock(CanvasStore.class);
    private final CanvasFunctionRunRepository runRepository =
        mock(CanvasFunctionRunRepository.class);
    private final CanvasFunctionRunTransactions transactions =
        mock(CanvasFunctionRunTransactions.class);
    private final CanvasFunctionRunStateCodecPort stateCodec =
        mock(CanvasFunctionRunStateCodecPort.class);
    private final CanvasFunctionRuntimeService service;

    private Fixture() {
      this(CanvasFunctionCatalog.from(List.of(dummyAdapter())));
    }

    private Fixture(CanvasFunctionCatalog catalog) {
      service =
          new CanvasFunctionRuntimeService(
              canvasStore, runRepository, transactions, catalog, stateCodec);
    }

    private static CanvasFunctionAdapter dummyAdapter() {
      CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
      when(adapter.functions()).thenReturn(List.of(definition("dummy.fn")));
      when(adapter.enabled()).thenReturn(true);
      return adapter;
    }
  }
}
