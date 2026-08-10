package fun.fengwk.kkstudio.core.studio.function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.core.studio.repo.impl.model.CanvasNodeDO;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionConfig.TextSegment;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionModel;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionRunException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Runtime service 只编排短事务、dispatch、查询边界和提交后的 best-effort cancel hook。 */
class CanvasFunctionRuntimeServiceTest {

  private static final long CANVAS_ID = 1L;
  private static final long NODE_ID = 2L;
  private static final Instant NOW = Instant.parse("2026-01-02T03:04:05Z");
  private static final CanvasFunctionModel MODEL =
      new CanvasFunctionModel(
          "test-image",
          "Test Image",
          CanvasResourceKind.IMAGE,
          new CanvasFunctionReferencePolicy(Set.of(CanvasResourceKind.IMAGE), 1, Map.of()),
          List.of());

  private CanvasNodeMapper nodeMapper;
  private CanvasFunctionRunRepository repository;
  private CanvasFunctionRunTransactions transactions;
  private CanvasFunctionDispatcher dispatcher;
  private CanvasFunctionModelRegistry registry;
  private CanvasFunctionRunStateCodec stateCodec;
  private CanvasFunctionRuntimeService service;
  private CanvasFunctionFrozenRun frozen;

  @BeforeEach
  void setUp() {
    nodeMapper = mock(CanvasNodeMapper.class);
    repository = mock(CanvasFunctionRunRepository.class);
    transactions = mock(CanvasFunctionRunTransactions.class);
    dispatcher = mock(CanvasFunctionDispatcher.class);
    registry = mock(CanvasFunctionModelRegistry.class);
    stateCodec =
        new CanvasFunctionRunStateCodec(
            new ObjectMapper(), new CanvasFunctionConfigCodec(new ObjectMapper()));
    service =
        new CanvasFunctionRuntimeService(
            nodeMapper, repository, transactions, dispatcher, registry, stateCodec);
    frozen =
        new CanvasFunctionFrozenRun(
            CANVAS_ID,
            NODE_ID,
            "output",
            "request",
            MODEL,
            new CanvasFunctionConfig(List.of(new TextSegment("prompt")), Map.of()),
            List.of(),
            "output.png",
            3L,
            "QUEUED",
            Map.of());
  }

  @Test
  void dispatchesRunningStartIncludingIdempotentReplayButNotTerminalReplay() {
    CanvasFunctionRun running = run(CanvasFunctionRunStatus.RUNNING, frozen);
    CanvasFunctionFrozenRun succeeded =
        stateCodec.checkpoint(frozen, "SUCCEEDED", frozen.adapterState());
    CanvasFunctionRun terminal = run(CanvasFunctionRunStatus.SUCCEEDED, succeeded);
    when(transactions.start(CANVAS_ID, NODE_ID, "request"))
        .thenReturn(new CanvasFunctionStartResult(running, true))
        .thenReturn(new CanvasFunctionStartResult(running, false))
        .thenReturn(new CanvasFunctionStartResult(terminal, false));

    assertSame(running, service.start(CANVAS_ID, NODE_ID, "request"));
    assertSame(running, service.start(CANVAS_ID, NODE_ID, "request"));
    assertSame(terminal, service.start(CANVAS_ID, NODE_ID, "request"));

    verify(dispatcher, times(2)).dispatch(NODE_ID, "request");
  }

  @Test
  void distinguishesMissingNodeAndMissingRun() {
    CanvasFunctionRunException missingNode =
        assertThrows(CanvasFunctionRunException.class, () -> service.get(CANVAS_ID, NODE_ID));
    assertEquals(CanvasFunctionRunException.Reason.NOT_FOUND, missingNode.reason());

    when(nodeMapper.getById(CANVAS_ID, NODE_ID)).thenReturn(new CanvasNodeDO());
    when(repository.findByNodeId(NODE_ID)).thenReturn(Optional.empty());
    CanvasFunctionRunException missingRun =
        assertThrows(CanvasFunctionRunException.class, () -> service.get(CANVAS_ID, NODE_ID));
    assertEquals(CanvasFunctionRunException.Reason.NOT_FOUND, missingRun.reason());

    CanvasFunctionRun running = run(CanvasFunctionRunStatus.RUNNING, frozen);
    when(repository.findByNodeId(NODE_ID)).thenReturn(Optional.of(running));
    assertSame(running, service.get(CANVAS_ID, NODE_ID));
  }

  @Test
  void invokesCancelHookAfterCancelledTransactionAndSwallowsAdapterFailure() {
    CanvasFunctionFrozenRun cancelled =
        stateCodec.checkpoint(frozen, "CANCELLED", Map.of("jobId", "job"));
    CanvasFunctionRun run = run(CanvasFunctionRunStatus.CANCELLED, cancelled);
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    when(transactions.cancel(CANVAS_ID, NODE_ID, "request")).thenReturn(run);
    when(registry.require(MODEL.key()))
        .thenReturn(new CanvasFunctionModelRegistry.RegisteredModel(MODEL, adapter));

    assertSame(run, service.cancel(CANVAS_ID, NODE_ID, "request"));
    verify(adapter).cancel(cancelled);

    doThrow(new IllegalStateException("provider unavailable")).when(adapter).cancel(cancelled);
    assertSame(run, service.cancel(CANVAS_ID, NODE_ID, "request"));
    verify(adapter, times(2)).cancel(cancelled);
  }

  @Test
  void doesNotInvokeCancelHookForNonCancelledTerminalReplay() {
    CanvasFunctionFrozenRun succeeded =
        stateCodec.checkpoint(frozen, "SUCCEEDED", frozen.adapterState());
    CanvasFunctionRun run = run(CanvasFunctionRunStatus.SUCCEEDED, succeeded);
    CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
    when(transactions.cancel(CANVAS_ID, NODE_ID, "request")).thenReturn(run);

    assertSame(run, service.cancel(CANVAS_ID, NODE_ID, "request"));

    verify(registry, never()).require(MODEL.key());
    verify(adapter, never()).cancel(succeeded);
  }

  private CanvasFunctionRun run(CanvasFunctionRunStatus status, CanvasFunctionFrozenRun state) {
    return new CanvasFunctionRun(
        NODE_ID, "request", status, state.stage(), stateCodec.encode(state), null, NOW);
  }
}
