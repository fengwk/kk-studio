package fun.fengwk.kkstudio.core.studio.function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.studio.repo.impl.mapper.CanvasNodeMapper;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionRunException;

/** Function run API orchestration：短事务优先，adapter cancel 仅 best effort。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CanvasFunctionRuntimeService {

  private final CanvasNodeMapper nodeMapper;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionRunTransactions transactions;
  private final CanvasFunctionDispatcher dispatcher;
  private final CanvasFunctionModelRegistry registry;
  private final CanvasFunctionRunStateCodec stateCodec;

  public CanvasFunctionRun start(long canvasId, long nodeId, String requestId) {
    CanvasFunctionStartResult result = transactions.start(canvasId, nodeId, requestId);
    if (result.run().status() == CanvasFunctionRunStatus.RUNNING) {
      dispatcher.dispatch(result.run().nodeId(), result.run().requestId());
    }
    return result.run();
  }

  public CanvasFunctionRun get(long canvasId, long nodeId) {
    requireNode(canvasId, nodeId);
    return runRepository
        .findByNodeId(nodeId)
        .orElseThrow(() -> notFound("Canvas Function run not found"));
  }

  public CanvasFunctionRun cancel(long canvasId, long nodeId, String requestId) {
    CanvasFunctionRun result = transactions.cancel(canvasId, nodeId, requestId);
    if (result.status() == CanvasFunctionRunStatus.CANCELLED) {
      bestEffortAdapterCancel(result);
    }
    return result;
  }

  private void bestEffortAdapterCancel(CanvasFunctionRun run) {
    try {
      CanvasFunctionModelRegistry.RegisteredModel registered =
          registry.require(stateCodec.modelKey(run.stateJson()));
      CanvasFunctionFrozenRun frozen = stateCodec.decode(run.stateJson(), registered.model());
      registered.adapter().cancel(frozen);
    } catch (RuntimeException error) {
      log.warn(
          "Canvas Function adapter cancel hook failed nodeId={} requestId={} type={}",
          run.nodeId(),
          run.requestId(),
          error.getClass().getSimpleName());
    }
  }

  private void requireNode(long canvasId, long nodeId) {
    if (canvasId <= 0L || nodeId <= 0L || nodeMapper.getById(canvasId, nodeId) == null) {
      throw notFound("Canvas Function node not found");
    }
  }

  private static CanvasFunctionRunException notFound(String message) {
    return new CanvasFunctionRunException(CanvasFunctionRunException.Reason.NOT_FOUND, message);
  }
}
