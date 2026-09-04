package fun.fengwk.kkstudio.canvas.infra.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** API orchestration 只提交 durable READY；查询与取消保持原协议和 adapter best-effort hook。 */
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

  /** READY/RUNNING cancel 转 terminal 后仍调用原 adapter cancel hook，协议保持 best effort。 */
  @Test
  void cancelInvokesTheExistingAdapterHook() {
    CanvasFunctionRun cancelled = run(CanvasFunctionRunStatus.CANCELLED);
    CanvasFunctionFrozenRun frozen = mock(CanvasFunctionFrozenRun.class);
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    CanvasFunctionModel model = mock(CanvasFunctionModel.class);
    when(model.key()).thenReturn("model");
    when(adapter.models()).thenReturn(List.of(model));
    when(adapter.enabled()).thenReturn(true);
    when(adapter.unavailableReason()).thenReturn(null);
    CanvasFunctionCatalog catalog = CanvasFunctionCatalog.from(List.of(adapter));
    CanvasFunctionCatalog.RegisteredModel registered = catalog.require("model");
    Fixture cancelFixture = new Fixture(catalog);
    when(cancelFixture.transactions.cancel(CANVAS, NODE, REQUEST.toString())).thenReturn(cancelled);
    when(cancelFixture.stateCodec.modelKey(cancelled.stateJson())).thenReturn("model");
    when(cancelFixture.stateCodec.decode(cancelled.stateJson(), registered.model()))
        .thenReturn(frozen);

    assertEquals(cancelled, cancelFixture.service.cancel(CANVAS, NODE, REQUEST.toString()));
    verify(adapter).cancel(frozen);
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
        null,
        NOW,
        NOW);
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
      this(CanvasFunctionCatalog.from(List.of()));
    }

    private Fixture(CanvasFunctionCatalog catalog) {
      service =
          new CanvasFunctionRuntimeService(
              canvasStore, runRepository, transactions, catalog, stateCodec);
    }
  }
}
