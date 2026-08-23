package fun.fengwk.kkstudio.platform.studio.function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionFrozenRun;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.util.List;
import java.util.UUID;

/** 执行一个 frozen run，checkpoint 与 terminal 都通过短事务 CAS + canvas version/patch 收敛。 */
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
  private final ObjectProvider<StorageBlobManager> blobManagers;
  private final ObjectProvider<CanvasResourceMaterializer> materializers;

  public void run(UUID nodeId, UUID requestId) {
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
              runRepository, transactions, storageServices, blobManagers, materializers, frozen);
      List<UUID> result = List.copyOf(registered.adapter().execute(context, frozen));
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
      transactions.failIfRunning(nodeId, requestId.toString(), PUBLIC_FAILURE);
    }
  }
}
