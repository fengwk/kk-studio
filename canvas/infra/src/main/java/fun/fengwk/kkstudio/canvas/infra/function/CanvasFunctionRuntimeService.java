package fun.fengwk.kkstudio.canvas.infra.function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionCatalog;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunException;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionRunStateCodecPort;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionService;

import java.util.Objects;
import java.util.UUID;

/** Function run API orchestration：短事务优先，adapter cancel 仅 best effort。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CanvasFunctionRuntimeService implements CanvasFunctionService {

  private final CanvasStore canvasStore;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionRunTransactions transactions;
  private final CanvasFunctionCatalog registry;
  private final CanvasFunctionRunStateCodecPort stateCodec;

  @Override
  public CanvasFunctionRun start(UUID canvasId, UUID nodeId, String requestId) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    CanvasFunctionStartResult result = transactions.start(canvasId, nodeId, requestId);
    return result.run();
  }

  @Override
  public CanvasFunctionRun get(UUID canvasId, UUID nodeId) {
    requireNode(canvasId, nodeId);
    return runRepository
        .findByNodeId(nodeId)
        .orElseThrow(() -> notFound("Canvas Function run not found"));
  }

  @Override
  public CanvasFunctionRun cancel(UUID canvasId, UUID nodeId, String requestId) {
    CanvasFunctionRun result = transactions.cancel(canvasId, nodeId, requestId);
    if (result.status() == CanvasFunctionRunStatus.CANCELLED) {
      bestEffortAdapterCancel(result);
    }
    return result;
  }

  private void bestEffortAdapterCancel(CanvasFunctionRun run) {
    try {
      CanvasFunctionCatalog.RegisteredModel registered =
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

  private void requireNode(UUID canvasId, UUID nodeId) {
    if (canvasStore.findNode(canvasId, nodeId).isEmpty()) {
      throw notFound("Canvas Function node not found");
    }
  }

  private static CanvasFunctionRunException notFound(String message) {
    return new CanvasFunctionRunException(CanvasFunctionRunException.Reason.NOT_FOUND, message);
  }
}
