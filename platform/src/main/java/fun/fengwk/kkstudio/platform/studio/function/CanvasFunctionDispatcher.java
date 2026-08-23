package fun.fengwk.kkstudio.platform.studio.function;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** 本进程按 nodeId/requestId 去重的有界异步 dispatcher。 */
@Slf4j
public final class CanvasFunctionDispatcher {

  private final ExecutorService executor;
  private final CanvasFunctionWorker worker;
  private final Set<RunKey> inFlight = ConcurrentHashMap.newKeySet();

  public CanvasFunctionDispatcher(ExecutorService executor, CanvasFunctionWorker worker) {
    this.executor = executor;
    this.worker = worker;
  }

  public boolean dispatch(UUID nodeId, UUID requestId) {
    RunKey key = new RunKey(nodeId, requestId);
    if (!inFlight.add(key)) {
      return true;
    }
    try {
      executor.execute(
          () -> {
            try {
              worker.run(nodeId, requestId);
            } finally {
              inFlight.remove(key);
            }
          });
      return true;
    } catch (RejectedExecutionException rejected) {
      inFlight.remove(key);
      log.warn("Canvas Function dispatch queue is full nodeId={} requestId={}", nodeId, requestId);
      return false;
    }
  }

  boolean isDispatched(UUID nodeId, UUID requestId) {
    return inFlight.contains(new RunKey(nodeId, requestId));
  }

  private record RunKey(UUID nodeId, UUID requestId) {}
}
