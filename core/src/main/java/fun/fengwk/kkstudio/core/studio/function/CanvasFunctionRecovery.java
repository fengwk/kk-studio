package fun.fengwk.kkstudio.core.studio.function;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.studio.canvas.CanvasFunctionRunRepository;

/** 应用就绪后补 dispatch 所有 durable RUNNING FunctionRun。 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "kk-studio.canvas.function",
    name = "recovery-enabled",
    havingValue = "true",
    matchIfMissing = true)
@RequiredArgsConstructor
public class CanvasFunctionRecovery {

  private static final String DISPATCH_FAILURE = "Function execution could not be scheduled";

  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionRunTransactions transactions;
  private final CanvasFunctionDispatcher dispatcher;

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    Iterable<CanvasFunctionRun> running;
    try {
      running = runRepository.findRunning();
    } catch (RuntimeException ex) {
      log.warn(
          "Failed to recover Canvas Function runs; recovery will retry after restart: {}",
          ex.getMessage());
      return;
    }
    for (CanvasFunctionRun run : running) {
      try {
        if (!dispatcher.dispatch(run.nodeId(), run.requestId())) {
          transactions.failIfRunning(run.nodeId(), run.requestId().toString(), DISPATCH_FAILURE);
        }
      } catch (RuntimeException ex) {
        log.warn(
            "Failed to recover Canvas Function run nodeId={} requestId={} type={}",
            run.nodeId(),
            run.requestId(),
            ex.getClass().getSimpleName());
      }
    }
  }
}
