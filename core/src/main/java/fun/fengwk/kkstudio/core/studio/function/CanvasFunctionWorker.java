package fun.fengwk.kkstudio.core.studio.function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.storage.S3StorageService;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.studio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.studio.canvas.function.CanvasFunctionFrozenRun;

import java.time.Clock;
import java.util.List;

/** 执行一个 frozen run，并只通过 CAS terminal apply 收敛。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CanvasFunctionWorker {

  private static final String PUBLIC_FAILURE = "Function execution failed";

  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionModelRegistry registry;
  private final CanvasFunctionRunStateCodec stateCodec;
  private final CanvasFunctionRunTransactions transactions;
  private final ObjectProvider<S3StorageService> storageServices;
  private final ObjectProvider<CanvasResourceMaterializer> materializers;
  private final Clock clock;

  public void run(long nodeId, String requestId) {
    CanvasFunctionRun current = runRepository.findByNodeId(nodeId).orElse(null);
    if (current == null
        || current.status() != CanvasFunctionRunStatus.RUNNING
        || !current.requestId().equals(requestId)) {
      return;
    }
    try {
      CanvasFunctionModelRegistry.RegisteredModel registered =
          registry.require(stateCodec.modelKey(current.stateJson()));
      if (!registered.adapter().enabled()) {
        throw new IllegalStateException("Canvas Function adapter is unavailable");
      }
      CanvasFunctionFrozenRun frozen = stateCodec.decode(current.stateJson(), registered.model());
      CanvasFunctionExecutionContextImpl context =
          new CanvasFunctionExecutionContextImpl(
              runRepository, stateCodec, storageServices, materializers, clock, frozen);
      List<Long> result = List.copyOf(registered.adapter().execute(context, frozen));
      if (!result.equals(List.of(frozen.targetResourceId()))) {
        throw new IllegalArgumentException(
            "adapter result must equal the preallocated target Resource id");
      }
      transactions.completeSuccess(context.currentRun(), result);
    } catch (CanvasFunctionInternalCancellation cancellation) {
      log.debug(
          "Canvas Function worker stopped after CAS cancellation nodeId={} requestId={}",
          nodeId,
          requestId);
    } catch (Throwable error) {
      log.warn(
          "Canvas Function worker failed nodeId={} requestId={} type={}",
          nodeId,
          requestId,
          error.getClass().getSimpleName());
      transactions.failIfRunning(nodeId, requestId, PUBLIC_FAILURE);
    }
  }
}
